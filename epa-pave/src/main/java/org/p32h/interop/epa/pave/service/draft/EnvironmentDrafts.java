package org.p32h.interop.epa.pave.service.draft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.OperationDetector;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.ConfigService;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.service.crd.ShapeEvidence;
import org.p32h.interop.epa.pave.service.crd.VendorSamples;
import org.p32h.interop.epa.pave.service.draft.DraftViews.Draft;
import org.p32h.interop.epa.pave.service.draft.DraftViews.FileLine;
import org.p32h.interop.epa.pave.service.draft.DraftViews.OperationRow;
import org.p32h.interop.epa.pave.service.draft.DraftViews.SecretReference;
import org.p32h.interop.epa.pave.service.draft.DraftViews.ShapeRow;
import org.p32h.interop.epa.pave.service.draft.DraftViews.SignInDraft;
import org.p32h.interop.epa.pave.service.error.FileGuard;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * "Fill the form from these files" (R-08): reads a Postman environment export, a collection
 * export or both, and says what the environment form can be filled with, for the tester to
 * review. Nothing is stored and nothing is sent: Save environment is the only thing that stores.
 *
 * <p>Each file is read inside its own {@link FileGuard} (R-07). Whatever goes wrong with one file
 * becomes that file's line, and the other file is still read. The operator's uploads ended in an
 * internal error for every file, round after round; a draft answers 200 for any file it is given.
 *
 * <p>Nothing is guessed. A secret is filled only as a {@code {{variable}}} or
 * {@code {{env:NAME}}} reference, never as a value typed into a file; a placeholder that nothing
 * defines stays visible as itself, with one line; addresses and values are never filled from a
 * file, since what the export defines is already shown beside each value as "from the export".
 */
@Component
public class EnvironmentDrafts {

    /** The slot names, which are also the names of the posted properties and of the field errors. */
    public static final String ENVIRONMENT_SLOT = "environmentExport";
    public static final String COLLECTION_SLOT = "collectionExport";

    /** Where a shape read from the collection's sample comes from, in the words RequestShapes uses. */
    static final String FROM_SAMPLE = "the collection's sample";

    static final String FROM_COLLECTION_SIGN_IN = "the collection's sign-in";
    static final String FROM_VARIABLE_NAMES = "the environment's variable names";

    /** As ConfigService lists variables: past this many places, the rest are counted. */
    private static final int USED_BY_SHOWN = 5;

    /** The roles a variable name can play in a sign-in, by its name lower case without _ - and dots. */
    private static final Set<String> TOKEN_URL_NAMES = Set.of("tokenurl", "accesstokenurl", "authurl", "oauthtokenurl");
    private static final Set<String> CLIENT_ID_NAMES = Set.of("clientid");
    private static final Set<String> CLIENT_SECRET_NAMES = Set.of("clientsecret");
    private static final Set<String> SCOPE_NAMES = Set.of("scope", "scopes");
    private static final Set<String> BEARER_NAMES = Set.of("bearertoken");

    private final Supplier<EnvironmentList> stored;
    private final Function<String, PostmanEnvironment> environmentParser;
    private final Function<String, PostmanCollection> collectionParser;

    @Autowired
    public EnvironmentDrafts(ConfigService config) {
        this(config::environments, PostmanParser::environment, PostmanParser::collection);
    }

    /**
     * With the stored environments and the two parsers given, so that a test can make a parser
     * fail the way a rebuilt classes folder or a deep file made the real one fail.
     */
    public EnvironmentDrafts(Supplier<EnvironmentList> stored, Function<String, PostmanEnvironment> environmentParser,
                             Function<String, PostmanCollection> collectionParser) {
        this.stored = stored;
        this.environmentParser = environmentParser;
        this.collectionParser = collectionParser;
    }

    /**
     * One file as the page read it.
     *
     * @param fileName the name of the file the tester chose, or null
     * @param text     its text, as the page decoded it
     */
    public record FileText(String fileName, String text) {

        /** Never the text: an export holds credentials. */
        @Override
        public String toString() {
            return "FileText[" + (text == null ? "no text" : text.length() + " characters") + "]";
        }
    }

    /**
     * Reads the files given. At least one is given; the caller refuses a request with none.
     *
     * @param forId the id of the environment being edited, or null for a new one
     * @throws NotFoundException when {@code forId} names no stored environment
     */
    public Draft draft(FileText environmentFile, FileText collectionFile, String forId) {
        EnvironmentList list = stored.get();
        EnvironmentView editing = null;
        if (Texts.present(forId)) {
            editing = list.environments().stream().filter(view -> forId.strip().equals(view.id())).findFirst().orElse(null);
            if (editing == null) {
                throw new NotFoundException("No environment with this id is stored: it may have been removed since the form opened.",
                        "Close the form and open the environment again from its card.");
            }
        }
        List<FileLine> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        PostmanEnvironment environment = null;
        if (environmentFile != null) {
            FileGuard.Read<PostmanEnvironment> read = FileGuard.read(environmentFile.fileName(),
                    () -> environmentParser.apply(environmentFile.text()));
            files.add(line(ENVIRONMENT_SLOT, environmentFile, read, read.ok() ? environmentRead(read.value()) : null));
            if (read.ok()) {
                environment = read.value();
                read.value().notes().forEach(note -> warnings.add("Environment export: " + note));
            }
        }

        PostmanCollection collection = null;
        CollectionAuth auth = null;
        if (collectionFile != null) {
            FileGuard.Read<CollectionRead> read = FileGuard.read(collectionFile.fileName(), () -> {
                PostmanCollection parsed = collectionParser.apply(collectionFile.text());
                return new CollectionRead(parsed, CollectionAuth.find(collectionFile.text()));
            });
            files.add(line(COLLECTION_SLOT, collectionFile, read, read.ok() ? collectionRead(read.value().collection()) : null));
            if (read.ok()) {
                collection = read.value().collection();
                auth = read.value().auth();
                read.value().collection().notes().forEach(note -> warnings.add("Collection export: " + note));
            }
        }

        String name = environment == null ? null : environment.name();
        Boolean nameTaken = null;
        if (name != null) {
            String exported = name;
            if (editing != null) {
                nameTaken = false;
                if (!editing.name().equals(exported)) {
                    warnings.add("This export is named \"" + Names.shown(exported) + "\". The environment keeps its name \""
                            + Names.shown(editing.name()) + "\": a name cannot change.");
                }
            } else {
                nameTaken = list.environments().stream().anyMatch(view -> exported.equals(view.name()));
            }
        }

        Set<String> defined = new LinkedHashSet<>();
        String environmentName = null;
        if (environment != null) {
            defined.addAll(environment.inForce().keySet());
            environmentName = environment.name();
        } else if (editing != null) {
            editing.variables().stream().filter(VariableView::inForce).forEach(view -> defined.add(view.name()));
            environmentName = editing.name();
        }
        if (collection != null) {
            defined.addAll(collection.inForce().keySet());
        }

        SignInDraft signIn = signIn(environment, auth, warnings);
        if (signIn != null) {
            undefined(signIn, defined, environment != null || editing != null, environmentName, warnings);
        }

        List<VariableView> variables = environment == null ? List.of() : variables(environment, collection);
        List<ShapeRow> shapes = new ArrayList<>();
        List<OperationRow> operations = new ArrayList<>();
        if (collection != null) {
            for (ShapeEvidence evidence : VendorSamples.read(collection)) {
                shapes.add(new ShapeRow(evidence.shape().name(), evidence.value(), FROM_SAMPLE, evidence.requestName()));
            }
            operations.addAll(operations(collection));
        }
        return new Draft(files, name, nameTaken, variables, signIn, shapes, operations, warnings, list.epoch());
    }

    // ------------------------------------------------------------------------------------------
    // The line per file
    // ------------------------------------------------------------------------------------------

    private record CollectionRead(PostmanCollection collection, CollectionAuth auth) {
    }

    private static FileLine line(String slot, FileText file, FileGuard.Read<?> read, String readLine) {
        if (read.ok()) {
            return new FileLine(slot, file.fileName(), true, readLine, null, null);
        }
        return new FileLine(slot, file.fileName(), false, "Not read: " + read.line(), read.reference(), read.cause());
    }

    private static String environmentRead(PostmanEnvironment environment) {
        int count = environment.variables().size();
        long secrets = environment.variables().stream().filter(PostmanVariable::secret).count();
        return "Read: " + count + (count == 1 ? " variable, " : " variables, ")
                + (secrets == 0 ? "no secret." : secrets + (secrets == 1 ? " secret." : " secrets."));
    }

    private static String collectionRead(PostmanCollection collection) {
        int count = collection.requests().size();
        long mapped = collection.requests().stream().filter(request -> detect(request) != EpaOperation.UNMAPPED).count();
        return "Read: " + count + (count == 1 ? " request, " : " requests, ") + mapped + " mapped to an operation.";
    }

    // ------------------------------------------------------------------------------------------
    // Sign-in
    // ------------------------------------------------------------------------------------------

    /**
     * The collection's OAuth2 or bearer helper first (R-08: it wins over variable names), else the
     * environment's variables named like sign-in settings, else nothing.
     */
    private static SignInDraft signIn(PostmanEnvironment environment, CollectionAuth auth, List<String> warnings) {
        if (auth != null) {
            SignInDraft fromCollection = auth.signIn(warnings);
            if (fromCollection != null) {
                return fromCollection;
            }
        }
        return environment == null ? null : fromVariableNames(environment);
    }

    private static SignInDraft fromVariableNames(PostmanEnvironment environment) {
        String tokenUrl = null;
        String clientId = null;
        String clientSecret = null;
        String scope = null;
        String bearer = null;
        for (PostmanVariable variable : environment.inForce().values()) {
            String key = roleKey(variable.key());
            String reference = "{{" + variable.key().strip() + "}}";
            if (tokenUrl == null && TOKEN_URL_NAMES.contains(key)) {
                tokenUrl = reference;
            } else if (clientId == null && CLIENT_ID_NAMES.contains(key)) {
                clientId = reference;
            } else if (clientSecret == null && CLIENT_SECRET_NAMES.contains(key)) {
                clientSecret = reference;
            } else if (scope == null && SCOPE_NAMES.contains(key)) {
                scope = reference;
            } else if (bearer == null && BEARER_NAMES.contains(key)) {
                bearer = reference;
            }
        }
        if (tokenUrl != null && clientId != null) {
            return new SignInDraft(SignInSettings.Method.CLIENT_CREDENTIALS.name(), tokenUrl, clientId, scope, null,
                    clientSecret == null ? null : new SecretReference(true, clientSecret), null, FROM_VARIABLE_NAMES);
        }
        if (bearer != null) {
            return new SignInDraft(SignInSettings.Method.BEARER_TOKEN.name(), null, null, null, null, null,
                    new SecretReference(true, bearer), FROM_VARIABLE_NAMES);
        }
        // Some of the names but not a whole client credentials sign-in: the method is not stated,
        // and what was found is offered for the fields, never a method the file does not show.
        if (tokenUrl == null && clientId == null && clientSecret == null) {
            return null;
        }
        return new SignInDraft(null, tokenUrl, clientId, scope, null, clientSecret == null ? null : new SecretReference(true, clientSecret),
                null, FROM_VARIABLE_NAMES);
    }

    /** The name lower case with underscores, hyphens, dots and spaces taken out, one character at a time. */
    static String roleKey(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '_' && c != '-' && c != '.' && c != ' ') {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** One line for each placeholder of the filled sign-in that nothing defines. It stays in the field as written. */
    private static void undefined(SignInDraft signIn, Set<String> defined, boolean anEnvironment, String environmentName,
                                  List<String> warnings) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : new String[] {signIn.tokenUrl(), signIn.clientId(), signIn.scope(),
                signIn.clientSecret() == null ? null : signIn.clientSecret().reference(),
                signIn.bearerToken() == null ? null : signIn.bearerToken().reference()}) {
            names.addAll(Placeholders.names(text));
        }
        for (String name : names) {
            // A reference to this machine's own variables is read at run time, never from an export.
            if (name.startsWith(VariableResolver.OS_REFERENCE) || defined.contains(name)) {
                continue;
            }
            String shown = "{{" + Names.shown(name) + "}}";
            if (anEnvironment) {
                warnings.add("Sign-in refers to " + shown + ", which the environment \"" + Names.shown(environmentName)
                        + "\" does not define. Give it a value in Postman and export again, or type the value.");
            } else {
                warnings.add("Sign-in refers to " + shown + ". Choose the environment export that defines it, or type the value.");
            }
        }
    }

    /**
     * The sign-in helper the collection sets: on the collection itself, else the first folder or
     * request that sets one, in the order of the file. The parser keeps only the names a helper
     * refers to, not which field each fills, so the helper is read here from the text again.
     *
     * @param type   oauth2, bearer or another Postman helper type
     * @param fields the helper's fields by key, values as written
     */
    record CollectionAuth(String type, Map<String, String> fields) {

        /** Holds field values that can be credentials: names only. */
        @Override
        public String toString() {
            return "CollectionAuth[" + type + ", " + fields.keySet() + "]";
        }

        static CollectionAuth find(String text) {
            JsonSafe.Parsed parsed = JsonSafe.parse(text);
            if (!parsed.ok()) {
                return null;
            }
            JsonNode root = parsed.node();
            JsonNode wrapped = JsonSafe.child(root, "collection");
            if (JsonSafe.isObject(wrapped) && JsonSafe.missing(JsonSafe.child(root, "info"))) {
                root = wrapped;
            }
            CollectionAuth own = of(JsonSafe.child(root, "auth"));
            if (own != null) {
                return own;
            }
            // Depth first in the order of the file, with a stack of our own: a walk that called
            // itself would take one frame per level of the file.
            Deque<JsonNode> pending = new ArrayDeque<>();
            pushReversed(pending, JsonSafe.child(root, "item"));
            while (!pending.isEmpty()) {
                JsonNode item = pending.pop();
                CollectionAuth found = of(JsonSafe.child(item, "auth"));
                if (found == null) {
                    found = of(JsonSafe.at(item, "request", "auth"));
                }
                if (found != null) {
                    return found;
                }
                pushReversed(pending, JsonSafe.child(item, "item"));
            }
            return null;
        }

        private static void pushReversed(Deque<JsonNode> pending, JsonNode items) {
            List<JsonNode> elements = JsonSafe.elements(items);
            for (int i = elements.size() - 1; i >= 0; i--) {
                if (JsonSafe.isObject(elements.get(i))) {
                    pending.push(elements.get(i));
                }
            }
        }

        /** The helper of one owner, or null when it sets none, inherits, or sets none on purpose. */
        private static CollectionAuth of(JsonNode auth) {
            if (!JsonSafe.isObject(auth)) {
                return null;
            }
            String type = JsonSafe.presentText(auth, "type");
            if (type == null || "noauth".equalsIgnoreCase(type) || "inherit".equalsIgnoreCase(type)) {
                return null;
            }
            String key = type.strip().toLowerCase(Locale.ROOT);
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode body = JsonSafe.child(auth, type.strip());
            if (JsonSafe.isArray(body)) {
                // Collection v2.1: a list of {key, value}.
                for (JsonNode field : JsonSafe.elements(body)) {
                    String name = JsonSafe.presentText(field, "key");
                    String value = JsonSafe.text(field, "value");
                    if (name != null && !fields.containsKey(name)) {
                        fields.put(name, value);
                    }
                }
            } else if (JsonSafe.isObject(body)) {
                // Older exports write the helper as an object of key and value.
                for (Map.Entry<String, JsonNode> field : JsonSafe.properties(body)) {
                    fields.put(field.getKey(), JsonSafe.text(field.getValue()));
                }
            }
            return new CollectionAuth(key, fields);
        }

        /** What the form can take from this helper; null, with a line, when it is not one PAVE signs in with. */
        SignInDraft signIn(List<String> warnings) {
            if ("oauth2".equals(type)) {
                return oauth2(warnings);
            }
            if ("bearer".equals(type)) {
                SecretReference token = secret(fields.get("token"), "a bearer token", "Bearer token", warnings);
                if (token == null) {
                    return null;
                }
                return new SignInDraft(SignInSettings.Method.BEARER_TOKEN.name(), null, null, null, null, null, token,
                        FROM_COLLECTION_SIGN_IN);
            }
            warnings.add("The collection signs in with Postman's " + Names.shown(type) + " helper, which PAVE does not use, so sign-in "
                    + "was not filled from it.");
            return null;
        }

        private SignInDraft oauth2(List<String> warnings) {
            String grant = Texts.trimToNull(fields.get("grant_type"));
            if (grant == null) {
                warnings.add("The collection's OAuth2 sign-in states no grant type, so sign-in was not filled from it. PAVE signs in "
                        + "with client credentials.");
                return null;
            }
            if (!"client_credentials".equalsIgnoreCase(grant)) {
                warnings.add("The collection's OAuth2 sign-in uses the grant type " + Names.shown(grant) + ", so sign-in was not filled "
                        + "from it. PAVE signs in with client credentials.");
                return null;
            }
            String authentication = Texts.trimToNull(fields.get("client_authentication"));
            String clientAuthentication = null;
            if ("header".equalsIgnoreCase(authentication)) {
                clientAuthentication = SignInSettings.ClientAuthentication.BASIC.name();
            } else if ("body".equalsIgnoreCase(authentication)) {
                clientAuthentication = SignInSettings.ClientAuthentication.BODY.name();
            }
            SecretReference clientSecret = secret(fields.get("clientSecret"), "a client secret", "Client secret", warnings);
            return new SignInDraft(SignInSettings.Method.CLIENT_CREDENTIALS.name(), Texts.trimToNull(fields.get("accessTokenUrl")),
                    Texts.trimToNull(fields.get("clientId")), Texts.trimToNull(fields.get("scope")), clientAuthentication, clientSecret,
                    null, FROM_COLLECTION_SIGN_IN);
        }

        /**
         * A secret field: filled only when it is wholly one reference. A value typed into the file
         * is never filled and never shown, and one line says so (R-08).
         */
        private static SecretReference secret(String value, String what, String field, List<String> warnings) {
            if (!Texts.present(value)) {
                return null;
            }
            if (Placeholders.sole(value) != null) {
                return new SecretReference(true, value.strip());
            }
            warnings.add("The collection's sign-in holds " + what + " typed into the file. It was not filled: type it in " + field
                    + ", or a {{variable}} or {{env:PAVE_NAME}} reference.");
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Variables, shapes and operations
    // ------------------------------------------------------------------------------------------

    /**
     * The export's variables as the form shows them, masked as every variable of a stored
     * environment is: a secret shows only its mask, unless its value only refers to other names.
     * Used means referred to by a request or a variable of the files of this draft.
     */
    private static List<VariableView> variables(PostmanEnvironment environment, PostmanCollection collection) {
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        if (collection != null) {
            for (PostmanRequest request : collection.requests()) {
                String where = "the request \"" + Names.shown(request.name()) + "\" of the collection";
                note(usage, request.url(), where);
                request.sentHeaders().forEach(header -> note(usage, header.value(), where));
                note(usage, request.body(), where);
            }
            for (PostmanVariable variable : collection.inForce().values()) {
                note(usage, variable.value(), "the variable \"" + Names.shown(variable.key()) + "\" of the collection");
            }
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        for (PostmanVariable variable : inForce.values()) {
            note(usage, variable.value(), "the variable \"" + Names.shown(variable.key()) + "\" of this environment");
        }
        List<VariableView> views = new ArrayList<>();
        for (PostmanVariable variable : environment.variables()) {
            boolean variableInForce = inForce.get(variable.key()) == variable;
            String value = variable.value();
            boolean set = variable.hasValue();
            String pointer = Placeholders.sole(value);
            boolean fromOperatingSystem = pointer != null && pointer.startsWith(VariableResolver.OS_REFERENCE);
            boolean secret = variable.secret() || fromOperatingSystem;
            String display = !set ? null : !secret || Placeholders.referencesOnly(value) ? value : Secrets.mask(variable.key());
            String readsFrom = fromOperatingSystem
                    ? "the operating system variable " + pointer.substring(VariableResolver.OS_REFERENCE.length()).strip() : null;
            List<String> usedBy = new ArrayList<>(variableInForce ? usage.getOrDefault(variable.key(), Set.of()) : Set.of());
            if (usedBy.size() > USED_BY_SHOWN) {
                int more = usedBy.size() - USED_BY_SHOWN;
                usedBy = new ArrayList<>(usedBy.subList(0, USED_BY_SHOWN));
                usedBy.add("and " + more + " more");
            }
            views.add(new VariableView(variable.key(), variable.type(), variable.enabled(), variableInForce, secret, set, display, readsFrom,
                    !usedBy.isEmpty(), usedBy));
        }
        return views;
    }

    private static void note(Map<String, Set<String>> usage, String template, String where) {
        for (String name : Placeholders.names(template)) {
            usage.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(where);
        }
    }

    /**
     * One row per operation the collection maps, from the first request mapped to it in the
     * order of the file: the request a run uses when it names none.
     */
    private static List<OperationRow> operations(PostmanCollection collection) {
        // In the order the file first maps each operation.
        Map<EpaOperation, OperationRow> rows = new LinkedHashMap<>();
        for (PostmanRequest request : collection.requests()) {
            EpaOperation operation = detect(request);
            if (operation == EpaOperation.UNMAPPED || rows.containsKey(operation)) {
                continue;
            }
            rows.put(operation, new OperationRow(operation.name(), shortLabel(operation), operation.label(), request.name(), request.url(),
                    request.scripted()));
        }
        return new ArrayList<>(rows.values());
    }

    private static EpaOperation detect(PostmanRequest request) {
        return OperationDetector.detect(request.name(), request.method(), request.url(), request.folderPath()).operation();
    }

    /**
     * The operation in a word or two, as the environment form names it (plan 7.5). The same
     * words as the configuration server's short labels; kept here until that label is on the
     * operation itself.
     */
    static String shortLabel(EpaOperation operation) {
        return switch (operation) {
            case CRD_ORDER_SIGN -> "order-sign";
            case CRD_ORDER_SELECT -> "order-select";
            case CRD_DISCOVERY -> "discovery";
            case DTR_QUESTIONNAIRE_PACKAGE -> "DTR package";
            case DTR_NEXT_QUESTION -> "next question";
            case DTR_RESPONSE -> "questionnaire response";
            case PAS_SUBMIT -> "PAS submit";
            case PAS_INQUIRE -> "PAS inquire";
            case SUBSCRIPTION_CREATE, SUBSCRIPTION_GET, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE -> "Subscription";
            case SUBSCRIPTION_NOTIFICATION -> "notification";
            case MEMBER_LOOKUP -> "member lookup";
            case PROVIDER_LOOKUP -> "provider lookup";
            case UNMAPPED -> "unmapped request";
        };
    }
}
