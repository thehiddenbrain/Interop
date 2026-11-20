package org.p32h.interop.epa.pave.service.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Ids;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
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
    private final Path directory;
    /** Files that were in the two upload directories at startup and that the state file did not name. */
    private final List<Path> strays;
    private volatile ConfigSnapshot current;

    /**
     * Opens the store, creating its directory. Fails, naming the path, when the directory cannot be
     * created or written or when a stored file cannot be read: a store that only fails at the
     * first upload would fail in front of a tester and not in front of whoever configured it.
     */
    public ConfigStore(ConfigStoreProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
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
            log.warn("The configuration store at {} holds {} file(s) that it does not use. They may hold credentials from an upload "
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
        this.current = new ConfigSnapshot(epoch, loaded.environments(), active, loaded.collections(), loaded.remembered());
        // Counts and names only. Never a variable value.
        log.info("Configuration store ready at {}: {} environment(s), {} collection(s), active environment: {}", directory,
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
            reasonsToAsk.add("an environment named \"" + oneLine(parsed.name()) + "\" is already stored here as prod, and this upload would replace it");
        }
        if (kind != EnvironmentKind.PROD) {
            refuseProductionAddresses(before, parsed, kind);
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
        writeAtomically(directory.resolve(file), content);
        StoredEnvironment stored = new StoredEnvironment(id, parsed.name(), kind, parsed, file, displayName(originalFileName),
                clock.instant().toString(), kind != EnvironmentKind.PROD && (needsConfirmation || acknowledgedNotProd), signs);

        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        if (previous == null) {
            environments.add(stored);
        } else {
            environments.set(environments.indexOf(previous), stored);
        }
        List<String> leftBehind = commit(new ConfigSnapshot(before.epoch() + 1, environments, before.activeName(), before.collections(),
                before.remembered()), file, previous == null ? null : previous.file());

        List<String> warnings = new ArrayList<>(secretWarnings(parsed.variables(), file));
        warnings.addAll(leftBehind);
        warnings.addAll(parsed.notes());
        // The name, the kind and a count. Never a variable value.
        log.info("Environment \"{}\" uploaded as {} with {} variable(s){}", oneLine(stored.name()), kind.wire(), parsed.variables().size(),
                previous == null ? "" : ", replacing the one of the same name");
        return new EnvironmentUpload(stored, previous != null, warnings, current);
    }

    /** Makes an uploaded environment the active one, with no restart. */
    synchronized ConfigSnapshot activate(String nameOrId) {
        ConfigSnapshot before = current;
        StoredEnvironment environment = requireEnvironment(before, nameOrId);
        if (environment.name().equals(before.activeName())) {
            return before;
        }
        commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), environment.name(), before.collections(),
                before.remembered()), null, null);
        // The name and the kind only.
        log.info("Active environment switched to \"{}\" ({})", oneLine(environment.name()), environment.kind().wire());
        return current;
    }

    synchronized Removal removeEnvironment(String nameOrId) {
        ConfigSnapshot before = current;
        StoredEnvironment environment = requireEnvironment(before, nameOrId);
        boolean wasActive = environment.name().equals(before.activeName());
        List<StoredEnvironment> environments = new ArrayList<>(before.environments());
        environments.remove(environment);
        List<String> warnings = commit(new ConfigSnapshot(before.epoch() + 1, environments, wasActive ? null : before.activeName(),
                before.collections(), before.remembered()), null, environment.file());
        // The name only.
        log.info("Environment \"{}\" removed{}", oneLine(environment.name()), wasActive ? ". It was the active one, so none is active now" : "");
        return new Removal(environment.name(), wasActive, warnings, current.epoch());
    }

    /**
     * Stores an uploaded collection and detects the operation of each request. One with the same
     * name is replaced, and the mapping corrections made on it are kept for every request that is
     * still there.
     */
    synchronized CollectionUpload uploadCollection(String originalFileName, byte[] content) {
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
                    warnings.add("The correction that mapped \"" + old.request().qualifiedName() + "\" to " + old.correction().label()
                            + " was dropped, because the new file has no request with that name in that folder.");
                } else {
                    corrections.put(old.request().id(), old.correction());
                }
            }
        }
        writeAtomically(directory.resolve(file), content);
        StoredCollection stored = new StoredCollection(id, parsed, file, displayName(originalFileName), clock.instant().toString(),
                mapped(parsed, corrections));

        List<StoredCollection> collections = new ArrayList<>(before.collections());
        if (previous == null) {
            collections.add(stored);
        } else {
            collections.set(collections.indexOf(previous), stored);
        }
        List<String> leftBehind = commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), collections,
                before.remembered()), file, previous == null ? null : previous.file());

        warnings.addAll(secretWarnings(parsed.variables(), file));
        warnings.addAll(literalCredentialWarnings(parsed, file));
        warnings.addAll(leftBehind);
        warnings.addAll(parsed.notes());
        // The name and counts only. Never a url, a header or a body.
        log.info("Collection \"{}\" uploaded with {} request(s){}", oneLine(parsed.name()), parsed.requests().size(),
                previous == null ? "" : ", replacing the one of the same name");
        return new CollectionUpload(stored, previous != null, warnings, current);
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
            throw new NotFoundException("The collection \"" + collection.name() + "\" has no request with the id " + shown(requestId) + ".");
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
        commit(new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), collections, before.remembered()),
                null, null);
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
                before.remembered()), null, collection.file());
        // The name only.
        log.info("Collection \"{}\" removed, with its mapping corrections", oneLine(collection.name()));
        return new Removal(collection.name(), false, warnings, current.epoch());
    }

    /**
     * Moves the epoch on without changing what is stored. A relock does this, so that a call
     * prepared while writes were unlocked cannot be sent after they were locked again. Nothing is
     * written: the lock state is never on disk.
     */
    synchronized long advanceEpoch() {
        ConfigSnapshot before = current;
        current = new ConfigSnapshot(before.epoch() + 1, before.environments(), before.activeName(), before.collections(),
                before.remembered());
        return current.epoch();
    }

    private static StoredEnvironment requireEnvironment(ConfigSnapshot snapshot, String nameOrId) {
        StoredEnvironment environment = snapshot.environment(nameOrId);
        if (environment == null) {
            throw new NotFoundException("There is no uploaded environment named " + shown(nameOrId) + ".");
        }
        return environment;
    }

    private static StoredCollection requireCollection(ConfigSnapshot snapshot, String collectionId) {
        StoredCollection collection = snapshot.collection(collectionId);
        if (collection == null) {
            throw new NotFoundException("There is no uploaded collection with the id " + shown(collectionId) + ".");
        }
        return collection;
    }

    /** An identifier from a request as it may be repeated in a message: one line, bounded, in quotes. */
    private static String shown(String identifier) {
        String text = oneLine(identifier);
        return "\"" + (text.length() > 80 ? text.substring(0, 80) + "..." : text) + "\"";
    }

    /**
     * A name from an uploaded file as it may be written to the log or into a sentence: on one
     * line. A name is whatever the file says, and a line break in it would let an upload write
     * what reads as a log line of its own.
     */
    static String oneLine(String name) {
        return name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").strip();
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
     */
    private static void refuseProductionAddresses(ConfigSnapshot before, PostmanEnvironment parsed, EnvironmentKind kind) {
        Set<String> owners = new LinkedHashSet<>();
        for (org.p32h.interop.epa.pave.postman.UrlNormal.Destination destination : ProductionDestinations.of(parsed)) {
            owners.addAll(before.productionOverlaps(destination));
        }
        if (owners.isEmpty()) {
            return;
        }
        String named = owners.stream().map(owner -> "\"" + oneLine(owner) + "\"").collect(java.util.stream.Collectors.joining(" and "));
        throw new ValidationException("This environment was declared as " + kind.wire() + ", and one of its addresses belongs to the "
                + "production environment " + named + ". It was not stored.", List.of(new FieldError("kind", "An address that this "
                + "installation knows as production stays production, even after its environment is deleted. If this environment is "
                + "production, upload it with the kind prod. If it is not, point its variables at the " + kind.wire()
                + " service and upload it again.")));
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
                warnings.add("The variable \"" + variable.key() + "\" holds a secret in plain text, and it now sits in the file "
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
                    requestsByHeader.computeIfAbsent(header.name().strip(), name -> new ArrayList<>()).add(request.qualifiedName());
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        requestsByHeader.forEach((header, requests) -> warnings.add("The header \"" + oneLine(header) + "\" holds a credential typed into "
                + "the collection in " + requests.size() + (requests.size() == 1 ? " request" : " requests") + ", the first being \""
                + oneLine(requests.get(0)) + "\", and it now sits in the file " + directory.resolve(file) + " in plain text. This tool "
                + "redacts it wherever it shows the header. To keep it out of that file, replace it in Postman with a {{placeholder}} and "
                + "export again."));
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

    private void writeAtomically(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling(target.getFileName() + TEMP_MARK + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            // The path and the type of the failure. Never content.
            log.error("The configuration store could not write {} ({})", target, e.getClass().getSimpleName());
            throw new ConfigStoreException("The configuration store could not write " + target + ". Nothing was changed. Check that "
                    + "the directory exists, is writable and has free space.", e);
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

    private static String stateJson(ConfigSnapshot snapshot) {
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
        return JsonSafe.pretty(root) + "\n";
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
                Files.createDirectories(each);
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
                throw unusable(state, "it lists the environment \"" + name + "\" twice");
            }
            EnvironmentKind kind;
            try {
                kind = EnvironmentKind.parse(JsonSafe.presentText(entry, "kind"));
            } catch (IllegalArgumentException e) {
                throw unusable(state, "the environment \"" + name + "\" has no kind of sandbox, uat or prod, and a kind is never guessed");
            }
            Path path = inside(file, state);
            PostmanEnvironment content;
            try {
                content = PostmanParser.environment(PostmanParser.decode(readBytes(path)));
            } catch (BadInputException e) {
                throw unusable(path, "it is no longer a Postman environment export");
            }
            if (!content.name().equals(name)) {
                throw unusable(path, "it holds an environment named \"" + content.name() + "\", and " + STATE_FILE + " lists it as \"" + name + "\"");
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
                throw unusable(state, "it lists the collection \"" + content.name() + "\" twice");
            }
            Map<String, EpaOperation> corrections = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> correction : JsonSafe.properties(JsonSafe.child(entry, "corrections"))) {
                EpaOperation operation;
                try {
                    operation = EpaOperation.parse(JsonSafe.text(correction.getValue()));
                } catch (IllegalArgumentException e) {
                    throw unusable(state, "a mapping correction in the collection \"" + content.name() + "\" names no operation this tool knows");
                }
                if (operation == EpaOperation.DTR_RESPONSE) {
                    throw unusable(state, "a mapping correction in the collection \"" + content.name() + "\" names " + operation.name()
                            + ", which no collection request can stand for");
                }
                corrections.put(correction.getKey(), operation);
            }
            collections.add(new StoredCollection(id, content, file, JsonSafe.presentText(entry, "originalFileName"),
                    JsonSafe.presentText(entry, "uploadedAt"), mapped(content, corrections)));
        }

        String active = JsonSafe.presentText(root, "activeEnvironment");
        if (active != null && !names.contains(active)) {
            throw unusable(state, "it names \"" + active + "\" as the active environment, and it lists no environment of that name");
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
