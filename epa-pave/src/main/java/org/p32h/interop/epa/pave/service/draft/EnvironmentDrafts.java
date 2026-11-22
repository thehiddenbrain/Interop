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
import org.p32h.interop.epa.pave.service.config.DraftSupport;
import org.p32h.interop.epa.pave.service.config.SecretSetting;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.service.crd.ShapeEvidence;
import org.p32h.interop.epa.pave.service.crd.VendorSamples;
import org.p32h.interop.epa.pave.service.draft.DraftViews.Draft;
import org.p32h.interop.epa.pave.service.draft.DraftViews.FileLine;
import org.p32h.interop.epa.pave.service.draft.DraftViews.OperationRow;
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
 * defines stays visible as itself, with one line. Addresses and values are not filled into
 * boxes: what the files give each one is said with where it comes from (D-307), since a box
 * filled from a file would be stored as typed and would lie over the file from then on.
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
    private final DraftSupport support;

    @Autowired
    public EnvironmentDrafts(ConfigService config) {
        this(config::environments, PostmanParser::environment, PostmanParser::collection, config.draftSupport());
    }

    /**
     * With the stored environments and the two parsers given, so that a test can make a parser
     * fail the way a rebuilt classes folder or a deep file made the real one fail. Addresses and
     * values are said from the files alone.
     */
    public EnvironmentDrafts(Supplier<EnvironmentList> stored, Function<String, PostmanEnvironment> environmentParser,
                             Function<String, PostmanCollection> collectionParser) {
        this(stored, environmentParser, collectionParser, DraftSupport.filesOnly());
    }

    /** As above, with what the configuration says of application.yml and of the collections stored. */
    public EnvironmentDrafts(Supplier<EnvironmentList> stored, Function<String, PostmanEnvironment> environmentParser,
                             Function<String, PostmanCollection> collectionParser, DraftSupport support) {
        this.stored = stored;
        this.environmentParser = environmentParser;
        this.collectionParser = collectionParser;
        this.support = support;
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
        List<TokenRequests.Found> tokenRequests = List.of();
        if (collectionFile != null) {
            FileGuard.Read<CollectionRead> read = FileGuard.read(collectionFile.fileName(), () -> {
                PostmanCollection parsed = collectionParser.apply(collectionFile.text());
                return new CollectionRead(parsed, CollectionAuth.find(collectionFile.text()), TokenRequests.find(collectionFile.text()));
            });
            files.add(line(COLLECTION_SLOT, collectionFile, read, read.ok() ? collectionRead(read.value().collection()) : null));
            if (read.ok()) {
                collection = read.value().collection();
                auth = read.value().auth();
                tokenRequests = read.value().tokenRequests();
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
        // The environment's variable names, in the order of the export: on Edit with no export
        // chosen, the stored environment's, so a collection alone is matched against them too.
        List<String> variableNames = new ArrayList<>();
        String environmentName = null;
        if (environment != null) {
            defined.addAll(environment.inForce().keySet());
            environment.inForce().values().forEach(variable -> variableNames.add(variable.key()));
            environmentName = environment.name();
        } else if (editing != null) {
            editing.variables().stream().filter(VariableView::inForce).forEach(view -> {
                defined.add(view.name());
                variableNames.add(view.name());
            });
            environmentName = editing.name();
        }
        if (collection != null) {
            defined.addAll(collection.inForce().keySet());
        }

        SignInDraft signIn = signIn(variableNames, auth, tokenRequests, warnings);
        if (signIn != null) {
            undefined(signIn, defined, environment != null || editing != null, environmentName, warnings);
            empty(signIn, environment, collection, warnings);
        }

        List<VariableView> variables = environment == null ? List.of() : variables(environment, collection);
        List<ShapeRow> shapes = new ArrayList<>();
        List<OperationRow> operations = new ArrayList<>();
        List<DraftAddresses.Detected> detected = List.of();
        if (collection != null) {
            for (ShapeEvidence evidence : VendorSamples.read(collection)) {
                shapes.add(new ShapeRow(evidence.shape().name(), evidence.value(), FROM_SAMPLE, evidence.requestName()));
            }
            operations.addAll(operations(collection));
            detected = DraftAddresses.detect(collection);
        }
        // What the files give each address and value (D-307), for any file that was read: with
        // neither read there is nothing to say of them.
        String collectionId = DraftAddresses.collectionId(collection);
        List<DraftViews.AddressDraft> addresses = List.of();
        List<DraftViews.RequestChoice> requests = List.of();
        List<DraftViews.ValueDraft> values = List.of();
        if (environment != null || collection != null) {
            String editingId = editing == null ? null : editing.id();
            addresses = DraftAddresses.addresses(detected, collectionId, environment, collection, editingId, support);
            Set<String> tokenNames = new LinkedHashSet<>();
            tokenRequests.forEach(found -> tokenNames.add(found.name()));
            requests = DraftAddresses.requests(detected, environment, collection, editingId, support, tokenNames);
            values = DraftAddresses.values(environment, collection, editingId, support);
        }
        return new Draft(files, name, nameTaken, variables, signIn, shapes, operations, warnings, list.epoch(), collectionId, addresses,
                requests, values);
    }

    // ------------------------------------------------------------------------------------------
    // The line per file
    // ------------------------------------------------------------------------------------------

    private record CollectionRead(PostmanCollection collection, CollectionAuth auth, List<TokenRequests.Found> tokenRequests) {
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
     * Where the sign-in is read from (D-305), in this order, the first that gives a whole client
     * credentials sign-in winning: the collection's OAuth2 helper, the collection's token request,
     * the environment's variables by name. A bearer helper, which gives no client credentials, is
     * used only when none of them does, and so is a part of a sign-in the variable names show.
     *
     * @param variableNames the environment's variable names in force, in the order of the export
     */
    private static SignInDraft signIn(List<String> variableNames, CollectionAuth auth, List<TokenRequests.Found> tokenRequests,
                                      List<String> warnings) {
        SignInDraft helper = auth == null ? null : auth.signIn(warnings);
        if (clientCredentials(helper)) {
            return helper;
        }
        SignInDraft fromRequest = fromTokenRequests(tokenRequests, warnings);
        if (fromRequest != null) {
            helperNotUsed(helper, warnings);
            return fromRequest;
        }
        // Its lines are said only when the names are what fills the form.
        List<String> nameLines = new ArrayList<>();
        SignInDraft fromNames = variableNames.isEmpty() ? null : fromVariableNames(variableNames, nameLines);
        if (clientCredentials(fromNames) || helper == null) {
            if (clientCredentials(fromNames)) {
                helperNotUsed(helper, warnings);
            }
            warnings.addAll(nameLines);
            return fromNames;
        }
        return helper;
    }

    private static boolean clientCredentials(SignInDraft draft) {
        return draft != null && SignInSettings.Method.CLIENT_CREDENTIALS.name().equals(draft.method());
    }

    /** A bearer helper set aside for client credentials is said, since the collection's own requests would carry it. */
    private static void helperNotUsed(SignInDraft helper, List<String> warnings) {
        if (helper != null && SignInSettings.Method.BEARER_TOKEN.name().equals(helper.method())) {
            warnings.add("The collection's bearer helper was not used: PAVE asks for a token itself, with the client credentials filled "
                    + "below.");
        }
    }

    // ------------------------------------------------------------------------------------------
    // The collection's token request
    // ------------------------------------------------------------------------------------------

    /** The form fields of a token request that PAVE's sign-in sends itself. */
    private static final Set<String> SIGN_IN_FIELDS = Set.of("grant_type", "client_id", "client_secret", "scope");

    /** The headers of a token request that PAVE's sign-in sends itself, lower case. */
    private static final Set<String> SIGN_IN_HEADERS = Set.of("content-type", "accept", "authorization");

    /**
     * The sign-in from the first token request in the file that asks with client credentials, or
     * null when there is none. What the request sends that PAVE's sign-in does not is said by name,
     * never by value: it is the first thing to look at when the sign-in is refused.
     */
    static SignInDraft fromTokenRequests(List<TokenRequests.Found> tokenRequests, List<String> warnings) {
        if (tokenRequests.isEmpty()) {
            return null;
        }
        TokenRequests.Found chosen = tokenRequests.stream().filter(found -> "client_credentials".equalsIgnoreCase(found.grant()))
                .findFirst().orElse(null);
        int others = tokenRequests.size() - 1;
        if (chosen == null) {
            TokenRequests.Found first = tokenRequests.get(0);
            warnings.add("The collection's token request \"" + first.name() + "\" " + (first.grant() == null ? "states no grant type"
                    : "uses the grant type " + Names.shown(first.grant())) + ", so sign-in was not filled from it. PAVE signs in with "
                    + "client credentials.");
            if (others > 0) {
                warnings.add("None of the collection's " + others + " other token request" + (others == 1 ? "" : "s")
                        + " asks with client credentials either.");
            }
            return null;
        }
        if (others > 0) {
            warnings.add("The collection holds " + others + " other token request" + (others == 1 ? "" : "s") + ". Sign-in was filled from \""
                    + chosen.name() + "\", the first in the file" + (tokenRequests.get(0) == chosen ? "." : " that asks with client credentials."));
        }
        Map<String, String> fields = chosen.fields();
        CollectionAuth basic = chosen.basic();
        String clientId = Texts.trimToNull(fields.get("client_id"));
        String secret = null;
        String clientAuthentication = null;
        if (fields.containsKey("client_secret")) {
            clientAuthentication = SignInSettings.ClientAuthentication.BODY.name();
            secret = fields.get("client_secret");
            if (clientId == null && basic != null) {
                clientId = Texts.trimToNull(basic.fields().get("username"));
            }
        } else if (basic != null) {
            clientAuthentication = SignInSettings.ClientAuthentication.BASIC.name();
            secret = basic.fields().get("password");
            clientId = Texts.orElse(Texts.trimToNull(basic.fields().get("username")), clientId);
        }
        SecretSetting clientSecret = CollectionAuth.secret(secret, "a client secret", "Client secret", warnings);

        List<String> sent = new ArrayList<>();
        fields.keySet().stream().filter(name -> !SIGN_IN_FIELDS.contains(name)).forEach(name -> sent.add(Names.shown(name)));
        Set<String> headers = new LinkedHashSet<>();
        for (String header : chosen.headers()) {
            if (!SIGN_IN_HEADERS.contains(header.strip().toLowerCase(Locale.ROOT)) && headers.stream().noneMatch(header::equalsIgnoreCase)) {
                headers.add(header);
            }
        }
        headers.forEach(header -> sent.add("the header " + Names.shown(header)));
        boolean posted = "POST".equals(chosen.method());
        if (!sent.isEmpty() || !posted) {
            String what = sent.isEmpty() ? "" : "also sends " + words(sent);
            String how = posted ? "" : "is sent with the method " + Names.shown(chosen.method());
            String not = sent.isEmpty() ? "PAVE's sign-in sends POST" : posted ? "PAVE's sign-in does not send " + (sent.size() == 1 ? "it" : "them")
                    : "PAVE's sign-in does not send " + (sent.size() == 1 ? "it" : "them") + ", and sends POST";
            warnings.add("The token request " + what + (what.isEmpty() || how.isEmpty() ? "" : ", and ") + how + ". " + not
                    + ": if the sign-in is refused, that is the first thing to look at.");
        }
        return new SignInDraft(SignInSettings.Method.CLIENT_CREDENTIALS.name(), Texts.trimToNull(chosen.url()), clientId,
                Texts.trimToNull(fields.get("scope")), clientAuthentication, clientSecret, null,
                "the collection's token request \"" + chosen.name() + "\"");
    }

    /** Names joined as a sentence lists them: a, b and c. */
    private static String words(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    // ------------------------------------------------------------------------------------------
    // The environment's variables by name
    // ------------------------------------------------------------------------------------------

    /**
     * One sign-in field a variable name can fill: an exact name as before wins (D-305), else every
     * name that holds the field's words is a candidate.
     *
     * @param field the field as the form posts it
     * @param words the field in a sentence
     */
    private record Role(String field, String words, Set<String> exact, List<String> within, boolean endsWith) {

        boolean candidate(String key) {
            for (String word : within) {
                if (endsWith ? key.endsWith(word) : key.contains(word)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Role> ROLES = List.of(
            new Role("tokenUrl", "the token url", TOKEN_URL_NAMES,
                    List.of("tokenurl", "tokenuri", "tokenendpoint", "accesstokenurl", "authurl", "oauthurl"), false),
            new Role("clientId", "the client id", CLIENT_ID_NAMES, List.of("clientid"), false),
            new Role("clientSecret", "the client secret", CLIENT_SECRET_NAMES, List.of("clientsecret"), false),
            new Role("scope", "the scope", SCOPE_NAMES, List.of("scope", "scopes"), true));

    /**
     * The sign-in the variable names show. One candidate fills its field; several fill nothing:
     * the tester chooses, and the form offers each as a reference to fill with one press.
     */
    static SignInDraft fromVariableNames(List<String> names, List<String> lines) {
        Map<String, String> filled = new LinkedHashMap<>();
        List<DraftViews.Candidates> candidates = new ArrayList<>();
        for (Role role : ROLES) {
            String exact = names.stream().filter(name -> role.exact().contains(roleKey(name))).findFirst().orElse(null);
            if (exact != null) {
                filled.put(role.field(), reference(exact));
                continue;
            }
            List<String> found = names.stream().filter(name -> role.candidate(roleKey(name))).map(EnvironmentDrafts::reference).toList();
            if (found.size() == 1) {
                filled.put(role.field(), found.get(0));
            } else if (found.size() > 1) {
                candidates.add(new DraftViews.Candidates(role.field(), found));
                lines.add("Several variables could be " + role.words() + ": " + String.join(", ", found.stream()
                        .map(each -> "{{" + Names.shown(each.substring(2, each.length() - 2)) + "}}").toList()) + ". Choose one.");
            }
        }
        String bearer = names.stream().filter(name -> BEARER_NAMES.contains(roleKey(name))).findFirst().map(EnvironmentDrafts::reference)
                .orElse(null);
        boolean tokenUrl = filled.containsKey("tokenUrl") || candidates.stream().anyMatch(each -> each.field().equals("tokenUrl"));
        boolean clientId = filled.containsKey("clientId") || candidates.stream().anyMatch(each -> each.field().equals("clientId"));
        String method;
        if (tokenUrl && clientId) {
            method = SignInSettings.Method.CLIENT_CREDENTIALS.name();
        } else if (bearer != null) {
            lines.clear();
            return new SignInDraft(SignInSettings.Method.BEARER_TOKEN.name(), null, null, null, null, null,
                    SecretSetting.of(bearer), FROM_VARIABLE_NAMES);
        } else if (!filled.containsKey("tokenUrl") && !filled.containsKey("clientId") && !filled.containsKey("clientSecret")
                && candidates.isEmpty()) {
            // Nothing, or a scope alone, is no sign-in.
            return null;
        } else {
            // Some of the names but not a whole client credentials sign-in: the method is not
            // stated, and what was found is offered for the fields, never a method the file does not show.
            method = null;
        }
        SecretSetting secret = filled.containsKey("clientSecret") ? SecretSetting.of(filled.get("clientSecret")) : null;
        return new SignInDraft(method, filled.get("tokenUrl"), filled.get("clientId"), filled.get("scope"), null, secret, null,
                FROM_VARIABLE_NAMES, SignInDraft.filled(filled.get("tokenUrl"), filled.get("clientId"), secret, filled.get("scope"), null,
                null), candidates);
    }

    private static String reference(String name) {
        return "{{" + name.strip() + "}}";
    }

    /**
     * One line for each filled reference whose variable the export gives with no value. Postman
     * exports a variable's initial value only, so a secret kept as a current value arrives empty.
     */
    private static void empty(SignInDraft signIn, PostmanEnvironment environment, PostmanCollection collection, List<String> warnings) {
        if (environment == null && collection == null) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String text : new String[] {signIn.tokenUrl(), signIn.clientId(), signIn.scope(),
                signIn.clientSecret() == null ? null : signIn.clientSecret().reference(),
                signIn.bearerToken() == null ? null : signIn.bearerToken().reference()}) {
            names.addAll(Placeholders.names(text));
        }
        for (String name : names) {
            PostmanVariable variable = environment == null ? null : environment.inForce().get(name);
            if (variable == null && collection != null) {
                variable = collection.inForce().get(name);
            }
            if (variable != null && !variable.hasValue()) {
                warnings.add("{{" + Names.shown(name) + "}} is empty in this export. Postman exports a variable's initial value only: type "
                        + "the value here, or set the initial value in Postman and export again.");
            }
        }
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
        static CollectionAuth of(JsonNode auth) {
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
                SecretSetting token = secret(fields.get("token"), "a bearer token", "Bearer token", warnings);
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
            SecretSetting clientSecret = secret(fields.get("clientSecret"), "a client secret", "Client secret", warnings);
            return new SignInDraft(SignInSettings.Method.CLIENT_CREDENTIALS.name(), Texts.trimToNull(fields.get("accessTokenUrl")),
                    Texts.trimToNull(fields.get("clientId")), Texts.trimToNull(fields.get("scope")), clientAuthentication, clientSecret,
                    null, FROM_COLLECTION_SIGN_IN);
        }

        /**
         * A secret field: filled only when it is wholly one reference. A value typed into the file
         * is never filled and never shown, and one line says so (R-08).
         */
        static SecretSetting secret(String value, String what, String field, List<String> warnings) {
            if (!Texts.present(value)) {
                return null;
            }
            if (Placeholders.sole(value) != null) {
                return SecretSetting.of(value);
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
            rows.put(operation, new OperationRow(operation.name(), operation.shortLabel(), operation.label(), request.name(), request.url(),
                    request.scripted()));
        }
        return new ArrayList<>(rows.values());
    }

    private static EpaOperation detect(PostmanRequest request) {
        return OperationDetector.detect(request.name(), request.method(), request.url(), request.folderPath()).operation();
    }
}
