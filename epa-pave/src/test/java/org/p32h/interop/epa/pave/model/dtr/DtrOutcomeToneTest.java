package org.p32h.interop.epa.pave.model.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Tone;

/** Orange means the tester has something to put right on this step, and nothing else on DTR. */
class DtrOutcomeToneTest {

    @Test
    void aStepThatWentRightIsNeutral() {
        assertThat(EnumSet.of(DtrOutcome.PACKAGE_LOADED, DtrOutcome.NEXT_QUESTION, DtrOutcome.RESPONSE_COMPLETED,
                DtrOutcome.NOTHING_REQUIRED)).allSatisfy(outcome -> assertThat(outcome.tone()).isEqualTo(Tone.NEUTRAL));
    }

    @Test
    void onlyAResponseLeftIncompleteAsksTheTesterToAct() {
        Set<DtrOutcome> action = EnumSet.noneOf(DtrOutcome.class);
        Arrays.stream(DtrOutcome.values()).filter(outcome -> outcome.tone() == Tone.ACTION).forEach(action::add);

        assertThat(action).containsExactlyInAnyOrder(DtrOutcome.RESPONSE_NOT_COMPLETE, DtrOutcome.REQUIRED_UNANSWERED);
    }

    @Test
    void noOutcomeThatIsNotAFaultIsDrawnAsOne() {
        assertThat(DtrOutcome.values()).filteredOn(outcome -> !outcome.fault())
                .allSatisfy(outcome -> assertThat(outcome.tone()).isNotEqualTo(Tone.FAULT));
    }
}
