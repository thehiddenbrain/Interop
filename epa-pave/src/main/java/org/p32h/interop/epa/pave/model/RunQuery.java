package org.p32h.interop.epa.pave.model;

import java.time.LocalDate;
import java.time.ZoneId;

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
 * @param testerRef        the tester reference a run carries, after its latest note. Compared exactly
 * @param zone             the clock {@code from} and {@code to} are days on. Null for the day each
 *                         run's own timestamp names, which is the day of the tool's clock it was made on
 * @param includeReplays   false to leave out the runs a replay made (those naming the run they replayed).
 *                         Null or true includes them, so every caller that does not ask is unchanged
 */
public record RunQuery(LocalDate from, LocalDate to, Protocol protocol, EpaOperation operation, TestResult result, String outcome,
                       String chainId, String suiteRunId, String correlationKey, String correlationValue, Integer limit,
                       String testerRef, ZoneId zone, Boolean includeReplays) {

    public static RunQuery all() {
        return builder().build();
    }

    public static RunQuery between(LocalDate from, LocalDate to) {
        return builder().from(from).to(to).build();
    }

    /** The runs of a range of days on the given clock, or on the days the runs' own timestamps name when the zone is null. */
    public static RunQuery between(LocalDate from, LocalDate to, ZoneId zone) {
        return builder().from(from).to(to).zone(zone).build();
    }

    /** The same question with no limit, to count every run that answers it. */
    public RunQuery withoutLimit() {
        return new RunQuery(from, to, protocol, operation, result, outcome, chainId, suiteRunId, correlationKey, correlationValue, null,
                testerRef, zone, includeReplays);
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
        return "RunQuery[from=" + from + ", to=" + to + ", protocol=" + protocol + ", result=" + result + ", includeReplays=" + includeReplays + "]";
    }

    public static final class Builder {
        private LocalDate from;
        private LocalDate to;
        private Protocol protocol;
        private EpaOperation operation;
        private TestResult result;
        private String outcome;
        private String chainId;
        private String suiteRunId;
        private String correlationKey;
        private String correlationValue;
        private Integer limit;
        private String testerRef;
        private ZoneId zone;
        private Boolean includeReplays;

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

        /** Only runs of this operation, for example the run that created a subscription and not every read of it. */
        public Builder operation(EpaOperation value) {
            this.operation = value;
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

        public Builder testerRef(String value) {
            this.testerRef = value;
            return this;
        }

        public Builder zone(ZoneId value) {
            this.zone = value;
            return this;
        }

        /** False leaves out the runs a replay made, so a list of the runs as first made is not swamped by its replays. */
        public Builder includeReplays(Boolean value) {
            this.includeReplays = value;
            return this;
        }

        public RunQuery build() {
            return new RunQuery(from, to, protocol, operation, result, outcome, chainId, suiteRunId, correlationKey, correlationValue, limit,
                    testerRef, zone, includeReplays);
        }
    }
}
