package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.chain.ChainService;
import tools.jackson.databind.JsonNode;

/**
 * The words for every next action, served where the History tab reads the outcome vocabulary, and
 * the same words every other screen uses for the code.
 */
class NextActionVocabularyTest {

    @Test
    void everyNextActionIsServedWithItsWordsOnEveryScenarioList() {
        JsonNode list = JsonSafe.mapper().valueToTree(new ScenarioList(false, "live", null, null, null, null));
        JsonNode actions = list.get("nextActions");
        assertThat(actions).hasSize(NextAction.values().length);
        for (int i = 0; i < NextAction.values().length; i++) {
            NextAction action = NextAction.values()[i];
            assertThat(actions.get(i).get("code").asString()).isEqualTo(action.name());
            assertThat(actions.get(i).get("label").asString()).as(action.name()).isEqualTo(action.label()).isNotBlank();
            assertThat(actions.get(i).propertyNames()).containsExactly("code", "label");
        }
    }

    @Test
    void theChainViewAndTheServedVocabularyWordAnActionAlike() {
        assertThat(Arrays.stream(NextAction.values())).allSatisfy(action ->
                assertThat(ChainService.instruction(action)).isEqualTo(NextActionOption.of(action).label()));
        // A replay or a suite refused on production is never told to unlock.
        assertThat(NextAction.SEND_ON_ITS_OWN.label()).doesNotStartWith("Unlock")
                .contains("A replay or a suite never carries the production unlock");
    }
}
