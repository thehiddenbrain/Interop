package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Map;

/**
 * One line of the test run ledger.
 *
 * <p>This type has no component for a member name, date of birth, address, gender, subscriber ID
 * or any request or response JSON, and none may be added. That alone is not enough, because
 * several components are filled from what a service returned, and a service can put anything
 * there: a sentence in place of a code, a person's name in place of a vendor. So every such
 * component is checked as the record is constructed, whoever constructs it. What has the shape
 * of a code is kept. Anything else is replaced by a statement that something was there and how
 * long it was, so the fact survives and the text does not:
 * <ul>
 *   <li>{@code coverageCodes}: an entry is kept only as {@code name=code}, optionally after a
 *       subject, where the code is one unbroken token</li>
 *   <li>{@code delegatedVendor}: only the vendors this tool knows by name are written. Any other
 *       text is shown on screen and not stored</li>
 *   <li>{@code correlation}: only the CORR keys are accepted, and a value must be a single line
 *       with no quotes or braces</li>
 *   <li>{@code procedureCodes} and {@code lineOfBusiness}: a code is one token, a line of business
 *       is a short label</li>
 * </ul>
 * A run line that lacks a run id, a timestamp, a protocol, an operation, an outcome or a result is
 * refused, so an incomplete line fails to read and is counted, and is never taken for a run.
 *
 * <p>The first group of components is always written, with null where a value does not apply. The
 * second group is written only when present. {@code memberId}, {@code dateOfService} and
 * {@code npi} are written only when the tracking configuration allows it; otherwise the ledger
 * stores {@code memberToken} and {@code memberIdLast4} so two runs for the same member can still
 * be correlated without the file becoming a roster.
 *
 * <p>Construct with {@link #builder()}. A service hands the ledger a draft holding the real member
 * ID; the ledger applies the tracking policy and returns the record as actually written.
 *
 * @param outcome          the protocol outcome code, for example NO_AUTH_REQUIRED
 * @param coverageCodes    standard codes from the response as {@code name=code} pairs. Coded values
 *                         only: an identifier such as an authorization number belongs in
 *                         {@code correlation} under {@link #CORR_AUTHORIZATION_REF}
 * @param errorType        an {@link OnyxFailureType} name, or {@link #ERROR_BLOCKED}
 * @param memberId         the member ID in its no-space form
 * @param targetSource     where the url came from: the name of the collection request that was
 *                         used, or null when it came from the fallback layer or nothing is configured
 * @param attachmentMode   for a PAS submission that carries supporting documentation, how it
 *                         travelled: fhir or x12-275
 * @param nextAction       the {@link NextAction} name the result stated as the next step. The
 *                         result on screen is gone when the browser tab is, and this line is all
 *                         the history, a reopened chain and a suite row ever see, so the answer to
 *                         "what do I do next" is kept here. A name, like the findings, so an old
 *                         line stays readable if the enum changes
 * @param correlation      identifiers that tie runs together, keyed by the CORR constants
 * @param findings         {@link FindingKind} names raised on this run
 * @param unrecordedInputs names of things this run sent that cannot be rebuilt from this line and
 *                         a fresh lookup, from the INPUT constants. Names only, never values. Empty
 *                         means the line is enough to run the same request again. Replay refuses a
 *                         record that lists any, because re-running it would send something else
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"recordType", "runId", "timestamp", "protocol", "operation", "target", "profile", "environment",
        "environmentKind", "scenarioId", "procedureCodes", "lineOfBusiness", "outcome", "coverageCodes",
        "delegatedVendor", "httpStatus", "elapsedMillis", "errorType", "expected", "result", "note", "testerRef",
        "chainId", "suiteRunId", "caseId", "replayOfRunId", "hook", "attachmentMode", "nextAction", "targetSource", "memberId",
        "memberToken", "memberIdLast4", "npi",
        "dateOfService", "correlation", "findings", "unrecordedInputs", "slow"})
public record RunRecord(
        String recordType,
        String runId,
        String timestamp,
        Protocol protocol,
        EpaOperation operation,
        String target,
        String profile,
        String environment,
        EnvironmentKind environmentKind,
        String scenarioId,
        List<String> procedureCodes,
        String lineOfBusiness,
        String outcome,
        List<String> coverageCodes,
        String delegatedVendor,
        Integer httpStatus,
        Long elapsedMillis,
        String errorType,
        String expected,
        TestResult result,
        String note,
        String testerRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String chainId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String suiteRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String caseId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String replayOfRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String hook,
        @JsonInclude(JsonInclude.Include.NON_NULL) String attachmentMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) String targetSource,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberIdLast4,
        @JsonInclude(JsonInclude.Include.NON_NULL) String npi,
        @JsonInclude(JsonInclude.Include.NON_NULL) String dateOfService,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> correlation,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> findings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> unrecordedInputs,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) Boolean slow) {

    /** The record type of a run line. Other line types in the same file carry a different value. */
    public static final String TYPE_RUN = "run";

    /** Error type of a run the production guardrail refused to send. */
    public static final String ERROR_BLOCKED = "BLOCKED";

    /** The identifier the tool put on a PAS claim, used to match a notification to its submission. */
    public static final String CORR_CLAIM_ID = "claimId";
    /** The id of a subscription a run created, read or changed. */
    public static final String CORR_SUBSCRIPTION_ID = "subscriptionId";
    /** An authorization number the service returned. The ledger tokenises it when it tokenises member IDs. */
    public static final String CORR_AUTHORIZATION_REF = "authorizationRef";
    /**
     * The earlier run this one follows from: the submission for an inquiry or a notification, the
     * package fetch for a later DTR step.
     */
    public static final String CORR_RUN_ID = "correlatedRunId";
    /** The run that created the subscription a notification arrived on. */
    public static final String CORR_SUBSCRIPTION_RUN_ID = "subscriptionRunId";
    /** The id of the ClaimResponse a submission or an inquiry returned, which a notification may name. */
    public static final String CORR_CLAIM_RESPONSE_ID = "claimResponseId";
    /** The canonical url of each questionnaire a run fetched or answered. Several are joined with a space. */
    public static final String CORR_QUESTIONNAIRE = "questionnaire";

    /** The request body was loaded from a collection and edited by hand. It is not kept. */
    public static final String INPUT_BODY = "body";
    /** Member details were typed or overridden by hand, so a fresh lookup would not reproduce them. */
    public static final String INPUT_MEMBER_DETAILS = "member-details";
    /** Provider details were typed or overridden by hand. */
    public static final String INPUT_PROVIDER_DETAILS = "provider-details";
    /** Placeholder values were typed on the form for this run only. */
    public static final String INPUT_RUN_VALUES = "run-values";
    /** The tester picked a specific collection request, which may not be the one a re-run would use. */
    public static final String INPUT_CHOSEN_REQUEST = "chosen-request";
    /** The run used something an earlier step produced, such as questionnaire answers, which the ledger does not hold. */
    public static final String INPUT_PRIOR_STEP = "prior-step";
    /** The line records something that arrived, such as a notification, so there is nothing to send again. */
    public static final String INPUT_INBOUND = "inbound";

    /** The correlation keys a record may carry. Anything else is a programming error, refused when the record is built. */
    private static final java.util.Set<String> CORRELATION_KEYS = java.util.Set.of(CORR_CLAIM_ID, CORR_SUBSCRIPTION_ID,
            CORR_AUTHORIZATION_REF, CORR_RUN_ID, CORR_SUBSCRIPTION_RUN_ID, CORR_CLAIM_RESPONSE_ID, CORR_QUESTIONNAIRE);

    private static final String TOKEN = "[A-Za-z0-9._\\-:/|+]{1,64}";
    private static final String NAME = "[A-Za-z0-9._-]{1,40}";
    private static final String ONE_SUBJECT = "[A-Za-z0-9._\\-/\\[\\]]{1,80}";
    private static final String SUBJECT = ONE_SUBJECT + "(?:, " + ONE_SUBJECT + ")*";
    private static final java.util.regex.Pattern CODE_ENTRY =
            java.util.regex.Pattern.compile("(?:(" + SUBJECT + "): )?(" + NAME + ")=(" + TOKEN + ")");
    private static final java.util.regex.Pattern NO_ANSWER_ENTRY = java.util.regex.Pattern.compile("(" + SUBJECT + "): \\(no answer\\)");
    private static final java.util.regex.Pattern NAMED_ENTRY =
            java.util.regex.Pattern.compile("(?:(" + SUBJECT + "): )?(" + NAME + ")=(.*)", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern PROCEDURE_CODE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]{0,19}");
    private static final java.util.regex.Pattern SHORT_LABEL = java.util.regex.Pattern.compile("[A-Za-z0-9 &/.,()'+\\-]{1,60}");
    private static final java.util.regex.Pattern CORRELATION_VALUE =
            java.util.regex.Pattern.compile("[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%| ]{1,512}");

    public RunRecord {
        // Boxed, not primitive, because Jackson 3 refuses to bind a missing property to a primitive
        // and a line without these two must still be readable. They are normalised here so callers
        // never see a null.
        elapsedMillis = elapsedMillis == null ? Long.valueOf(0L) : elapsedMillis;
        slow = slow != null && slow;
        recordType = recordType == null ? TYPE_RUN : recordType;
        if (TYPE_RUN.equals(recordType)) {
            if (Texts.blank(runId) || Texts.blank(timestamp) || Texts.blank(outcome) || protocol == null || operation == null
                    || result == null) {
                throw new IllegalStateException("A run record needs a run id, a timestamp, a protocol, an operation, an outcome and a result.");
            }
        }
        procedureCodes = procedureCodes == null ? List.of()
                : procedureCodes.stream().map(code -> kept(PROCEDURE_CODE, code, "not a code")).toList();
        coverageCodes = coverageCodes == null ? List.of() : coverageCodes.stream().map(RunRecord::codeEntry).toList();
        delegatedVendor = knownVendors(delegatedVendor);
        lineOfBusiness = lineOfBusiness == null ? null : kept(SHORT_LABEL, lineOfBusiness, "not a line of business");
        correlation = checkedCorrelation(correlation);
        findings = findings == null ? List.of() : List.copyOf(findings);
        unrecordedInputs = unrecordedInputs == null ? List.of() : List.copyOf(unrecordedInputs);
    }

    private static String kept(java.util.regex.Pattern shape, String value, String what) {
        return value != null && shape.matcher(value).matches() ? value : "(" + what + ", " + (value == null ? 0 : value.length()) + " characters)";
    }

    /** One coverage code entry, kept when it has the shape of one, otherwise replaced by what can safely be said about it. */
    private static String codeEntry(String entry) {
        if (entry == null) {
            return "(not a code entry, 0 characters)";
        }
        if (CODE_ENTRY.matcher(entry).matches() || NO_ANSWER_ENTRY.matcher(entry).matches()) {
            return entry;
        }
        java.util.regex.Matcher named = NAMED_ENTRY.matcher(entry);
        if (named.matches()) {
            // The element name is the tool's own. What followed it was not a code, so only its length is kept.
            return (named.group(1) == null ? "" : named.group(1) + ": ") + named.group(2) + "=(not a code, " + named.group(3).length()
                    + " characters)";
        }
        return "(not a code entry, " + entry.length() + " characters)";
    }

    /**
     * The vendor names to write: each one this tool knows, in its own spelling. A name it does not
     * know is not written. Under a vendor code a service can return any text, and the one place a
     * person's name must never land is a file that is kept.
     */
    private static String knownVendors(String vendors) {
        if (Texts.blank(vendors)) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : vendors.split(";")) {
            if (Texts.blank(part)) {
                continue;
            }
            out.add(DelegatedVendors.known(part) ? DelegatedVendors.canonical(part)
                    : "(vendor not on the known list, " + part.strip().length() + " characters)");
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    private static Map<String, String> checkedCorrelation(Map<String, String> given) {
        if (given == null || given.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        given.forEach((key, value) -> {
            if (!CORRELATION_KEYS.contains(key)) {
                throw new IllegalArgumentException("A run record can be correlated only under its own CORR keys.");
            }
            if (value != null) {
                out.put(key, kept(CORRELATION_VALUE, value, "not recorded"));
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Prints what identifies the run and nothing else. A draft holds the real member ID, the date
     * of service and, for PAS, a real authorization number.
     */
    @Override
    public String toString() {
        return "RunRecord[" + runId + ", " + protocol + ", " + operation + ", " + outcome + "]";
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.runId = runId;
        b.timestamp = timestamp;
        b.protocol = protocol;
        b.operation = operation;
        b.target = target;
        b.profile = profile;
        b.environment = environment;
        b.environmentKind = environmentKind;
        b.scenarioId = scenarioId;
        b.procedureCodes = procedureCodes;
        b.lineOfBusiness = lineOfBusiness;
        b.outcome = outcome;
        b.coverageCodes = coverageCodes;
        b.delegatedVendor = delegatedVendor;
        b.httpStatus = httpStatus;
        b.elapsedMillis = elapsedMillis;
        b.errorType = errorType;
        b.expected = expected;
        b.result = result;
        b.note = note;
        b.testerRef = testerRef;
        b.chainId = chainId;
        b.suiteRunId = suiteRunId;
        b.caseId = caseId;
        b.replayOfRunId = replayOfRunId;
        b.hook = hook;
        b.attachmentMode = attachmentMode;
        b.nextAction = nextAction;
        b.targetSource = targetSource;
        b.memberId = memberId;
        b.memberToken = memberToken;
        b.memberIdLast4 = memberIdLast4;
        b.npi = npi;
        b.dateOfService = dateOfService;
        b.correlation = correlation;
        b.findings = findings;
        b.unrecordedInputs = unrecordedInputs;
        b.slow = slow;
        return b;
    }

    public static final class Builder {
        private String runId;
        private String timestamp;
        private Protocol protocol;
        private EpaOperation operation;
        private String target;
        private String profile;
        private String environment;
        private EnvironmentKind environmentKind;
        private String scenarioId;
        private List<String> procedureCodes = List.of();
        private String lineOfBusiness;
        private String outcome;
        private List<String> coverageCodes = List.of();
        private String delegatedVendor;
        private Integer httpStatus;
        private long elapsedMillis;
        private String errorType = OnyxFailureType.NONE.name();
        private String expected;
        private TestResult result;
        private String note;
        private String testerRef;
        private String chainId;
        private String suiteRunId;
        private String caseId;
        private String replayOfRunId;
        private String hook;
        private String attachmentMode;
        private String nextAction;
        private String targetSource;
        private String memberId;
        private String memberToken;
        private String memberIdLast4;
        private String npi;
        private String dateOfService;
        private Map<String, String> correlation = Map.of();
        private List<String> findings = List.of();
        private List<String> unrecordedInputs = List.of();
        private boolean slow;

        private Builder() {
        }

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder timestamp(String value) {
            this.timestamp = value;
            return this;
        }

        public Builder protocol(Protocol value) {
            this.protocol = value;
            return this;
        }

        public Builder operation(EpaOperation value) {
            this.operation = value;
            return this;
        }

        public Builder target(String value) {
            this.target = value;
            return this;
        }

        public Builder profile(String value) {
            this.profile = value;
            return this;
        }

        public Builder environment(EnvironmentRef value) {
            this.environment = value == null ? null : value.name();
            this.environmentKind = value == null ? null : value.kind();
            return this;
        }

        public Builder environment(String name, EnvironmentKind kind) {
            this.environment = name;
            this.environmentKind = kind;
            return this;
        }

        public Builder scenarioId(String value) {
            this.scenarioId = value;
            return this;
        }

        public Builder procedureCodes(List<String> value) {
            this.procedureCodes = value;
            return this;
        }

        public Builder lineOfBusiness(String value) {
            this.lineOfBusiness = value;
            return this;
        }

        public Builder outcome(String value) {
            this.outcome = value;
            return this;
        }

        public Builder outcome(OutcomeCode value) {
            this.outcome = value == null ? null : value.name();
            return this;
        }

        public Builder coverageCodes(List<String> value) {
            this.coverageCodes = value;
            return this;
        }

        public Builder delegatedVendor(String value) {
            this.delegatedVendor = value;
            return this;
        }

        public Builder httpStatus(Integer value) {
            this.httpStatus = value;
            return this;
        }

        public Builder elapsedMillis(long value) {
            this.elapsedMillis = value;
            return this;
        }

        public Builder errorType(OnyxFailureType value) {
            this.errorType = value == null ? OnyxFailureType.NONE.name() : value.name();
            return this;
        }

        public Builder errorType(String value) {
            this.errorType = value;
            return this;
        }

        public Builder expected(String value) {
            this.expected = value;
            return this;
        }

        public Builder result(TestResult value) {
            this.result = value;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
            return this;
        }

        public Builder testerRef(String value) {
            this.testerRef = value;
            return this;
        }

        public Builder chainId(String value) {
            this.chainId = value;
            return this;
        }

        public Builder suiteRunId(String value) {
            this.suiteRunId = value;
            return this;
        }

        public Builder caseId(String value) {
            this.caseId = value;
            return this;
        }

        public Builder replayOfRunId(String value) {
            this.replayOfRunId = value;
            return this;
        }

        public Builder hook(String value) {
            this.hook = value;
            return this;
        }

        public Builder attachmentMode(String value) {
            this.attachmentMode = value;
            return this;
        }

        public Builder nextAction(String value) {
            this.nextAction = value;
            return this;
        }

        public Builder nextAction(NextAction value) {
            this.nextAction = value == null ? null : value.name();
            return this;
        }

        public Builder targetSource(String value) {
            this.targetSource = value;
            return this;
        }

        public Builder memberId(String value) {
            this.memberId = value;
            return this;
        }

        public Builder memberToken(String value) {
            this.memberToken = value;
            return this;
        }

        public Builder memberIdLast4(String value) {
            this.memberIdLast4 = value;
            return this;
        }

        public Builder npi(String value) {
            this.npi = value;
            return this;
        }

        public Builder dateOfService(String value) {
            this.dateOfService = value;
            return this;
        }

        public Builder correlation(Map<String, String> value) {
            this.correlation = value;
            return this;
        }

        public Builder findings(List<String> value) {
            this.findings = value;
            return this;
        }

        public Builder unrecordedInputs(List<String> value) {
            this.unrecordedInputs = value;
            return this;
        }

        public Builder slow(boolean value) {
            this.slow = value;
            return this;
        }

        /** Builds the record, refusing one that could not be traced or compared later. */
        public RunRecord build() {
            return new RunRecord(TYPE_RUN, runId, timestamp, protocol, operation, target, profile, environment,
                    environmentKind, scenarioId, procedureCodes, lineOfBusiness, outcome, coverageCodes, delegatedVendor,
                    httpStatus, elapsedMillis, errorType, expected, result, note, testerRef, chainId, suiteRunId, caseId,
                    replayOfRunId, hook, attachmentMode, nextAction, targetSource, memberId, memberToken, memberIdLast4, npi, dateOfService, correlation,
                    findings, unrecordedInputs, slow);
        }

    }
}
