package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The result of one run, in the same shape for every protocol.
 *
 * <p>The components follow the order of the result view: the verdict, the test result, the next
 * step, the determination grid, the protocol detail, the findings, the additional fields, the
 * wire view and the error. Two of them answer different questions and are never merged:
 * {@code verdict} is what the service said, {@code expectation} is whether that matched what the
 * tester expected.
 *
 * <p>Construct with {@link #builder()}.
 *
 * <p>The verdict, the test result, the next step and the ledger receipt are always present. The
 * rest depends on how far the run got, and the screen must not assume more:
 * <ul>
 *   <li>a run the production guardrail refused has no {@code detail}, no {@code timing} and a
 *       {@code wire} whose response is absent, because nothing was sent</li>
 *   <li>a run in which this tool failed after the call has no {@code detail} and no
 *       {@code determination}, but does carry the {@code wire} and the {@code timing}</li>
 *   <li>a call that got no response, such as a timeout, has a {@code wire} whose response says so</li>
 * </ul>
 * A null is left out of the JSON, never written as null.
 *
 * @param <D>              the protocol's own detail type
 * @param additionalFields everything the response carried that the designed view did not use
 * @param error            set when the call failed, was refused, or the tool failed after it
 * @param ledger           what the ledger did with this run. Checked by the screen on every result
 * @param answeredBy       who answered: the service, a mock scenario, or nobody. The strip draws
 *                         the mock's hatch, or the mark of a step nobody answered, from this alone.
 *                         Worked out by {@link AnsweredBy#of} when the builder is not told
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunEnvelope<D>(
        String runId,
        String chainId,
        Protocol protocol,
        EpaOperation operation,
        EnvironmentRef environment,
        String profile,
        AnsweredBy answeredBy,
        VerdictView verdict,
        ExpectationView expectation,
        NextStepView nextStep,
        List<DeterminationRow> determination,
        D detail,
        List<Finding> findings,
        List<AdditionalField> additionalFields,
        WireView wire,
        ErrorView error,
        LedgerReceipt ledger,
        Timing timing) {

    public RunEnvelope {
        determination = determination == null ? List.of() : List.copyOf(determination);
        findings = findings == null ? List.of() : List.copyOf(findings);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    public static <D> Builder<D> builder() {
        return new Builder<>();
    }

    /** Redacted, because the wire view and the detail carry member data. */
    @Override
    public String toString() {
        return "RunEnvelope[runId=" + runId + ", protocol=" + protocol + ", operation=" + operation + "]";
    }

    public static final class Builder<D> {
        private String runId;
        private String chainId;
        private Protocol protocol;
        private EpaOperation operation;
        private EnvironmentRef environment;
        private String profile;
        private AnsweredBy answeredBy;
        private VerdictView verdict;
        private ExpectationView expectation;
        private NextStepView nextStep;
        private List<DeterminationRow> determination = List.of();
        private D detail;
        private List<Finding> findings = List.of();
        private List<AdditionalField> additionalFields = List.of();
        private WireView wire;
        private ErrorView error;
        private LedgerReceipt ledger;
        private Timing timing;

        private Builder() {
        }

        public Builder<D> runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder<D> chainId(String value) {
            this.chainId = value;
            return this;
        }

        public Builder<D> protocol(Protocol value) {
            this.protocol = value;
            return this;
        }

        public Builder<D> operation(EpaOperation value) {
            this.operation = value;
            return this;
        }

        public Builder<D> environment(EnvironmentRef value) {
            this.environment = value;
            return this;
        }

        public Builder<D> profile(String value) {
            this.profile = value;
            return this;
        }

        public Builder<D> answeredBy(AnsweredBy value) {
            this.answeredBy = value;
            return this;
        }

        public Builder<D> verdict(VerdictView value) {
            this.verdict = value;
            return this;
        }

        public Builder<D> expectation(ExpectationView value) {
            this.expectation = value;
            return this;
        }

        public Builder<D> nextStep(NextStepView value) {
            this.nextStep = value;
            return this;
        }

        public Builder<D> determination(List<DeterminationRow> value) {
            this.determination = value;
            return this;
        }

        public Builder<D> detail(D value) {
            this.detail = value;
            return this;
        }

        public Builder<D> findings(List<Finding> value) {
            this.findings = value;
            return this;
        }

        public Builder<D> additionalFields(List<AdditionalField> value) {
            this.additionalFields = value;
            return this;
        }

        public Builder<D> wire(WireView value) {
            this.wire = value;
            return this;
        }

        public Builder<D> error(ErrorView value) {
            this.error = value;
            return this;
        }

        public Builder<D> ledger(LedgerReceipt value) {
            this.ledger = value;
            return this;
        }

        public Builder<D> timing(Timing value) {
            this.timing = value;
            return this;
        }

        /**
         * Builds the envelope, refusing one that would leave a tester unable to answer what happened,
         * whether it matched and what to do next.
         */
        public RunEnvelope<D> build() {
            if (Texts.blank(runId) || protocol == null || operation == null) {
                throw new IllegalStateException("A run result needs a run id, a protocol and an operation.");
            }
            if (verdict == null || expectation == null || nextStep == null || ledger == null) {
                throw new IllegalStateException("A run result needs a verdict, an expectation, a next step and a ledger receipt.");
            }
            // The same rule as the ledger line's, and the same test of a refusal the screen applies.
            boolean refused = verdict.tone() == Tone.BLOCKED || (error != null && error.kind() == ErrorKind.BLOCKED);
            AnsweredBy who = answeredBy != null ? answeredBy : AnsweredBy.of(operation, profile, refused);
            return new RunEnvelope<>(runId, chainId, protocol, operation, environment, profile, who, verdict, expectation,
                    nextStep, determination, detail, findings, additionalFields, wire, error, ledger, timing);
        }
    }
}
