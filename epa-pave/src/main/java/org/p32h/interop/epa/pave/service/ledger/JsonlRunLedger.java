package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.ledger.DayFiles.DayFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The test run ledger as JSON Lines files: one record per line, one file per day, appended and
 * never rewritten.
 *
 * <p><b>Never lose a run.</b> A line is written with one append, then flushed and synced before
 * {@link #record} returns. The file is opened for appending only, so no fault in this class can
 * overwrite a byte that is already there. Writes are serialised, so two runs that finish together
 * give two whole lines. If the file ends in the middle of a line, which is what a crash during a
 * write leaves behind, a line break is written first: the torn line is lost and the new one is not.
 * A write that fails is never an exception. It is a receipt that says so and names the file.
 *
 * <p><b>A day's file does not change after that day.</b> Everything written on a day goes into that
 * day's file, a note on an older run included. A note is its own line and is applied when the run
 * is read. Reading therefore goes from the newest file backwards, so the notes for a run have been
 * seen by the time the run is reached. That holds while the clock's date does not go backwards. If
 * it is set back across midnight, or the tool is restarted in a time zone that is still on the day
 * before, a note written then lands in an older file than its run and is not applied. The note is
 * still on disk, and adding it again once the date has caught up applies it.
 *
 * <p><b>What a line may hold</b> is decided by {@link TrackingPolicy}, which is applied here to
 * every draft, so no caller can get it wrong. The record type itself has no place for a member
 * name, a date of birth, an address or wire JSON, and this class keeps no side file, cache or index
 * that could hold one: what it remembers between calls is the salt, counts of lines by file and the
 * outcome of its last writes.
 *
 * <p>Two instances of the tool pointed at one directory are not coordinated beyond what an append
 * gives them. Counts are checked against the size of the file, so a line another instance wrote is
 * counted, but nothing here locks the file.
 */
@Component
public class JsonlRunLedger implements RunLedger {

    private static final Logger log = LoggerFactory.getLogger(JsonlRunLedger.class);

    private static final String SWITCHED_OFF = "tracking is switched off in configuration (tracking.enabled is false)";
    private static final String CHECK_FOLDER = "Check that the folder exists, that this tool may write to it and that the disk is not full.";
    private static final String STARTUP_ADVICE = "Set tracking.directory to a folder this tool can write to, or set tracking.enabled to "
            + "false to run with no ledger.";

    /** The key under which a directory that could not be listed is counted among what could not be read. */
    private static final String LISTING = "(the directory)";

    private final Clock clock;
    private final boolean enabled;
    private final Path directory;
    private final Path saltFile;
    private final TrackingPolicy policy;

    /** Held for every append and for every read of a file's bytes, so a reader never sees half of a line this instance is writing. */
    private final Object lock = new Object();

    // Once the ledger is built, everything below is read and written only while holding the lock.
    private LedgerTokens tokens;
    private DayCount counted;
    private final Map<String, Integer> unreadableByFile = new HashMap<>();
    private final Set<String> reportedUnreadable = new HashSet<>();
    private String lastWriteAt;
    private String lastError;
    private String lastErrorAt;
    private int failedWrites;
    private boolean lastWriteFailed;

    /** How many runs a file held when it was this size. A file only grows, so the same size means the same lines. */
    private record DayCount(String fileName, long size, int runs) {
    }

    /** What an append did: a message when it failed, and the count of runs in today's file when that is known. */
    private record Appended(String failure, Integer runsToday) {
    }

    /**
     * @param codeList what tells a line of business the tool knows from any other text: a line the
     *                 code list names, directly or through {@code codelist.line-of-business-aliases}
     */
    public JsonlRunLedger(TrackingProperties tracking, ConfigStoreProperties configStore, EpaProperties epa, Clock clock,
                          ProcedureCodeDirectory codeList) {
        this.clock = clock;
        this.enabled = tracking.enabled();
        this.directory = path("tracking.directory", tracking.directory());
        Path configuredSalt = path("tracking.salt-file", tracking.saltFile());
        Path store = path("config-store.directory", configStore.directory());
        this.saltFile = configuredSalt != null ? configuredSalt
                : (store == null ? Path.of("") : store).resolve(LedgerTokens.DEFAULT_FILE_NAME).toAbsolutePath().normalize();
        Objects.requireNonNull(codeList, "codeList");
        this.policy = new TrackingPolicy(tracking.recordMemberId(), epa.mock(), tracking.recordNpi(), this::tokens, codeList::statesLine);

        // Checked whether or not tracking is on. It costs nothing, and a salt beside the lines it
        // protects would let anyone who copies the folder test member IDs against them.
        if (directory != null && saltFile.startsWith(directory)) {
            throw new IllegalStateException(saltInsideLedger());
        }
        if (!enabled) {
            // Switched off: no check, no directory, no file. Reading what an earlier session wrote still works.
            return;
        }
        if (directory == null) {
            throw new IllegalStateException("tracking.directory is blank, so there is nowhere to keep the run history. " + STARTUP_ADVICE);
        }
        try {
            // A folder made here is for its owner only, where the file system has owners. See OwnerOnly.
            Files.createDirectories(directory, OwnerOnly.directory());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The run history directory " + directory + " could not be created ("
                    + e.getClass().getSimpleName() + "). " + STARTUP_ADVICE);
        }
        try {
            proveWritable();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The run history directory " + directory + " cannot be written to ("
                    + e.getClass().getSimpleName() + "). " + STARTUP_ADVICE);
        }
        Path todayFile = todayFile();
        if (Files.exists(todayFile)) {
            try (FileChannel channel = FileChannel.open(todayFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                // Opening it is the check. Nothing is written: a line that is not a record does not belong in the file.
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Today's ledger file " + todayFile + " cannot be opened for appending ("
                        + e.getClass().getSimpleName() + "), so no run could be recorded. " + STARTUP_ADVICE);
            }
        }
        refuseSaltReachedThroughALink();
        try {
            this.tokens = LedgerTokens.loadOrCreate(saltFile);
        } catch (IOException e) {
            throw new IllegalStateException("The salt file " + saltFile + " could not be read or created (" + e.getClass().getSimpleName()
                    + "). Set tracking.salt-file to a file this tool can read and write, outside the run history directory.");
        }
        if (tracking.recordMemberId() && epa.live()) {
            // Logs a setting and a folder. Once, here, and never per run.
            log.warn("tracking.record-member-id is true on the live profile: real member IDs and dates of service are being written "
                    + "to the run history in {} (one file per day, {}). Set it to false to store a token in their place.",
                    directory, DayFiles.nameFor(LocalDate.now(clock)));
        }
        synchronized (lock) {
            try {
                // Counted now, so a line torn by a crash is reported from the first look at the health of the ledger.
                dayCount(todayFile);
            } catch (IOException e) {
                // Counted as unreadable by the read itself, and found again by the first write.
            }
        }
    }

    private static Path path(String property, String value) {
        if (Texts.blank(value)) {
            return null;
        }
        try {
            return Path.of(value.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException(property + " is not a path this system can use.");
        }
    }

    private String saltInsideLedger() {
        return "The salt file " + saltFile + " is inside the run history directory " + directory + ". The salt is what keeps the "
                + "member tokens in the ledger from being matched to member IDs, so it must be kept somewhere else. Set "
                + "tracking.salt-file to a file outside that directory.";
    }

    /**
     * The same refusal for a salt file that only reaches the ledger directory through a link. The
     * file may not exist yet, and neither may the folders above it, so the part of its path that
     * does exist is followed through its links, and the rest is what creating the file would add
     * beneath that.
     */
    private void refuseSaltReachedThroughALink() {
        Path realSalt;
        Path realDirectory;
        try {
            realDirectory = directory.toRealPath();
            Path existing = saltFile;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return;
            }
            realSalt = existing.toRealPath().resolve(existing.relativize(saltFile)).normalize();
        } catch (IOException e) {
            // The paths cannot be resolved, so the comparison of the paths as written is the one that applies.
            return;
        }
        if (realSalt.startsWith(realDirectory)) {
            throw new IllegalStateException(saltInsideLedger());
        }
    }

    /**
     * Proves the directory can be written to by writing to it. Asking the file system is not
     * enough: what it says about a folder and what happens when a file is made in it are two
     * things, and they differ for an administrator and on some network shares.
     *
     * <p>Not private, so that a test can make the proof itself fail. A folder that really refuses
     * a file is not to be had on every machine: an administrator is refused almost nowhere.
     */
    void proveWritable() throws IOException {
        Path probe = directory.resolve(".write-check-" + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(probe, java.util.EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                OwnerOnly.file())) {
            writeFully(channel, "PAVE startup write check. Safe to delete.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            channel.force(true);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                // A leftover check file is not a ledger file and is never read.
            }
        }
    }

    private Path todayFile() {
        return directory.resolve(DayFiles.nameFor(LocalDate.now(clock)));
    }

    /** The salt. With tracking on it was loaded at startup; with tracking off it is found on first need. */
    private LedgerTokens tokens() {
        synchronized (lock) {
            if (tokens == null) {
                tokens = existingSaltOrTemporary();
            }
            return tokens;
        }
    }

    /**
     * With tracking switched off nothing is written, a salt file included. An existing salt is
     * used, so identifiers still match lines an earlier session wrote. Without one the salt lives
     * in memory for this session: nothing is being stored, so there is nothing for a token to match later.
     */
    private LedgerTokens existingSaltOrTemporary() {
        try {
            if (Files.isRegularFile(saltFile)) {
                return LedgerTokens.load(saltFile);
            }
        } catch (IOException | RuntimeException e) {
            // Falls through. With tracking off, a salt that cannot be read must not stop a run.
        }
        return LedgerTokens.ephemeral();
    }

    /** Applies the tracking policy. A separate step so that its failure, which should not be possible, can be shown to be survived. */
    RunRecord policyApplied(RunRecord draft) {
        return policy.apply(draft);
    }

    @Override
    public LedgerReceipt record(RunRecord draft) {
        // A receipt cannot be made without the record of a run, so a draft that is missing or is not
        // a run is the one thing that is thrown. Neither is a failed write: both are a defect in the
        // caller. A run the builder made always passes, and a line of another kind, such as a note
        // read back from a file, would otherwise be copied into a run line or fail half way through.
        Objects.requireNonNull(draft, "draft");
        if (LedgerLines.isCase(draft)) {
            return recordCase(draft);
        }
        if (!LedgerLines.isRun(draft)) {
            throw new IllegalArgumentException("Only a run can be recorded, and this record is not one.");
        }
        RunRecord record;
        try {
            record = policyApplied(draft);
        } catch (RuntimeException e) {
            String message = "This run was NOT recorded: the ledger failed while preparing its line (" + e.getClass().getSimpleName() + ").";
            // Logs the kind of failure only. The draft holds the member ID and is never logged.
            log.error("A run could not be prepared for the ledger: {}", e.getClass().getName());
            if (enabled) {
                synchronized (lock) {
                    failed(message);
                }
            }
            return new LedgerReceipt(false, TrackingPolicy.withoutMemberData(draft), message, null);
        }
        if (!enabled) {
            return new LedgerReceipt(false, record, "This run was NOT recorded: " + SWITCHED_OFF + ".", null);
        }
        Appended appended = append(() -> LedgerLines.runLine(record), true, "This run");
        return new LedgerReceipt(appended.failure() == null, record, appended.failure(), appended.runsToday());
    }

    /**
     * Appends the case line of a chain case. It holds the run id of the case's last step, the
     * chain, the suite and the case, what the case declared and its result: nothing the tracking
     * policy governs, so it is written as it is. It is not a run and is not counted as one.
     */
    private LedgerReceipt recordCase(RunRecord line) {
        if (!enabled) {
            return new LedgerReceipt(false, line, "The result of this case was NOT recorded: " + SWITCHED_OFF + ".", null);
        }
        Appended appended = append(() -> LedgerLines.caseLine(line), false, "The result of this case");
        return new LedgerReceipt(appended.failure() == null, line, appended.failure(), appended.runsToday());
    }

    /** Appends one line to today's file. Never throws. */
    private Appended append(Supplier<byte[]> line, boolean run, String what) {
        synchronized (lock) {
            // The day is read inside the lock, so a line is never written to yesterday's file after today's was started.
            Path file = todayFile();
            byte[] bytes;
            long sizeBefore;
            try {
                bytes = line.get();
                boolean existed = Files.exists(file);
                sizeBefore = existed ? Files.size(file) : 0;
                if (endsMidLine(file)) {
                    byte[] separated = new byte[bytes.length + 1];
                    separated[0] = '\n';
                    System.arraycopy(bytes, 0, separated, 1, bytes.length);
                    bytes = separated;
                }
                // A day's file is created for its owner only, where the file system has owners. See OwnerOnly.
                try (FileChannel channel = FileChannel.open(file, java.util.EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND), OwnerOnly.file())) {
                    writeFully(channel, bytes);
                    channel.force(true);
                }
                if (!existed) {
                    syncDirectory();
                }
            } catch (IOException | RuntimeException e) {
                String message = what + " was NOT recorded: the ledger file " + file + " could not be written ("
                        + e.getClass().getSimpleName() + "). " + CHECK_FOLDER;
                // Logs which file and what kind of failure. Never the line.
                log.error("A ledger write failed for {}: {}", file, e.getClass().getName());
                failed(message);
                return new Appended(message, countIfReadable(file));
            }
            lastWriteAt = RunStamps.timestamp(clock);
            lastWriteFailed = false;
            // Counted after the write is known to have worked: a count that cannot be made must not turn a kept run into a lost one.
            return new Appended(null, countAfterAppend(file, sizeBefore, bytes.length, run));
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * True when the file ends without a line break, or when that cannot be found out. A spare line
     * break costs an empty line that every reader skips. A missing one would join the new record
     * to a torn one and lose both.
     */
    private static boolean endsMidLine(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1);
            return channel.read(last) != 1 || last.get(0) != '\n';
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Syncs the directory after a day's file is created, so the file's own entry survives a power
     * cut along with its first line. Not every platform lets a directory be opened this way, and
     * the line itself has already been synced, so a failure here is not a failed write.
     */
    private void syncDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            // See above.
        }
    }

    private void failed(String message) {
        lastError = message;
        lastErrorAt = RunStamps.timestamp(clock);
        lastWriteFailed = true;
        failedWrites++;
    }

    private Integer countAfterAppend(Path file, long sizeBefore, int written, boolean run) {
        String name = file.getFileName().toString();
        if (counted != null && counted.fileName().equals(name) && counted.size() == sizeBefore) {
            // Nothing but this line was added since the file was last counted, so it is not read again.
            counted = new DayCount(name, sizeBefore + written, counted.runs() + (run ? 1 : 0));
            return counted.runs();
        }
        try {
            return dayCount(file).runs();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The count of runs in a file, or null when it cannot be read. Null is "cannot say", which is not zero. */
    private Integer countIfReadable(Path file) {
        try {
            return Files.isRegularFile(file) ? dayCount(file).runs() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** How many runs a file holds, reading it only when it has changed size since it was last counted. */
    private DayCount dayCount(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (!Files.exists(file)) {
            counted = new DayCount(name, 0, 0);
            return counted;
        }
        if (counted != null && counted.fileName().equals(name) && counted.size() == Files.size(file)) {
            return counted;
        }
        LedgerLines.Content content = read(file);
        counted = new DayCount(name, content.byteCount(), content.runs());
        return counted;
    }

    /**
     * Reads one file. The bytes are taken while no line is being written, so the last line is never
     * half of one this instance is still writing. A query does not hold the lock while it parses them.
     */
    private LedgerLines.Content read(Path file) throws IOException {
        String name = file.getFileName().toString();
        byte[] bytes;
        synchronized (lock) {
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                // A file that cannot be read at all is counted once, so the health of the ledger never reads as clean.
                unreadableByFile.put(name, 1);
                if (reportedUnreadable.add(name)) {
                    // Logs which file and what kind of failure.
                    log.error("The ledger file {} could not be read: {}", file, e.getClass().getName());
                }
                throw e;
            }
            reportedUnreadable.remove(name);
        }
        LedgerLines.Content content = LedgerLines.parse(bytes);
        synchronized (lock) {
            unreadableByFile.put(name, content.unreadable());
        }
        return content;
    }

    private List<RunRecord> linesOf(DayFile file) {
        try {
            return read(file.path()).lines();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<DayFile> dayFiles() {
        try {
            List<DayFile> files = DayFiles.newestFirst(directory);
            synchronized (lock) {
                unreadableByFile.remove(LISTING);
                reportedUnreadable.remove(LISTING);
            }
            return files;
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                unreadableByFile.put(LISTING, 1);
                if (reportedUnreadable.add(LISTING)) {
                    // Logs which folder and what kind of failure.
                    log.error("The run history directory {} could not be listed: {}", directory, e.getClass().getName());
                }
            }
            return List.of();
        }
    }

    @Override
    public List<RunRecord> query(RunQuery given) {
        RunQuery query = given == null ? RunQuery.all() : given;
        int limit = query.limit() == null ? 0 : query.limit();
        // A run is stamped a moment before its line is written, so one stamped just before
        // midnight can sit in the next day's file. A day either side of the range is read so that
        // such a run is found under the day its own timestamp names.
        LocalDate oldestFile = query.from() == null ? null : query.from().minusDays(1);
        LocalDate newestFileWithRuns = query.to() == null ? null : query.to().plusDays(1);
        List<RunRecord> matching = new ArrayList<>();
        Map<String, RunRecord> latestNotes = new HashMap<>();
        Map<String, RunRecord> latestCases = new HashMap<>();
        for (DayFile file : dayFiles()) {
            if (oldestFile != null && file.day().isBefore(oldestFile)) {
                break;
            }
            // A newer file than the range can still hold a note on a run inside it.
            boolean mayHoldRuns = newestFileWithRuns == null || !file.day().isAfter(newestFileWithRuns);
            List<RunRecord> lines = linesOf(file);
            for (int i = lines.size() - 1; i >= 0; i--) {
                RunRecord line = lines.get(i);
                if (LedgerLines.isNote(line)) {
                    latestNotes.putIfAbsent(line.runId(), line);
                    continue;
                }
                if (LedgerLines.isCase(line)) {
                    latestCases.putIfAbsent(line.runId(), line);
                    continue;
                }
                if (!mayHoldRuns) {
                    continue;
                }
                RunRecord run = withNote(line, latestNotes.get(line.runId())).withCase(latestCases.get(line.runId()));
                if (accepts(query, run, file.day())) {
                    matching.add(run);
                    if (limit > 0 && matching.size() == limit) {
                        return matching;
                    }
                }
            }
        }
        return matching;
    }

    private boolean accepts(RunQuery query, RunRecord run, LocalDate fileDay) {
        if (query.from() != null || query.to() != null) {
            LocalDate day = dayOf(run, fileDay);
            if (query.from() != null && day.isBefore(query.from())) {
                return false;
            }
            if (query.to() != null && day.isAfter(query.to())) {
                return false;
            }
        }
        if (query.protocol() != null && query.protocol() != run.protocol()) {
            return false;
        }
        if (query.operation() != null && query.operation() != run.operation()) {
            return false;
        }
        // The last step of a chain case holds two results: its own and the case's. A filter for
        // either finds it, so a chain that failed as a whole is among the failed runs.
        if (query.result() != null && query.result() != run.result() && query.result() != run.caseResult()) {
            return false;
        }
        if (query.outcome() != null && !query.outcome().strip().equalsIgnoreCase(run.outcome())) {
            return false;
        }
        // A filter that is blank and not null matches nothing. Treating it as "no filter" would
        // hand a caller that lost its chain id every run in the ledger as that chain.
        if (query.chainId() != null && !query.chainId().equals(run.chainId())) {
            return false;
        }
        if (query.suiteRunId() != null && !query.suiteRunId().equals(run.suiteRunId())) {
            return false;
        }
        if (query.correlationKey() != null) {
            String stored = run.correlation().get(query.correlationKey());
            return stored != null && policy.matches(stored, query.correlationValue());
        }
        return true;
    }

    /** The day a run belongs to: the one its own timestamp names, or the day of its file when the timestamp does not read as a date. */
    private static LocalDate dayOf(RunRecord run, LocalDate fileDay) {
        String timestamp = run.timestamp();
        LocalDate day = timestamp == null || timestamp.length() < 10 ? null : IsoDates.parse(timestamp.substring(0, 10));
        return day == null ? fileDay : day;
    }

    private static RunRecord withNote(RunRecord run, RunRecord note) {
        return note == null ? run : run.toBuilder().note(note.note()).testerRef(note.testerRef()).build();
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        if (Texts.blank(runId)) {
            return Optional.empty();
        }
        String wanted = runId.strip();
        RunRecord latestNote = null;
        RunRecord latestCase = null;
        for (DayFile file : dayFiles()) {
            List<RunRecord> lines = linesOf(file);
            for (int i = lines.size() - 1; i >= 0; i--) {
                RunRecord line = lines.get(i);
                if (!wanted.equals(line.runId())) {
                    continue;
                }
                if (LedgerLines.isCase(line)) {
                    latestCase = latestCase == null ? line : latestCase;
                    continue;
                }
                if (!LedgerLines.isNote(line)) {
                    return Optional.of(withNote(line, latestNote).withCase(latestCase));
                }
                if (latestNote == null) {
                    latestNote = line;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public LedgerReceipt addNote(String runId, String note, String testerRef) {
        RunRecord known = find(runId).orElseThrow(() -> RunIds.notFound(runId));
        if (note == null && testerRef == null) {
            throw ValidationException.of("note", "Give a note, a tester reference or both. Nothing was changed.");
        }
        // The endpoint checks the same limits first. Checked here too, so no caller can append more
        // than a run line may hold: a note line is not a record and the record's own check never sees it.
        Map<String, String> tooLong = RunInputs.textForTheLedger(note, testerRef, null);
        if (!tooLong.isEmpty()) {
            throw new ValidationException("The note was not added. Nothing was changed.",
                    tooLong.entrySet().stream().map(e -> new FieldError(e.getKey(), e.getValue())).toList());
        }
        if (!enabled) {
            return new LedgerReceipt(false, known, "The note was NOT recorded: " + SWITCHED_OFF + ".", null);
        }
        synchronized (lock) {
            // Read again inside the lock, so two amendments made together build on each other.
            RunRecord current = find(runId).orElse(known);
            // A value that was not given leaves what the run has. A blank one clears it.
            String newNote = note == null ? current.note() : Texts.trimToNull(note);
            String newTesterRef = testerRef == null ? current.testerRef() : Texts.trimToNull(testerRef);
            String timestamp = RunStamps.timestamp(clock);
            Appended appended = append(() -> LedgerLines.noteLine(current.runId(), timestamp, newNote, newTesterRef), false, "The note");
            if (appended.failure() != null) {
                // The run as the ledger still has it. Showing the new note here would say it was kept.
                return new LedgerReceipt(false, current, appended.failure(), appended.runsToday());
            }
            return new LedgerReceipt(true, current.toBuilder().note(newNote).testerRef(newTesterRef).build(), null, appended.runsToday());
        }
    }

    @Override
    public LedgerHealth health() {
        String fileName = DayFiles.nameFor(LocalDate.now(clock));
        synchronized (lock) {
            Integer runsToday = 0;
            boolean usable = false;
            if (directory != null) {
                Path file = directory.resolve(fileName);
                usable = Files.isDirectory(directory) && (!Files.exists(file) || Files.isRegularFile(file));
                try {
                    runsToday = dayCount(file).runs();
                } catch (IOException e) {
                    // Not none: unknown. The file is also counted as unreadable, which the same answer shows.
                    runsToday = null;
                }
            }
            // Writable means: the write check at startup passed, no write has failed since the last
            // one that worked, and the folder and today's file are still what they should be. The
            // folder is not written to on every look, because this is asked for all day long.
            boolean writable = enabled && usable && !lastWriteFailed;
            int unreadable = unreadableByFile.values().stream().mapToInt(Integer::intValue).sum();
            return new LedgerHealth(enabled, directory == null ? null : directory.toString(), writable, fileName, runsToday, unreadable,
                    lastWriteAt, lastError, lastErrorAt, failedWrites);
        }
    }

    @Override
    public String tokenFor(String identifier) {
        return policy.stored(identifier);
    }

    @Override
    public boolean matches(String stored, String identifier) {
        return policy.matches(stored, identifier);
    }
}
