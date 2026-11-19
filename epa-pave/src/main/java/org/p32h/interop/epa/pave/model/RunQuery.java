package org.p32h.interop.epa.pave.model;

import java.time.LocalDate;

/**
 * A question to the ledger. Every component is optional; a null does not filter.
 *
 * <p>Construct with a factory or {@link #builder()}, never with the constructor, so adding a
 * filter later does not break callers.
 *
 * @param from             first day to include
 * @param to               last day to include
 * @param correlationKey   one of the RunRecord CORR constants
 * @param correlationValue the raw identifier to match under that key. The ledger applies its
 *                         storage policy before comparing, so this is never a token
 * @param limit            the most records to return, newest first. Null or zero for no limit
 */
public record RunQuery(LocalDate from, LocalDate to, Protocol protocol, TestResult result, String outcome, String chainId,
                       String suiteRunId, String correlationKey, String correlationValue, Integer limit) {

    public static RunQuery all() {
        return builder().build();
    }

    public static RunQuery between(LocalDate from, LocalDate to) {
        return builder().from(from).to(to).build();
    }

    public static RunQuery forChain(String chainId) {
        return builder().chainId(chainId).build();
    }

    public static RunQuery forSuite(String suiteRunId) {
        return builder().suiteRunId(suiteRunId).build();
    }

    /** Runs carrying one correlation identifier, such as the run that created a subscription. */
    public static RunQuery correlated(String key, String value) {
        return builder().correlation(key, value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because a correlation value can be an authorization number. */
    @Override
    public String toString() {
        return "RunQuery[from=" + from + ", to=" + to + ", protocol=" + protocol + ", result=" + result + "]";
    }

    public static final class Builder {
        private LocalDate from;
        private LocalDate to;
        private Protocol protocol;
        private TestResult result;
        private String outcome;
        private String chainId;
        private String suiteRunId;
        private String correlationKey;
        private String correlationValue;
        private Integer limit;

        private Builder() {
        }

        public Builder from(LocalDate value) {
            this.from = value;
            return this;
        }

        public Builder to(LocalDate value) {
            this.to = value;
            return this;
        }

        public Builder protocol(Protocol value) {
            this.protocol = value;
            return this;
        }

        public Builder result(TestResult value) {
            this.result = value;
            return this;
        }

        public Builder outcome(String value) {
            this.outcome = value;
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

        public Builder correlation(String key, String value) {
            this.correlationKey = key;
            this.correlationValue = value;
            return this;
        }

        public Builder limit(Integer value) {
            this.limit = value;
            return this;
        }

        public RunQuery build() {
            return new RunQuery(from, to, protocol, result, outcome, chainId, suiteRunId, correlationKey, correlationValue, limit);
        }
    }
}
