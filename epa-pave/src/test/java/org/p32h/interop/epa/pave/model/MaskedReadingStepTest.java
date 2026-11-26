package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The marked next step reads as one sentence: what the rules give follows the colon, so its first
 * word loses its capital, and a word written in capitals throughout keeps them.
 */
class MaskedReadingStepTest {

    private static final MaskedReading READING = new MaskedReading(List.of("status was read as [secret statusWord]"));

    private static String marked(String headline) {
        return READING.on(new NextStepView(NextAction.NOTHING_FURTHER, headline, "Why.", Map.of())).headline();
    }

    @Test
    void theRulesSentenceGoesOnAfterTheColonInLowerCase() {
        assertThat(marked("The flow ends here. Record this result.")).isEqualTo(MaskedReading.STEP_FIRST
                + "the flow ends here. Record this result.");
        assertThat(marked("Nothing follows from a read. Record this result.")).isEqualTo(MaskedReading.STEP_FIRST
                + "nothing follows from a read. Record this result.");
        assertThat(marked("Next: read the response detail below.")).isEqualTo(MaskedReading.STEP_FIRST
                + "read the response detail below.");
    }

    @Test
    void aWordInCapitalsThroughoutKeepsThem() {
        assertThat(marked("PAS is next.")).isEqualTo(MaskedReading.STEP_FIRST + "PAS is next.");
        assertThat(marked("Next: DTR asks for the package.")).isEqualTo(MaskedReading.STEP_FIRST + "DTR asks for the package.");
    }

    @Test
    void whatTheStepOffersIsUnchanged() {
        NextStepView step = new NextStepView(NextAction.START_DTR, "Next: complete it.", "Why.",
                Map.of(NextStepView.CARRY_RUN_ID, List.of("run-placeholder")));
        NextStepView marked = READING.on(step);
        assertThat(marked.action()).isEqualTo(NextAction.START_DTR);
        assertThat(marked.reason()).isEqualTo("Why.");
        assertThat(marked.carry()).isEqualTo(step.carry());
    }
}
