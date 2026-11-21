package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One test case described by its inputs alone, so it can be run again without a browser. A suite
 * row is one of these, and so is a ledger record being replayed.
 *
 * <p>It carries identifiers only. Member name and date of birth are resolved again from the source
 * of truth when the case runs, which is why a case can be stored without storing member data.
 *
 * <p>Construct with {@link #builder()}.
 *
 * @param chain       true to run the whole journey, CRD then DTR then PAS, as far as each step
 *                    allows. A row that leaves it out is not a chain
 * @param operation   the single operation to run when {@code chain} is false. For a chain, the
 *                    CRD hook it starts with, where null means order-sign
 * @param correlation identifiers a follow-up read needs, keyed by the RunRecord CORR constants:
 *                    the claim id for a PAS inquiry, the subscription id for a subscription read
 * @param configEpoch the configuration epoch the case must run under, or null to not check. A
 *                    suite pins the epoch it started under, so its cases cannot drift to another
 *                    environment part way through
 * @param unrecognised every key of a suite row this tool does not know. A case that has any is
 *                    reported as invalid, naming them: a misspelt {@code expected} would otherwise
 *                    make every row unverified without a word
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseSpec(
        String caseId,
        Boolean chain,
        EpaOperation operation,
        String memberId,
        String npi,
        List<String> procedureCodes,
        String dateOfService,
        String scenarioId,
        String expected,
        String testerRef,
        String note,
        String chainId,
        String suiteRunId,
        String replayOfRunId,
        Map<String, String> correlation,
        Long configEpoch,
        @JsonAnySetter Map<String, Object> unrecognised) {

    public CaseSpec {
        // Boxed, because Jackson 3 refuses to bind a missing property to a primitive and a suite
        // row may leave this out. Normalised here so callers never see a null.
        chain = chain != null && chain;
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        correlation = correlation == null ? Map.of() : Map.copyOf(correlation);
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder holding this case, for a copy that differs in a component or two. A key the case
     * did not recognise is not carried over, so a copy is only ever made of a case that had none.
     */
    public Builder toBuilder() {
        if (!unrecognised.isEmpty()) {
            throw new IllegalStateException("A case with keys this tool does not know is not copied. It is reported as invalid.");
        }
        Builder b = new Builder();
        b.caseId = caseId;
        b.chain = chain;
        b.operation = operation;
        b.memberId = memberId;
        b.npi = npi;
        b.procedureCodes = procedureCodes;
        b.dateOfService = dateOfService;
        b.scenarioId = scenarioId;
        b.expected = expected;
        b.testerRef = testerRef;
        b.note = note;
        b.chainId = chainId;
        b.suiteRunId = suiteRunId;
        b.replayOfRunId = replayOfRunId;
        b.correlation = correlation;
        b.configEpoch = configEpoch;
        return b;
    }

    /** Redacted, because it carries a member ID. */
    @Override
    public String toString() {
        return "CaseSpec[caseId=" + caseId + ", operation=" + operation + ", chain=" + chain + "]";
    }

    public static final class Builder {
        private String caseId;
        private boolean chain;
        private EpaOperation operation;
        private String memberId;
        private String npi;
        private List<String> procedureCodes = List.of();
        private String dateOfService;
        private String scenarioId;
        private String expected;
        private String testerRef;
        private String note;
        private String chainId;
        private String suiteRunId;
        private String replayOfRunId;
        private Map<String, String> correlation = Map.of();
        private Long configEpoch;

        private Builder() {
        }

        public Builder caseId(String value) {
            this.caseId = value;
            return this;
        }

        public Builder chain(boolean value) {
            this.chain = value;
            return this;
        }

        public Builder operation(EpaOperation value) {
            this.operation = value;
            return this;
        }

        public Builder memberId(String value) {
            this.memberId = value;
            return this;
        }

        public Builder npi(String value) {
            this.npi = value;
            return this;
        }

        public Builder procedureCodes(List<String> value) {
            this.procedureCodes = value;
            return this;
        }

        public Builder dateOfService(String value) {
            this.dateOfService = value;
            return this;
        }

        public Builder scenarioId(String value) {
            this.scenarioId = value;
            return this;
        }

        public Builder expected(String value) {
            this.expected = value;
            return this;
        }

        public Builder testerRef(String value) {
            this.testerRef = value;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
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

        public Builder replayOfRunId(String value) {
            this.replayOfRunId = value;
            return this;
        }

        public Builder correlation(Map<String, String> value) {
            this.correlation = value;
            return this;
        }

        public Builder configEpoch(Long value) {
            this.configEpoch = value;
            return this;
        }

        public CaseSpec build() {
            return new CaseSpec(caseId, chain, operation, memberId, npi, procedureCodes, dateOfService, scenarioId, expected,
                    testerRef, note, chainId, suiteRunId, replayOfRunId, correlation, configEpoch, Map.of());
        }
    }
}
