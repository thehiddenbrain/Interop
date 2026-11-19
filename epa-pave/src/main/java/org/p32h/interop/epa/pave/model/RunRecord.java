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
 * or any request or response JSON, and none may be added. The ledger is not a store of member
 * data, and the surest way to keep it that way is for the record to have nowhere to put any.
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
        "chainId", "suiteRunId", "caseId", "replayOfRunId", "hook", "attachmentMode", "targetSource", "memberId", "memberToken",
        "memberIdLast4", "npi",
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

    public RunRecord {
        // Boxed, not primitive, because Jackson 3 refuses to bind a missing property to a primitive
        // and a line without these two must still be readable. They are normalised here so callers
        // never see a null.
        elapsedMillis = elapsedMillis == null ? Long.valueOf(0L) : elapsedMillis;
        slow = slow != null && slow;
        recordType = recordType == null ? TYPE_RUN : recordType;
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        coverageCodes = coverageCodes == null ? List.of() : List.copyOf(coverageCodes);
        correlation = correlation == null ? Map.of() : Map.copyOf(correlation);
        findings = findings == null ? List.of() : List.copyOf(findings);
        unrecordedInputs = unrecordedInputs == null ? List.of() : List.copyOf(unrecordedInputs);
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
            require(runId, "runId");
            require(timestamp, "timestamp");
            require(outcome, "outcome");
            if (protocol == null || operation == null || result == null) {
                throw new IllegalStateException("A run record needs a protocol, an operation and a result.");
            }
            return new RunRecord(TYPE_RUN, runId, timestamp, protocol, operation, target, profile, environment,
                    environmentKind, scenarioId, procedureCodes, lineOfBusiness, outcome, coverageCodes, delegatedVendor,
                    httpStatus, elapsedMillis, errorType, expected, result, note, testerRef, chainId, suiteRunId, caseId,
                    replayOfRunId, hook, attachmentMode, targetSource, memberId, memberToken, memberIdLast4, npi, dateOfService, correlation,
                    findings, unrecordedInputs, slow);
        }

        private static void require(String value, String name) {
            if (Texts.blank(value)) {
                throw new IllegalStateException("A run record needs a " + name + ".");
            }
        }
    }
}
