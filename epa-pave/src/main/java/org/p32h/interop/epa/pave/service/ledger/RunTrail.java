package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.config.TrailProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trail: the last runs' requests and responses, one file per run, beside the ledger and never
 * in it.
 *
 * <p>The ledger keeps a line per run that may never hold wire JSON or, on live, a member ID. The
 * vendor needs to see what was sent and answered, and a reload of the Workbench needs the case
 * again, so this keeps what the browser was answered, whose wire view is already masked, and the
 * case as posted less what can hold a secret. Member data stays inside: it is what the files are
 * for. That is why the folder and every file are for their owner only, the files are bounded in
 * number and in size, and nothing of an entry, not even its folder or a run id, reaches the log.
 *
 * <p>A file is named {@code <epoch millis, 13 digits>-<runId>.json}, so the order of the names is
 * the order the runs were kept in, and the oldest are found and removed without opening a file.
 * A file is written whole under a temporary name and then moved into place, so a reader never
 * sees half of one and a crash leaves at most a temporary file, which the next start removes.
 * A file that cannot be read is skipped and counted, never fatal: the trail is a convenience
 * beside the ledger, and nothing that goes wrong in it may stop a run.
 */
public class RunTrail {

    private static final Logger log = LoggerFactory.getLogger(RunTrail.class);

    /** 256 MB in all, the bound R-21 set: a few hundred runs with attachments fit, a full disk does not follow. */
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    public static final String SWITCHED_OFF = "The trail is switched off on this installation (trail.enabled).";

    private static final String SUFFIX = ".json";
    private static final String TEMPORARY = ".tmp";
    private static final int MILLIS_DIGITS = 13;

    private final boolean enabled;
    private final int maxEntries;
    private final long maxBytes;
    private final Path folder;
    private final Clock clock;
    private final JsonMapper json;

    /** Held for every write, read and removal, so a purge never removes a file a reader is opening. */
    private final Object lock = new Object();

    private int damaged;

    /** What the trail holds, as Configuration > Advanced > Trail shows it. */
    public record Summary(boolean enabled, int count, int maxEntries, long bytes, String folder, int damaged) {
    }

    /**
     * What the trail holds for one run.
     *
     * @param keptAt when it was kept, from the file's name, or null when it is not kept
     * @param entry  the entry as written, or null when it is not kept
     * @param line   why it is not kept, or null when it is
     */
    public record Lookup(String runId, Instant keptAt, JsonNode entry, String line) {

        public boolean kept() {
            return entry != null;
        }

        /** The run id only. The entry holds member data and the wire. */
        @Override
        public String toString() {
            return "Lookup[" + runId + ", kept=" + kept() + "]";
        }
    }

    public RunTrail(TrailProperties trail, TrackingProperties tracking, Clock clock) {
        this(trail, tracking, clock, MAX_BYTES);
    }

    /** The same with another size bound, so a test can reach the bound with a few small files. */
    public RunTrail(TrailProperties trail, TrackingProperties tracking, Clock clock, long maxBytes) {
        this.enabled = trail.enabled();
        this.maxEntries = Math.max(1, trail.maxEntries());
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.json = JsonSafe.mapper();
        String directory = Texts.present(tracking.directory()) ? tracking.directory() : "./run-history";
        this.folder = Path.of(directory).resolve("trail").toAbsolutePath().normalize();
        if (enabled) {
            removeTemporaryFiles();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public Path folder() {
        return folder;
    }

    /** The line a run the trail does not hold is answered with. */
    public String notKeptLine() {
        return enabled ? "Not kept: the trail holds the last " + maxEntries + " runs." : SWITCHED_OFF;
    }

    /**
     * True when the text has the shape of an id this tool mints for a run. Only such an id is ever
     * put into a file name or looked for, so no text from a request reaches the file system.
     */
    public static boolean isRunId(String text) {
        if (text == null || !text.startsWith("run-") || text.length() != 40) {
            return false;
        }
        try {
            return UUID.fromString(text.substring(4)).toString().equals(text.substring(4));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Keeps one entry, then removes the oldest past the bounds. Throws when the file cannot be
     * written; the caller decides that this never touches the answer.
     */
    public void keep(String runId, JsonNode entry) throws IOException {
        if (!enabled || !isRunId(runId) || entry == null) {
            return;
        }
        byte[] bytes = json.writeValueAsBytes(entry);
        synchronized (lock) {
            ensureFolder();
            long now = clock.millis();
            String name = millis(now) + "-" + runId + SUFFIX;
            Path target = folder.resolve(name);
            Path temporary = folder.resolve("." + name + TEMPORARY);
            try {
                Files.deleteIfExists(temporary);
                Files.createFile(temporary, OwnerOnly.file());
                try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    out.write(bytes);
                }
                move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            purge();
        }
    }

    /** What the trail holds for a run. The id must be one {@link #isRunId} accepts. */
    public Lookup find(String runId) {
        if (!enabled || !isRunId(runId)) {
            return new Lookup(runId, null, null, notKeptLine());
        }
        synchronized (lock) {
            Path file = fileOf(runId);
            if (file == null) {
                return new Lookup(runId, null, null, notKeptLine());
            }
            try {
                JsonNode entry = json.readTree(Files.readAllBytes(file));
                if (entry == null || !entry.isObject() || !runId.equals(JsonSafe.presentText(entry, "runId"))) {
                    return damaged(runId);
                }
                return new Lookup(runId, Instant.ofEpochMilli(millisOf(file.getFileName().toString())), entry, null);
            } catch (NoSuchFileException e) {
                return new Lookup(runId, null, null, notKeptLine());
            } catch (IOException | JacksonException e) {
                return damaged(runId);
            }
        }
    }

    /** The bytes of the file kept for a run, or null when none is. */
    public byte[] bytes(String runId) {
        Lookup found = find(runId);
        if (!found.kept()) {
            return null;
        }
        return json.writeValueAsBytes(found.entry());
    }

    public Summary summary() {
        synchronized (lock) {
            List<Path> files = entries();
            long bytes = 0;
            for (Path file : files) {
                bytes += size(file);
            }
            return new Summary(enabled, files.size(), maxEntries, bytes, folder.toString(), damaged);
        }
    }

    /** Removes every entry. Works with the trail switched off too, so what an earlier start kept can go. */
    public int empty() {
        synchronized (lock) {
            int removed = 0;
            for (Path file : entries()) {
                if (remove(file)) {
                    removed++;
                }
            }
            // Counts only: a path or a run id says what was tested.
            log.info("The trail was emptied: {} runs removed.", removed);
            return removed;
        }
    }

    private Lookup damaged(String runId) {
        damaged++;
        // Counts only, never the file or the run id.
        log.warn("A file of the trail could not be read and was skipped ({} so far).", damaged);
        return new Lookup(runId, null, null, "Not kept: the trail's file for this run could not be read.");
    }

    private void purge() {
        List<Path> files = entries();
        long bytes = 0;
        long[] sizes = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            sizes[i] = size(files.get(i));
            bytes += sizes[i];
        }
        int count = files.size();
        int removed = 0;
        for (int i = 0; i < files.size() && (count > maxEntries || bytes > maxBytes); i++) {
            if (remove(files.get(i))) {
                removed++;
            }
            count--;
            bytes -= sizes[i];
        }
        if (removed > 0) {
            // Counts only.
            log.debug("The trail removed {} of its oldest runs to stay within its bounds.", removed);
        }
    }

    /** The trail's entry files, oldest first. Anything else in the folder is left alone. */
    private List<Path> entries() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return files;
        }
        try (DirectoryStream<Path> listed = Files.newDirectoryStream(folder)) {
            for (Path file : listed) {
                if (isEntryName(file.getFileName().toString())) {
                    files.add(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Types only.
            log.warn("The trail's folder could not be listed ({}).", e.getClass().getSimpleName());
        }
        Collections.sort(files, (a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return files;
    }

    private Path fileOf(String runId) {
        String ending = "-" + runId + SUFFIX;
        Path found = null;
        for (Path file : entries()) {
            if (file.getFileName().toString().endsWith(ending)) {
                found = file;
            }
        }
        return found;
    }

    static boolean isEntryName(String name) {
        if (name.length() != MILLIS_DIGITS + 1 + 40 + SUFFIX.length() || !name.endsWith(SUFFIX) || name.charAt(MILLIS_DIGITS) != '-') {
            return false;
        }
        for (int i = 0; i < MILLIS_DIGITS; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return isRunId(name.substring(MILLIS_DIGITS + 1, name.length() - SUFFIX.length()));
    }

    private static long millisOf(String name) {
        return Long.parseLong(name.substring(0, MILLIS_DIGITS));
    }

    private static String millis(long value) {
        String digits = Long.toString(Math.max(0, value));
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < MILLIS_DIGITS; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    private void ensureFolder() throws IOException {
        if (!Files.isDirectory(folder)) {
            Path parent = folder.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent, OwnerOnly.directory());
            }
            Files.createDirectory(folder, OwnerOnly.directory());
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean remove(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    private void removeTemporaryFiles() {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> listed = Files.newDirectoryStream(folder)) {
            for (Path file : listed) {
                String name = file.getFileName().toString();
                if (name.startsWith(".") && name.endsWith(TEMPORARY) && isEntryName(name.substring(1, name.length() - TEMPORARY.length()))) {
                    remove(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Types only.
            log.warn("The trail's folder could not be listed at start ({}).", e.getClass().getSimpleName());
        }
    }

    /** When an entry was kept, to the millisecond, for the answer that names it. */
    public static String keptAtText(Instant keptAt) {
        return keptAt == null ? null : keptAt.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
