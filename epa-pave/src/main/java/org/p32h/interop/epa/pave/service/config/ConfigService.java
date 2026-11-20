package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.postman.PostmanAuth;
import org.p32h.interop.epa.pave.postman.PostmanExample;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.CallPlan.Source;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Activated;
import org.p32h.interop.epa.pave.service.config.ConfigViews.AuthView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.AvailableRequest;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Effective;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.ExampleView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.HeaderView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.MappingChanged;
import org.p32h.interop.epa.pave.service.config.ConfigViews.OperationRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.PathVariableView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Removed;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestDetail;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestSummary;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Resolved;
import org.p32h.interop.epa.pave.service.config.ConfigViews.ResolvedHeaderView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.ScriptView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.SettingRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.TargetView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.TokenRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.stereotype.Component;

/**
 * What the Config tab does, one method for each endpoint: change the stored configuration, and
 * describe it.
 *
 * <p>Everything that leaves here is a {@link ConfigViews} type built from masked rows. This class
 * is where the value of a secret stops: a stored environment goes in, and what comes out says
 * that a secret is set and never what it is. Nothing here logs.
 */
@Component
public class ConfigService {

    /** How many places a variable is used in are listed before the rest are counted. */
    private static final int USED_BY_SHOWN = 5;

    private static final String NOT_APPLIED = "This tool does not apply Postman's authentication helpers. A request is sent with the "
            + "headers written on it, and authentication is set once under onyx.auth. If the far side refuses the credentials, this "
            + "is the first place to look.";

    private final ConfigStore store;
    private final ConfigStoreGateway gateway;
    private final ConfigStoreProperties storeProperties;
    private final OnyxProperties onyx;
    private final LookupProperties lookup;
    private final EpaProperties epa;

    public ConfigService(ConfigStore store, ConfigStoreGateway gateway, ConfigStoreProperties storeProperties, OnyxProperties onyx,
                         LookupProperties lookup, EpaProperties epa) {
        this.store = store;
        this.gateway = gateway;
        this.storeProperties = storeProperties;
        this.onyx = onyx;
        this.lookup = lookup;
        this.epa = epa;
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    public EnvironmentUploaded uploadEnvironment(String fileName, byte[] content, EnvironmentKind kind, boolean acknowledgeNotProd) {
        ConfigStore.EnvironmentUpload upload = store.uploadEnvironment(fileName, content, kind, acknowledgeNotProd);
        return new EnvironmentUploaded(environmentView(upload.environment(), upload.after()), upload.replaced(), upload.warnings(),
                upload.epoch());
    }

    public EnvironmentList environments() {
        ConfigSnapshot snapshot = store.snapshot();
        List<EnvironmentView> views = new ArrayList<>();
        snapshot.environments().forEach(environment -> views.add(environmentView(environment, snapshot)));
        return new EnvironmentList(views, snapshot.activeName(), snapshot.epoch(), store.problems(), productionDestinations(snapshot));
    }

    /** @param unlock the session's unlock. A switch drops it, and the answer says whether a write would be sent now */
    public Activated activate(String nameOrId, ProdUnlock unlock) {
        ConfigSnapshot snapshot = store.activate(nameOrId);
        return new Activated(snapshot.activeRef(), snapshot.epoch(), gateway.writeAccess(unlock));
    }

    public Removed removeEnvironment(String nameOrId) {
        ConfigStore.Removal removal = store.removeEnvironment(nameOrId);
        return new Removed(removal.name(), removal.wasActive(), removal.epoch(), removal.warnings());
    }

    private EnvironmentView environmentView(StoredEnvironment environment, ConfigSnapshot snapshot) {
        // An environment's variables reach every collection request and every setting of application.yml.
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        snapshot.collections().forEach(collection -> noteCollection(usage, collection));
        noteApplicationYml(usage);
        Map<String, PostmanVariable> inForce = environment.content().inForce();
        // A variable of this environment can also be used by another variable of this environment.
        for (PostmanVariable variable : inForce.values()) {
            note(usage, variable.value(), "the variable \"" + variable.key() + "\" of this environment");
        }
        List<VariableView> variables = new ArrayList<>();
        environment.content().variables().forEach(variable -> variables.add(variableView(variable, usage, inForce.get(variable.key()) == variable)));
        return new EnvironmentView(environment.id(), environment.name(), environment.kind(), environment.name().equals(snapshot.activeName()),
                environment.uploadedAt(), environment.originalFileName(), store.directory().resolve(environment.file()).toString(),
                environment.acknowledgedNotProd(), environment.productionSigns(), variables);
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    public CollectionUploaded uploadCollection(String fileName, byte[] content) {
        ConfigStore.CollectionUpload upload = store.uploadCollection(fileName, content);
        return new CollectionUploaded(collectionView(upload.collection(), upload.after()), upload.replaced(), upload.warnings(), upload.epoch());
    }

    public CollectionList collections() {
        ConfigSnapshot snapshot = store.snapshot();
        List<CollectionView> views = new ArrayList<>();
        snapshot.collections().forEach(collection -> views.add(collectionView(collection, snapshot)));
        return new CollectionList(views, snapshot.epoch());
    }

    /**
     * One request in full: its headers, its raw body as the collection holds it, and its saved
     * examples with their bodies. A credential typed into the body is given as a placeholder of its
     * own, which a send of this body resolves to the value as written; one typed into the url or a
     * saved example is masked.
     */
    public RequestDetail request(String collectionId, String requestId) {
        ConfigSnapshot snapshot = store.snapshot();
        StoredCollection collection = requireCollection(snapshot, collectionId);
        StoredCollection.Mapped mapped = collection.request(requestId);
        if (mapped == null) {
            throw NotFoundException.noLongerHeld("That request of the collection \"" + ConfigStore.oneLine(collection.name()) + "\"", "collections");
        }
        PostmanRequest request = mapped.request();
        List<HeaderView> headers = new ArrayList<>();
        request.headers().forEach(header -> headers.add(new HeaderView(header.name(), HeaderMasking.shownTemplate(header.name(), header.value()), header.disabled())));
        List<PathVariableView> pathVariables = new ArrayList<>();
        request.pathVariables().forEach(variable -> pathVariables.add(new PathVariableView(variable.name(), variable.value())));
        return new RequestDetail(collection.id(), collection.name(), summary(snapshot, collection, mapped), headers, request.bodyMode(),
                request.bodyLanguage(),
                LiteralCredentials.inBody(request.body()).template(), pathVariables, scripts(request), auth(request.auth()), examples(request),
                request.description());
    }

    public MappingChanged correctMapping(String collectionId, String requestId, EpaOperation operation) {
        ConfigStore.MappingChange change = store.correctMapping(collectionId, requestId, operation);
        StoredCollection.Mapped mapped = change.request();
        RequestSummary summary = summary(change.after(), change.collection(), mapped);
        List<String> notes = new ArrayList<>();
        if (!change.changed()) {
            notes.add("The request already was " + mappedWords(mapped.effective()) + ". Nothing changed.");
        } else if (mapped.correction() == null) {
            notes.add("That is what detection proposed, so the correction was removed and the mapping follows detection again.");
        } else {
            notes.add("The request is now " + mappedWords(mapped.effective())
                    + (mapped.effective() == EpaOperation.UNMAPPED ? ", so it is sent only as written, from the Config tab" : "")
                    + ". Detection proposed " + (mapped.detection().operation() == EpaOperation.UNMAPPED ? "no operation"
                    : mapped.detection().operation().label()) + ".");
        }
        if (summary.guardedAsWrite() && !mapped.effective().writesWith(mapped.request().method())) {
            notes.add("This request still counts as a write, because of its name, its url or its method. Mapping it to "
                    + mapped.effective().label() + " does not change that: on a production environment it stays blocked until "
                    + "writes are unlocked.");
        }
        return new MappingChanged(change.collection().id(), summary, change.changed(), notes, change.epoch());
    }

    /** What a request is mapped to, in words: "Unmapped request" is the label of no operation, so it is never said as one. */
    private static String mappedWords(EpaOperation operation) {
        return operation == EpaOperation.UNMAPPED ? "not mapped to any operation" : "mapped to " + operation.label();
    }

    public Removed removeCollection(String collectionId) {
        ConfigStore.Removal removal = store.removeCollection(collectionId);
        return new Removed(removal.name(), false, removal.epoch(), removal.warnings());
    }

    private CollectionView collectionView(StoredCollection collection, ConfigSnapshot snapshot) {
        // A collection's variables reach the requests of that collection and nothing else.
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        noteCollection(usage, collection);
        Map<String, PostmanVariable> inForce = collection.content().inForce();
        List<VariableView> variables = new ArrayList<>();
        collection.content().variables().forEach(variable -> variables.add(variableView(variable, usage, inForce.get(variable.key()) == variable)));
        List<RequestSummary> requests = new ArrayList<>();
        collection.requests().forEach(mapped -> requests.add(summary(snapshot, collection, mapped)));
        return new CollectionView(collection.id(), collection.name(), collection.content().schema(), collection.uploadedAt(),
                collection.originalFileName(), store.directory().resolve(collection.file()).toString(), variables, requests,
                collection.content().notes());
    }

    private RequestSummary summary(ConfigSnapshot snapshot, StoredCollection collection, StoredCollection.Mapped mapped) {
        PostmanRequest request = mapped.request();
        // The gateway's own judgement, under the environment that is active now. A value typed for
        // one run can still change it, and a call is judged again when it is prepared.
        boolean guarded = gateway.countsAsWrite(snapshot, collection, mapped);
        PostmanAuth auth = request.auth();
        return RequestSummary.builder()
                .id(request.id())
                .name(request.name())
                .folderPath(request.folderPath())
                .method(request.method())
                .urlTemplate(LiteralCredentials.inUrl(request.url()).shown())
                .detectedOperation(mapped.detection().operation())
                .detectionBasis(mapped.detection().basis())
                .detectionConflict(mapped.detection().conflict())
                .correctedOperation(mapped.correction())
                .effectiveOperation(mapped.effective())
                .guardedAsWrite(guarded)
                .scripted(request.scripted())
                .testScripted(request.testScripted())
                .hasBody(Texts.present(request.body()))
                .exampleCount(request.examples().size())
                .authHelper(auth == null ? null : auth.type() + ", set on " + auth.from())
                .notes(request.notes())
                .build();
    }

    private static List<ScriptView> scripts(PostmanRequest request) {
        List<ScriptView> scripts = new ArrayList<>();
        request.scripts().forEach(script -> scripts.add(new ScriptView(script.listen(), script.from(), script.source())));
        return scripts;
    }

    private static AuthView auth(PostmanAuth auth) {
        return auth == null ? null : new AuthView(auth.type(), auth.from(), auth.variables(), NOT_APPLIED);
    }

    private static List<ExampleView> examples(PostmanRequest request) {
        List<ExampleView> examples = new ArrayList<>();
        for (PostmanExample example : request.examples()) {
            List<HeaderView> headers = new ArrayList<>();
            for (PostmanHeader header : example.headers()) {
                // A saved response can carry a cookie or a token of its own.
                headers.add(new HeaderView(header.name(), Secrets.credentialHeader(header.name())
                        ? Secrets.redactCredential(header.name(), header.value()) : header.value(), false));
            }
            examples.add(new ExampleView(example.name(), example.status(), example.code(), headers,
                    LiteralCredentials.inBody(example.body()).shown()));
        }
        return examples;
    }

    private static StoredCollection requireCollection(ConfigSnapshot snapshot, String collectionId) {
        StoredCollection collection = snapshot.collection(collectionId);
        if (collection == null) {
            throw NotFoundException.noLongerHeld("The collection", "collections");
        }
        return collection;
    }

    // ------------------------------------------------------------------------------------------
    // The production unlock
    // ------------------------------------------------------------------------------------------

    /** Mints an unlock for the active production environment. The caller keeps it in the browser session and nowhere else. */
    public ProdUnlock unlock(String typedEnvironmentName) {
        return gateway.unlock(typedEnvironmentName);
    }

    public void lock() {
        gateway.relock();
    }

    public WriteAccess writeAccess(ProdUnlock unlock) {
        return gateway.writeAccess(unlock);
    }

    // ------------------------------------------------------------------------------------------
    // Everything in force
    // ------------------------------------------------------------------------------------------

    /** @param unlock the session's unlock, or null. It decides what this session is told about writes */
    public Effective effective(ProdUnlock unlock) {
        ConfigSnapshot snapshot = store.snapshot();
        List<OperationRow> rows = new ArrayList<>();
        for (EpaOperation operation : EpaOperation.values()) {
            rows.add(row(operation, snapshot, unlock));
        }
        return new Effective(epa.mode(), snapshot.activeRef(), snapshot.epoch(), store.problems(), store.allowProdWrites(),
                gateway.writeAccess(unlock), store.directory().toString(), rows, settings(), productionDestinations(snapshot));
    }

    /** Every address the store knows as production, so that nothing about the guardrail is remembered out of sight. */
    private static List<ConfigViews.ProductionDestinationView> productionDestinations(ConfigSnapshot snapshot) {
        List<ConfigViews.ProductionDestinationView> views = new ArrayList<>();
        for (ConfigSnapshot.KnownDestination known : snapshot.remembered()) {
            StoredEnvironment stored = snapshot.byName(known.environment());
            // A destination whose host or port is built from a secret is listed without it.
            views.add(new ConfigViews.ProductionDestinationView(known.environment(), known.secret()
                    ? "an address whose host is built from a secret of the environment, not shown" : known.destination().shown(),
                    stored != null && stored.kind() == org.p32h.interop.epa.pave.model.EnvironmentKind.PROD));
        }
        return views;
    }

    private OperationRow row(EpaOperation operation, ConfigSnapshot snapshot, ProdUnlock unlock) {
        OperationRow.Builder row = OperationRow.of(operation);
        if (operation == EpaOperation.MEMBER_LOOKUP || operation == EpaOperation.PROVIDER_LOOKUP) {
            row.lookupEnabled((operation == EpaOperation.MEMBER_LOOKUP ? lookup.member() : lookup.provider()).enabled());
        }
        if (operation == EpaOperation.SUBSCRIPTION_NOTIFICATION) {
            return row.requests(mappedTo(operation, snapshot, null)).unavailable("A notification is sent to this tool by the far side. "
                    + "The tool never sends one, so it has no target. A sample notification in a collection is listed here and can "
                    + "only be sent raw.").build();
        }
        if (!operation.outbound()) {
            return row.requests(mappedTo(operation, snapshot, null)).unavailable("This step is completed inside the tool. Nothing is "
                    + "sent for it, so it has no target.").build();
        }
        if (operation == EpaOperation.UNMAPPED) {
            return row.requests(mappedTo(operation, snapshot, null)).note("A request this tool could not map has no target of its "
                    + "own. Each one is listed here and is sent raw, by naming it.").build();
        }
        CallPlan plan = gateway.describe(snapshot, CallIntent.of(operation).withUnlock(unlock));
        row.method(plan.method())
                .requests(mappedTo(operation, snapshot, plan.target().source() == Source.REQUEST ? plan.target().request() : null));
        if (plan.refusal() != null) {
            row.blocked(plan.refusal().reason() + " " + plan.refusal().action()).blockedShort(plan.refusal().brief());
        }
        boolean mock = epa.mock();
        if (!plan.url().configured()) {
            return (mock ? row.note(plan.target().absent() + " On the mock profile nothing is sent, so this still runs.")
                    : row.unavailable(plan.target().absent())).build();
        }
        TargetView target = target(plan);
        row.target(target);
        // The url's tokens are filled per call and are not counted here, so every name left is a
        // placeholder an environment or a value for one run can supply.
        List<String> missing = new ArrayList<>(target.unresolved());
        String stillNeeds = stillNeeds(plan, target.resolved() ? null : braced(target.unresolved()), missing);
        if (stillNeeds != null) {
            if (mock) {
                row.note(stillNeeds + (headersMissing(plan) ? onMockAHeaderIsRefused(missing)
                        : " On the mock profile nothing is sent, so this still runs."));
            } else {
                row.unavailable(stillNeeds + whereToGive(missing));
            }
        }
        return row.build();
    }

    /**
     * Why a run of this plan is refused as things stand for a placeholder with no value, or null
     * when nothing is missing: the url, given as {@code urlNames}, and every header the call would
     * carry. A header with no value is refused on both profiles before anything is sent or
     * answered (see {@code Exchanges.requireSendable}), so a screen that decides from this whether
     * to offer a form must hear of it as it hears of the url.
     *
     * @param urlNames the url's missing names as the sentence gives them, or null when the url is complete
     * @param names    collects every placeholder named, for the sentence that says where to give them
     */
    private static String stillNeeds(CallPlan plan, String urlNames, List<String> names) {
        List<String> parts = new ArrayList<>();
        if (urlNames != null) {
            parts.add("the url still needs a value for " + urlNames);
        }
        for (CallPlan.Header header : plan.headers()) {
            List<String> unresolved = header.header().unresolved();
            if (Texts.present(header.header().name()) && !unresolved.isEmpty()) {
                parts.add("the header " + ConfigStore.oneLine(header.header().name()) + " still needs a value for " + braced(unresolved));
                unresolved.stream().filter(name -> !names.contains(name)).forEach(names::add);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        ConfigSnapshot snapshot = plan.snapshot();
        String sentence = String.join(", ", parts) + ", and "
                + (snapshot.active() == null ? "no environment is active" : "the active environment is \"" + snapshot.activeName() + "\"") + ".";
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    private static boolean headersMissing(CallPlan plan) {
        return plan.headers().stream().anyMatch(header -> Texts.present(header.header().name()) && !header.header().unresolved().isEmpty());
    }

    /** Where a placeholder gets a value, naming each, so that it cannot be read as a part of the url filled per call. */
    private static String whereToGive(List<String> names) {
        boolean one = names.size() == 1;
        return " Give " + (one ? "" : "each of ") + braced(names) + " a value in an environment on the Config tab, or type "
                + (one ? "it" : "them") + " among the values for this run.";
    }

    /**
     * The mock profile answers a url that is not complete from a scenario, but it refuses a header
     * with no value as live does, so a row that said "this still runs" would be untrue.
     */
    private static String onMockAHeaderIsRefused(List<String> names) {
        return " On the mock profile nothing is sent, but a header with no value is refused before a scenario answers, so a run is "
                + "refused here too." + whereToGive(names);
    }

    private static String braced(List<String> names) {
        return String.join(", ", names.stream().map(name -> "{{" + name + "}}").toList());
    }

    private static TargetView target(CallPlan plan) {
        // A token such as {id} is filled when a call is made, so here it is listed, not counted as missing.
        List<String> tokens = List.copyOf(plan.tokens());
        List<String> unresolved = plan.missingPlaceholders();
        boolean fromRequest = plan.target().source() == Source.REQUEST;
        return new TargetView(plan.url().template(), plan.filled().shown(), unresolved.isEmpty(), unresolved, tokens, plan.url().source(),
                plan.target().requestName(), fromRequest ? new RequestRef(plan.target().collection().id(),
                plan.target().request().request().id()) : null, plan.target().scripted());
    }

    /** Every request mapped to the operation, in collection order, with the one a call uses by default marked. */
    private List<AvailableRequest> mappedTo(EpaOperation operation, ConfigSnapshot snapshot, StoredCollection.Mapped usedByDefault) {
        List<AvailableRequest> requests = new ArrayList<>();
        for (StoredCollection collection : snapshot.collections()) {
            for (StoredCollection.Mapped mapped : collection.requests()) {
                if (mapped.effective() == operation) {
                    requests.add(new AvailableRequest(collection.id(), collection.name(), summary(snapshot, collection, mapped),
                            mapped == usedByDefault));
                }
            }
        }
        return requests;
    }

    /** The application.yml layer, row by row. Built here so that a properties record, which holds the secrets, is never returned. */
    private List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(plain("config-store.directory", store.directory().toString()));
        rows.add(plain("config-store.active-environment", storeProperties.activeEnvironment()));
        rows.add(plain("config-store.allow-prod-writes", String.valueOf(storeProperties.allowProdWrites())));
        rows.add(plain("onyx.crd.url", onyx.crd().url()));
        rows.add(plain("onyx.dtr.url", onyx.dtr().url()));
        rows.add(plain("onyx.pas.url", onyx.pas().url()));
        rows.add(plain("onyx.pas.attachment-mode", onyx.pas().attachmentMode()));
        rows.add(plain("onyx.subscription.url", onyx.subscription().url()));
        rows.add(plain("onyx.subscription.callback-url", onyx.subscription().callbackUrl()));
        rows.add(plain("onyx.connect-timeout-ms", String.valueOf(onyx.connectTimeoutMs())));
        rows.add(plain("onyx.read-timeout-ms", String.valueOf(onyx.readTimeoutMs())));
        rows.add(plain("onyx.auth.enabled", String.valueOf(onyx.auth().enabled())));
        rows.add(plain("onyx.auth.token-url", onyx.auth().tokenUrl()));
        rows.add(plain("onyx.auth.client-id", onyx.auth().clientId()));
        rows.add(secret("onyx.auth.client-secret", onyx.auth().clientSecret()));
        rows.add(plain("onyx.auth.scopes", onyx.auth().scopes()));
        new TreeMap<>(onyx.variables()).forEach((name, value) -> rows.add(Secrets.looksSecret(name) || pointsAtOperatingSystem(value)
                ? secret("onyx.variables." + name, value) : plain("onyx.variables." + name, value)));
        endpoint(rows, "lookup.member", lookup.member());
        endpoint(rows, "lookup.provider", lookup.provider());
        return rows;
    }

    private static void endpoint(List<SettingRow> rows, String prefix, LookupProperties.Endpoint endpoint) {
        rows.add(plain(prefix + ".url", endpoint.url()));
        rows.add(plain(prefix + ".enabled", String.valueOf(endpoint.enabled())));
        rows.add(plain(prefix + ".connect-timeout-ms", String.valueOf(endpoint.connectTimeoutMs())));
        rows.add(plain(prefix + ".read-timeout-ms", String.valueOf(endpoint.readTimeoutMs())));
        rows.add(plain(prefix + ".use-onyx-auth", String.valueOf(endpoint.useOnyxAuth())));
        new TreeMap<>(endpoint.headers()).forEach((name, value) -> {
            String property = prefix + ".headers." + name;
            if (Secrets.credentialHeader(name) || pointsAtOperatingSystem(value)) {
                String shown = HeaderMasking.shownTemplate(name, value);
                // A header that reads its value from the operating system is a secret whatever it is called.
                rows.add(new SettingRow(property, Texts.blank(value) ? null : Placeholders.referencesOnly(value) ? value
                        : Secrets.credentialHeader(name) ? shown : Secrets.mask(property), Texts.present(value), true));
            } else {
                rows.add(plain(property, value));
            }
        });
    }

    private static SettingRow plain(String property, String value) {
        return new SettingRow(property, Texts.trimToNull(value), Texts.present(value), false);
    }

    private static SettingRow secret(String property, String value) {
        if (Texts.blank(value)) {
            return new SettingRow(property, null, false, true);
        }
        return new SettingRow(property, Placeholders.referencesOnly(value) ? value : Secrets.mask(property), true, true);
    }

    // ------------------------------------------------------------------------------------------
    // The preview before sending
    // ------------------------------------------------------------------------------------------

    /**
     * What a request would be, without sending it: the url beside its template, the headers, every
     * variable it needs, the vendor's sample body and what it resolves to, and its saved examples.
     *
     * @param request   a specific collection request, or null for the one the operation uses by default
     * @param variables values typed for this run
     * @param urlValues values for the {@code {name}} parts of the url, such as the id of a lookup
     */
    public Resolved resolve(EpaOperation operation, RequestRef request, Map<String, String> variables, Map<String, String> urlValues,
                            ProdUnlock unlock) {
        if (operation == null) {
            throw ValidationException.of("operation", "State which operation to resolve.");
        }
        if (!operation.outbound()) {
            throw ValidationException.of("operation", operation.label() + " never leaves the tool, so there is no request to resolve for it.");
        }
        CallPlan plan = gateway.describe(store.snapshot(), CallIntent.of(operation).withRequest(request).withRunValues(present(variables))
                .withUrlValues(present(urlValues)).withUnlock(unlock));
        PostmanRequest source = plan.target().source() == Source.REQUEST ? plan.target().request().request() : null;

        Map<String, VariableRow> rows = new LinkedHashMap<>();
        if (plan.urlVariables() != null) {
            collect(rows, plan.url().variables(), plan.urlVariables().explanations(), "the url");
        }
        List<ResolvedHeaderView> headers = new ArrayList<>();
        for (CallPlan.Header header : plan.headers()) {
            String name = header.header().name();
            collect(rows, header.how().variables(), header.how().explanations(), "the header " + name);
            // The display form the call itself carries, so the preview shows what the wire view will.
            headers.add(new ResolvedHeaderView(name, HeaderMasking.shownTemplate(name, header.template()), header.header().display(),
                    header.header().resolved(), header.header().unresolved()));
        }
        String bodyTemplate = source == null ? null : source.body();
        Resolution body = bodyTemplate == null ? null : plan.resolver().apply(bodyTemplate);
        if (body != null) {
            collect(rows, body.variables(), body.explanations(), "the body");
        }

        List<TokenRow> tokens = new ArrayList<>();
        for (String token : plan.tokens()) {
            String saved = source == null ? null : source.pathVariables().stream().filter(variable -> variable.name().equals(token))
                    .map(PostmanRequest.PathVariable::value).findFirst().orElse(null);
            tokens.add(new TokenRow(token, plan.filled() != null && !plan.filled().missingTokens().contains(token), saved));
        }

        boolean fromRequest = source != null;
        String incomplete = plan.incomplete();
        if (plan.url().configured()) {
            // The url's own words when it alone is short, and the headers added when one is: a run
            // is refused for a header with no value on both profiles.
            List<String> missing = new ArrayList<>();
            String stillNeeds = stillNeeds(plan, plan.url().resolved() ? null : String.join(", ", plan.url().unresolved()), missing);
            if (stillNeeds != null && headersMissing(plan)) {
                incomplete = stillNeeds + (epa.mock() ? onMockAHeaderIsRefused(missing) : whereToGive(missing));
            }
        }
        List<String> notes = new ArrayList<>(source == null ? List.of() : source.notes());
        if (incomplete != null && epa.mock()) {
            notes.add(headersMissing(plan) ? incomplete : incomplete + " On the mock profile nothing is sent, so the run is still answered from a scenario.");
        }
        return Resolved.builder()
                .operation(operation)
                .method(plan.method())
                .environment(plan.environment())
                .epoch(plan.snapshot().epoch())
                .url(plan.url())
                .urlShown(plan.filled() == null ? null : plan.filled().shown())
                .headers(headers)
                .variables(new ArrayList<>(rows.values()))
                .urlTokens(tokens)
                .bodyTemplate(LiteralCredentials.inBody(bodyTemplate).template())
                .body(body == null ? null : body.display())
                .bodyUnresolved(body == null ? List.of() : body.unresolved())
                .bodyMode(source == null ? null : source.bodyMode())
                .requestName(plan.target().requestName())
                .request(fromRequest ? new RequestRef(plan.target().collection().id(), source.id()) : null)
                .scripted(plan.target().scripted())
                .scripts(source == null ? List.of() : scripts(source))
                .auth(source == null ? null : auth(source.auth()))
                .examples(source == null ? List.of() : examples(source))
                .write(plan.write())
                .blocked(plan.refusal() == null ? null : plan.refusal().reason() + " " + plan.refusal().action())
                .unavailable(epa.mock() ? null : incomplete)
                .notes(notes)
                .build();
    }

    /** A map as a browser posts it, without the entries that carry no value. */
    private static Map<String, String> present(Map<String, String> posted) {
        Map<String, String> out = new LinkedHashMap<>();
        if (posted != null) {
            posted.forEach((key, value) -> {
                if (key != null && value != null) {
                    out.put(key, value);
                }
            });
        }
        return out;
    }

    /** Adds each variable once, under the first place it was met, and notes every further place it is used. */
    private static void collect(Map<String, VariableRow> rows, List<VariableUse> uses, Map<String, String> explanations, String where) {
        for (VariableUse use : uses) {
            VariableRow known = rows.get(use.name());
            if (known != null) {
                if (!known.usedIn().contains(where)) {
                    List<String> usedIn = new ArrayList<>(known.usedIn());
                    usedIn.add(where);
                    rows.put(use.name(), new VariableRow(known.name(), known.source(), known.secret(), known.display(), known.resolved(),
                            known.reason(), usedIn));
                }
                continue;
            }
            boolean resolved = use.source() != VariableSource.UNRESOLVED;
            String reason = resolved ? (use.source() == VariableSource.GENERATED ? "Generated by this tool. Every use gets a new value, "
                    + "so the request that is sent will not carry the one shown here." : null) : explanations.get(use.name());
            rows.put(use.name(), new VariableRow(use.name(), use.source(), use.secret(), use.display(), resolved, reason, List.of(where)));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Masking, and which variables are used
    // ------------------------------------------------------------------------------------------

    /** @param inForce false for a variable that is switched off or that a later definition of the same name replaces */
    private static VariableView variableView(PostmanVariable variable, Map<String, Set<String>> usage, boolean inForce) {
        String value = variable.value();
        boolean set = variable.hasValue();
        boolean fromOperatingSystem = pointsAtOperatingSystem(value);
        boolean secret = variable.secret() || fromOperatingSystem;
        String display = !set ? null : !secret || Placeholders.referencesOnly(value) ? value : Secrets.mask(variable.key());
        String readsFrom = fromOperatingSystem
                ? "the operating system variable " + Placeholders.sole(value).substring(VariableResolver.OS_REFERENCE.length()).strip() : null;
        // A variable that is not in force is never used, whatever refers to its name.
        List<String> usedBy = new ArrayList<>(inForce ? usage.getOrDefault(variable.key(), Set.of()) : Set.of());
        if (usedBy.size() > USED_BY_SHOWN) {
            int more = usedBy.size() - USED_BY_SHOWN;
            usedBy = new ArrayList<>(usedBy.subList(0, USED_BY_SHOWN));
            usedBy.add("and " + more + " more");
        }
        return new VariableView(variable.key(), variable.type(), variable.enabled(), inForce, secret, set, display, readsFrom,
                !usedBy.isEmpty(), usedBy);
    }

    private static boolean pointsAtOperatingSystem(String value) {
        String pointer = Placeholders.sole(value);
        return pointer != null && pointer.startsWith(VariableResolver.OS_REFERENCE);
    }

    /** Notes where the requests and the variables of one collection refer to placeholders. */
    private static void noteCollection(Map<String, Set<String>> usage, StoredCollection collection) {
        for (StoredCollection.Mapped mapped : collection.requests()) {
            PostmanRequest request = mapped.request();
            String where = "the request \"" + request.name() + "\" of the collection \"" + collection.name() + "\"";
            note(usage, request.url(), where);
            request.sentHeaders().forEach(header -> note(usage, header.value(), where));
            note(usage, request.body(), where);
        }
        for (PostmanVariable variable : collection.content().inForce().values()) {
            note(usage, variable.value(), "the variable \"" + variable.key() + "\" of the collection \"" + collection.name() + "\"");
        }
    }

    /** Notes where the application.yml layer, and the urls this tool builds from it, refer to placeholders. */
    private void noteApplicationYml(Map<String, Set<String>> usage) {
        note(usage, onyx.crd().url(), "application.yml: onyx.crd.url");
        note(usage, onyx.dtr().url(), "application.yml: onyx.dtr.url");
        note(usage, onyx.pas().url(), "application.yml: onyx.pas.url");
        note(usage, onyx.subscription().url(), "application.yml: onyx.subscription.url");
        note(usage, onyx.subscription().callbackUrl(), "application.yml: onyx.subscription.callback-url");
        note(usage, onyx.auth().tokenUrl(), "application.yml: onyx.auth.token-url");
        note(usage, onyx.auth().clientId(), "application.yml: onyx.auth.client-id");
        note(usage, onyx.auth().clientSecret(), "application.yml: onyx.auth.client-secret");
        note(usage, onyx.auth().scopes(), "application.yml: onyx.auth.scopes");
        new TreeMap<>(onyx.variables()).forEach((name, value) -> note(usage, value, "application.yml: onyx.variables." + name));
        note(usage, lookup.member().url(), "application.yml: lookup.member.url");
        note(usage, lookup.provider().url(), "application.yml: lookup.provider.url");
        new TreeMap<>(lookup.member().headers()).forEach((name, value) -> note(usage, value, "application.yml: lookup.member.headers." + name));
        new TreeMap<>(lookup.provider().headers()).forEach((name, value) -> note(usage, value, "application.yml: lookup.provider.headers." + name));
        for (EpaOperation operation : EpaOperation.values()) {
            note(usage, operation.fallbackPath(), "the url this tool builds for " + operation.label() + " when no request is mapped to it");
        }
    }

    private static void note(Map<String, Set<String>> usage, String template, String where) {
        for (String name : Placeholders.names(template)) {
            usage.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(where);
        }
    }
}
