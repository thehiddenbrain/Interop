package org.p32h.interop.epa.pave.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.VariableSource;

/**
 * What the Config endpoints answer with.
 *
 * <p>Every type here is built for the browser from masked rows. None of them holds the value of a
 * secret, and none of them is a stored type or a properties record: a stored environment holds
 * credentials, and returning one would return them. A null is left out of the JSON, never written
 * as null.
 */
public final class ConfigViews {

    private ConfigViews() {
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    /**
     * One variable of an environment or a collection.
     *
     * @param name      the variable name
     * @param type      the type Postman gave it, or absent
     * @param enabled   false when the export switches it off. A disabled variable is never used
     * @param inForce   false when the variable is switched off, or when a later definition of the
     *                  same name in the same file replaces it. Only a variable in force is ever used
     * @param secret    true when its value is never shown
     * @param set       true when it has a value. A variable that is defined and blank is not set
     * @param display   what to show for the value: the value itself, or for a secret that is set a
     *                  mask that names the variable. A secret whose value only points at other
     *                  variables is shown as written, since a reference is not a secret. Absent
     *                  when there is no value
     * @param readsFrom the operating system variable the value is read from, when the value is a
     *                  reference to one. Absent otherwise
     * @param used      true when it is in force and something refers to it. For a variable of an
     *                  environment that is a collection request, a fallback url or a setting, or
     *                  another variable of the environment. For a variable of a collection it is a
     *                  request or another variable of that same collection, since no other
     *                  collection ever sees it
     * @param usedBy    where it is referred to, in words
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariableView(String name, String type, boolean enabled, boolean inForce, boolean secret, boolean set, String display,
                               String readsFrom, boolean used, List<String> usedBy) {

        public VariableView {
            usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
        }
    }

    /**
     * @param id                  the id that is safe in a url path. The endpoints that take an
     *                            environment name take this too
     * @param file                where the uploaded file sits on disk
     * @param acknowledgedNotProd true when the uploader confirmed that this environment is not
     *                            production. Without it, a write to a url that reads as
     *                            production is refused under this environment
     * @param productionSigns     what about it reads as production, in words
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentView(String id, String name, EnvironmentKind kind, boolean active, String uploadedAt, String originalFileName,
                                  String file, boolean acknowledgedNotProd, List<String> productionSigns, List<VariableView> variables,
                                  List<String> addressWarnings) {

        public EnvironmentView {
            productionSigns = productionSigns == null ? List.of() : List.copyOf(productionSigns);
            variables = variables == null ? List.of() : List.copyOf(variables);
            addressWarnings = addressWarnings == null ? List.of() : List.copyOf(addressWarnings);
        }
    }

    /** @param warnings one for each secret that now sits in the file in plain text, and anything the parser set aside */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentUploaded(EnvironmentView environment, boolean replaced, List<String> warnings, long epoch) {

        public EnvironmentUploaded {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * @param active   the name of the active environment, or absent when none is
     * @param problems what is wrong with the configuration as it stands, in words
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentList(List<EnvironmentView> environments, String active, long epoch, List<String> problems,
                                  List<ProductionDestinationView> productionDestinations) {

        public EnvironmentList {
            environments = environments == null ? List.of() : List.copyOf(environments);
            problems = problems == null ? List.of() : List.copyOf(problems);
            productionDestinations = productionDestinations == null ? List.of() : List.copyOf(productionDestinations);
        }
    }

    /**
     * An address this installation knows as production. Nothing is remembered in secret: the list
     * is shown, with whether the environment it belonged to is still stored.
     *
     * @param environment the production environment the address belongs or belonged to, a long name by
     *                    its start and its length ({@link org.p32h.interop.epa.pave.postman.Names#shown})
     * @param destination scheme, host, port and base path. Never a query or user information
     * @param stored      false when that environment has been deleted and the address is remembered
     * @param forgettable true when "Forget this production address" is offered: no environment stored
     *                    as prod holds it, and it is not built from a secret (R-35)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductionDestinationView(String environment, String destination, boolean stored, boolean forgettable) {

        /** A row as it was before Forget, for an address of a stored prod environment: never forgettable. */
        public ProductionDestinationView(String environment, String destination, boolean stored) {
            this(environment, destination, stored, !stored);
        }
    }

    /** What "Forget this production address" answers: the address as it was listed, and the new epoch. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Forgotten(String forgotten, long epoch) {
    }

    /**
     * A stored file that could not be read at the start and is left out (R-32).
     *
     * @param file the file as the store names it, relative to the store folder: what
     *             {@code POST /api/config/unreadable/remove} is given back
     * @param line what is wrong and what to do, in words. The same sentence is among the problems
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnreadableView(String file, String line) {
    }

    /** What "Remove it from the store" answers: the file as listed, where it was moved (absent when it was not there), the new epoch. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnreadableRemoved(String removed, String movedTo, long epoch) {
    }

    /** What switching the active environment answers: the environment now in force, the new epoch, and whether a write would be sent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Activated(EnvironmentRef environment, long epoch, WriteAccess writeAccess, List<String> addressWarnings) {

        public Activated {
            addressWarnings = addressWarnings == null ? List.of() : List.copyOf(addressWarnings);
        }
    }

    /**
     * @param removed   the name of what was removed
     * @param wasActive true when the active environment was removed, so none is active now
     * @param warnings  set when the file could not be deleted from disk
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Removed(String removed, boolean wasActive, long epoch, List<String> warnings) {

        public Removed {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    /**
     * One request of a collection with its mapping.
     *
     * @param detectedOperation  what detection proposed
     * @param detectionBasis     what the proposal rests on, in words
     * @param detectionConflict  true when the name and the url read as different operations
     * @param correctedOperation the operation an operator chose in its place, or absent
     * @param effectiveOperation the operation in force
     * @param effectiveLabel     its label, so the browser keeps no copy of the list
     * @param guardedAsWrite     true when sending this request counts as a write, whatever it is
     *                           mapped to. On a production environment it then needs an unlock
     * @param scripted           true when a pre-request script would run before it in Postman. The
     *                           script is never run here
     * @param testScripted       true when a script would run after the response in Postman, which
     *                           may store a value a later request needs
     * @param authHelper         the Postman authentication helper that would apply, or absent.
     *                           This tool does not apply it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestSummary(String id, String name, List<String> folderPath, String method, String urlTemplate,
                                 EpaOperation detectedOperation, String detectionBasis, boolean detectionConflict,
                                 EpaOperation correctedOperation, EpaOperation effectiveOperation, String effectiveLabel,
                                 boolean guardedAsWrite, boolean scripted, boolean testScripted, boolean hasBody, int exampleCount,
                                 String authHelper, List<String> notes) {

        public RequestSummary {
            folderPath = folderPath == null ? List.of() : List.copyOf(folderPath);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Named setters, because eighteen components in a row invite two of one type to change places. */
        public static final class Builder {
            private String id;
            private String name;
            private List<String> folderPath;
            private String method;
            private String urlTemplate;
            private EpaOperation detectedOperation;
            private String detectionBasis;
            private boolean detectionConflict;
            private EpaOperation correctedOperation;
            private EpaOperation effectiveOperation;
            private boolean guardedAsWrite;
            private boolean scripted;
            private boolean testScripted;
            private boolean hasBody;
            private int exampleCount;
            private String authHelper;
            private List<String> notes;

            private Builder() {
            }

            public Builder id(String value) {
                this.id = value;
                return this;
            }

            public Builder name(String value) {
                this.name = value;
                return this;
            }

            public Builder folderPath(List<String> value) {
                this.folderPath = value;
                return this;
            }

            public Builder method(String value) {
                this.method = value;
                return this;
            }

            public Builder urlTemplate(String value) {
                this.urlTemplate = value;
                return this;
            }

            public Builder detectedOperation(EpaOperation value) {
                this.detectedOperation = value;
                return this;
            }

            public Builder detectionBasis(String value) {
                this.detectionBasis = value;
                return this;
            }

            public Builder detectionConflict(boolean value) {
                this.detectionConflict = value;
                return this;
            }

            public Builder correctedOperation(EpaOperation value) {
                this.correctedOperation = value;
                return this;
            }

            public Builder effectiveOperation(EpaOperation value) {
                this.effectiveOperation = value;
                return this;
            }

            public Builder guardedAsWrite(boolean value) {
                this.guardedAsWrite = value;
                return this;
            }

            public Builder scripted(boolean value) {
                this.scripted = value;
                return this;
            }

            public Builder testScripted(boolean value) {
                this.testScripted = value;
                return this;
            }

            public Builder hasBody(boolean value) {
                this.hasBody = value;
                return this;
            }

            public Builder exampleCount(int value) {
                this.exampleCount = value;
                return this;
            }

            public Builder authHelper(String value) {
                this.authHelper = value;
                return this;
            }

            public Builder notes(List<String> value) {
                this.notes = value;
                return this;
            }

            public RequestSummary build() {
                return new RequestSummary(id, name, folderPath, method, urlTemplate, detectedOperation, detectionBasis, detectionConflict,
                        correctedOperation, effectiveOperation, effectiveOperation == null ? null : effectiveOperation.label(),
                        guardedAsWrite, scripted, testScripted, hasBody, exampleCount, authHelper, notes);
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionView(String id, String name, String schema, String uploadedAt, String originalFileName, String file,
                                 List<VariableView> variables, List<RequestSummary> requests, List<String> notes) {

        public CollectionView {
            variables = variables == null ? List.of() : List.copyOf(variables);
            requests = requests == null ? List.of() : List.copyOf(requests);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionUploaded(CollectionView collection, boolean replaced, List<String> warnings, long epoch) {

        public CollectionUploaded {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionList(List<CollectionView> collections, long epoch) {

        public CollectionList {
            collections = collections == null ? List.of() : List.copyOf(collections);
        }
    }

    /**
     * @param value    the value as written, placeholders intact. For a header that is a credential
     *                 by its own name the value is shown only when it is made of placeholders;
     *                 anything typed literally is redacted
     * @param disabled true when the collection switches the header off, so it is not sent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeaderView(String name, String value, boolean disabled) {
    }

    /** A saved example response, with its body exactly as the collection holds it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExampleView(String name, String status, Integer code, List<HeaderView> headers, String body) {

        public ExampleView {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }

        /** Prints no body. */
        @Override
        public String toString() {
            return "ExampleView[" + name + ", " + code + "]";
        }
    }

    /**
     * A script Postman would run around the request, as text. It is shown so a tester can read
     * what it would have computed. It is never run.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScriptView(String listen, String from, String source) {

        /** Prints where the script sits and never its text. */
        @Override
        public String toString() {
            return "ScriptView[" + listen + ", " + from + "]";
        }
    }

    /** @param note what this tool does about the helper, which is nothing, and what to do instead */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthView(String type, String from, List<String> variables, String note) {

        public AuthView {
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /** @param savedValue the value the collection saved for a {@code :name} path variable. Shown, never sent */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PathVariableView(String name, String savedValue) {
    }

    /** One request in full. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestDetail(String collectionId, String collectionName, RequestSummary request, List<HeaderView> headers,
                                String bodyMode, String bodyLanguage, String body, List<PathVariableView> pathVariables,
                                List<ScriptView> scripts, AuthView auth, List<ExampleView> examples, String description) {

        public RequestDetail {
            headers = headers == null ? List.of() : List.copyOf(headers);
            pathVariables = pathVariables == null ? List.of() : List.copyOf(pathVariables);
            scripts = scripts == null ? List.of() : List.copyOf(scripts);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        /** Prints the request and never its body, which is vendor sample data. */
        @Override
        public String toString() {
            return "RequestDetail[" + collectionId + ", " + (request == null ? null : request.id()) + "]";
        }
    }

    /**
     * @param changed false when the mapping already was what was asked for, so nothing moved
     * @param notes   what the correction does and does not change, in words
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MappingChanged(String collectionId, RequestSummary request, boolean changed, List<String> notes, long epoch) {

        public MappingChanged {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Everything in force
    // ------------------------------------------------------------------------------------------

    /**
     * Where an operation goes.
     *
     * @param template   the url as configured, placeholders and tokens intact
     * @param display    the url with every placeholder that resolved filled in and secrets masked.
     *                   A placeholder with no value, and a token such as {@code {id}} that is
     *                   filled when the call is made, are left as written
     * @param resolved   true when every placeholder has a value
     * @param unresolved the placeholders that have none
     * @param tokens     the tokens that are filled from the identifier a call is for
     * @param source      where the template came from, in words
     * @param requestName the name of the collection request the call would be sent with, or absent
     *                    when the url came from application.yml or was derived
     * @param request     a reference to that request, for choosing it again, or absent
     * @param scripted    true when that request carries a pre-request script this tool will not run
     * @param typed       true when the address is the one typed in the environment's settings, which
     *                    wins over every request of a collection unless a run names one; absent
     *                    otherwise. A screen tells a tester where a step's request comes from by it,
     *                    never by reading the words of {@code source}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetView(String template, String display, boolean resolved, List<String> unresolved, List<String> tokens,
                             String source, String requestName, RequestRef request, boolean scripted, Boolean typed) {

        public TargetView {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    /**
     * A collection request that is mapped to an operation and can be chosen for it.
     *
     * @param usedByDefault true for the one a call uses when it names no request
     * @param errorSample   true for a sample of an error (its name contains "error"), which a run
     *                      uses only when it is chosen; absent otherwise. Said by the server, so a
     *                      screen never applies the rule itself
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AvailableRequest(String collectionId, String collectionName, RequestSummary request, boolean usedByDefault,
                                   Boolean errorSample) {
    }

    /**
     * One operation and where it stands.
     *
     * @param label       the operation in words. The browser keeps no copy of these
     * @param write       true when the operation always changes something on the far side
     * @param outbound    false for the two operations that never leave the tool
     * @param method      the method a call would be sent with, or absent when there is no call
     * @param target      where it goes, or absent when it has no url
     * @param requests    the collection requests mapped to it
     * @param unavailable why it cannot be sent as things stand, in words, or absent when it can
     * @param blocked     why the production guardrail would refuse it for this session, or absent
     * @param note        anything else worth saying, such as that the mock profile needs no url
     * @param blockedShort  one short sentence for a blocked row, so that a table of rows does not
     *                      repeat the full reason on every write row. The full reason is in
     *                      {@code blocked} and, for the active environment, once in
     *                      {@code writeAccess.reason}. Absent when the row is not blocked
     * @param lookupEnabled for a lookup row, whether the lookup is switched on, so that no screen
     *                      has to build a setting key from an operation name. Absent on every other row
     * @param defaultRule   on a row with more than one request mapped to it, which of them a run uses
     *                      when it names none, why that one, and how to have another used. Absent
     *                      when one request or none is mapped
     * @param withoutSignIn on a live row that can be sent while signing in is off, why the call goes
     *                      without signing in although something suggests it should not: a Postman
     *                      helper on the request used, or sign-in settings in the active environment.
     *                      Names only, never a value. Absent otherwise, and always on mock
     * @param shortLabel    the operation in a word or two (plan 7.5), served as "short"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationRow(EpaOperation operation, String label, Protocol protocol, boolean write, boolean outbound, String method,
                               TargetView target, List<AvailableRequest> requests, String unavailable, String blocked, String note,
                               String blockedShort, Boolean lookupEnabled, String defaultRule, String withoutSignIn,
                               @com.fasterxml.jackson.annotation.JsonProperty("short") String shortLabel) {

        public OperationRow {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }

        /** A row for an operation, with what the operation says about itself already filled in. */
        public static Builder of(EpaOperation operation) {
            return new Builder(operation);
        }

        /** Named setters, so the three sentences a row can carry cannot change places. */
        public static final class Builder {
            private final EpaOperation operation;
            private String method;
            private TargetView target;
            private List<AvailableRequest> requests;
            private String unavailable;
            private String blocked;
            private String note;
            private String blockedShort;
            private Boolean lookupEnabled;
            private String defaultRule;
            private String withoutSignIn;

            private Builder(EpaOperation operation) {
                this.operation = operation;
            }

            public Builder method(String value) {
                this.method = value;
                return this;
            }

            public Builder target(TargetView value) {
                this.target = value;
                return this;
            }

            public Builder requests(List<AvailableRequest> value) {
                this.requests = value;
                return this;
            }

            public Builder unavailable(String value) {
                this.unavailable = value;
                return this;
            }

            public Builder blocked(String value) {
                this.blocked = value;
                return this;
            }

            public Builder note(String value) {
                this.note = value;
                return this;
            }

            public Builder blockedShort(String value) {
                this.blockedShort = value;
                return this;
            }

            public Builder lookupEnabled(Boolean value) {
                this.lookupEnabled = value;
                return this;
            }

            public Builder defaultRule(String value) {
                this.defaultRule = value;
                return this;
            }

            public Builder withoutSignIn(String value) {
                this.withoutSignIn = value;
                return this;
            }

            public OperationRow build() {
                return new OperationRow(operation, operation.label(), operation.protocol(), operation.write(), operation.outbound(), method,
                        target, requests, unavailable, blocked, note, blockedShort, lookupEnabled, defaultRule, withoutSignIn,
                        operation.shortLabel());
            }
        }
    }

    /**
     * One setting of the application.yml layer.
     *
     * @param value  what to show: the value, or for a secret a mask. A secret that only points at
     *               variables is shown as written. Absent when the setting is not set
     * @param set    true when the setting has a value
     * @param secret true when the value is never shown
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingRow(String property, String value, boolean set, boolean secret) {
    }

    /**
     * Everything in force.
     *
     * @param mode            mock or live
     * @param problems        what is wrong with the configuration as it stands, in words
     * @param allowProdWrites the configuration floor. While false no unlock is possible
     * @param writeAccess     whether a write would be sent right now for this browser session
     * @param storeDirectory  where uploaded files are kept
     * @param operations      one row for every operation the tool knows, in the order of the enum
     * @param settings        the application.yml layer, secrets masked
     * @param readiness       the short answer to "can I send now", drawn first on the Config tab
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Effective(String mode, EnvironmentRef environment, long epoch, List<String> problems, boolean allowProdWrites,
                            WriteAccess writeAccess, String storeDirectory, List<OperationRow> operations, List<SettingRow> settings,
                            List<ProductionDestinationView> productionDestinations, Readiness readiness,
                            List<UnreadableView> unreadable) {

        public Effective {
            unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
            productionDestinations = productionDestinations == null ? List.of() : List.copyOf(productionDestinations);
            problems = problems == null ? List.of() : List.copyOf(problems);
            operations = operations == null ? List.of() : List.copyOf(operations);
            settings = settings == null ? List.of() : List.copyOf(settings);
        }
    }

    /**
     * Whether a tester can send now, in the few facts that decide it. The counts come from the same
     * operation rows the table below them is drawn from, so the two cannot disagree.
     *
     * @param environmentLine  the active environment in words, or that none is active
     * @param collections      how many collections are uploaded
     * @param mapped           how many of their requests are mapped to an operation
     * @param sendable         how many outbound operations nothing stands in the way of
     * @param outbound         how many operations would send something
     * @param signIn           whether calls sign in to the service. Absent on mock, where nothing is sent
     * @param missingSecrets   secrets of the active environment that have no value. Always empty on mock
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Readiness(String environmentLine, boolean environmentActive, int collections, int mapped, int sendable, int outbound,
                            SignIn signIn, List<MissingSecret> missingSecrets) {

        public Readiness {
            missingSecrets = missingSecrets == null ? List.of() : List.copyOf(missingSecrets);
        }
    }

    /**
     * Whether calls sign in, and what suggests they should while they do not. The tool never
     * decides to sign in for the tester: it says that it does not, and how to switch it on.
     *
     * @param enabled                    onyx.auth.enabled
     * @param environmentSignInVariables names of variables of the active environment that read as
     *                                   sign-in settings and that nothing uses. Only while sign-in is off
     * @param requestHelpers             each distinct Postman sign-in helper on a request used by
     *                                   default, as "oauth2, set on the collection". Only while sign-in is off
     * @param operationsWithHelper       how many operations use such a request
     * @param problem                    while sign-in is on, the first onyx.auth setting with no value
     *                                   and why. Absent when all of them resolve
     * @param howToSwitchOn              the start-up arguments that would switch sign-in on, with the
     *                                   environment's own variable names in place. An instruction, never a value.
     *                                   Absent when the environment's own settings decide, which are changed on its form
     * @param from                       where the sign-in in force is decided: "this environment" when the active
     *                                   environment's settings state a method, else "application.yml"
     * @param method                     the method the active environment states (NONE, CLIENT_CREDENTIALS or
     *                                   BEARER_TOKEN), absent when it states none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignIn(boolean enabled, List<String> environmentSignInVariables, List<String> requestHelpers, int operationsWithHelper,
                         String problem, String howToSwitchOn, String from, String method) {

        public SignIn {
            environmentSignInVariables = environmentSignInVariables == null ? List.of() : List.copyOf(environmentSignInVariables);
            requestHelpers = requestHelpers == null ? List.of() : List.copyOf(requestHelpers);
        }
    }

    /**
     * A secret with no value.
     *
     * @param readsFrom the operating system variable it reads from, which is a name and not a
     *                  secret. Absent when the value is simply blank
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissingSecret(String name, String readsFrom) {
    }

    // ------------------------------------------------------------------------------------------
    // The preview before sending
    // ------------------------------------------------------------------------------------------

    /**
     * One variable a request needs.
     *
     * @param source   the layer that supplied its value, or UNRESOLVED
     * @param display  the value, or a mask for a secret. Absent when it has no value
     * @param resolved false when it has no value, so the request cannot be sent as it stands
     * @param reason   why it has no value, in words. Absent when it has one
     * @param usedIn   where the request refers to it: the url, a header by name, the body
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VariableRow(String name, VariableSource source, boolean secret, String display, boolean resolved, String reason,
                              List<String> usedIn) {

        public VariableRow {
            usedIn = usedIn == null ? List.of() : List.copyOf(usedIn);
        }

        /** The source in words, served beside the code so that no screen keeps its own copy. Never read from a request. */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceLabel",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String sourceLabel() {
            return source == null ? null : source.label();
        }

        /** Prints no value. A value typed for one run can be an identifier. */
        @Override
        public String toString() {
            return "VariableRow[" + name + ", " + source + "]";
        }
    }

    /**
     * @param template   the value as written. Redacted for a credential header that holds anything
     *                   typed literally
     * @param display    the resolved value in the form that is shown. A credential header shows its
     *                   scheme at most. Absent when the value did not resolve
     * @param unresolved the placeholders in the value that have no value
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResolvedHeaderView(String name, String template, String display, boolean resolved, List<String> unresolved) {

        public ResolvedHeaderView {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }
    }

    /**
     * A {@code {name}} or {@code :name} part of the url, which is filled from the identifier a call is for.
     *
     * @param supplied   true when the preview was given a value for it
     * @param savedValue the value the collection saved for it, as a suggestion. It is never sent
     *                   unless it is supplied
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenRow(String name, boolean supplied, String savedValue) {
    }

    /**
     * What a request would be, before it is sent.
     *
     * @param url            the template beside what it resolved to
     * @param urlShown       the url with whatever resolved filled in, for when it is not complete
     * @param variables      every variable the url, the headers and the body need
     * @param bodyTemplate   the vendor's sample body exactly as the collection holds it, or absent
     * @param body           the body with its placeholders resolved, in the form that is shown.
     *                       Absent when there is no body or it did not resolve
     * @param bodyUnresolved the placeholders in the body that have no value
     * @param scripted       true when the request carries a pre-request script this tool will not run
     * @param write          true when sending it counts as a write
     * @param blocked        why the production guardrail would refuse it for this session, or absent
     * @param unavailable    why it cannot be sent as things stand, or absent when it can
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resolved(EpaOperation operation, String label, String method, EnvironmentRef environment, long epoch, ResolvedUrl url,
                           String urlShown, List<ResolvedHeaderView> headers, List<VariableRow> variables, List<TokenRow> urlTokens,
                           String bodyTemplate, String body, List<String> bodyUnresolved, String bodyMode, String requestName,
                           RequestRef request, boolean scripted, List<ScriptView> scripts, AuthView auth, List<ExampleView> examples,
                           boolean write, String blocked, String unavailable, List<String> notes) {

        public Resolved {
            headers = headers == null ? List.of() : List.copyOf(headers);
            variables = variables == null ? List.of() : List.copyOf(variables);
            urlTokens = urlTokens == null ? List.of() : List.copyOf(urlTokens);
            bodyUnresolved = bodyUnresolved == null ? List.of() : List.copyOf(bodyUnresolved);
            scripts = scripts == null ? List.of() : List.copyOf(scripts);
            examples = examples == null ? List.of() : List.copyOf(examples);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** Prints the operation and the method. The body is vendor sample data, and the url can hold an identifier. */
        @Override
        public String toString() {
            return "Resolved[" + operation + " " + method + "]";
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Named setters, because this is the widest answer the Config endpoints give. */
        public static final class Builder {
            private EpaOperation operation;
            private String method;
            private EnvironmentRef environment;
            private long epoch;
            private ResolvedUrl url;
            private String urlShown;
            private List<ResolvedHeaderView> headers;
            private List<VariableRow> variables;
            private List<TokenRow> urlTokens;
            private String bodyTemplate;
            private String body;
            private List<String> bodyUnresolved;
            private String bodyMode;
            private String requestName;
            private RequestRef request;
            private boolean scripted;
            private List<ScriptView> scripts;
            private AuthView auth;
            private List<ExampleView> examples;
            private boolean write;
            private String blocked;
            private String unavailable;
            private List<String> notes;

            private Builder() {
            }

            public Builder operation(EpaOperation value) {
                this.operation = value;
                return this;
            }

            public Builder method(String value) {
                this.method = value;
                return this;
            }

            public Builder environment(EnvironmentRef value) {
                this.environment = value;
                return this;
            }

            public Builder epoch(long value) {
                this.epoch = value;
                return this;
            }

            public Builder url(ResolvedUrl value) {
                this.url = value;
                return this;
            }

            public Builder urlShown(String value) {
                this.urlShown = value;
                return this;
            }

            public Builder headers(List<ResolvedHeaderView> value) {
                this.headers = value;
                return this;
            }

            public Builder variables(List<VariableRow> value) {
                this.variables = value;
                return this;
            }

            public Builder urlTokens(List<TokenRow> value) {
                this.urlTokens = value;
                return this;
            }

            public Builder bodyTemplate(String value) {
                this.bodyTemplate = value;
                return this;
            }

            public Builder body(String value) {
                this.body = value;
                return this;
            }

            public Builder bodyUnresolved(List<String> value) {
                this.bodyUnresolved = value;
                return this;
            }

            public Builder bodyMode(String value) {
                this.bodyMode = value;
                return this;
            }

            public Builder requestName(String value) {
                this.requestName = value;
                return this;
            }

            public Builder request(RequestRef value) {
                this.request = value;
                return this;
            }

            public Builder scripted(boolean value) {
                this.scripted = value;
                return this;
            }

            public Builder scripts(List<ScriptView> value) {
                this.scripts = value;
                return this;
            }

            public Builder auth(AuthView value) {
                this.auth = value;
                return this;
            }

            public Builder examples(List<ExampleView> value) {
                this.examples = value;
                return this;
            }

            public Builder write(boolean value) {
                this.write = value;
                return this;
            }

            public Builder blocked(String value) {
                this.blocked = value;
                return this;
            }

            public Builder unavailable(String value) {
                this.unavailable = value;
                return this;
            }

            public Builder notes(List<String> value) {
                this.notes = value;
                return this;
            }

            public Resolved build() {
                return new Resolved(operation, operation == null ? null : operation.label(), method, environment, epoch, url, urlShown,
                        headers, variables, urlTokens, bodyTemplate, body, bodyUnresolved, bodyMode, requestName, request, scripted,
                        scripts, auth, examples, write, blocked, unavailable, notes);
            }
        }
    }
}
