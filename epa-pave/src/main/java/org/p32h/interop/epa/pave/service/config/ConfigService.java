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
import org.p32h.interop.epa.pave.postman.Names;
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
import org.p32h.interop.epa.pave.service.error.BadInputException;
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
        if (kind == null) {
            throw ValidationException.of("kind", "State what kind of environment this is: sandbox, uat or prod. It is never guessed.");
        }
        // The file is read inside its own guard (R-07); the checks that follow, the production
        // guardrail above all, run outside it and keep their own answers.
        org.p32h.interop.epa.pave.postman.PostmanEnvironment parsed = guardedRead(fileName,
                () -> org.p32h.interop.epa.pave.postman.PostmanParser.environment(org.p32h.interop.epa.pave.postman.PostmanParser.decode(content)));
        // The view is built inside the upload, before the commit, so a view that could not be built stores nothing.
        ConfigStore.Described<ConfigStore.EnvironmentUpload, EnvironmentView> described = store.uploadEnvironment(fileName, content, parsed,
                kind, acknowledgeNotProd, this::environmentView);
        ConfigStore.EnvironmentUpload upload = described.upload();
        return new EnvironmentUploaded(described.view(), upload.replaced(), upload.warnings(), upload.epoch());
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
        return new Activated(snapshot.activeRef(), snapshot.epoch(), gateway.writeAccess(unlock), AddressChecks.of(snapshot.active(), snapshot));
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
            note(usage, variable.value(), "the variable \"" + Names.shown(variable.key()) + "\" of this environment");
        }
        List<VariableView> variables = new ArrayList<>();
        environment.content().variables().forEach(variable -> variables.add(variableView(variable, usage, inForce.get(variable.key()) == variable)));
        return new EnvironmentView(environment.id(), environment.name(), environment.kind(), environment.name().equals(snapshot.activeName()),
                environment.uploadedAt(), environment.originalFileName(), store.directory().resolve(environment.file()).toString(),
                environment.acknowledgedNotProd(), environment.productionSigns(), variables, AddressChecks.of(environment, snapshot));
    }

    // ------------------------------------------------------------------------------------------
    // An environment's settings (plan 5.4)
    // ------------------------------------------------------------------------------------------

    /** One environment for its form, every setting beside what is in force. */
    public SettingsViews.EnvironmentSettingsView settings(String nameOrId) {
        ConfigSnapshot snapshot = store.snapshot();
        StoredEnvironment environment = snapshot.environment(nameOrId);
        if (environment == null) {
            throw NotFoundException.noLongerHeld("The environment", "environments");
        }
        return settingsView(environment, snapshot);
    }

    /**
     * Makes or saves an environment from its form, with the exports chosen, in one commit (R-08).
     * Each export is read inside its own guard (R-07); the checks that follow the reading, the
     * production guardrail above all, run outside it and keep their own answers.
     */
    public SettingsViews.SettingsSaved saveSettings(SettingsViews.EnvironmentSettingsInput input) {
        List<org.p32h.interop.epa.pave.model.FieldError> errors = new ArrayList<>();
        EnvironmentKind kind = null;
        if (Texts.present(input.kind())) {
            try {
                kind = EnvironmentKind.parse(input.kind());
            } catch (IllegalArgumentException e) {
                errors.add(new org.p32h.interop.epa.pave.model.FieldError("kind", "The kind is sandbox, uat or prod."));
            }
        } else if (!Texts.present(input.id())) {
            errors.add(new org.p32h.interop.epa.pave.model.FieldError("kind",
                    "State what kind of environment this is: sandbox, uat or prod. It is never guessed."));
        }
        ConfigStore.Export<org.p32h.interop.epa.pave.postman.PostmanEnvironment> environmentExport = export("environmentExport",
                input.environmentExport(), text -> org.p32h.interop.epa.pave.postman.PostmanParser.environment(text), errors);
        ConfigStore.Export<org.p32h.interop.epa.pave.postman.PostmanCollection> collectionExport = export("collectionExport",
                input.collectionExport(), text -> org.p32h.interop.epa.pave.postman.PostmanParser.collection(text), errors);
        // Every field error of the settings is found before anything is stored, and all of them are answered at once.
        SettingsInput.check(input, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String id = Texts.present(input.id()) ? input.id().strip() : null;
        ConfigStore.Described<ConfigStore.SettingsSaved, SettingsViews.EnvironmentSettingsView> described = store.saveSettings(
                new ConfigStore.SettingsSave(id, input.name(), kind, Boolean.TRUE.equals(input.acknowledgeNotProd()), environmentExport,
                        collectionExport, stored -> SettingsInput.merge(input, stored)), this::settingsView);
        ConfigStore.SettingsSaved saved = described.upload();
        List<String> warnings = new ArrayList<>(saved.warnings());
        warnings.addAll(SettingsInput.plainSecretWarnings(input, saved.environment().name(), store.directory()));
        return new SettingsViews.SettingsSaved(environmentView(saved.environment(), saved.after()), described.view(), warnings,
                saved.epoch());
    }

    /**
     * Reads one uploaded file inside its own guard (R-07). A refusal of the file is thrown as it
     * was, so its answer keeps its status and its action; any other failure of reading it answers
     * the guard's line with the reference it is logged under and the cause by types.
     */
    private static <T> T guardedRead(String fileName, java.util.function.Supplier<T> reader) {
        RuntimeException[] refused = new RuntimeException[1];
        org.p32h.interop.epa.pave.service.error.FileGuard.Read<T> read = org.p32h.interop.epa.pave.service.error.FileGuard.read(fileName, () -> {
            try {
                return reader.get();
            } catch (BadInputException | ValidationException e) {
                refused[0] = e;
                return null;
            }
        });
        if (refused[0] != null) {
            throw refused[0];
        }
        if (!read.ok()) {
            throw new BadInputException(read.line(), read.reference() == null ? "Nothing was stored."
                    : "Nothing was stored. Reference " + read.reference() + (read.cause() == null ? "" : ", cause " + read.cause()) + ".");
        }
        return read.value();
    }

    /** One export of a save, read inside its own guard; a refusal or a failure becomes a field error at its slot. */
    private static <T> ConfigStore.Export<T> export(String field, SettingsViews.FileInput file, java.util.function.Function<String, T> parse,
                                                    List<org.p32h.interop.epa.pave.model.FieldError> errors) {
        if (file == null) {
            return null;
        }
        if (file.text() == null) {
            errors.add(new org.p32h.interop.epa.pave.model.FieldError(field, "The file holds no text. Choose it again."));
            return null;
        }
        org.p32h.interop.epa.pave.service.error.FileGuard.Read<ConfigStore.Export<T>> read =
                org.p32h.interop.epa.pave.service.error.FileGuard.read(file.fileName(), () -> {
                    byte[] content = file.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    T parsed = parse.apply(org.p32h.interop.epa.pave.postman.PostmanParser.decode(content));
                    return new ConfigStore.Export<>(file.fileName(), content, parsed);
                });
        if (!read.ok()) {
            errors.add(new org.p32h.interop.epa.pave.model.FieldError(field, read.reference() == null ? read.line()
                    : read.line() + " Reference " + read.reference() + "."));
            return null;
        }
        return read.value();
    }

    SettingsViews.EnvironmentSettingsView settingsView(StoredEnvironment environment, ConfigSnapshot snapshot) {
        EnvironmentSettings settings = environment.settings();
        EnvironmentView shown = environmentView(environment, snapshot);
        SettingsViews.ExportView export = new SettingsViews.ExportView(environment.originalFileName(), environment.uploadedAt(),
                shown.variables());
        boolean mock = EpaProperties.MOCK.equals(epa.mode());
        SettingsViews.ProfileView profile = new SettingsViews.ProfileView(epa.mode(), mock ? "Mock, chosen when PAVE starts"
                : "Live, chosen when PAVE starts");

        Timeouts application = gateway.protocolTimeouts();
        SettingsViews.TimeoutsView timeouts = new SettingsViews.TimeoutsView(settings.timeouts().connectMs(), settings.timeouts().readMs(),
                settings.timeouts().over(application), new SettingsViews.TimeoutsView.Defaults(application.connectMs(), application.readMs()));

        org.p32h.interop.epa.pave.model.MemberIdRule rule = settings.memberIdRule();
        List<SettingsViews.Option> ruleOptions = new ArrayList<>();
        for (org.p32h.interop.epa.pave.model.MemberIdRule each : org.p32h.interop.epa.pave.model.MemberIdRule.values()) {
            ruleOptions.add(new SettingsViews.Option(each.name(), each.words()));
        }
        SettingsViews.MemberIdRuleView memberIdRule = new SettingsViews.MemberIdRuleView(
                (rule == null ? org.p32h.interop.epa.pave.model.MemberIdRule.PACKED : rule).name(),
                rule == null ? org.p32h.interop.epa.pave.model.RequestShapes.FROM_DEFAULT
                        : org.p32h.interop.epa.pave.model.RequestShapes.FROM_ENVIRONMENT, ruleOptions);

        List<SettingsViews.AddressView> addresses = new ArrayList<>();
        ConfigStoreGateway.AddressInForce orderSign = null;
        for (EpaOperation operation : StoredSettings.ADDRESSED) {
            ConfigStoreGateway.AddressInForce inForce = gateway.addressInForce(snapshot, environment, operation);
            if (operation == EpaOperation.CRD_ORDER_SIGN) {
                orderSign = inForce;
            }
            String typed = settings.addresses().get(operation);
            boolean subscription = operation == EpaOperation.SUBSCRIPTION_CREATE;
            addresses.add(new SettingsViews.AddressView(StoredSettings.key(operation), subscription ? "Subscription" : operation.label(),
                    operation.shortLabel(), typed == null ? null : LiteralCredentials.inUrl(typed).shown(),
                    new SettingsViews.AddressInForce(inForce.display(), inForce.template(), inForce.source(), inForce.sourceLabel(),
                            inForce.resolved(), inForce.unresolved())));
        }

        List<SettingsViews.ShapeView> shapes = new ArrayList<>();
        for (org.p32h.interop.epa.pave.model.RequestShape shape : org.p32h.interop.epa.pave.model.RequestShape.values()) {
            String stated = settings.shapes().get(shape);
            String value;
            String from;
            String requestName = null;
            String sampled = orderSign == null || orderSign.savedBody() == null ? null
                    : org.p32h.interop.epa.pave.service.crd.VendorSamples.fromBody(shape, orderSign.savedBody());
            if (stated != null) {
                value = stated;
                from = org.p32h.interop.epa.pave.model.RequestShapes.FROM_ENVIRONMENT;
            } else if (sampled != null) {
                // R-37: with nothing stated, the vendor's own sample the call is sent from is the evidence.
                value = sampled;
                from = org.p32h.interop.epa.pave.model.RequestShapes.FROM_SAMPLE;
                requestName = orderSign.requestName();
            } else {
                value = shape.defaultValue();
                from = org.p32h.interop.epa.pave.model.RequestShapes.FROM_DEFAULT;
            }
            List<SettingsViews.Option> options = shape.options().stream().map(option -> new SettingsViews.Option(option.value(),
                    option.words())).toList();
            shapes.add(new SettingsViews.ShapeView(shape.name(), shape.label(), value, from, requestName, options, shape.why()));
        }

        List<SettingsViews.ValueView> values = new ArrayList<>();
        for (KnownValues.Known known : KnownValues.ALL) {
            values.add(valueView(environment, known.name(), known.label(), known.why()));
        }
        for (String name : settings.values().keySet()) {
            if (KnownValues.of(name) == null) {
                values.add(valueView(environment, name, null, null));
            }
        }

        return new SettingsViews.EnvironmentSettingsView(environment.id(), environment.name(), environment.kind(),
                environment.name().equals(snapshot.activeName()), environment.acknowledgedNotProd(), export, profile,
                signInView(settings.signIn()), timeouts, memberIdRule, shapes, values, addresses, environment.settingsProblem(),
                snapshot.epoch());
    }

    private SettingsViews.SignInView signInView(SignInSettings signIn) {
        if (signIn == null) {
            String applies = onyx.auth().enabled() ? "client credentials" : "no sign-in";
            return new SettingsViews.SignInView(null, null, null, null, null, SecretSetting.of(null), SecretSetting.of(null),
                    "Not stated: application.yml applies (" + applies + ")");
        }
        String words = switch (signIn.method()) {
            case NONE -> "None";
            case CLIENT_CREDENTIALS -> "Client credentials";
            case BEARER_TOKEN -> "Bearer token";
        };
        return new SettingsViews.SignInView(signIn.method().name(), signIn.tokenUrl() == null ? null
                : LiteralCredentials.inUrl(signIn.tokenUrl()).shown(), signIn.clientId(), signIn.scope(),
                signIn.clientAuthentication().name(), SecretSetting.of(signIn.clientSecret()), SecretSetting.of(signIn.bearerToken()),
                words + ", this environment");
    }

    private SettingsViews.ValueView valueView(StoredEnvironment environment, String name, String label, String why) {
        String typed = environment.settings().values().get(name);
        if (typed != null) {
            return new SettingsViews.ValueView(name, label, why, typed, new SettingsViews.InForce(typed, "THIS_ENVIRONMENT", "typed here"));
        }
        Resolution resolution = gateway.resolutionUnder(environment, "{{" + name + "}}");
        if (resolution.complete()) {
            VariableSource source = resolution.variables().isEmpty() ? null : resolution.variables().get(0).source();
            boolean yml = source == VariableSource.APPLICATION_YML;
            return new SettingsViews.ValueView(name, label, why, null, new SettingsViews.InForce(resolution.display(),
                    yml ? "APPLICATION_YML" : "EXPORT", yml ? "application.yml" : "from the export"));
        }
        if (KnownValues.CALLBACK_URL.equals(name) && Texts.present(onyx.subscription().callbackUrl())) {
            return new SettingsViews.ValueView(name, label, why, null, new SettingsViews.InForce(
                    LiteralCredentials.inUrl(onyx.subscription().callbackUrl().strip()).shown(), "APPLICATION_YML", "application.yml"));
        }
        return new SettingsViews.ValueView(name, label, why, null, new SettingsViews.InForce(null, "NOT_SET", "not set"));
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    public CollectionUploaded uploadCollection(String fileName, byte[] content) {
        org.p32h.interop.epa.pave.postman.PostmanCollection parsed = guardedRead(fileName,
                () -> org.p32h.interop.epa.pave.postman.PostmanParser.collection(org.p32h.interop.epa.pave.postman.PostmanParser.decode(content)));
        // The view is built inside the upload, before the commit, so a view that could not be built stores nothing.
        ConfigStore.Described<ConfigStore.CollectionUpload, CollectionView> described = store.uploadCollection(fileName, content, parsed,
                this::collectionView);
        ConfigStore.CollectionUpload upload = described.upload();
        return new CollectionUploaded(described.view(), upload.replaced(), upload.warnings(), upload.epoch());
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
            throw NotFoundException.noLongerHeld("That request of the collection \"" + ConfigStore.quoted(collection.name()) + "\"", "collections");
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
                .authHelper(auth == null ? null : Names.shown(auth.type()) + ", set on " + auth.from())
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
        // Worked out once: every row and the checklist above the rows read the same names.
        // The environment's own sign-in decides when it states a method: an environment that signs
        // in is never said to send without sign-in, and one whose sign-in is None is.
        SignInSettings stated = snapshot.active() == null ? null : snapshot.active().settings().signIn();
        boolean signsIn = stated == null ? onyx.auth().enabled() : stated.method() != SignInSettings.Method.NONE;
        boolean signInOff = !epa.mock() && !signsIn;
        List<String> signInNames = signInOff ? environmentSignInVariables(snapshot) : List.of();
        List<OperationRow> rows = new ArrayList<>();
        for (EpaOperation operation : EpaOperation.values()) {
            rows.add(row(operation, snapshot, unlock, signInOff, signInNames));
        }
        return new Effective(epa.mode(), snapshot.activeRef(), snapshot.epoch(), store.problems(), store.allowProdWrites(),
                gateway.writeAccess(unlock), store.directory().toString(), rows, settings(), productionDestinations(snapshot),
                readiness(snapshot, rows, signInNames));
    }

    /** The names a variable is matched against, lower case with underscores, hyphens and dots taken out. */
    private static final Set<String> SIGN_IN_NAMES = Set.of("tokenurl", "accesstokenurl", "authurl", "clientid", "clientsecret", "scope",
            "scopes", "granttype", "audience");

    private static final String HELPER_NOT_APPLIED = "The request used carries a Postman sign-in helper (%s), which this tool does not apply.";

    private static final String SETTINGS_UNUSED = "The active environment holds sign-in settings that nothing uses.";

    /**
     * The short answer above the table: what is active, what is uploaded, how many operations can
     * be sent, whether calls sign in and which secrets have no value. Counted from {@code rows}, the
     * rows the table is drawn from.
     */
    private ConfigViews.Readiness readiness(ConfigSnapshot snapshot, List<OperationRow> rows, List<String> signInNames) {
        StoredEnvironment active = snapshot.active();
        String environmentLine = active == null ? "none is active."
                : ConfigStore.quoted(active.name()) + " (" + active.kind().wire() + "), active.";
        int mapped = 0;
        for (StoredCollection collection : snapshot.collections()) {
            mapped += (int) collection.requests().stream().filter(request -> request.effective() != EpaOperation.UNMAPPED).count();
        }
        List<OperationRow> outbound = rows.stream().filter(row -> row.outbound() && row.operation() != EpaOperation.UNMAPPED).toList();
        int sendable = (int) outbound.stream().filter(row -> row.unavailable() == null && row.blocked() == null).count();
        if (epa.mock()) {
            return new ConfigViews.Readiness(environmentLine, active != null, snapshot.collections().size(), mapped, sendable,
                    outbound.size(), null, List.of());
        }
        return new ConfigViews.Readiness(environmentLine, active != null, snapshot.collections().size(), mapped, sendable, outbound.size(),
                signIn(snapshot, rows, signInNames), missingSecrets(active));
    }

    private ConfigViews.SignIn signIn(ConfigSnapshot snapshot, List<OperationRow> rows, List<String> signInNames) {
        OnyxProperties.Auth auth = onyx.auth();
        StoredEnvironment active = snapshot.active();
        SignInSettings stated = active == null ? null : active.settings().signIn();
        if (stated != null && stated.method() != SignInSettings.Method.NONE) {
            return new ConfigViews.SignIn(true, List.of(), List.of(), 0, environmentSignInProblem(active, stated), null,
                    Timeouts.FROM_ENVIRONMENT, stated.method().name());
        }
        if (stated == null && auth.enabled()) {
            return new ConfigViews.SignIn(true, List.of(), List.of(), 0, signInProblem(auth), null, Timeouts.FROM_APPLICATION, null);
        }
        Set<String> helpers = new LinkedHashSet<>();
        int withHelper = 0;
        for (OperationRow row : rows) {
            String helper = row.requests().stream().filter(AvailableRequest::usedByDefault).map(available -> available.request().authHelper())
                    .filter(Texts::present).findFirst().orElse(null);
            // Every operation that would send, not only those that can be sent now: once the
            // rest is fixed, they too go without signing in.
            if (helper != null && row.outbound() && row.operation() != EpaOperation.UNMAPPED) {
                helpers.add(helper);
                withHelper++;
            }
        }
        if (stated != null) {
            // The environment states None: its own form is where that changes, not the start-up arguments.
            return new ConfigViews.SignIn(false, signInNames, List.copyOf(helpers), withHelper, null, null, Timeouts.FROM_ENVIRONMENT,
                    stated.method().name());
        }
        return new ConfigViews.SignIn(false, signInNames, List.copyOf(helpers), withHelper, null, howToSwitchOn(signInNames),
                Timeouts.FROM_APPLICATION, null);
    }

    /**
     * The first setting of the environment's own sign-in that has no value, and why, or null when
     * every one it needs resolves. Names only, never a value.
     */
    private String environmentSignInProblem(StoredEnvironment active, SignInSettings stated) {
        String[][] settings = stated.method() == SignInSettings.Method.BEARER_TOKEN
                ? new String[][] {{"The bearer token", stated.bearerToken()}}
                : new String[][] {{"The token url", stated.tokenUrl()}, {"The client id", stated.clientId()},
                        {"The client secret", stated.clientSecret()}};
        for (String[] setting : settings) {
            String where = setting[0] + " of the sign-in of \"" + Names.shown(active.name()) + "\"";
            if (Texts.blank(setting[1])) {
                return where + " has no value: it is not set. Set it in Configuration > Environments.";
            }
            Resolution resolution = gateway.resolutionUnder(active, setting[1]);
            if (!resolution.text().resolved() || Texts.blank(resolution.text().value())) {
                String reason = resolution.text().unresolved().stream().map(name -> resolution.explanations().get(name))
                        .filter(Texts::present).findFirst().orElse("it resolves to nothing.");
                return where + " has no value: " + reason;
            }
        }
        return null;
    }

    /** The first onyx.auth setting that does not resolve, with the reason the resolver gives, or null when all do. */
    private String signInProblem(OnyxProperties.Auth auth) {
        String[][] settings = {{"onyx.auth.token-url", auth.tokenUrl()}, {"onyx.auth.client-id", auth.clientId()},
                {"onyx.auth.client-secret", auth.clientSecret()}};
        for (String[] setting : settings) {
            if (Texts.blank(setting[1])) {
                return setting[0] + " has no value: it is not set.";
            }
            Resolution resolution = gateway.resolution(setting[1]);
            if (!resolution.text().resolved() || Texts.blank(resolution.text().value())) {
                String reason = resolution.text().unresolved().stream().map(name -> resolution.explanations().get(name))
                        .filter(Texts::present).findFirst().orElse("it resolves to nothing.");
                return setting[0] + " has no value: " + reason;
            }
        }
        return null;
    }

    /**
     * The start-up arguments the README gives under "Access tokens", with the environment's own
     * names where one was found for a role, and a plain marker where none was.
     */
    private static String howToSwitchOn(List<String> signInNames) {
        String tokenUrl = roleOf(signInNames, "tokenurl", "accesstokenurl", "authurl");
        String clientId = roleOf(signInNames, "clientid");
        String clientSecret = roleOf(signInNames, "clientsecret");
        return "--onyx.auth.enabled=true --onyx.auth.token-url=" + (tokenUrl == null ? "<your token url>" : "{{" + tokenUrl + "}}")
                + " --onyx.auth.client-id=" + (clientId == null ? "<your client id>" : "{{" + clientId + "}}")
                + " --onyx.auth.client-secret=" + (clientSecret == null ? "<your client secret>" : "{{" + clientSecret + "}}");
    }

    private static String roleOf(List<String> names, String... roles) {
        for (String role : roles) {
            for (String name : names) {
                if (signInKey(name).equals(role)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static String signInKey(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
    }

    /**
     * Variables of the active environment that read as sign-in settings and that nothing uses: no
     * collection request, no other variable and no setting of application.yml refers to them. The
     * onyx.auth settings are left out of that, because they are not used while sign-in is off.
     */
    private List<String> environmentSignInVariables(ConfigSnapshot snapshot) {
        StoredEnvironment active = snapshot.active();
        if (active == null) {
            return List.of();
        }
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        snapshot.collections().forEach(collection -> noteCollection(usage, collection));
        noteApplicationYml(usage, false);
        Map<String, PostmanVariable> inForce = active.content().inForce();
        inForce.values().forEach(variable -> note(usage, variable.value(), variable.key()));
        List<String> names = new ArrayList<>();
        for (PostmanVariable variable : inForce.values()) {
            String name = variable.key();
            boolean readsAsSignIn = SIGN_IN_NAMES.contains(signInKey(name)) || Secrets.looksSecret(name);
            if (variable.enabled() && readsAsSignIn && !usage.containsKey(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Secrets of the active environment, by type or by name, with no value: blank, or read from an
     * operating system variable that does not resolve. Names only.
     */
    private List<ConfigViews.MissingSecret> missingSecrets(StoredEnvironment active) {
        List<ConfigViews.MissingSecret> missing = new ArrayList<>();
        if (active == null) {
            return missing;
        }
        for (PostmanVariable variable : active.content().inForce().values()) {
            String value = variable.value();
            boolean fromOperatingSystem = pointsAtOperatingSystem(value);
            if (!variable.enabled() || !(variable.secret() || fromOperatingSystem || Secrets.looksSecret(variable.key()))) {
                continue;
            }
            if (Texts.blank(value)) {
                missing.add(new ConfigViews.MissingSecret(variable.key(), null));
            } else if (fromOperatingSystem && !gateway.resolution(value).text().resolved()) {
                missing.add(new ConfigViews.MissingSecret(variable.key(),
                        Placeholders.sole(value).substring(VariableResolver.OS_REFERENCE.length()).strip()));
            }
        }
        return missing;
    }

    /** Every address the store knows as production, so that nothing about the guardrail is remembered out of sight. */
    private static List<ConfigViews.ProductionDestinationView> productionDestinations(ConfigSnapshot snapshot) {
        List<ConfigViews.ProductionDestinationView> views = new ArrayList<>();
        for (ConfigSnapshot.KnownDestination known : snapshot.remembered()) {
            StoredEnvironment stored = snapshot.byName(known.environment());
            // A destination whose host or port is built from a secret is listed without it.
            String destination = known.secret() ? "an address whose host is built from a secret of the environment, not shown"
                    : known.destination().everyPortAndPath() ? "every port and every path of the host " + known.destination().host()
                    + ", because an address of this environment could not be read the way the HTTP client reads it"
                    : known.destination().shown();
            // The environment's name is held to the rule of a sentence: each address repeats it, and the
            // answer gives the name of a stored environment whole once already, as its label.
            views.add(new ConfigViews.ProductionDestinationView(Names.shown(known.environment()), destination,
                    stored != null && stored.kind() == org.p32h.interop.epa.pave.model.EnvironmentKind.PROD));
        }
        return views;
    }

    /**
     * @param signInOff   true on live while onyx.auth is switched off
     * @param signInNames the active environment's sign-in variables that nothing uses, worked out once
     */
    private OperationRow row(EpaOperation operation, ConfigSnapshot snapshot, ProdUnlock unlock, boolean signInOff, List<String> signInNames) {
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
        StoredCollection.Mapped used = plan.target().source() == Source.REQUEST ? plan.target().request() : null;
        row.method(plan.method()).requests(mappedTo(operation, snapshot, used)).defaultRule(defaultRule(operation, snapshot, used));
        if (plan.refusal() != null) {
            row.blocked(plan.refusal().reason() + " " + plan.refusal().action()).blockedShort(plan.refusal().brief());
        }
        boolean mock = epa.mock();
        if (!plan.url().configured()) {
            String missing = plan.incomplete();
            return (mock ? row.note(missing + " On the mock profile nothing is sent, so this still runs.")
                    : row.unavailable(missing)).build();
        }
        TargetView target = target(plan);
        row.target(target);
        // The url's tokens are filled per call and are not counted here, so every name left is a
        // placeholder an environment or a value for one run can supply.
        List<String> missing = new ArrayList<>(target.unresolved());
        String stillNeeds = stillNeeds(plan, target.resolved() ? List.of() : target.unresolved(), missing);
        if (stillNeeds != null) {
            if (mock) {
                row.note(stillNeeds + (headersMissing(plan) ? onMockAHeaderIsRefused(missing)
                        : " On the mock profile nothing is sent, so this still runs."));
            } else {
                row.unavailable(stillNeeds);
            }
        }
        if (signInOff && stillNeeds == null && plan.refusal() == null) {
            // The server's words, so that the page composes nothing about signing in itself.
            PostmanAuth helper = used == null ? null : used.request().auth();
            row.withoutSignIn(helper != null ? HELPER_NOT_APPLIED.formatted(Names.shown(helper.type()) + ", set on " + helper.from())
                    : signInNames.isEmpty() ? null : SETTINGS_UNUSED);
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
    private static String stillNeeds(CallPlan plan, List<String> urlNames, List<String> names) {
        List<String> all = new ArrayList<>(urlNames);
        for (CallPlan.Header header : plan.headers()) {
            List<String> unresolved = header.header().unresolved();
            if (Texts.present(header.header().name()) && !unresolved.isEmpty()) {
                unresolved.stream().filter(name -> !all.contains(name)).forEach(all::add);
                unresolved.stream().filter(name -> !names.contains(name)).forEach(names::add);
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        // In plain words (I-1): no braces, no property, and the place the value is typed.
        return plan.snapshot().active() == null ? Readiness.NO_ENVIRONMENT
                : Readiness.valuesMissing(plan.intent().operation(), all, !urlNames.isEmpty());
    }

    private static boolean headersMissing(CallPlan plan) {
        return plan.headers().stream().anyMatch(header -> Texts.present(header.header().name()) && !header.header().unresolved().isEmpty());
    }

    /**
     * The mock profile answers a url that is not complete from a scenario, but it refuses a header
     * with no value as live does, so a row that said "this still runs" would be untrue.
     */
    private static String onMockAHeaderIsRefused(List<String> names) {
        return " On the mock profile nothing is sent, but a header with no value is refused before a scenario answers, so a run is "
                + "refused here too.";
    }

    /**
     * Placeholders as a sentence names them. A placeholder's name is the file's, of any length, so
     * each is held to {@link Names#shown(String)}; the target's list of them keeps each whole.
     */
    private static String braced(List<String> names) {
        return String.join(", ", names.stream().map(name -> "{{" + Names.shown(name) + "}}").toList());
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
    /**
     * Which of several mapped requests a run uses when it names none, said where the choice can be
     * seen. The rule is the gateway's: the first in the order the collections and their requests are
     * listed, passing over a request whose name contains "error", which is a sample of a failure and
     * is used only when it is chosen. There is no action to make a request the one used: taking the
     * requests above it off the operation does it, so the sentence says that.
     *
     * @param used the request a run uses, or null when none of them is used unasked
     * @return the sentence, or null when one request or none is mapped
     */
    private static String defaultRule(EpaOperation operation, ConfigSnapshot snapshot, StoredCollection.Mapped used) {
        int mapped = 0;
        int errorSamplesBefore = 0;
        boolean seen = false;
        String usedFrom = null;
        for (StoredCollection collection : snapshot.collections()) {
            for (StoredCollection.Mapped request : collection.requests()) {
                if (request.effective() != operation) {
                    continue;
                }
                mapped++;
                if (request == used) {
                    seen = true;
                    usedFrom = collection.name();
                } else if (!seen && errorSample(request)) {
                    errorSamplesBefore++;
                }
            }
        }
        if (mapped < 2) {
            return null;
        }
        String label = operation.label();
        if (used == null || usedFrom == null) {
            return "None of the " + mapped + " requests mapped to " + label + " is used when a run names none: the name of each "
                    + "contains \"error\", so each is taken for a sample of an error, which is used only when it is chosen. To have one "
                    + "used by every run, map to " + label + " a request whose name does not contain \"error\".";
        }
        String before = errorSamplesBefore == 0 ? ""
                : ", after " + (errorSamplesBefore == 1 ? "1 request whose name contains" : errorSamplesBefore + " requests whose names contain")
                + " \"error\" and which " + (errorSamplesBefore == 1 ? "is" : "are") + " used only when chosen";
        return "Of the " + mapped + " requests mapped to " + label + ", a run that names none uses \"" + ConfigStore.oneLine(used.request()
                .quotedName()) + "\" of the collection \"" + ConfigStore.quoted(usedFrom) + "\", because it comes first in the order "
                + "the collections and their requests are listed" + before + ". To have another of them used, take off " + label + " each "
                + "request listed above that one, apart from a sample of an error.";
    }

    private static boolean errorSample(StoredCollection.Mapped request) {
        return request.request().name().toLowerCase(java.util.Locale.ROOT).contains("error");
    }

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
        rows.add(url("onyx.subscription.callback-url", onyx.subscription().callbackUrl()));
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
        rows.add(url(prefix + ".url", endpoint.url()));
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

    /**
     * A url as it may be shown: a credential typed into it, such as the access token of the
     * address to call back or the api key of a lookup, masked by the name of the place it sits
     * in, as the request side and every answer mask it. The row is then marked secret.
     */
    private static SettingRow url(String property, String value) {
        if (Texts.blank(value)) {
            return plain(property, value);
        }
        org.p32h.interop.epa.pave.postman.LiteralCredentials.Marked typed = org.p32h.interop.epa.pave.postman.LiteralCredentials.inUrl(value.strip());
        return typed.none() ? plain(property, value) : new SettingRow(property, typed.shown(), true, true);
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
            String stillNeeds = stillNeeds(plan, plan.url().resolved() ? List.of() : plan.url().unresolved(), missing);
            if (stillNeeds != null && headersMissing(plan)) {
                incomplete = stillNeeds + (epa.mock() ? onMockAHeaderIsRefused(missing) : "");
            }
        }
        List<String> notes = new ArrayList<>(source == null ? List.of() : source.notes());
        if (incomplete != null && epa.mock()) {
            notes.add(headersMissing(plan) ? incomplete : incomplete + onMockAnIncompleteUrl(operation, plan));
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

    /**
     * What an incomplete target means on mock. A run from a protocol tab is answered by a scenario,
     * which needs no url. A request sent as written is refused while its url still needs a value,
     * on mock as on live (RawService), so a preview that said only the first would be contradicted
     * by the send it prepares. An unmapped request can only be sent as written.
     */
    private static String onMockAnIncompleteUrl(EpaOperation operation, CallPlan plan) {
        if (!plan.url().configured()) {
            return " On the mock profile nothing is sent, so the run is still answered from a scenario.";
        }
        String asWritten = "Sent as written from the Config tab, it is refused until every part of its url has a value, on the mock "
                + "profile as on live, and nothing is sent.";
        return operation == EpaOperation.UNMAPPED ? " " + asWritten
                : " On the mock profile a run from its tab is still answered from a scenario, since nothing is sent. " + asWritten;
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
            String where = "the request \"" + Names.shown(request.name()) + "\" of the collection \"" + Names.shown(collection.name()) + "\"";
            note(usage, request.url(), where);
            request.sentHeaders().forEach(header -> note(usage, header.value(), where));
            note(usage, request.body(), where);
        }
        for (PostmanVariable variable : collection.content().inForce().values()) {
            note(usage, variable.value(), "the variable \"" + Names.shown(variable.key()) + "\" of the collection \"" + Names.shown(collection.name()) + "\"");
        }
    }

    /** Notes where the application.yml layer, and the urls this tool builds from it, refer to placeholders. */
    private void noteApplicationYml(Map<String, Set<String>> usage) {
        noteApplicationYml(usage, true);
    }

    /** @param withSignIn false to leave out the onyx.auth settings, which nothing reads while sign-in is off */
    private void noteApplicationYml(Map<String, Set<String>> usage, boolean withSignIn) {
        note(usage, onyx.crd().url(), "application.yml: onyx.crd.url");
        note(usage, onyx.dtr().url(), "application.yml: onyx.dtr.url");
        note(usage, onyx.pas().url(), "application.yml: onyx.pas.url");
        note(usage, onyx.subscription().url(), "application.yml: onyx.subscription.url");
        note(usage, onyx.subscription().callbackUrl(), "application.yml: onyx.subscription.callback-url");
        if (withSignIn) {
            note(usage, onyx.auth().tokenUrl(), "application.yml: onyx.auth.token-url");
            note(usage, onyx.auth().clientId(), "application.yml: onyx.auth.client-id");
            note(usage, onyx.auth().clientSecret(), "application.yml: onyx.auth.client-secret");
            note(usage, onyx.auth().scopes(), "application.yml: onyx.auth.scopes");
        }
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
