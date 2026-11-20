package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.dtr.ConditionState;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;

/**
 * The words a DTR screen shows beside a code, sent by the server so that the screen keeps no copy
 * of the server's vocabulary (DTR review R05 and R16).
 */
class DtrScreenWordsTest {

    @Test
    void everyResponseKindHasASentenceEvenWhereTheReadingHadNothingMoreParticularToSay() {
        for (DtrResponseKind kind : DtrResponseKind.values()) {
            DtrDetail detail = new DtrDetail(kind, null, null, null, null, null, null, null, null, null, null, null, true, true);
            assertThat(detail.responseNote()).as(kind.name()).isNotBlank().isEqualTo(kind.note());
        }
        // A sentence the reading did give is kept.
        assertThat(new DtrDetail(DtrResponseKind.BUNDLE, "The package arrived as a Bundle.", null, null, null, null, null, null, null, null,
                null, null, true, true).responseNote()).isEqualTo("The package arrived as a Bundle.");
        assertThat(new HashSet<>(Arrays.stream(DtrResponseKind.values()).map(DtrResponseKind::note).toList()))
                .hasSize(DtrResponseKind.values().length);
    }

    @Test
    void aConditionCarriesTheWordsForItsState() {
        for (ConditionState state : ConditionState.values()) {
            EnableWhenView condition = new EnableWhenView(Shown.of("q1"), null, Shown.of("true"), state, "Because.");
            assertThat(condition.stateLabel()).as(state.name()).isEqualTo(state.label());
        }
        assertThat(JsonSafe.mapper().valueToTree(new EnableWhenView(Shown.of("q1"), null, Shown.of("true"), ConditionState.NOT_EVALUATED,
                "Because.")).get("stateLabel").asString()).isEqualTo("The condition could not be evaluated");
    }

    @Test
    void everyValueCarriedToTheNextStepIsNamedInWordsAndNothingCarriedHasNoLabels() {
        NextStepView next = new NextStepView(NextAction.START_PAS, "Next.", "Because.", Map.of(
                NextStepView.CARRY_DTR_LINK_URL, List.of("https://example.org/p32h-placeholder/launch"),
                NextStepView.CARRY_DOC_PURPOSE, List.of("withpa"),
                NextStepView.CARRY_RUN_ID, List.of()));
        assertThat(next.carryLabels()).containsOnlyKeys(NextStepView.CARRY_DTR_LINK_URL, NextStepView.CARRY_DOC_PURPOSE)
                .containsEntry(NextStepView.CARRY_DTR_LINK_URL, "Address of the DTR app the payer's card links to")
                .containsEntry(NextStepView.CARRY_DOC_PURPOSE, "What CRD said the documentation is for");
        assertThat(NextStepView.of(NextAction.REVIEW, "Next.", "Because.").carryLabels()).isNull();
        assertThat(JsonSafe.mapper().valueToTree(NextStepView.of(NextAction.REVIEW, "Next.", "Because.")).has("carryLabels")).isFalse();
        // Every key a step may carry has words of its own.
        for (var field : NextStepView.class.getFields()) {
            if (field.getName().startsWith("CARRY_") && field.getType() == String.class) {
                try {
                    assertThat(NextStepView.CARRY_LABELS).as(field.getName()).containsKey((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    @Test
    void aDroppedAnswerIsHeldWithItsQuestionAndPrintedNowhere() {
        QuestionRef dropped = new QuestionRef("q2", "Second").withValues(List.of("Placeholder answer"));
        assertThat(dropped.values()).containsExactly("Placeholder answer");
        assertThat(dropped.toString()).doesNotContain("Placeholder answer");
        assertThat(new QuestionRef("q2", "Second").values()).isNull();
    }
}
