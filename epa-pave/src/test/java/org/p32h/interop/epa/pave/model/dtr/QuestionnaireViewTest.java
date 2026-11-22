package org.p32h.interop.epa.pave.model.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import tools.jackson.databind.JsonNode;

/**
 * The questionnaire's chip names its kind in two words (plan 7.5, {@code kindShort}), taken from the
 * kind on every constructor, and names nothing when this tool could not tell the kind.
 */
class QuestionnaireViewTest {

    private static QuestionnaireView of(QuestionnaireKind kind) {
        return new QuestionnaireView(1, "entry[0]", "A form", null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, kind, "basis", 0, 0, null, null, null, null, null, null, null, "{}");
    }

    @Test
    void eachKindHasItsShortWordsAndAnUndeterminedKindHasNone() {
        assertThat(of(QuestionnaireKind.STATIC).kindShort()).isEqualTo("static form");
        assertThat(of(QuestionnaireKind.ADAPTIVE).kindShort()).isEqualTo("adaptive form");
        assertThat(of(QuestionnaireKind.UNDETERMINED).kindShort()).isNull();
        assertThat(of(null).kindShort()).isNull();
    }

    @Test
    void theShortWordsAreOnTheWireAndLeftOffWhenThereAreNone() {
        JsonNode adaptive = JsonSafe.mapper().valueToTree(of(QuestionnaireKind.ADAPTIVE));
        assertThat(adaptive.get("kindShort").asString()).isEqualTo("adaptive form");
        assertThat(JsonSafe.mapper().valueToTree(of(QuestionnaireKind.UNDETERMINED)).has("kindShort")).isFalse();
    }
}
