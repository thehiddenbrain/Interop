package com.thehiddenbrain.interop.epa.pave.model;

import java.util.List;

/**
 * One test case described by its inputs alone, so it can be run again without a browser. A suite
 * row is one of these, and so is a ledger record being replayed.
 *
 * <p>It carries identifiers only. Member name and date of birth are resolved again from the source
 * of truth when the case runs, which is why a case can be stored without storing member data.
 *
 * <p>Construct with {@link #builder()}.
 *
 * @param chain     true to run the whole journey, CRD then DTR then PAS, as far as each step allows
 * @param operation the single operation to run when {@code chain} is false
 */
public record CaseSpec(
        String caseId,
        boolean chain,
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
        String replayOfRunId) {

    public CaseSpec {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
    }

    public static Builder builder() {
        return new Builder();
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

        public CaseSpec build() {
            return new CaseSpec(caseId, chain, operation, memberId, npi, procedureCodes, dateOfService, scenarioId, expected,
                    testerRef, note, chainId, suiteRunId, replayOfRunId);
        }
    }
}
