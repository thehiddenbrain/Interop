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
    private static final int STATE_VERSION = 1;
    private static final String TEMP_MARK = ".tmp-";
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
        ConfigSnapshot loaded = load();
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
        PostmanEnvironment parsed = PostmanParser.environment(PostmanParser.decode(content));
        ConfigSnapshot before = current;
        StoredEnvironment previous = before.byName(parsed.name());
        List<String> signs = ProductionSigns.in(parsed);
        // What the file says about itself is kept with the environment. What this installation
        // already holds under the same name is a reason of its own to ask, and is not kept: once
        // the upload is confirmed it is no longer true.
        List<String> reasonsToAsk = new ArrayList<>(signs);
        if (previous != null && previous.kind() == EnvironmentKind.PROD) {
            reasonsToAsk.add("an environment named \"" + quoted(parsed.name()) + "\" is already stored here as prod, and this upload would replace it");
        }
        if (kind != EnvironmentKind.PROD) {
            refuseProductionAddresses(before, parsed, kind);
        } else {
            refuseUnreadableProduction(parsed, before.context(), "upload");
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
        StoredEnvironment stored = new StoredEnvironment(id, parsed.name(), kind, parsed, file, displayName(originalFileName),
                clock.instant().toString(), kind != EnvironmentKind.PROD && (needsConfirmation || acknowledgedNotProd), signs);

        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        if (previous == null) {
            environments.add(stored);
        } else {
            environments.set(environments.indexOf(previous), stored);
        }
        // A production environment uploaded again, written plainly on the same host, replaces what
        // its earlier, unreadable address protected on that host. Uploaded on another host it adds
        // to what is remembered and takes nothing away: the old host may still be production.
        List<UrlNormal.Destination> plain = kind != EnvironmentKind.PROD ? List.of()
                : ProductionDestinations.production(parsed, before.context()).stream().map(ProductionDestinations.Found::destination)
                .filter(UrlNormal.Destination::readable).toList();
        List<ConfigSnapshot.KnownDestination> remembered = before.remembered().stream()
                .filter(known -> !(known.environment().equals(parsed.name()) && !known.destination().readable()
                        && plain.stream().anyMatch(each -> new UrlNormal.Destination(null, known.destination().host(), -1, "", false)
                        .sameOrigin(each))))
                .toList();
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

    /** Makes an uploaded environment the active one, with no restart. */
    synchronized ConfigSnapshot activate(String nameOrId) {
        ConfigSnapshot before = current;
        StoredEnvironment environment = requireEnvironment(before, nameOrId);
        if (environment.name().equals(before.activeName())) {
            return before;
        }
        if (environment.kind() == EnvironmentKind.PROD) {
            refuseUnreadableProduction(environment.content(), before.context(), "activate");
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
        PostmanCollection parsed = PostmanParser.collection(PostmanParser.decode(content));
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
     * <p>When no environment that made the address production is stored any more, the refusal says
     * that the address is remembered from a removed environment, and how to forget it. An
     * environment uploaded as prod by a slip and then removed leaves its addresses refused for
     * good, and the one way out the refusal used to give, pointing the variables at the sandbox,
     * cannot work when they point there already (R-35). Nothing is forgotten here: the way out is
     * the tester's own edit, made with PAVE stopped.
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
        // An owner that is no longer stored is known only as a name the store remembers the address by.
        boolean removed = owners.stream().noneMatch(owner -> before.byName(owner) != null);
        String fromRemoved = "remembered as production from the removed environment" + (several ? "s " : " ") + named;
        String anAddressOf = removed ? "an address " + fromRemoved : "an address of the " + production;
        ProductionDestinations.Found shown = quoted;
        String said;
        if (shown == null) {
            said = "one of its addresses is " + anAddressOf + ", or lies above or beneath one.";
        } else if (!shown.destination().readable() && shown.written() != null) {
            said = "its address \"" + UrlNormal.asWritten(shown.written()) + "\", in the variable \"" + quoted(shown.variable())
                    + "\", cannot be read the way the HTTP client reads it. Read as closely as this tool can, it is " + anAddressOf
                    + ", or lies above or beneath one.";
        } else if (before.remembered().stream().anyMatch(known -> known.destination().everyPortAndPath()
                && known.destination().covers(shown.destination()))) {
            said = removed ? "its address " + oneLine(shown.destination().shown()) + " is on a host " + fromRemoved + ". "
                    + (several ? "One of them" : "That environment") + " had an address this tool cannot read the way the HTTP client "
                    + "reads it, so every port and every path of its host is taken as production."
                    : "its address " + oneLine(shown.destination().shown()) + " is on the host of the " + production + ". That "
                    + "production environment has an address this tool cannot read the way the HTTP client reads it, so every port and "
                    + "every path of its host is taken as production.";
        } else {
            said = "its address " + oneLine(shown.destination().shown()) + " is " + anAddressOf + ", or lies above or beneath one.";
        }
        String note = "An address that this installation knows as production stays production, even after its environment is deleted. "
                + "If this environment is production, upload it with the kind prod. If it is not, point its variables at the " + kind.wire()
                + " service and upload it again.";
        if (removed) {
            // The store keeps no way to forget an address while it runs, so the way out is the
            // one that works: the remembered entries taken out of store.json while PAVE is stopped.
            String entries = owners.stream().map(owner -> "\"" + quoted(owner) + "\"").collect(java.util.stream.Collectors.joining(" or "));
            note = "If " + named + (several ? " were" : " was") + " uploaded as prod by mistake: stop PAVE, delete the entries whose "
                    + "environment is " + entries + " from productionDestinations in " + directory.resolve(STATE_FILE) + ", start PAVE "
                    + "again and upload this environment again. Otherwise the address stays production: if this environment is "
                    + "production, upload it with the kind prod, and if it is not, point its variables at the " + kind.wire()
                    + " service and upload it again.";
        }
        throw new ValidationException("This environment was declared as " + kind.wire() + ", and " + said + " It was not stored.",
                List.of(new FieldError("kind", note)));
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
        try {
            refuseMissingFiles(next);
            writeAtomically(directory.resolve(STATE_FILE), stateJson(next).getBytes(StandardCharsets.UTF_8));
        } catch (ConfigStoreException e) {
            if (newFile != null && !newFile.equals(oldFile)) {
                deleteQuietly(directory.resolve(newFile));
            }
            throw e;
        }
        current = next;
        List<String> warnings = new ArrayList<>();
        // Uploading the same bytes again gives the same file name. That file is the new one too, so it stays.
        if (oldFile != null && !oldFile.equals(newFile) && !deleteQuietly(directory.resolve(oldFile))) {
            warnings.add("The file " + directory.resolve(oldFile) + " is no longer used and could not be deleted. It may still hold "
                    + "credentials. Delete it by hand.");
        }
        return warnings;
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
     * True when a start would refuse store.json as it lies on disk now: it cannot be read, or it
     * names a file that is not there. False when there is no store.json, since a start then opens
     * an empty store, and when every file it names is there.
     */
    private boolean startRefused() {
        Path state = directory.resolve(STATE_FILE);
        if (!Files.exists(state)) {
            return false;
        }
        try {
            JsonSafe.Parsed parsed = JsonSafe.parse(read(state));
            if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
                return true;
            }
            for (String list : List.of("environments", "collections")) {
                for (JsonNode entry : JsonSafe.elements(parsed.node(), list)) {
                    String file = JsonSafe.presentText(entry, "file");
                    Path named = file == null ? null : directory.resolve(file).normalize();
                    if (named == null || !named.startsWith(directory) || !Files.isRegularFile(named)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            // Unreadable, or a path that cannot be used: a start refuses both.
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

    /** The text of store.json for a snapshot. Every character outside ASCII is written as its escape, see {@link #asciiOnly}. */
    static String stateJson(ConfigSnapshot snapshot) {
        ObjectNode root = JsonSafe.mapper().createObjectNode();
        root.put("version", STATE_VERSION);
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
        }
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
        if (version > STATE_VERSION) {
            throw unusable(state, "it was written by a later version of this tool");
        }

        List<StoredEnvironment> environments = new ArrayList<>();
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
            PostmanEnvironment content;
            try {
                content = PostmanParser.environment(PostmanParser.decode(readBytes(path)));
            } catch (BadInputException e) {
                throw unusable(path, "it is no longer a Postman environment export");
            }
            if (!content.name().equals(name)) {
                throw unusable(path, "it holds an environment named \"" + Names.shown(content.name()) + "\", and " + STATE_FILE + " lists it as \"" + Names.shown(name) + "\"");
            }
            environments.add(new StoredEnvironment(Ids.named(name), name, kind, content, file, JsonSafe.presentText(entry, "originalFileName"),
                    JsonSafe.presentText(entry, "uploadedAt"), Boolean.TRUE.equals(JsonSafe.bool(entry, "acknowledgedNotProd")),
                    ProductionSigns.in(content)));
        }

        List<StoredCollection> collections = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : JsonSafe.elements(root, "collections")) {
            String file = JsonSafe.presentText(entry, "file");
            if (file == null) {
                throw unusable(state, "one of its collections has no file");
            }
            Path path = inside(file, state);
            PostmanCollection content;
            try {
                content = PostmanParser.collection(PostmanParser.decode(readBytes(path)));
            } catch (BadInputException e) {
                throw unusable(path, "it is no longer a Postman collection export");
            }
            String id = Ids.named(content.name());
            if (!ids.add(id)) {
                throw unusable(state, "it lists the collection \"" + Names.shown(content.name()) + "\" twice");
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
        if (active != null && !names.contains(active)) {
            throw unusable(state, "it names \"" + Names.shown(active) + "\" as the active environment, and it lists no environment of that name");
        }
        Long epoch = JsonSafe.longValue(root, "epoch");
        List<ConfigSnapshot.KnownDestination> remembered = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(root, "productionDestinations")) {
            String environment = JsonSafe.presentText(entry, "environment");
            org.p32h.interop.epa.pave.postman.UrlNormal.Destination destination =
                    org.p32h.interop.epa.pave.postman.UrlNormal.destination(JsonSafe.presentText(entry, "destination"));
            if (environment == null || destination == null) {
                throw unusable(state, "one of its production destinations has no environment or no address this tool can read");
            }
            remembered.add(new ConfigSnapshot.KnownDestination(environment, destination));
        }
        return new ConfigSnapshot(epoch == null ? 0L : epoch, environments, active, collections, remembered);
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
