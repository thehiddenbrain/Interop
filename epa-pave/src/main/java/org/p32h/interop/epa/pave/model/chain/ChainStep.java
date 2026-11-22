package org.p32h.interop.epa.pave.model.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * One of the four steps of the journey as the Workbench's stepper draws it: Coverage check,
 * Questionnaire, Submit and Status. Kept beside the three stages, which stay as they are, because
 * PAS is two steps on screen: a submission is answered once, and its status is asked for, or
 * notified, afterwards.
 *
 * @param step        which step, the stable code the page keys on
 * @param name        the step's name on screen
 * @param protocol    the protocol whose runs make up the step
 * @param state       where the step stands, in the states the stages use
 * @param chip        the chip under the step's name
 * @param stateLine   one sentence saying where the step stands and, when it has not run, why
 * @param latestRunId the most recent run of the step, which the page opens. Null when it has not run
 * @param nextAction  what the journey calls for from this step: the latest run's next action, or,
 *                    for a step that has not run and is next or not needed, the action of the run
 *                    that placed it so. Null when nothing names one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainStep(Step step, String name, Protocol protocol, ChainStageState state, Chip chip, String stateLine, String latestRunId,
                        NextAction nextAction) {

    /** The four steps, in order, with their names and the protocol of their runs. */
    public enum Step {
        COVERAGE("Coverage check", Protocol.CRD),
        QUESTIONNAIRE("Questionnaire", Protocol.DTR),
        SUBMIT("Submit", Protocol.PAS),
        STATUS("Status", Protocol.PAS);

        private final String words;
        private final Protocol protocol;

        Step(String words, Protocol protocol) {
            this.words = words;
            this.protocol = protocol;
        }

        public String words() {
            return words;
        }

        public Protocol protocol() {
            return protocol;
        }

        /**
         * The step a run belongs to, or null for a run of no step, such as a lookup or a request sent
         * as written. A subscription call and a notification belong to Status: they are how the status
         * of a submission reaches the tester.
         */
        public static Step of(EpaOperation operation) {
            if (operation == null) {
                return null;
            }
            return switch (operation) {
                case CRD_ORDER_SIGN, CRD_ORDER_SELECT, CRD_DISCOVERY -> COVERAGE;
                case DTR_QUESTIONNAIRE_PACKAGE, DTR_NEXT_QUESTION, DTR_RESPONSE -> QUESTIONNAIRE;
                case PAS_SUBMIT -> SUBMIT;
                case PAS_INQUIRE, SUBSCRIPTION_CREATE, SUBSCRIPTION_GET, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE,
                     SUBSCRIPTION_NOTIFICATION -> STATUS;
                // A lookup, a request sent as written, and an operation added later belong to no step until one is decided for
                // them: shown among the chain's other runs, never drawn into a step they may not be.
                default -> null;
            };
        }

        /** The steps in their order. */
        public static List<Step> inOrder() {
            return List.of(values());
        }
    }

    /**
     * The chip under a step's name.
     *
     * @param text its words
     * @param tone its tone, or null for a step with no run, which the page draws dashed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chip(String text, Tone tone) {

        /** Prints the tone only. The words are the tool's own, or an outcome code as a ledger line wrote it. */
        @Override
        public String toString() {
            return "Chip[" + tone + "]";
        }
    }

    /** Prints which step and where it stands. The line names outcomes as a ledger line wrote them. */
    @Override
    public String toString() {
        return "ChainStep[" + step + ", " + state + "]";
    }
}
