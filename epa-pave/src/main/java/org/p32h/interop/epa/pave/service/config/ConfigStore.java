package org.p32h.interop.epa.pave.service.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Ids;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.PostmanAuth;
import org.p32h.interop.epa.pave.postman.PostmanExample;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Holds the uploaded Postman environments and collections, which one is active, and the operator's
 * mapping corrections, and keeps all of it on disk so it survives a restart.
 *
 * <p>What is on disk, under {@code config-store.directory}:
 * <ul>
 *   <li>{@code store.json}: which environments and collections exist, the kind of each environment,
 *       the active environment and the mapping corrections. It is the one point of commit: a
 *       change has happened when this file has been replaced, and not before</li>
 *   <li>{@code environments/} and {@code collections/}: each uploaded file, byte for byte as it
 *       was uploaded. A file's name ends in a hash of its content, so replacing an environment
 *       writes a new file beside the old one, and the old one is removed only after the commit</li>
 * </ul>
 * Every file is written to a temporary name and then moved into place, so a crash leaves either
 * the old file or the new one and never half of either. A crash between writing an uploaded file
 * and the commit leaves a whole file that nothing names: it is reported at the next start and on
 * the Config tab, and left where it is.
 *
 * <p>The production unlock is not here. It is never written anywhere.
 *
 * <p>Every change builds a new {@link ConfigSnapshot} with the next epoch. A reader takes the
 * current snapshot once and works against it.
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    static final String STATE_FILE = "store.json";
    static final String ENVIRONMENTS = "environments";
    static final String COLLECTIONS = "collections";
    /** The version a store with no settings is written as, so an older build still opens it (R-05). */
    static final int STATE_VERSION = 1;
    /** The version a store is written as from the first save of a setting on (R-05). */
    static final int SETTINGS_VERSION = 2;
    /** The copy of store.json taken once, before it is first written as version 2: going back needs one file renamed. */
    static final String BEFORE_V2 = "store.json.before-v2";
    private static final String TEMP_MARK = ".tmp-";
    /** The file a running copy holds a lock on, so that a second copy on the same folder is refused at its start (R-32). */
    static final String LOCK_FILE = ".lock";
    /** Where a stored file that cannot be read is moved by "Remove it from the store": aside, never deleted (R-32). */
    static final String UNREADABLE = "unreadable";

    /** The stores of this JVM holding the lock of a folder, by folder: a system lock is held per JVM, not per store. */
    private static final Map<Path, HeldLock> LOCKS = new java.util.HashMap<>();

    private static final class HeldLock {
        private final FileChannel channel;
        private final java.nio.channels.FileLock lock;
        private int holders = 1;

        private HeldLock(FileChannel channel, java.nio.channels.FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }
    /**
     * How long a replace that was refused is tried again. A virus scanner, an indexer or a sync
     * client on Windows holds a file it has just seen for moments, and a refusal it causes is gone
     * well within a second.
     */
    static final long REPLACE_RETRY_MILLIS = 1000;
    static final long REPLACE_RETRY_STEP_MILLIS = 100;
    /**
     * Room a file system needs beyond the bytes of a file, for its directory entry and blocks. A
     * write that failed with less than this free beside the bytes failed for want of space.
     */
    static final long SPACE_MARGIN_BYTES = 64 * 1024;

    /**
     * The file system calls whose failures this store tells apart, behind one seam. Windows alone
     * refuses to replace a file marked read-only or one another program holds open, and a full disk
     * cannot be made where the tests run, so a test stands in for these calls to make each of those
     * failures happen on any machine. Everything else the store does goes to the file system directly.
     */
    interface Disk {

        /** Writes the bytes to a new file, created for the owner alone, and forces them to the disk. */
        void writeNew(Path file, byte[] bytes) throws IOException;

        /** Puts the file in place of the target, in one step where the file system allows it. */
        void replace(Path file, Path target) throws IOException;

        /** True when the file carries the read-only attribute of Windows. False where there is none, or it cannot be read. */
        boolean readOnly(Path file);

        /** Takes that attribute off the file. False when it could not be taken off. */
        boolean clearReadOnly(Path file);

        /** The bytes this account may still write on the disk that holds the folder, or -1 when that cannot be read. */
        long usableSpace(Path folder);

        /** True where a file another program holds open cannot be replaced, which is Windows. */
        boolean locksFiles();

        /** Waits between two attempts to replace a file. */
        void pause(long millis);
    }

    /** The file system itself. */
    static final Disk SYSTEM_DISK = new SystemDisk();

    private static final class SystemDisk implements Disk {

        @Override
        public void writeNew(Path file, byte[] bytes) throws IOException {
            // Created for the owner alone where the file system has owners: an environment file can hold a client secret.
            try (FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    OwnerOnly.file())) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        @Override
        public void replace(Path file, Path target) throws IOException {
            try {
                Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public boolean readOnly(Path file) {
            try {
                return Files.readAttributes(file, DosFileAttributes.class).isReadOnly();
            } catch (IOException | RuntimeException e) {
                // No such attribute here, or the file is gone: either way nothing marks it read-only.
                return false;
            }
        }

        @Override
        public boolean clearReadOnly(Path file) {
            try {
                Files.setAttribute(file, "dos:readonly", false);
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public long usableSpace(Path folder) {
            try {
                return Files.getFileStore(folder).getUsableSpace();
            } catch (IOException | RuntimeException e) {
                return -1;
            }
        }

        @Override
        public boolean locksFiles() {
            return System.getProperty("os.name", "").startsWith("Windows");
        }

        @Override
        public void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                // A stop is under way: the attempts that are left run at once, and the failure is reported as it stands.
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * What an environment upload did.
     *
     * @param after the configuration as the upload left it. Whatever describes the upload is built
     *              from this, so it cannot be mixed with a change another request made a moment later
     */
    record EnvironmentUpload(StoredEnvironment environment, boolean replaced, List<String> warnings, ConfigSnapshot after) {

        long epoch() {
            return after.epoch();
        }
    }

    /** What a collection upload did. */
    record CollectionUpload(StoredCollection collection, boolean replaced, List<String> warnings, ConfigSnapshot after) {

        long epoch() {
            return after.epoch();
        }
    }

    /**
     * An upload with the answer that describes it, which was built from the configuration the
     * upload leaves before anything was written: a description that failed to build then stops the
     * upload instead of answering "internal error" for a file that was stored all the same.
     */
    record Described<U, V>(U upload, V view) {
    }

    /** What removing an environment or a collection did. */
    record Removal(String name, boolean wasActive, List<String> warnings, long epoch) {
    }

    /** What a mapping correction did. */
    record MappingChange(StoredCollection collection, StoredCollection.Mapped request, boolean changed, ConfigSnapshot after) {

        long epoch() {
            return after.epoch();
        }
    }

    private final ConfigStoreProperties properties;
    private final Clock clock;
    /** The {@code onyx.variables} of application.yml, through which the addresses of an environment are read as well. */
    private final Map<String, String> applicationYml;
    private final Path directory;
    private final Disk disk;
    /** Files that were in the two upload directories at startup and that the state file did not name. */
    private final List<Path> strays;
    private volatile ConfigSnapshot current;
    /** The version store.json was last read or written as. Once version 2, it stays version 2. */
    private volatile int version = STATE_VERSION;
    /**
     * The entries of store.json whose file could not be read at the start: left out of the
     * configuration, listed, and written back as they were on every commit, so nothing is lost
     * until the tester removes one or uploads its environment or collection again.
     */
    private volatile List<Unreadable> unreadable = List.of();
    /** True while this store counts among the holders of its folder's lock. */
    private boolean locked;

    /**
     * Opens the store, creating its directory. Fails, naming the path, when the directory cannot be
     * created or written or when a stored file cannot be read: a store that only fails at the
     * first upload would fail in front of a tester and not in front of whoever configured it.
     */
    public ConfigStore(ConfigStoreProperties properties, Clock clock) {
        this(properties, null, clock);
    }

    /**
     * @param onyx where {@code onyx.variables} comes from: the last layer a url is resolved through,
     *             and so a layer the address of a production environment can come from
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ConfigStore(ConfigStoreProperties properties, org.p32h.interop.epa.pave.config.OnyxProperties onyx, Clock clock) {
        this(properties, onyx, clock, SYSTEM_DISK);
    }

    /** As the application's constructor, with the file system calls a test stands in for. */
    ConfigStore(ConfigStoreProperties properties, org.p32h.interop.epa.pave.config.OnyxProperties onyx, Clock clock, Disk disk) {
        this.properties = properties;
        this.clock = clock;
        this.disk = disk;
        this.applicationYml = onyx == null ? Map.of() : onyx.variables();
        if (Texts.blank(properties.directory())) {
            throw new IllegalStateException("config-store.directory is blank. Set it to the directory uploaded Postman files are kept in.");
        }
        try {
            this.directory = Path.of(properties.directory().strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("config-store.directory is \"" + properties.directory() + "\", which is not a usable path.", e);
        }
        prepareDirectories();
        ConfigSnapshot loaded;
        try {
            loaded = load();
        } catch (RuntimeException e) {
            // A store that does not open holds nothing: the lock goes with it.
            close();
            throw e;
        }
        this.strays = strays(loaded);
        if (!strays.isEmpty()) {
            // A count and the directory. The Config tab names each file.
            log.warn("The configuration store at {} holds files that it does not use: {}. They may hold credentials from an upload "
                    + "that did not finish. The Config tab lists them.", directory, strays.size());
        }

        String wanted = Texts.trimToNull(properties.activeEnvironment());
        String active = loaded.activeName();
        if (wanted != null) {
            if (loaded.byName(wanted) != null) {
                active = wanted;
            } else {
                // The environment that was asked for is not there, and no other one is put in its
                // place. The tool starts, so that the environment can be uploaded.
                active = null;
                // The configured name only. Nothing from an uploaded file.
                log.warn("config-store.active-environment names \"{}\", which is not in the configuration store. No environment is "
                        + "active. Upload it on the Config tab and activate it, or clear the property.", wanted);
            }
        }
        // The epoch starts above every epoch an earlier run of this installation used, so a browser
        // tab left open across a restart can never hold an epoch that reads as current.
        long epoch = Math.max(clock.millis(), loaded.epoch() + 1);
        this.current = new ConfigSnapshot(epoch, loaded.environments(), active, loaded.collections(), loaded.remembered(),
                applicationYml);
        // Counts and names only. Never a variable value.
        log.info("Configuration store ready at {}: environments {}, collections {}, active environment: {}", directory,
                current.environments().size(), current.collections().size(), active == null ? "none" : "\"" + active + "\"");
    }

    /** The whole configuration as it is now. Take it once and work against it. */
    ConfigSnapshot snapshot() {
        return current;
    }

    long epoch() {
        return current.epoch();
    }

    boolean allowProdWrites() {
        return properties.allowProdWrites();
    }

    Path directory() {
        return directory;
    }

    /**
     * An entry of store.json whose file could not be read at the start.
     *
     * @param list  "environments" or "collections": the array of store.json the entry is kept in
     * @param file  the file as store.json names it, relative to the store folder; what "Remove it
     *              from the store" is given back
     * @param key   the environment's name or the collection's id the entry stands for, or null when
     *              it does not say. An upload under that name or id takes its place
     * @param line  what is wrong and what to do, in words
     * @param entry the entry as store.json holds it, written back unchanged
     */
    record Unreadable(String list, String file, String key, String line, JsonNode entry) {

        /** Names the file only: the entry can hold settings with a secret in them. */
        @Override
        public String toString() {
            return "Unreadable[" + list + ", " + file + "]";
        }
    }

    /** The stored files that could not be read, as listed under In force now. */
    List<Unreadable> unreadable() {
        return unreadable;
    }

    /** What forgetting a production address did: the address as it was listed, and the new epoch. */
    record Forgotten(String forgotten, long epoch) {
    }

    /**
     * Forgets a production address remembered from an environment that is no longer stored as
     * prod (R-35): the way back from a slip of the mouse. The name must be typed exactly as the
     * address was remembered under, and an address a stored prod environment holds is never
     * forgotten, whoever else it was remembered from.
     *
     * @param listed      the address as the list of production addresses shows it
     * @param environment the environment's name, typed
     */
    synchronized Forgotten forgetDestination(String listed, String environment) {
        ConfigSnapshot before = current;
        String wanted = listed.strip();
        List<ConfigSnapshot.KnownDestination> matching = before.remembered().stream()
                .filter(known -> !known.secret() && (known.listed().equals(wanted) || known.destination().shown().equals(wanted))).toList();
        if (matching.isEmpty()) {
            throw new NotFoundException("No address of that text is remembered as production. It may have been forgotten already.",
                    "Read the list again.");
        }
        List<ConfigSnapshot.KnownDestination> named = matching.stream().filter(known -> known.environment().equals(environment)).toList();
        if (named.isEmpty()) {
            throw ValidationException.of("environment", "That is not the name this address was remembered from. Type the name exactly as "
                    + "the list gives it, letter case and spaces included.");
        }
        StoredEnvironment owner = before.byName(environment);
        if (owner != null && owner.kind() == EnvironmentKind.PROD) {
            throw ValidationException.of("destination", "This address belongs to the production environment \"" + quoted(environment)
                    + "\", which is stored. An address of a stored production environment is never forgotten.");
        }
        List<ConfigSnapshot.KnownDestination> remembered = new ArrayList<>(before.remembered());
        remembered.removeAll(named);
        ConfigSnapshot next = new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), before.collections(),
                remembered, before.applicationYml());
        // The snapshot adds back every address of the prod environments stored now: one that comes
        // back is held by one of them, and stays.
        for (ConfigSnapshot.KnownDestination known : next.remembered()) {
            StoredEnvironment holder = next.byName(known.environment());
            if (holder != null && holder.kind() == EnvironmentKind.PROD
                    && named.stream().anyMatch(gone -> gone.destination().equals(known.destination()))) {
                throw ValidationException.of("destination", "This address belongs to the production environment \""
                        + quoted(known.environment()) + "\", which is stored. An address of a stored production environment is never "
                        + "forgotten.");
            }
        }
        commit(next, null, null);
        // The environment's name only, which the list shows. The address is no secret, but nothing more is needed.
        log.info("Forgot a production address remembered from \"{}\"", quoted(environment));
        return new Forgotten(named.get(0).listed(), current.epoch());
    }

    /** What removing an unreadable stored file did: the file as listed, where it was moved (null when it was not there), the new epoch. */
    record UnreadableRemoved(String removed, String movedTo, long epoch) {
    }

    /**
     * "Remove it from the store": the file moves to {@link #UNREADABLE} beside store.json, never
     * deleted, and its entry leaves store.json. A file that is not there any more only loses its
     * entry. The move comes first and is put back when store.json cannot be written, so a failure
     * changes nothing.
     */
    synchronized UnreadableRemoved removeUnreadable(String file) {
        String wanted = file == null ? "" : file.strip();
        Unreadable found = null;
        for (Unreadable each : unreadable) {
            if (each.file().equals(wanted)) {
                found = each;
                break;
            }
        }
        if (found == null) {
            throw new NotFoundException("No stored file of that name is listed as unreadable. It may have been removed already.",
                    "Read the list again.");
        }
        Path source = null;
        try {
            Path resolved = directory.resolve(found.file()).normalize();
            source = resolved.startsWith(directory) && Files.isRegularFile(resolved) ? resolved : null;
        } catch (InvalidPathException e) {
            // A name that is no path holds no file to move: only the entry goes.
        }
        Path moved = null;
        if (source != null) {
            Path aside = directory.resolve(UNREADABLE);
            try {
                Files.createDirectories(aside, OwnerOnly.directory());
                moved = aside.resolve(source.getFileName().toString());
                for (int n = 2; Files.exists(moved); n++) {
                    moved = aside.resolve(source.getFileName() + "." + n);
                }
                Files.move(source, moved);
            } catch (IOException | RuntimeException e) {
                // The paths and the type of the failure. Never content.
                log.error("The configuration store could not move {} to {} ({})", source, aside, e.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + source + " could not be moved to " + aside + ". Check that "
                        + "nothing holds it open and that the folder can be written, then try again. Nothing was changed.", e);
            }
        }
        List<Unreadable> after = new ArrayList<>(unreadable);
        after.remove(found);
        ConfigSnapshot before = current;
        ConfigSnapshot next = new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), before.collections(),
                before.remembered(), before.applicationYml());
        try {
            commitAll(next, List.of(), List.of(), after);
        } catch (RuntimeException e) {
            if (moved != null) {
                try {
                    Files.move(moved, source);
                } catch (IOException | RuntimeException back) {
                    // The paths only: the entry is still in store.json, so the next start lists the file as missing.
                    log.error("The configuration store could not move {} back to {} ({})", moved, source, back.getClass().getSimpleName());
                }
            }
            throw e;
        }
        // The file as store.json named it, and where it went. Never content.
        log.info("Removed the unreadable stored file {} from the configuration store{}", found.file(), moved == null ? "" : ", moved to " + moved);
        return new UnreadableRemoved(found.file(), moved == null ? null : moved.toString(), current.epoch());
    }

    /**
     * What is wrong with the configuration as it stands, in words, for the Config tab:
     * {@code config-store.active-environment} names an environment that has not been uploaded, or
     * the store directory holds a file the store does not use.
     */
    List<String> problems() {
        ConfigSnapshot snapshot = current;
        List<String> problems = new ArrayList<>();
        String wanted = Texts.trimToNull(properties.activeEnvironment());
        if (wanted != null && snapshot.byName(wanted) == null) {
            problems.add("config-store.active-environment names \"" + wanted + "\", which is not in the configuration store, so it "
                    + "was not activated at startup. Upload an environment with exactly that name and activate it, or clear the property.");
        }
        // Listed here as well as in the effective view's unreadable list, so a page that draws only
        // the problems still shows that something was left out.
        unreadable.forEach(each -> problems.add(each.line()));
        for (StoredEnvironment environment : snapshot.environments()) {
            if (environment.settingsProblem() != null) {
                problems.add("The settings of the environment \"" + quoted(environment.name()) + "\" in " + directory.resolve(STATE_FILE)
                        + " cannot be read: " + environment.settingsProblem() + ". They are kept as stored and not used, and nothing is sent "
                        + "under that environment until they are saved again in Configuration > Environments.");
            }
        }
        if (!strays.isEmpty()) {
            Set<Path> used = used(snapshot);
            for (Path stray : strays) {
                // An upload of the same file since startup gives it the same name, and then it is in use.
                if (!used.contains(stray) && Files.exists(stray)) {
                    problems.add("The file " + stray + " is in the configuration store and nothing uses it. It can be left over from an "
                            + "upload that did not finish, and it may hold credentials. Delete it by hand.");
                }
            }
        }
        return problems;
    }

    private Set<Path> used(ConfigSnapshot snapshot) {
        Set<Path> used = new HashSet<>();
        snapshot.environments().forEach(environment -> used.add(directory.resolve(environment.file()).normalize()));
        snapshot.collections().forEach(collection -> used.add(directory.resolve(collection.file()).normalize()));
        return used;
    }

    /**
     * The files in the two upload directories that the loaded state does not name. A crash between
     * writing an uploaded file and committing the state leaves one behind, and so does a file that
     * could not be deleted when it was replaced. They are reported and left alone: this tool
     * deletes only what it knows it wrote and no longer needs.
     */
    private List<Path> strays(ConfigSnapshot loaded) {
        Set<Path> used = used(loaded);
        for (Unreadable each : unreadable) {
            try {
                used.add(directory.resolve(each.file()).normalize());
            } catch (InvalidPathException e) {
                // No path, so no file of the folder it could stand for.
            }
        }
        List<Path> found = new ArrayList<>();
        for (String each : List.of(ENVIRONMENTS, COLLECTIONS)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory.resolve(each))) {
                for (Path file : files) {
                    Path normal = file.normalize();
                    if (Files.isRegularFile(normal) && !used.contains(normal)) {
                        found.add(normal);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // The path only.
                log.warn("The configuration store could not list {}", directory.resolve(each));
            }
        }
        found.sort(null);
        return List.copyOf(found);
    }

    // ------------------------------------------------------------------------------------------
    // Changes. Each one moves the epoch.
    // ------------------------------------------------------------------------------------------

    /**
     * Stores an uploaded environment. One with the same name is replaced. Uploading never changes
     * which environment is active.
     *
     * @param kind                required, and never inferred from the file
     * @param acknowledgedNotProd the uploader's confirmation that this environment is not
     *                            production. Required when the file reads as production. Kept
     *                            whenever it is given, because a write under this environment to
     *                            a url that reads as production is sent only when it was
     */
    synchronized EnvironmentUpload uploadEnvironment(String originalFileName, byte[] content, EnvironmentKind kind,
                                                     boolean acknowledgedNotProd) {
        return uploadEnvironment(originalFileName, content, kind, acknowledgedNotProd, (stored, after) -> null).upload();
    }

    /** As {@link #uploadEnvironment(String, byte[], EnvironmentKind, boolean)}, with the answer described before the commit. */
    synchronized <V> Described<EnvironmentUpload, V> uploadEnvironment(String originalFileName, byte[] content, EnvironmentKind kind,
                                                                       boolean acknowledgedNotProd,
                                                                       BiFunction<StoredEnvironment, ConfigSnapshot, V> describe) {
        if (kind == null) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }
        return uploadEnvironment(originalFileName, content, PostmanParser.environment(PostmanParser.decode(content)), kind,
                acknowledgedNotProd, describe);
    }

    /** As above, for an export already read inside its own guard (R-07), so the file is parsed once. */
    synchronized <V> Described<EnvironmentUpload, V> uploadEnvironment(String originalFileName, byte[] content, PostmanEnvironment parsed,
                                                                       EnvironmentKind kind, boolean acknowledgedNotProd,
                                                                       BiFunction<StoredEnvironment, ConfigSnapshot, V> describe) {
        if (kind == null) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }
        ConfigSnapshot before = current;
        StoredEnvironment previous = before.byName(parsed.name());
        // The settings typed on screen stay with the environment, so its typed addresses and
        // values are checked with the new export as one environment.
        PostmanEnvironment scanned = StoredEnvironment.withSettings(parsed, previous == null ? null : previous.settings());
        List<String> signs = ProductionSigns.in(scanned);
        // What the file says about itself is kept with the environment. What this installation
        // already holds under the same name is a reason of its own to ask, and is not kept: once
        // the upload is confirmed it is no longer true.
        List<String> reasonsToAsk = new ArrayList<>(signs);
        if (previous != null && previous.kind() == EnvironmentKind.PROD) {
            reasonsToAsk.add("an environment named \"" + quoted(parsed.name()) + "\" is already stored here as prod, and this upload would replace it");
        }
        if (kind != EnvironmentKind.PROD) {
            refuseProductionAddresses(before, scanned, kind);
            refuseProductionTokenUrl(before, scanned, previous == null ? null : previous.settings(), kind);
        } else {
            refuseUnreadableProduction(scanned, before.context(), "upload");
        }
        boolean needsConfirmation = kind != EnvironmentKind.PROD && !reasonsToAsk.isEmpty();
        if (needsConfirmation && !acknowledgedNotProd) {
            throw new ValidationException("This environment looks like production and was declared as " + kind.wire()
                    + ". It was not stored.", List.of(new FieldError("acknowledgeNotProd", "It looks like production because "
                    + String.join(", and ", reasonsToAsk) + ". If it really is " + kind.wire() + ", upload it again with "
                    + "acknowledgeNotProd set to true. If it is production, declare its kind as prod.")));
        }

        String id = Ids.named(parsed.name());
        String file = ENVIRONMENTS + "/" + id + "-" + Ids.hash(content, 8) + ".json";
        // The settings typed on screen belong to the environment, not to the file: an export uploaded again keeps them.
        StoredEnvironment stored = new StoredEnvironment(id, parsed.name(), kind, parsed, file, displayName(originalFileName),
                clock.instant().toString(), kind != EnvironmentKind.PROD && (needsConfirmation || acknowledgedNotProd), signs)
                .withSettingsOf(previous);

        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        if (previous == null) {
            environments.add(stored);
        } else {
            environments.set(environments.indexOf(previous), stored);
        }
        // A production environment uploaded again, written plainly on the same host, replaces what
        // its earlier, unreadable address protected on that host. Uploaded on another host it adds
        // to what is remembered and takes nothing away: the old host may still be production.
        List<ConfigSnapshot.KnownDestination> remembered = rememberedAfter(before, scanned, kind);
        ConfigSnapshot next = new ConfigSnapshot(before.epoch() + 1, environments, before.activeName(), before.collections(), remembered,
                before.applicationYml());

        // Every check that can fail runs before anything is written, so an upload that fails
        // leaves the store as it was. A check that threw after the commit used to answer "internal
        // error" for a file that had been stored all the same.
        List<String> warnings = new ArrayList<>(secretWarnings(parsed.variables(), file));
        warnings.addAll(AddressChecks.of(next.byName(stored.name()), next));
        List<String> listedLast = new ArrayList<>(parsed.notes());
        listedLast.addAll(memberDataWarnings("environment", parsed.variables(), List.of(), file));
        V view = describe.apply(stored, next);

        writeAtomically(directory.resolve(file), content);
        warnings.addAll(commit(next, file, previous == null ? null : previous.file()));
        warnings.addAll(listedLast);
        // The name, the kind and a count. Never a variable value.
        log.info("Environment \"{}\" uploaded as {}, variables {}{}", quoted(stored.name()), kind.wire(), parsed.variables().size(),
                previous == null ? "" : ", replacing the one of the same name");
        return new Described<>(new EnvironmentUpload(stored, previous != null, warnings, current), view);
    }

    /**
     * What is remembered as production once this environment is stored with this kind: an earlier,
     * unreadable address of the same production environment on a host it now names plainly is
     * replaced by the plain one; nothing else is taken away.
     */
    private static List<ConfigSnapshot.KnownDestination> rememberedAfter(ConfigSnapshot before, PostmanEnvironment parsed, EnvironmentKind kind) {
        List<UrlNormal.Destination> plain = kind != EnvironmentKind.PROD ? List.of()
                : ProductionDestinations.production(parsed, before.context()).stream().map(ProductionDestinations.Found::destination)
                .filter(UrlNormal.Destination::readable).toList();
        return before.remembered().stream()
                .filter(known -> !(known.environment().equals(parsed.name()) && !known.destination().readable()
                        && plain.stream().anyMatch(each -> new UrlNormal.Destination(null, known.destination().host(), -1, "", false)
                        .sameOrigin(each))))
                .toList();
    }

    /**
     * An export posted with a save of settings, already read inside its own guard (R-07).
     *
     * @param fileName the name it was chosen under, for display only
     * @param content  the bytes stored: the text as the page decoded it (R-06)
     * @param parsed   what it was read as: a {@link PostmanEnvironment} or a {@link PostmanCollection}
     */
    record Export<T>(String fileName, byte[] content, T parsed) {

        /** Holds the file's kind only: an export can hold credentials and member data. */
        @Override
        public String toString() {
            return "Export[" + (parsed == null ? "none" : parsed.getClass().getSimpleName()) + "]";
        }
    }

    /**
     * A save of one environment from the environment form (plan 5.3, 5.4).
     *
     * @param id                 the stored environment to update, or null to create one
     * @param name               for a create, the name when no export gives one; for an update, when
     *                           given, it must be the stored name (R-09: no rename)
     * @param kind               for a create, required; for an update, null keeps the stored kind
     * @param acknowledgeNotProd the confirmation that an environment that reads as production is not
     * @param environmentExport  an environment export to store with it, or null
     * @param collectionExport   a collection export to store in the same commit, or null
     * @param settings           the settings to store, from the stored ones (or none, for a create
     *                           or a stored entry that could not be read); throws for a field error
     */
    record SettingsSave(String id, String name, EnvironmentKind kind, boolean acknowledgeNotProd, Export<PostmanEnvironment> environmentExport,
                        Export<PostmanCollection> collectionExport,
                        java.util.function.UnaryOperator<EnvironmentSettings> settings) {

        /** Names the parts given and never a value. */
        @Override
        public String toString() {
            return "SettingsSave[" + (id == null ? "create" : "update") + ", kind=" + kind + ", environmentExport=" + environmentExport
                    + ", collectionExport=" + collectionExport + "]";
        }
    }

    /** What a save of settings did. */
    record SettingsSaved(StoredEnvironment environment, boolean created, List<String> warnings, ConfigSnapshot after) {

        long epoch() {
            return after.epoch();
        }
    }

    /**
     * Stores an environment's settings, with the exports chosen beside them, in one commit (R-08).
     * Every check an upload makes is made here as well, a change of kind included (R-09), and
     * before anything is written; saving never changes which environment is active (G22). The
     * answer is described from the configuration the save leaves, before the commit.
     */
    synchronized <V> Described<SettingsSaved, V> saveSettings(SettingsSave save, BiFunction<StoredEnvironment, ConfigSnapshot, V> describe) {
        ConfigSnapshot before = current;
        StoredEnvironment previous = null;
        if (save.id() != null) {
            previous = requireEnvironment(before, save.id());
            if (Texts.present(save.name()) && !save.name().equals(previous.name())) {
                throw ValidationException.of("name", "The name of a stored environment cannot be changed: production writes are unlocked "
                        + "and production addresses remembered by it. Keep \"" + quoted(previous.name()) + "\", or make a new environment "
                        + "from the other export.");
            }
        }
        PostmanEnvironment parsed;
        byte[] content = null;
        if (save.environmentExport() != null) {
            parsed = save.environmentExport().parsed();
            content = save.environmentExport().content();
            if (previous != null && !parsed.name().equals(previous.name())) {
                throw ValidationException.of("environmentExport", "This export is the environment \"" + quoted(parsed.name()) + "\", and "
                        + "this form edits \"" + quoted(previous.name()) + "\". A stored environment is never renamed: choose its own "
                        + "export, or make a new environment from this one.");
            }
            if (previous == null && Texts.present(save.name()) && !save.name().strip().equals(parsed.name())) {
                throw ValidationException.of("name", "The name comes from the export once one is chosen: \"" + quoted(parsed.name()) + "\".");
            }
        } else if (previous != null) {
            parsed = previous.content();
        } else {
            if (!Texts.present(save.name())) {
                throw ValidationException.of("name", "Type a name for the environment, or choose its Postman environment export.");
            }
            String typed = save.name().strip();
            // An environment made on screen has no export: an empty one stands in, so every
            // environment of the store keeps one file, as a start reads it.
            ObjectNode empty = JsonSafe.mapper().createObjectNode();
            empty.put("name", typed);
            empty.putArray("values");
            content = asciiOnly(JsonSafe.pretty(empty)).getBytes(StandardCharsets.UTF_8);
            parsed = PostmanParser.environment(PostmanParser.decode(content));
        }
        if (previous == null && before.byName(parsed.name()) != null) {
            throw ValidationException.of("name", "An environment named \"" + quoted(parsed.name()) + "\" is already stored. Open it with "
                    + "Edit, or choose another name.");
        }
        EnvironmentKind kind = save.kind() != null ? save.kind() : previous == null ? null : previous.kind();
        if (kind == null) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }

        EnvironmentSettings settings = save.settings().apply(previous == null ? EnvironmentSettings.none() : previous.settings());
        // The typed addresses and values are the environment's own addresses (plan 5.1), so the
        // checks read them with the export as one environment.
        PostmanEnvironment scanned = StoredEnvironment.withSettings(parsed, settings);
        // A typed address or value that is new or changed by this save and reads as production
        // asks for the confirmation again (R-09), even when the environment was confirmed before.
        List<String> typedSigns = typedProductionSigns(previous == null ? EnvironmentSettings.none() : previous.settings(), settings);
        boolean typedChanged = previous != null && (!settings.addresses().equals(previous.settings().addresses())
                || !settings.values().equals(previous.settings().values()));

        // The checks of an upload with a kind, whenever the export, the kind or a typed address is new.
        boolean wholly = previous == null || content != null || kind != previous.kind();
        boolean checked = wholly || typedChanged;
        List<String> signs = ProductionSigns.in(scanned);
        boolean acknowledged = previous != null && kind == previous.kind() && previous.acknowledgedNotProd();
        // Whatever else changed: the sign-in posts the client secret on the next call.
        refuseProductionTokenUrl(before, scanned, settings, kind);
        if (checked) {
            List<String> reasonsToAsk = new ArrayList<>(wholly ? signs : typedSigns);
            if (previous != null && previous.kind() == EnvironmentKind.PROD && kind != EnvironmentKind.PROD) {
                reasonsToAsk.add("it is stored here as prod, and this save would make it " + kind.wire());
            }
            if (kind != EnvironmentKind.PROD) {
                refuseProductionAddresses(before, scanned, kind);
            } else {
                refuseUnreadableProduction(scanned, before.context(), "upload");
            }
            boolean needsConfirmation = kind != EnvironmentKind.PROD && !reasonsToAsk.isEmpty();
            if (needsConfirmation && !save.acknowledgeNotProd()) {
                throw new ValidationException("This environment looks like production and was declared as " + kind.wire()
                        + ". Nothing was saved.", List.of(new FieldError("acknowledgeNotProd", "It looks like production because "
                        + String.join(", and ", reasonsToAsk) + ". If it really is " + kind.wire() + ", tick \"This environment is not "
                        + "production\" and save again. If it is production, choose the kind prod.")));
            }
            // A save that only changes typed addresses keeps a confirmation given before: what it
            // confirmed is still stored, and this save asked again for anything new that needed it.
            acknowledged = kind != EnvironmentKind.PROD && (needsConfirmation || save.acknowledgeNotProd() || (!wholly && acknowledged));
        }

        String id = Ids.named(parsed.name());
        String file = content == null ? previous.file() : ENVIRONMENTS + "/" + id + "-" + Ids.hash(content, 8) + ".json";
        StoredEnvironment stored = new StoredEnvironment(id, parsed.name(), kind, parsed, file,
                content == null ? previous.originalFileName() : save.environmentExport() == null ? null
                        : displayName(save.environmentExport().fileName()),
                content == null ? previous.uploadedAt() : clock.instant().toString(), acknowledged, signs).withSettings(settings);
        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        if (previous == null) {
            environments.add(stored);
        } else {
            environments.set(environments.indexOf(previous), stored);
        }

        List<String> warnings = new ArrayList<>();
        List<String> listedLast = new ArrayList<>();
        List<StoredCollection> collections = new ArrayList<>(before.collections());
        StoredCollection previousCollection = null;
        StoredCollection storedCollection = null;
        String collectionFile = null;
        if (save.collectionExport() != null) {
            PostmanCollection collection = save.collectionExport().parsed();
            String collectionId = Ids.named(collection.name());
            previousCollection = before.collection(collectionId);
            collectionFile = COLLECTIONS + "/" + collectionId + "-" + Ids.hash(save.collectionExport().content(), 8) + ".json";
            Map<String, EpaOperation> corrections = new LinkedHashMap<>();
            if (previousCollection != null) {
                for (StoredCollection.Mapped old : previousCollection.requests()) {
                    if (old.correction() != null && collection.request(old.request().id()) != null) {
                        corrections.put(old.request().id(), old.correction());
                    } else if (old.correction() != null) {
                        warnings.add("A mapping correction on \"" + old.request().quotedName() + "\" was dropped, because the new file has "
                                + "no request with that name in that folder.");
                    }
                }
            }
            storedCollection = new StoredCollection(collectionId, collection, collectionFile, displayName(save.collectionExport().fileName()),
                    clock.instant().toString(), mapped(collection, corrections));
            if (previousCollection == null) {
                collections.add(storedCollection);
            } else {
                collections.set(collections.indexOf(previousCollection), storedCollection);
            }
            warnings.addAll(secretWarnings(collection.variables(), collectionFile));
            warnings.addAll(literalCredentialWarnings(collection, collectionFile));
            listedLast.addAll(collection.notes());
            listedLast.addAll(memberDataWarnings("collection", collection.variables(), collection.requests(), collectionFile));
        }
        ConfigSnapshot next = new ConfigSnapshot(before.epoch() + 1, environments, before.activeName(), collections,
                checked ? rememberedAfter(before, scanned, kind) : before.remembered(), before.applicationYml());

        if (content != null && save.environmentExport() != null) {
            warnings.addAll(secretWarnings(parsed.variables(), file));
            listedLast.addAll(parsed.notes());
            listedLast.addAll(memberDataWarnings("environment", parsed.variables(), List.of(), file));
        }
        warnings.addAll(AddressChecks.of(next.byName(stored.name()), next));
        V view = describe.apply(stored, next);

        List<String> newFiles = new ArrayList<>();
        List<String> oldFiles = new ArrayList<>();
        try {
            if (content != null) {
                writeAtomically(directory.resolve(file), content);
                newFiles.add(file);
                if (previous != null) {
                    oldFiles.add(previous.file());
                }
            }
            if (storedCollection != null) {
                writeAtomically(directory.resolve(collectionFile), save.collectionExport().content());
                newFiles.add(collectionFile);
                if (previousCollection != null) {
                    oldFiles.add(previousCollection.file());
                }
            }
        } catch (RuntimeException e) {
            for (String written : newFiles) {
                if (!oldFiles.contains(written)) {
                    deleteQuietly(directory.resolve(written));
                }
            }
            throw e;
        }
        warnings.addAll(commitAll(next, newFiles, oldFiles));
        warnings.addAll(listedLast);
        // The name, the kind and the parts stated. Never a value: a setting can be a secret or an address with credentials.
        log.info("Environment \"{}\" {} as {}, settings {}{}", quoted(stored.name()), previous == null ? "made" : "saved", kind.wire(),
                settings, storedCollection == null ? "" : ", with a collection");
        return new Described<>(new SettingsSaved(stored, previous == null, warnings, current), view);
    }

    /**
     * Each typed address or value that this save sets or changes and that reads as production, as
     * the reason to ask completes "It looks like production because ...". The address or the
     * value is never quoted.
     */
    private static List<String> typedProductionSigns(EnvironmentSettings before, EnvironmentSettings after) {
        List<String> signs = new ArrayList<>();
        after.addresses().forEach((operation, address) -> {
            if (!address.equals(before.addresses().get(operation)) && ProductionSigns.saysProd(address)) {
                signs.add("the " + StoredEnvironment.addressName(operation) + " contains \"prod\"");
            }
        });
        after.values().forEach((name, value) -> {
            if (!value.equals(before.values().get(name)) && ProductionSigns.looksLikeUrl(value) && ProductionSigns.saysProd(value)) {
                signs.add("the value " + org.p32h.interop.epa.pave.postman.Names.shown(name) + " holds a url that contains \"prod\"");
            }
        });
        return signs;
    }

    /** Makes an uploaded environment the active one, with no restart. */
    synchronized ConfigSnapshot activate(String nameOrId) {
        ConfigSnapshot before = current;
        StoredEnvironment environment = requireEnvironment(before, nameOrId);
        if (environment.name().equals(before.activeName())) {
            return before;
        }
        if (environment.kind() == EnvironmentKind.PROD) {
            refuseUnreadableProduction(environment.scanned(), before.context(), "activate");
        }
        commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), environment.name(), before.collections(),
                before.remembered(), before.applicationYml()), null, null);
        // The name and the kind only.
        log.info("Active environment switched to \"{}\" ({})", quoted(environment.name()), environment.kind().wire());
        return current;
    }

    synchronized Removal removeEnvironment(String nameOrId) {
        ConfigSnapshot before = current;
        StoredEnvironment environment = requireEnvironment(before, nameOrId);
        boolean wasActive = environment.name().equals(before.activeName());
        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        environments.remove(environment);
        List<String> warnings = commit(new ConfigSnapshot(before.epoch() + 1, environments, wasActive ? null : before.activeName(),
                before.collections(), before.remembered(), before.applicationYml()), null, environment.file());
        // The name only.
        log.info("Environment \"{}\" removed{}", quoted(environment.name()), wasActive ? ". It was the active one, so none is active now" : "");
        return new Removal(environment.name(), wasActive, warnings, current.epoch());
    }

    /**
     * Stores an uploaded collection and detects the operation of each request. One with the same
     * name is replaced, and the mapping corrections made on it are kept for every request that is
     * still there.
     */
    synchronized CollectionUpload uploadCollection(String originalFileName, byte[] content) {
        return uploadCollection(originalFileName, content, (stored, after) -> null).upload();
    }

    /** As {@link #uploadCollection(String, byte[])}, with the answer described before the commit. */
    synchronized <V> Described<CollectionUpload, V> uploadCollection(String originalFileName, byte[] content,
                                                                     BiFunction<StoredCollection, ConfigSnapshot, V> describe) {
        return uploadCollection(originalFileName, content, PostmanParser.collection(PostmanParser.decode(content)), describe);
    }

    /** As above, for an export already read inside its own guard (R-07), so the file is parsed once. */
    synchronized <V> Described<CollectionUpload, V> uploadCollection(String originalFileName, byte[] content, PostmanCollection parsed,
                                                                     BiFunction<StoredCollection, ConfigSnapshot, V> describe) {
        ConfigSnapshot before = current;
        String id = Ids.named(parsed.name());
        StoredCollection previous = before.collection(id);
        String file = COLLECTIONS + "/" + id + "-" + Ids.hash(content, 8) + ".json";

        List<String> warnings = new ArrayList<>();
        Map<String, EpaOperation> corrections = new LinkedHashMap<>();
        if (previous != null) {
            for (StoredCollection.Mapped old : previous.requests()) {
                if (old.correction() == null) {
                    continue;
                }
                if (parsed.request(old.request().id()) == null) {
                    // "Unmapped request" is the label of no operation, so taking a request off is never said as mapping it.
                    warnings.add((old.correction() == EpaOperation.UNMAPPED
                            ? "The correction that took \"" + old.request().quotedName() + "\" off every operation"
                            : "The correction that mapped \"" + old.request().quotedName() + "\" to " + old.correction().label())
                            + " was dropped, because the new file has no request with that name in that folder.");
                } else {
                    corrections.put(old.request().id(), old.correction());
                }
            }
        }
        StoredCollection stored = new StoredCollection(id, parsed, file, displayName(originalFileName), clock.instant().toString(),
                mapped(parsed, corrections));

        List<StoredCollection> collections = new ArrayList<>(before.collections());
        if (previous == null) {
            collections.add(stored);
        } else {
            collections.set(collections.indexOf(previous), stored);
        }
        ConfigSnapshot next = new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), collections,
                before.remembered(), before.applicationYml());

        // Every check that can fail runs before anything is written, so an upload that fails
        // leaves the store as it was. The scan for literal credentials once threw after the commit:
        // the upload answered "internal error", the collection was stored all the same, and every
        // later reading of it threw again.
        warnings.addAll(secretWarnings(parsed.variables(), file));
        warnings.addAll(literalCredentialWarnings(parsed, file));
        List<String> listedLast = new ArrayList<>(parsed.notes());
        listedLast.addAll(memberDataWarnings("collection", parsed.variables(), parsed.requests(), file));
        V view = describe.apply(stored, next);

        writeAtomically(directory.resolve(file), content);
        warnings.addAll(commit(next, file, previous == null ? null : previous.file()));
        warnings.addAll(listedLast);
        // The name and counts only. Never a url, a header or a body.
        log.info("Collection \"{}\" uploaded, requests {}{}", quoted(parsed.name()), parsed.requests().size(),
                previous == null ? "" : ", replacing the one of the same name");
        return new Described<>(new CollectionUpload(stored, previous != null, warnings, current), view);
    }

    /**
     * Records which operation a request stands for, in place of what detection proposed. Choosing
     * the operation detection proposed removes the correction, since there is then nothing to correct.
     */
    synchronized MappingChange correctMapping(String collectionId, String requestId, EpaOperation operation) {
        ConfigSnapshot before = current;
        StoredCollection collection = requireCollection(before, collectionId);
        StoredCollection.Mapped request = collection.request(requestId);
        if (request == null) {
            throw NotFoundException.noLongerHeld("That request of the collection \"" + quoted(collection.name()) + "\"", "collections");
        }
        if (operation == null) {
            throw ValidationException.of("operation", "Choose the operation this request stands for.");
        }
        if (operation == EpaOperation.DTR_RESPONSE) {
            throw ValidationException.of("operation", operation.label() + " is a step completed inside this tool. Nothing is sent for it, "
                    + "so no collection request can stand for it. Choose another operation, or UNMAPPED.");
        }
        EpaOperation correction = operation == request.detection().operation() ? null : operation;
        if (correction == request.correction()) {
            return new MappingChange(collection, request, false, before);
        }
        List<StoredCollection.Mapped> requests = new ArrayList<>();
        for (StoredCollection.Mapped each : collection.requests()) {
            requests.add(each == request ? new StoredCollection.Mapped(each.request(), each.detection(), correction) : each);
        }
        StoredCollection changed = new StoredCollection(collection.id(), collection.content(), collection.file(),
                collection.originalFileName(), collection.uploadedAt(), requests);
        List<StoredCollection> collections = new ArrayList<>(before.collections());
        collections.set(collections.indexOf(collection), changed);
        commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), collections, before.remembered(),
                before.applicationYml()), null, null);
        // The operations only.
        log.info("Mapping of one request corrected to {}", correction == null ? "what detection proposed" : correction.name());
        return new MappingChange(changed, changed.request(requestId), true, current);
    }

    synchronized Removal removeCollection(String collectionId) {
        ConfigSnapshot before = current;
        StoredCollection collection = requireCollection(before, collectionId);
        List<StoredCollection> collections = new ArrayList<>(before.collections());
        collections.remove(collection);
        List<String> warnings = commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), collections,
                before.remembered(), before.applicationYml()), null, collection.file());
        // The name only.
        log.info("Collection \"{}\" removed, with its mapping corrections", quoted(collection.name()));
        return new Removal(collection.name(), false, warnings, current.epoch());
    }

    private static StoredEnvironment requireEnvironment(ConfigSnapshot snapshot, String nameOrId) {
        StoredEnvironment environment = snapshot.environment(nameOrId);
        if (environment == null) {
            throw NotFoundException.noLongerHeld("The environment", "environments");
        }
        return environment;
    }

    private static StoredCollection requireCollection(ConfigSnapshot snapshot, String collectionId) {
        StoredCollection collection = snapshot.collection(collectionId);
        if (collection == null) {
            throw NotFoundException.noLongerHeld("The collection", "collections");
        }
        return collection;
    }

    /**
     * A name from an uploaded file as it may be written to the log or into a sentence: on one
     * line. A name is whatever the file says, and a line break in it would let an upload write
     * what reads as a log line of its own.
     */
    static String oneLine(String name) {
        return name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    /**
     * A name from an uploaded file as a sentence or the log quotes it: on one line, and a long one
     * by its start and its length ({@link Names#shown(String)}). It is cut before it is made one
     * line, so the length given is the length the file gives.
     */
    static String quoted(String name) {
        return oneLine(Names.shown(name));
    }

    /** Names in quotes, joined with "and". */
    static String namedAll(List<String> names) {
        return names.stream().map(name -> "\"" + quoted(name) + "\"").collect(java.util.stream.Collectors.joining(" and "));
    }

    private static List<StoredCollection.Mapped> mapped(PostmanCollection collection, Map<String, EpaOperation> corrections) {
        List<StoredCollection.Mapped> requests = new ArrayList<>();
        for (PostmanRequest request : collection.requests()) {
            OperationDetector.Detection detection = OperationDetector.detect(request.name(), request.method(), request.url(), request.folderPath());
            EpaOperation correction = corrections.get(request.id());
            // A correction that says what detection now says is no correction.
            requests.add(new StoredCollection.Mapped(request, detection, correction == detection.operation() ? null : correction));
        }
        return requests;
    }

    /**
     * Refuses an environment that is not declared production when one of its addresses is an
     * address of a production environment this store holds or has held. No confirmation lets it
     * through: production uploaded again under a sandbox's kind, or under the same name after a
     * delete, is the mistake the write guardrail cannot recover from. Declaring it production is
     * the way through.
     *
     * <p>The refusal's sentence is the guardrail's and keeps its words whatever is stored (R-16).
     * When no environment that made the address production is stored any more, its field note
     * begins with that fact and a way out that works at that moment, then gives its old sentences
     * unchanged (R-35). An environment uploaded as prod by a slip and then removed leaves its
     * addresses refused for good, and the old note alone, pointing the variables at the sandbox,
     * cannot help when they point there already. Nothing is forgotten here: the way out is
     * "Forget this production address", which the tester confirms by the environment's name.
     */
    private void refuseProductionAddresses(ConfigSnapshot before, PostmanEnvironment parsed, EnvironmentKind kind) {
        Set<String> owners = new LinkedHashSet<>();
        ProductionDestinations.Found quoted = null;
        for (ProductionDestinations.Found found : ProductionDestinations.of(parsed, before.context())) {
            List<String> overlapping = before.productionOverlaps(found.destination());
            if (!overlapping.isEmpty() && quoted == null && !found.secret()) {
                quoted = found;
            }
            owners.addAll(overlapping);
        }
        if (owners.isEmpty()) {
            return;
        }
        String named = owners.stream().map(owner -> "\"" + quoted(owner) + "\"").collect(java.util.stream.Collectors.joining(" and "));
        // The address is quoted only when no secret is part of it, and only as far as the first secret.
        // One the client cannot read is quoted as the file writes it, since this tool's own
        // reading of it is not an address the file holds.
        boolean several = owners.size() > 1;
        String production = "production environment" + (several ? "s " : " ") + named;
        ProductionDestinations.Found shown = quoted;
        String said;
        if (shown == null) {
            said = "one of its addresses is an address of the " + production + ", or lies above or beneath one.";
        } else if (!shown.destination().readable() && shown.written() != null) {
            said = "its address \"" + UrlNormal.asWritten(shown.written()) + "\", in the variable \"" + quoted(shown.variable())
                    + "\", cannot be read the way the HTTP client reads it. Read as closely as this tool can, it is an address of the "
                    + production + ", or lies above or beneath one.";
        } else if (before.remembered().stream().anyMatch(known -> known.destination().everyPortAndPath()
                && known.destination().covers(shown.destination()))) {
            said = "its address " + oneLine(shown.destination().shown()) + " is on the host of the " + production + ". That "
                    + "production environment has an address this tool cannot read the way the HTTP client reads it, so every port and "
                    + "every path of its host is taken as production.";
        } else {
            said = "its address " + oneLine(shown.destination().shown()) + " is an address of the " + production
                    + ", or lies above or beneath one.";
        }
        String note = "An address that this installation knows as production stays production, even after its environment is deleted. "
                + "If this environment is production, upload it with the kind prod. If it is not, point its variables at the " + kind.wire()
                + " service and upload it again.";
        // An owner that is no longer stored is known only as a name the store remembers the address by.
        boolean removed = owners.stream().noneMatch(owner -> before.byName(owner) != null);
        if (removed) {
            // The way out that works while PAVE runs: Forget, offered for exactly these addresses (R-35).
            note = named + (several ? " are" : " is") + " no longer stored. If " + (several ? "they were" : "it was") + " uploaded as "
                    + "prod by mistake, forget " + (several ? "their addresses" : "the address") + " in Configuration > Production writes "
                    + "and upload this environment again. " + note;
        }
        throw new ValidationException("This environment was declared as " + kind.wire() + ", and " + said + " It was not stored.",
                List.of(new FieldError("kind", note)));
    }

    /**
     * Refuses an environment that is not production whose Sign-in token url is, or lies beneath,
     * an address a production environment uses for a call (I-28): a sign-in posts the client
     * secret there. Said in the words of the address refusal, at the field of the token url. A
     * token url a stored production environment signs in with is allowed, see {@link TokenUrlGuard}.
     */
    private void refuseProductionTokenUrl(ConfigSnapshot before, PostmanEnvironment scanned, EnvironmentSettings settings,
                                          EnvironmentKind kind) {
        SignInSettings signIn = settings == null ? null : settings.signIn();
        if (kind == EnvironmentKind.PROD || signIn == null || !Texts.present(signIn.tokenUrl())) {
            return;
        }
        for (ProductionDestinations.Found found : ProductionDestinations.resolvedText(scanned, signIn.tokenUrl(), before.context())) {
            List<String> owners = TokenUrlGuard.owners(before, found.destination(), null);
            if (owners.isEmpty()) {
                continue;
            }
            boolean several = owners.size() > 1;
            String production = "production environment" + (several ? "s " : " ") + namedAll(owners);
            String said = found.secret() ? "its token url" : "its token url " + oneLine(found.destination().shown());
            String note = "A sign-in sends the client secret to the token url, so a token url at an address a production environment "
                    + "uses for a call is a write to that production environment. Use the token url of the " + kind.wire() + " service, "
                    + "or the one the production environment signs in with.";
            if (owners.stream().noneMatch(owner -> before.byName(owner) != null)) {
                String entries = owners.stream().map(owner -> "\"" + quoted(owner) + "\"").collect(java.util.stream.Collectors.joining(" or "));
                // The way out that works while PAVE runs: Forget, offered for exactly these addresses (R-35).
                note = namedAll(owners) + (several ? " are" : " is") + " no longer stored. If " + (several ? "they were" : "it was")
                        + " uploaded as prod by mistake, press Forget this production address beside each address of "
                        + entries + " in Configuration > Production writes and save this environment again. " + note;
            }
            throw new ValidationException("This environment was declared as " + kind.wire() + ", and " + said + " is an address of the "
                    + production + ", or lies beneath one. It was not stored.", List.of(new FieldError("signIn.tokenUrl", note)));
        }
    }

    /**
     * Refuses a production environment whose address is not known exactly: one the HTTP client
     * cannot read as this tool does, a variable that holds an address and has no value, and one
     * built from an operating system variable, whose value is known only at the moment of sending.
     * The address is read through every layer the gateway resolves through when it sends,
     * application.yml included. Where production is must be known exactly: the addresses of a
     * production environment are what a write needs an unlock for and what a sandbox may not use,
     * and an address this tool does not know protects nothing. An operator who means production
     * writes its address into the environment, plainly.
     *
     * @param step what is refused: "upload", "activate" or "unlock"
     */
    static void refuseUnreadableProduction(PostmanEnvironment environment, ProductionDestinations.Context context, String step) {
        List<ProductionDestinations.Problem> problems = ProductionDestinations.problems(environment, context);
        if (problems.isEmpty()) {
            return;
        }
        ProductionDestinations.Problem first = problems.get(0);
        String holds = holds(first);
        boolean unreadable = first.why() == ProductionDestinations.Unknown.UNREADABLE;
        String plainly = unreadable
                ? "Write the address plainly, as scheme://host:port/path with the host as a name or as four decimal numbers and a "
                + "port of at most 65535, and upload the environment again."
                : "Write the address itself into the environment, plainly, as scheme://host:port/path, and upload the environment again.";
        if ("upload".equals(step)) {
            throw new ValidationException("This environment was declared as prod, and " + holds + ". It was not stored.",
                    List.of(new FieldError("kind", "The addresses of a production environment are what a write must be unlocked for "
                            + "and what no other environment may use, so this tool must " + (unreadable
                            ? "read them exactly as the HTTP client does. " : "know them before anything is sent. ") + plainly)));
        }
        String name = "\"" + quoted(environment.name()) + "\"";
        String refused = "activate".equals(step) ? name + " cannot be made active" : "Writes to " + name + " cannot be unlocked";
        throw new BadInputException(refused + ": it is a production environment, and " + holds + ".", "Nothing was changed. " + plainly
                + (unreadable ? " Until then this tool takes every port and every path of that address's host as production." : ""));
    }

    /** Where an address of a production environment is held and why it is not known, in words. A secret is named and never quoted. */
    private static String holds(ProductionDestinations.Problem problem) {
        String variable = "the variable \"" + quoted(problem.variable()) + "\"";
        String where = problem.own() ? variable : variable + ", which this environment does not define and "
                + (problem.from() == null ? "another layer" : problem.from()) + " gives,";
        String written = problem.secret() || problem.written() == null ? null : UrlNormal.asWritten(problem.written());
        return switch (problem.why()) {
            case UNREADABLE -> {
                String address;
                if (problem.secret()) {
                    address = " holds an address that is not quoted here because " + (problem.own() && problem.written() == null
                            ? "the variable is a secret" : "a secret is part of it");
                } else if (!problem.own() || written == null) {
                    address = " holds the address \"" + UrlNormal.asWritten(problem.resolved() == null ? "" : problem.resolved()) + "\"";
                } else if (problem.resolved() != null) {
                    address = " holds \"" + written + "\", which resolves through application.yml or the variables of a collection to the "
                            + "address \"" + UrlNormal.asWritten(problem.resolved()) + "\"";
                } else {
                    address = " holds the address \"" + written + "\"";
                }
                yield where + address + ", which this tool cannot read the way the HTTP client reads it";
            }
            case NO_VALUE -> where + (written == null ? "" : " holds \"" + written + "\", which") + " has no value in this environment, "
                    + "its collections or application.yml, so the address it would send to is not known";
            case AT_SENDING -> where + (written == null ? "" : " holds \"" + written + "\", which") + " is read from an operating system "
                    + "variable only at the moment of sending, so the address it would send to is not known before then";
        };
    }

    /**
     * A warning when an upload can hold member data: a variable whose name reads as a member,
     * patient or subscriber identifier, a name or a birth date, or a request with a saved example
     * response. The variables and requests are named. A value never is.
     *
     * <p>Package-private so that a test can make this check fail and see that the upload stores nothing.
     */
    List<String> memberDataWarnings(String what, List<PostmanVariable> variables, List<PostmanRequest> requests, String file) {
        List<String> warnings = new ArrayList<>();
        String kept = " The " + what + " is kept on disk exactly as it was uploaded, values included, in the file "
                + directory.resolve(file) + ". An environment or a collection that holds real member data must not be uploaded: use "
                + "placeholders such as S123456789 in its place.";
        List<String> names = MemberDataNames.in(variables);
        if (!names.isEmpty()) {
            warnings.add((names.size() == 1 ? "The variable " : "The variables ") + String.join(", ", names.stream()
                    .map(name -> "\"" + quoted(name) + "\"").toList()) + (names.size() == 1 ? " reads" : " read")
                    + " as member data: an identifier, a name, a birth date, an address or a telephone number." + kept);
        }
        List<String> withExamples = new ArrayList<>();
        for (PostmanRequest request : requests) {
            if (!request.examples().isEmpty()) {
                withExamples.add(request.quotedName());
            }
        }
        if (!withExamples.isEmpty()) {
            String first = "\"" + oneLine(withExamples.get(0)) + "\"";
            warnings.add((withExamples.size() == 1 ? "The request " + first + " carries"
                    : withExamples.size() + " requests, the first being " + first + ", carry") + " a saved example response, which can "
                    + "hold the member data of whoever the example was saved for." + kept);
        }
        return warnings;
    }

    /**
     * One warning for each secret that now sits in a file in plain text. A secret whose value only
     * refers to the operating system or to other variables is not itself in the file, so it is not
     * warned about. A disabled variable is: it is not used, and it is still in the file.
     */
    private List<String> secretWarnings(List<PostmanVariable> variables, String file) {
        List<String> warnings = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (PostmanVariable variable : variables) {
            if (variable.secret() && variable.hasValue() && !Placeholders.referencesOnly(variable.value()) && named.add(variable.key())) {
                warnings.add("The variable \"" + Names.shown(variable.key()) + "\" holds a secret in plain text, and it now sits in the file "
                        + directory.resolve(file) + ". To keep it out of that file, give the variable the value {{env:"
                        + VariableResolver.OS_NAME_PREFIX + "YOUR_NAME}} in Postman, export again, and set the operating system "
                        + "variable of that name where this tool runs.");
            }
        }
        return warnings;
    }

    /**
     * One warning for each header name under which a collection carries a credential that was
     * typed into it, not referred to through a placeholder. Such a credential sits in the stored
     * file in plain text exactly as a secret variable does. The header and the first request are
     * named. The value is not.
     */
    private List<String> literalCredentialWarnings(PostmanCollection collection, String file) {
        Map<String, List<String>> requestsByHeader = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PostmanRequest request : collection.requests()) {
            // Headers that are switched off are looked at too. They are not sent, and they are still in the file.
            for (PostmanHeader header : request.headers()) {
                if (HeaderMasking.literalCredential(header.name(), header.value())) {
                    requestsByHeader.computeIfAbsent(header.name().strip(), name -> new ArrayList<>()).add(request.quotedName());
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        requestsByHeader.forEach((header, requests) -> warnings.add("The header \"" + quoted(header) + "\" holds a credential typed into "
                + "the collection in " + requests.size() + (requests.size() == 1 ? " request" : " requests") + ", the first being \""
                + oneLine(requests.get(0)) + "\", and it now sits in the file " + directory.resolve(file) + " in plain text. This tool "
                + "redacts it wherever it shows the header. To keep it out of that file, replace it in Postman with a {{placeholder}} and "
                + "export again."));

        // The places are named. A value never is.
        String stored = " It sits in the file " + directory.resolve(file) + " in plain text: the collection is kept exactly as it was "
                + "uploaded, value included.";
        String move = " To keep it out of that file, move the value into a secret variable of the environment, or into an operating "
                + "system variable referred to as {{env:" + VariableResolver.OS_NAME_PREFIX + "YOUR_NAME}}, refer to it from the request as "
                + "a {{placeholder}} in Postman, and export again.";
        Set<String> helpers = new LinkedHashSet<>();
        for (PostmanRequest request : collection.requests()) {
            List<String> places = new ArrayList<>();
            LiteralCredentials.inUrl(request.url()).found().forEach(found -> places.add(found.place()));
            LiteralCredentials.inBody(request.body()).found().forEach(found -> places.add(found.place()));
            request.formCredentials().forEach(field -> places.add("the field " + quoted(field) + " of its " + quoted(request.bodyMode())
                    + " body"));
            if (!places.isEmpty()) {
                warnings.add("The request \"" + oneLine(request.quotedName()) + "\" holds a credential typed into the collection, in "
                        + String.join(" and in ", places) + "." + stored + " This tool masks it wherever it shows the request, and sends it "
                        + "as written." + move);
            }
            for (PostmanExample example : request.examples()) {
                List<String> inExample = new ArrayList<>();
                example.headers().stream().filter(header -> HeaderMasking.literalCredential(header.name(), header.value()))
                        .forEach(header -> inExample.add("its header " + quoted(header.name())));
                LiteralCredentials.inBody(example.body()).found().forEach(found -> inExample.add(found.place()));
                if (!inExample.isEmpty()) {
                    warnings.add("The saved example \"" + quoted(example.name()) + "\" of the request \"" + oneLine(request.quotedName())
                            + "\" holds a credential, in " + String.join(" and in ", inExample) + "." + stored + " This tool masks it "
                            + "wherever it shows the example. A saved example is never sent. If it is a real credential, have it revoked.");
                }
            }
        }
        // Every helper the file sets, whether or not a request inherits it: each one is in the file as uploaded.
        for (PostmanAuth auth : collection.helpers()) {
            if (!auth.literals().isEmpty() && helpers.add(auth.type() + "|" + auth.from())) {
                warnings.add("The authentication helper " + quoted(auth.type()) + " set on " + oneLine(auth.from()) + " holds a credential "
                        + "typed into the collection, in its field " + String.join(" and its field ", auth.literals().stream()
                        .map(ConfigStore::quoted).toList()) + "." + stored + " This tool does not apply the helper, so it never sends or "
                        + "shows that value." + move);
            }
        }
        return warnings;
    }

    /** The uploaded file name for display: its last path element, on one line, bounded. Never used as a path. */
    private static String displayName(String originalFileName) {
        if (Texts.blank(originalFileName)) {
            return null;
        }
        String name = originalFileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", " ").strip();
        return name.isEmpty() ? null : name.length() > 200 ? name.substring(0, 200) : name;
    }

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    /**
     * Makes a new snapshot the current one. The state file is replaced first; only when that has
     * succeeded does the snapshot in memory change, so memory and disk never disagree.
     *
     * @param newFile a content file written for this change. Removed again if the commit fails
     * @param oldFile a content file this change makes redundant. Removed after the commit
     * @return a warning when the redundant file could not be removed, since it may hold credentials
     */
    private List<String> commit(ConfigSnapshot next, String newFile, String oldFile) {
        return commitAll(next, newFile == null ? List.of() : List.of(newFile), oldFile == null ? List.of() : List.of(oldFile));
    }

    /**
     * As {@link #commit(ConfigSnapshot, String, String)} for a change that wrote several content
     * files, such as a save of an environment's settings with its exports. The version written is 2
     * from the first time any environment holds settings on; before store.json is first written as
     * version 2, it is copied once to {@link #BEFORE_V2}, so an older build can be given its store back.
     */
    private List<String> commitAll(ConfigSnapshot next, List<String> newFiles, List<String> oldFiles) {
        // An upload under the name or id of an unreadable entry takes its place.
        List<Unreadable> kept = new ArrayList<>();
        for (Unreadable each : unreadable) {
            boolean replaced = each.key() != null && (ENVIRONMENTS.equals(each.list()) ? next.byName(each.key()) != null
                    : next.collection(each.key()) != null);
            if (!replaced) {
                kept.add(each);
            }
        }
        return commitAll(next, newFiles, oldFiles, kept);
    }

    private List<String> commitAll(ConfigSnapshot next, List<String> newFiles, List<String> oldFiles, List<Unreadable> keep) {
        int writeAs = version >= SETTINGS_VERSION || holdsSettings(next) ? SETTINGS_VERSION : STATE_VERSION;
        try {
            refuseMissingFiles(next);
            if (writeAs >= SETTINGS_VERSION && version < SETTINGS_VERSION) {
                keepVersionOne();
            }
            writeAtomically(directory.resolve(STATE_FILE), stateJson(next, writeAs, keep).getBytes(StandardCharsets.UTF_8));
        } catch (ConfigStoreException e) {
            for (String newFile : newFiles) {
                if (!oldFiles.contains(newFile)) {
                    deleteQuietly(directory.resolve(newFile));
                }
            }
            throw e;
        }
        current = next;
        version = writeAs;
        unreadable = List.copyOf(keep);
        List<String> warnings = new ArrayList<>();
        for (String oldFile : oldFiles) {
            // Uploading the same bytes again gives the same file name. That file is the new one too, so it stays.
            if (!newFiles.contains(oldFile) && !deleteQuietly(directory.resolve(oldFile))) {
                warnings.add("The file " + directory.resolve(oldFile) + " is no longer used and could not be deleted. It may still hold "
                        + "credentials. Delete it by hand.");
            }
        }
        return warnings;
    }

    /**
     * Copies store.json as it is to {@link #BEFORE_V2}, once: a copy already there is the version 1
     * store from before, and is never overwritten by a later one. No store.json yet means there is
     * no version 1 store to keep.
     */
    private void keepVersionOne() {
        Path state = directory.resolve(STATE_FILE);
        Path backup = directory.resolve(BEFORE_V2);
        if (!Files.exists(state) || Files.exists(backup)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(state);
        } catch (IOException | RuntimeException e) {
            // The path and the type of the failure. Never content.
            log.error("The configuration store could not read {} to keep a copy of it ({})", state, e.getClass().getSimpleName());
            throw new ConfigStoreException("The configuration store could not read " + state + " to keep a copy of it before writing it in "
                    + "the new format. Nothing was changed.", e);
        }
        writeAtomically(backup, bytes);
        // The path only.
        log.info("The configuration store kept its version 1 state as {} before writing version 2", backup);
    }

    /**
     * Refuses a change when a file the new state would name is not on disk. A store.json that
     * names a missing file stops the next start, so it is never written: the store folder changed
     * under the running tool, as a clean-up, a scanner or a second copy of the tool on the same
     * folder changes it, and memory no longer says what is there.
     */
    private void refuseMissingFiles(ConfigSnapshot next) {
        int missing = 0;
        for (StoredEnvironment environment : next.environments()) {
            missing += Files.isRegularFile(directory.resolve(environment.file())) ? 0 : 1;
        }
        for (StoredCollection collection : next.collections()) {
            missing += Files.isRegularFile(directory.resolve(collection.file())) ? 0 : 1;
        }
        if (missing == 0) {
            return;
        }
        // The folder and a count. Never a name from an uploaded file.
        log.error("The configuration store at {} is missing {} of the files it names, so the change was refused", directory, missing);
        String files = missing == 1 ? "1 stored file is missing" : missing + " stored files are missing";
        // A start reads store.json as it lies on disk. Where that still names a missing file, a
        // restart is refused as well, so the sentence says what does work.
        String what = startRefused() ? (missing == 1 ? "Put it back" : "Put them back") + " and try again, or stop PAVE, move the folder "
                + "aside and start PAVE again with an empty store." : "Stop PAVE and start it again.";
        throw ConfigStoreException.ofThisComputer("The configuration store folder " + directory + " changed while PAVE ran: " + files + ". "
                + what, null);
    }

    /**
     * True when a start would refuse store.json as it lies on disk now: it cannot be read, or its
     * version is not one this tool reads. False when there is no store.json, since a start then
     * opens an empty store.
     */
    private boolean startRefused() {
        Path state = directory.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            return false;
        }
        try {
            // A stored file that is missing or cannot be read no longer stops a start: it is left
            // out and listed. Only a store.json that cannot be read itself does.
            JsonSafe.Parsed parsed = JsonSafe.parse(read(state));
            if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
                return true;
            }
            Integer version = JsonSafe.integer(parsed.node(), "version");
            return version == null || version < STATE_VERSION || version > SETTINGS_VERSION;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Writes a file of the store to a temporary name and moves it into place. A failure whose cause
     * lies with this computer's files is named as such, with what to do about it: a disk that is
     * full, a file marked read-only that could not be cleared, a file another program kept open.
     * Any other failure keeps the general sentence, so that nothing is named that was not seen.
     */
    private void writeAtomically(Path target, byte[] bytes) {
        Path folder = target.getParent();
        Path temporary = target.resolveSibling(target.getFileName() + TEMP_MARK + UUID.randomUUID());
        boolean written = false;
        try {
            // A folder removed while the tool runs, by a clean-up or by hand, is made again, so the
            // next save works without a restart. Whether the files it held are still there is the
            // commit's question.
            Files.createDirectories(folder, OwnerOnly.directory());
            disk.writeNew(temporary, bytes);
            written = true;
            replace(temporary, target);
        } catch (ConfigStoreException e) {
            deleteQuietly(temporary);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            if (!written && noRoom(folder, bytes.length)) {
                // The folder and the type of the failure. Never content.
                log.error("The configuration store could not write {}: the disk is full ({})", target, e.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The disk that holds " + folder + " is full. Free some space, then try again. "
                        + "Nothing was changed.", e);
            }
            // The path and the type of the failure. Never content.
            log.error("The configuration store could not write {} ({})", target, e.getClass().getSimpleName());
            throw new ConfigStoreException("The configuration store could not write " + target + ". Nothing was changed. Check that "
                    + "the directory exists, is writable and has free space.", e);
        }
    }

    /** True when the disk that holds the folder has too little room left for these bytes. The space is read after the failure, not guessed. */
    private boolean noRoom(Path folder, int length) {
        long usable = disk.usableSpace(folder);
        return usable >= 0 && usable < length + SPACE_MARGIN_BYTES;
    }

    /**
     * Puts the written file in place of the target. Windows refuses to replace a file marked
     * read-only and one another program holds open, and both are common on a workstation: a sync
     * or copy tool sets the attribute, a scanner or an indexer opens what was just written. The
     * attribute is cleared once, since the file is the store's own, and a refusal that may be a
     * lock is tried again for about a second before it is reported.
     */
    private void replace(Path temporary, Path target) throws IOException {
        boolean clearedOnce = false;
        long waited = 0;
        while (true) {
            FileSystemException refused;
            try {
                disk.replace(temporary, target);
                return;
            } catch (NoSuchFileException | FileAlreadyExistsException | DirectoryNotEmptyException e) {
                // A folder that went away, or something other than a file in the way: no attribute and no lock.
                throw e;
            } catch (FileSystemException e) {
                refused = e;
            }
            boolean readOnly = disk.readOnly(target);
            if (readOnly && !clearedOnce) {
                clearedOnce = true;
                if (disk.clearReadOnly(target)) {
                    // The path only.
                    log.warn("The configuration store took the read-only attribute off {} to replace it", target);
                    continue;
                }
            }
            if (readOnly) {
                // The path and the type of the failure. Never content.
                log.error("The configuration store could not write {}: it is marked read-only ({})", target,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + target + " is marked read-only. Clear Read-only in its "
                        + "Properties, then try again. Nothing was changed.", refused);
            }
            if (waited < REPLACE_RETRY_MILLIS) {
                disk.pause(REPLACE_RETRY_STEP_MILLIS);
                waited += REPLACE_RETRY_STEP_MILLIS;
                continue;
            }
            // Elsewhere an open file does not stop a replace, so a refusal there is not called a lock.
            if (disk.locksFiles() && Files.exists(target)) {
                // The path and the type of the failure. Never content.
                log.error("The configuration store could not write {}: another program holds it open ({})", target,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + target + " is in use by another program (a sync or backup "
                        + "client, a virus scanner, an editor). Close it or wait, then try again. Nothing was changed.", refused);
            }
            throw refused;
        }
    }

    private boolean deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException | RuntimeException e) {
            // The path only.
            log.warn("The configuration store could not delete {} ({})", file, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The text of store.json for a snapshot, as version 2 when any environment holds settings and as
     * version 1 otherwise. Every character outside ASCII is written as its escape, see {@link #asciiOnly}.
     */
    static String stateJson(ConfigSnapshot snapshot) {
        return stateJson(snapshot, holdsSettings(snapshot) ? SETTINGS_VERSION : STATE_VERSION);
    }

    /** True when an environment of the snapshot states a setting, or holds a settings entry that could not be read. */
    static boolean holdsSettings(ConfigSnapshot snapshot) {
        return snapshot.environments().stream().anyMatch(environment -> !environment.settings().empty()
                || environment.unreadSettings() != null);
    }

    /** The text of store.json for a snapshot, written as the version given. Version 1 holds no settings. */
    static String stateJson(ConfigSnapshot snapshot, int version) {
        return stateJson(snapshot, version, List.of());
    }

    /** As above, with the entries whose file could not be read written back after the readable ones, as they were. */
    static String stateJson(ConfigSnapshot snapshot, int version, List<Unreadable> unreadable) {
        ObjectNode root = JsonSafe.mapper().createObjectNode();
        root.put("version", version);
        root.put("epoch", snapshot.epoch());
        if (snapshot.activeName() != null) {
            root.put("activeEnvironment", snapshot.activeName());
        }
        ArrayNode environments = root.putArray("environments");
        for (StoredEnvironment environment : snapshot.environments()) {
            ObjectNode entry = environments.addObject();
            entry.put("name", environment.name());
            entry.put("kind", environment.kind().wire());
            entry.put("file", environment.file());
            putIfPresent(entry, "originalFileName", environment.originalFileName());
            putIfPresent(entry, "uploadedAt", environment.uploadedAt());
            entry.put("acknowledgedNotProd", environment.acknowledgedNotProd());
            if (version >= SETTINGS_VERSION) {
                if (environment.unreadSettings() != null) {
                    entry.set("settings", environment.unreadSettings().deepCopy());
                } else if (!environment.settings().empty()) {
                    entry.set("settings", StoredSettings.write(environment.settings()));
                }
            }
        }
        unreadable.stream().filter(each -> ENVIRONMENTS.equals(each.list())).forEach(each -> environments.add(each.entry().deepCopy()));
        // Every production destination ever known here, so that a deletion does not make
        // production forgettable. Scheme, host, port and path only: never a query or user information.
        ArrayNode known = root.putArray("productionDestinations");
        for (ConfigSnapshot.KnownDestination destination : snapshot.remembered()) {
            if (destination.secret()) {
                // Built from a secret of the environment, which this file must not hold.
                continue;
            }
            ObjectNode entry = known.addObject();
            entry.put("environment", destination.environment());
            entry.put("destination", destination.destination().shown());
        }
        ArrayNode collections = root.putArray("collections");
        for (StoredCollection collection : snapshot.collections()) {
            ObjectNode entry = collections.addObject();
            entry.put("id", collection.id());
            entry.put("name", collection.name());
            entry.put("file", collection.file());
            putIfPresent(entry, "originalFileName", collection.originalFileName());
            putIfPresent(entry, "uploadedAt", collection.uploadedAt());
            ObjectNode corrections = entry.putObject("corrections");
            for (StoredCollection.Mapped request : collection.requests()) {
                if (request.correction() != null) {
                    corrections.put(request.request().id(), request.correction().name());
                }
            }
        }
        unreadable.stream().filter(each -> COLLECTIONS.equals(each.list())).forEach(each -> collections.add(each.entry().deepCopy()));
        return asciiOnly(JsonSafe.pretty(root)) + "\n";
    }

    /**
     * JSON text with every character outside ASCII written as its escape, which JSON allows
     * anywhere in a string; outside strings the text is ASCII already. Written as it was, a name
     * holding half of a surrogate pair (a broken emoji) became a question mark in the file, while
     * the uploaded file kept it, and the store refused its own files at the next start because the
     * two names differed. Escaped, every name reads back exactly, and an editor that saves the
     * file in another encoding cannot change one.
     */
    static String asciiOnly(String json) {
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                out.append(c);
            } else {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (Texts.present(value)) {
            node.put(field, value);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------------------------------

    private void prepareDirectories() {
        for (Path each : List.of(directory, directory.resolve(ENVIRONMENTS), directory.resolve(COLLECTIONS))) {
            try {
                Files.createDirectories(each, OwnerOnly.directory());
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("The configuration store directory " + each + " cannot be created ("
                        + e.getClass().getSimpleName() + "). Set config-store.directory to a directory this tool can create and write to.", e);
            }
            try {
                Files.delete(Files.createTempFile(each, ".write-check-", ".tmp"));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("The configuration store directory " + each + " is not writable ("
                        + e.getClass().getSimpleName() + "). Set config-store.directory to a directory this tool can write to.", e);
            }
            // A folder made by an earlier version, or by hand, is closed to other accounts as well,
            // with the files already in it.
            OwnerOnly.restrict(each);
        }
        // Taken before anything in the folder is cleaned up: a temporary file of another copy that
        // is writing at this moment is not a leftover.
        lockFolder();
        for (Path each : List.of(directory, directory.resolve(ENVIRONMENTS), directory.resolve(COLLECTIONS))) {
            // A crash between writing a temporary file and moving it leaves the temporary file
            // behind, and it can hold a credential from an upload. Nothing reads these.
            try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(each, "*" + TEMP_MARK + "*")) {
                leftovers.forEach(this::deleteQuietly);
            } catch (IOException | RuntimeException e) {
                // The path only.
                log.warn("The configuration store could not look for leftover temporary files in {}", each);
            }
        }
    }

    /**
     * Takes the lock on {@link #LOCK_FILE} for as long as this store is open, so that a second copy
     * of PAVE on the same folder is refused at its start: the two would overwrite each other's
     * store.json and share one ledger and one trail. The system lets go of the lock when the copy
     * that held it ends, however it ends, so a lock file a killed copy left behind refuses nothing.
     * A file system that cannot lock at all is told in the log and the store opens without the lock:
     * a protection the disk cannot give must not stop the tool.
     */
    private void lockFolder() {
        Path lockFile = directory.resolve(LOCK_FILE);
        synchronized (LOCKS) {
            HeldLock held = LOCKS.get(directory);
            if (held != null) {
                // The system lock is the whole JVM's: a second store on the folder in this JVM (two
                // test contexts, a restart within a test) shares it, and the folder is let go of
                // when the last of them closes.
                held.holders++;
                this.locked = true;
                return;
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), OwnerOnly.file());
                java.nio.channels.FileLock lock = channel.tryLock();
                if (lock == null) {
                    closeQuietly(channel);
                    throw anotherCopy();
                }
                LOCKS.put(directory, new HeldLock(channel, lock));
                this.locked = true;
            } catch (java.nio.channels.OverlappingFileLockException e) {
                closeQuietly(channel);
                throw anotherCopy();
            } catch (IOException | UnsupportedOperationException e) {
                closeQuietly(channel);
                // The path and the type of the failure only.
                log.warn("The configuration store could not lock {} ({}). It opens without the lock, so a second copy of PAVE on this "
                        + "folder would not be refused.", lockFile, e.getClass().getSimpleName());
            }
        }
    }

    private IllegalStateException anotherCopy() {
        // The folder only.
        log.error("Another copy of PAVE holds the lock on the configuration store {}", directory);
        return new IllegalStateException("Another copy of PAVE is using the configuration store " + directory + ". Stop it, or give this "
                + "copy its own config-store.directory and tracking.directory.");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // Nothing is held by a channel that failed to close: the lock goes with the process.
        }
    }

    /**
     * Lets go of the folder's lock when the last store of this JVM on that folder closes, so that a
     * context that stops and a new one that starts on the same folder do not refuse each other.
     */
    @jakarta.annotation.PreDestroy
    public void close() {
        if (!locked) {
            return;
        }
        locked = false;
        synchronized (LOCKS) {
            HeldLock held = LOCKS.get(directory);
            if (held == null || --held.holders > 0) {
                return;
            }
            LOCKS.remove(directory);
            try {
                held.lock.release();
            } catch (IOException | RuntimeException e) {
                // The path and the type of the failure only. Closing the channel below lets go of it as well.
                log.warn("The configuration store could not let go of the lock on {} ({})", directory.resolve(LOCK_FILE),
                        e.getClass().getSimpleName());
            }
            closeQuietly(held.channel);
        }
    }

    private ConfigSnapshot load() {
        Path state = directory.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            return new ConfigSnapshot(0L, List.of(), null, List.of());
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(read(state));
        if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
            throw unusable(state, "it is not a JSON object");
        }
        JsonNode root = parsed.node();
        Integer version = JsonSafe.integer(root, "version");
        if (version == null || version < 1) {
            throw unusable(state, "it does not state its version");
        }
        if (version > SETTINGS_VERSION) {
            throw unusable(state, "it was written by a later version of this tool (version " + version + "; this one reads versions "
                    + STATE_VERSION + " and " + SETTINGS_VERSION + ")");
        }
        this.version = version;

        List<StoredEnvironment> environments = new ArrayList<>();
        List<Unreadable> leftOut = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode entry : JsonSafe.elements(root, "environments")) {
            String name = JsonSafe.presentText(entry, "name");
            String file = JsonSafe.presentText(entry, "file");
            if (name == null || file == null) {
                throw unusable(state, "one of its environments has no name or no file");
            }
            if (!names.add(name)) {
                throw unusable(state, "it lists the environment \"" + Names.shown(name) + "\" twice");
            }
            EnvironmentKind kind;
            try {
                kind = EnvironmentKind.parse(JsonSafe.presentText(entry, "kind"));
            } catch (IllegalArgumentException e) {
                throw unusable(state, "the environment \"" + Names.shown(name) + "\" has no kind of sandbox, uat or prod, and a kind is never guessed");
            }
            Path path = inside(file, state);
            String what = "the environment \"" + quoted(name) + "\"";
            PostmanEnvironment content;
            try {
                content = PostmanParser.environment(PostmanParser.decode(storedBytes(path)));
            } catch (Unopened e) {
                leftOut.add(new Unreadable(ENVIRONMENTS, file, name, e.line(path, what), entry.deepCopy()));
                continue;
            } catch (BadInputException e) {
                leftOut.add(new Unreadable(ENVIRONMENTS, file, name, "The file " + path + " of " + what + " is no longer a Postman "
                        + "environment export, so the environment is left out. Remove it from the store and upload the environment again.",
                        entry.deepCopy()));
                continue;
            }
            if (!content.name().equals(name)) {
                leftOut.add(new Unreadable(ENVIRONMENTS, file, name, "The file " + path + " of " + what + " holds an environment named \""
                        + quoted(content.name()) + "\", so the environment is left out. Remove it from the store and upload the environment "
                        + "again.", entry.deepCopy()));
                continue;
            }
            // A version 1 store has no settings: whatever such an entry holds under that name is not read as settings.
            JsonNode settingsNode = version >= SETTINGS_VERSION ? JsonSafe.child(entry, "settings") : null;
            StoredSettings.Read settings = StoredSettings.read(settingsNode);
            if (settings.problem() != null) {
                // The name and why, never a value: the entry can hold a client secret.
                log.warn("The settings of the environment \"{}\" in {} cannot be read: {}. They are kept as they are and not used.",
                        quoted(name), STATE_FILE, settings.problem());
            }
            environments.add(new StoredEnvironment(Ids.named(name), name, kind, content, file, JsonSafe.presentText(entry, "originalFileName"),
                    JsonSafe.presentText(entry, "uploadedAt"), Boolean.TRUE.equals(JsonSafe.bool(entry, "acknowledgedNotProd")),
                    ProductionSigns.in(content), settings.settings(), settings.problem(),
                    settings.problem() == null ? null : settingsNode.deepCopy()));
        }

        List<StoredCollection> collections = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : JsonSafe.elements(root, "collections")) {
            String file = JsonSafe.presentText(entry, "file");
            if (file == null) {
                throw unusable(state, "one of its collections has no file");
            }
            Path path = inside(file, state);
            String listedName = JsonSafe.presentText(entry, "name");
            String what = listedName == null ? "a collection" : "the collection \"" + quoted(listedName) + "\"";
            String listedId = JsonSafe.presentText(entry, "id");
            PostmanCollection content;
            try {
                content = PostmanParser.collection(PostmanParser.decode(storedBytes(path)));
            } catch (Unopened e) {
                leftOut.add(new Unreadable(COLLECTIONS, file, listedId, e.line(path, what), entry.deepCopy()));
                continue;
            } catch (BadInputException e) {
                leftOut.add(new Unreadable(COLLECTIONS, file, listedId, "The file " + path + " of " + what + " is no longer a Postman "
                        + "collection export, so the collection is left out. Remove it from the store and upload the collection again.",
                        entry.deepCopy()));
                continue;
            }
            String id = Ids.named(content.name());
            if (!ids.add(id)) {
                leftOut.add(new Unreadable(COLLECTIONS, file, null, "The file " + path + " holds the collection \"" + quoted(content.name())
                        + "\", which another stored file holds as well, so this one is left out. Remove it from the store.", entry.deepCopy()));
                continue;
            }
            Map<String, EpaOperation> corrections = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> correction : JsonSafe.properties(JsonSafe.child(entry, "corrections"))) {
                EpaOperation operation;
                try {
                    operation = EpaOperation.parse(JsonSafe.text(correction.getValue()));
                } catch (IllegalArgumentException e) {
                    throw unusable(state, "a mapping correction in the collection \"" + Names.shown(content.name()) + "\" names no operation this tool knows");
                }
                if (operation == EpaOperation.DTR_RESPONSE) {
                    throw unusable(state, "a mapping correction in the collection \"" + Names.shown(content.name()) + "\" names " + operation.name()
                            + ", which no collection request can stand for");
                }
                corrections.put(correction.getKey(), operation);
            }
            collections.add(new StoredCollection(id, content, file, JsonSafe.presentText(entry, "originalFileName"),
                    JsonSafe.presentText(entry, "uploadedAt"), mapped(content, corrections)));
        }

        String active = JsonSafe.presentText(root, "activeEnvironment");
        this.unreadable = List.copyOf(leftOut);
        for (Unreadable each : leftOut) {
            // The file as store.json names it. Never content.
            log.warn("The configuration store left out {}: it cannot be read. In force now lists it.", each.file());
        }
        boolean activeLoaded = false;
        for (StoredEnvironment environment : environments) {
            activeLoaded |= environment.name().equals(active);
        }
        if (active != null && !activeLoaded && names.contains(active)) {
            // The active environment is among those left out: none is active until it is back.
            log.warn("The active environment \"{}\" in {} is left out because its file cannot be read. No environment is active.",
                    quoted(active), STATE_FILE);
            active = null;
        }
        if (active != null && !names.contains(active)) {
            throw unusable(state, "it names \"" + Names.shown(active) + "\" as the active environment, and it lists no environment of that name");
        }
        Long epoch = JsonSafe.longValue(root, "epoch");
        List<ConfigSnapshot.KnownDestination> remembered = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(root, "productionDestinations")) {
            String environment = JsonSafe.presentText(entry, "environment");
            String written = JsonSafe.presentText(entry, "destination");
            if (written == null) {
                // Nothing is there to protect, and nothing to keep.
                log.warn("The configuration store left out a production destination of {} that holds no address", STATE_FILE);
                continue;
            }
            if (environment == null) {
                environment = UNNAMED_PRODUCTION;
            }
            remembered.add(new ConfigSnapshot.KnownDestination(environment, rememberedDestination(written)));
        }
        return new ConfigSnapshot(epoch == null ? 0L : epoch, environments, active, collections, remembered);
    }

    /** Who a remembered production address belongs to when store.json does not say: still production, under this name. */
    static final String UNNAMED_PRODUCTION = "an environment store.json does not name";

    /**
     * A remembered production address as store.json writes it, read back: as an address where it
     * reads as one, else as the whole host it names, else as its text taken for a host (hunt-files 5:
     * a host with a space in it was remembered and then stopped every start). Kept rather than
     * refused, it still protects whatever has the same host, and it is written back as it was.
     */
    static org.p32h.interop.epa.pave.postman.UrlNormal.Destination rememberedDestination(String written) {
        org.p32h.interop.epa.pave.postman.UrlNormal.Destination destination = org.p32h.interop.epa.pave.postman.UrlNormal.destination(written);
        if (destination == null) {
            destination = org.p32h.interop.epa.pave.postman.UrlNormal.wholeHost(written);
        }
        if (destination == null) {
            destination = new org.p32h.interop.epa.pave.postman.UrlNormal.Destination(null, written.strip().toLowerCase(java.util.Locale.ROOT),
                    -1, "", false);
        }
        return destination;
    }

    /** A path named in the state file, which must stay inside the store directory. */
    private Path inside(String file, Path state) {
        Path resolved;
        try {
            resolved = directory.resolve(file).normalize();
        } catch (InvalidPathException e) {
            throw unusable(state, "it names a file with a path that cannot be used");
        }
        if (!resolved.startsWith(directory)) {
            throw unusable(state, "it names a file outside the configuration store directory");
        }
        return resolved;
    }

    private String read(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    /** A stored file that is not there or cannot be read, told apart so the line says which. */
    private static final class Unopened extends RuntimeException {
        private final boolean missing;
        private final String type;

        private Unopened(boolean missing, String type) {
            super(null, null, false, false);
            this.missing = missing;
            this.type = type;
        }

        String line(Path path, String what) {
            String noun = what.startsWith("the environment") ? "environment" : "collection";
            return "The file " + path + " of " + what + (missing ? " is missing" : " cannot be read (" + type + ")") + ", so the " + noun
                    + " is left out. Put the file back and start PAVE again, or remove it from the store.";
        }
    }

    private static byte[] storedBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new Unopened(true, null);
        } catch (IOException | RuntimeException e) {
            throw new Unopened(!Files.exists(file), e.getClass().getSimpleName());
        }
    }

    private byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            throw unusable(file, "it is missing or cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private IllegalStateException unusable(Path file, String why) {
        return new IllegalStateException("The configuration store file " + file + " cannot be used: " + why + ". Repair it, or move the "
                + "directory " + directory + " aside to start with an empty store.");
    }
}
