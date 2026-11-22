package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ScenarioSummary;
import tools.jackson.databind.JsonNode;

/**
 * The scenario picker shows each bundled scenario in a few words (plan 7.5): every file states them,
 * they fit, the summary serves them as {@code short}, and a file whose short words are not text is
 * refused rather than drawn as nothing.
 */
class ScenarioShortLabelsTest {

    private static final String MINIMAL = "{\"id\":\"x-1\",\"number\":1,\"label\":\"L\",\"httpStatus\":200,\"response\":{\"cards\":[]}";

    @Test
    void everyBundledScenarioHasShortWordsOfAtMost32AsciiCharacters() {
        ScenarioRepository bundled = new ScenarioRepository(AppProperties.defaults());
        int read = 0;
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            ScenarioRepository.Loaded loaded = bundled.load(protocol);
            assertThat(loaded.problems()).as(protocol).isEmpty();
            for (Scenario scenario : loaded.scenarios()) {
                String words = scenario.summary().shortLabel();
                assertThat(words).as(scenario.source()).isNotBlank();
                assertThat(words.length()).as(scenario.source()).isLessThanOrEqualTo(32);
                assertThat(words.chars().allMatch(c -> c >= 0x20 && c < 0x7f)).as(scenario.source()).isTrue();
                read++;
            }
        }
        assertThat(read).isEqualTo(ScenarioRepository.bundledIds().size());
    }

    @Test
    void theWordsAreThePlansWordForWord() {
        ScenarioRepository bundled = new ScenarioRepository(AppProperties.defaults());
        Map<String, String> byId = new HashMap<>();
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            bundled.load(protocol).scenarios().forEach(scenario -> byId.put(scenario.id(), scenario.shortLabel()));
        }
        assertThat(byId).containsEntry("crd-01-pa-required", "prior authorization required")
                .containsEntry("crd-11-bad-request-400", "rejected, HTTP 400")
                .containsEntry("dtr-14-adaptive-loop-fault", "fails part way")
                .containsEntry("pas-10-subscription-lifecycle", "subscription created and deleted")
                .containsEntry("pas-12-notification-unmatched", "notification matching nothing")
                .containsEntry("raw-01-not-modelled", "unmapped request");
    }

    @Test
    void theSummaryServesTheWordsAsShortAndLeavesThemOutWhenAFileStatesNone() {
        ScenarioSummary with = ScenarioRepository.parse("x.json", MINIMAL + ",\"short\":\"a few words\"}").summary();
        JsonNode json = JsonSafe.mapper().valueToTree(with);
        assertThat(json.get("short").asString()).isEqualTo("a few words");
        assertThat(json.has("shortLabel")).isFalse();
        JsonNode without = JsonSafe.mapper().valueToTree(ScenarioRepository.parse("x.json", MINIMAL + "}").summary());
        assertThat(without.has("short")).isFalse();
    }

    @Test
    void shortWordsThatAreNotTextAreRefused() {
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", MINIMAL + ",\"short\":3}"))
                .hasMessage("its short is not a piece of text.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", MINIMAL + ",\"short\":\"  \"}"))
                .hasMessage("its short is not a piece of text.");
    }
}
