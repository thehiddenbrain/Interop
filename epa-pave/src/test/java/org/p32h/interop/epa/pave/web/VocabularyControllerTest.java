package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/vocabulary} as the page reads it (plan 7.1): every short word the screens show for
 * a code, in the shape and order the plan settled, so the page keeps no copy of any of them.
 */
class VocabularyControllerTest {

    private final MockMvc mvc = TestMvc.standalone(new VocabularyController());

    private JsonNode vocabulary() throws Exception {
        String body = mvc.perform(get("/api/vocabulary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(body).node();
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static List<String> texts(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : list) {
            out.add(item.has(field) ? item.get(field).asString() : null);
        }
        return out;
    }

    @Test
    void theAnswerHoldsEveryPartInThePlansOrder() throws Exception {
        assertThat(names(vocabulary())).containsExactly("steps", "stepStates", "outcomes", "nextActions", "testResults", "answeredBy");
    }

    @Test
    void theStepsAreTheFourOfTheJourneyWithTheirProtocols() throws Exception {
        JsonNode steps = vocabulary().get("steps");
        assertThat(texts(steps, "step")).containsExactly("COVERAGE", "QUESTIONNAIRE", "SUBMIT", "STATUS");
        assertThat(texts(steps, "name")).containsExactly("Coverage check", "Questionnaire", "Submit", "Status");
        assertThat(texts(steps, "protocol")).containsExactly("CRD", "DTR", "PAS", "PAS");
        assertThat(names(steps.get(0))).containsExactly("step", "name", "protocol");
    }

    @Test
    void aStepWithNoRunHasItsChipAndOnlyNextCarriesATone() throws Exception {
        JsonNode states = vocabulary().get("stepStates");
        assertThat(names(states)).containsExactly("NOT_RUN", "NOT_REACHED", "NOT_NEEDED", "NEXT");
        assertThat(states.get("NOT_RUN").get("text").asString()).isEqualTo("Not run");
        assertThat(states.get("NOT_REACHED").get("text").asString()).isEqualTo("Not run");
        assertThat(states.get("NOT_NEEDED").get("text").asString()).isEqualTo("Not needed");
        assertThat(states.get("NEXT").get("text").asString()).isEqualTo("Next");
        assertThat(states.get("NEXT").get("tone").asString()).isEqualTo("NEUTRAL");
        assertThat(states.get("NOT_RUN").has("tone")).isFalse();
        assertThat(states.get("NOT_NEEDED").has("tone")).isFalse();
    }

    @Test
    void everyOutcomeOfEveryGroupIsServedWithItsShortWordsAndTone() throws Exception {
        JsonNode outcomes = vocabulary().get("outcomes");
        assertThat(names(outcomes)).containsExactly("CRD", "DTR", "PAS", "SUBSCRIPTION", "NOTIFICATION", "RAW", "RUN");
        assertThat(outcomes.get("CRD").size()).isEqualTo(CrdOutcome.values().length);
        assertThat(outcomes.get("DTR").size()).isEqualTo(DtrOutcome.values().length);
        assertThat(outcomes.get("PAS").size()).isEqualTo(PasOutcome.values().length);
        assertThat(outcomes.get("SUBSCRIPTION").size()).isEqualTo(SubscriptionOutcome.values().length);
        assertThat(outcomes.get("NOTIFICATION").size()).isEqualTo(NotificationOutcome.values().length);
        assertThat(outcomes.get("RAW").size()).isEqualTo(RawOutcome.values().length);
        assertThat(outcomes.get("RUN").size()).isEqualTo(RunOutcome.values().length);
        for (String group : names(outcomes)) {
            for (JsonNode option : outcomes.get(group)) {
                assertThat(names(option)).as(group).containsExactly("code", "label", "short", "tone", "fault");
                assertThat(option.get("short").asString()).as(group).isNotBlank();
            }
        }
        JsonNode first = outcomes.get("CRD").get(0);
        assertThat(first.get("code").asString()).isEqualTo("AUTH_REQUIRED");
        assertThat(first.get("short").asString()).isEqualTo("PA required");
        assertThat(first.get("tone").asString()).isEqualTo("ACTION");
        assertThat(first.get("fault").asBoolean()).isFalse();
        assertThat(texts(outcomes.get("PAS"), "short")).contains("Mixed decisions");
        assertThat(texts(outcomes.get("CRD"), "short")).contains("Mixed answers");
    }

    @Test
    void everyNextActionIsListedAndOnlyTheOnesAButtonDoesCarryOne() throws Exception {
        JsonNode actions = vocabulary().get("nextActions");
        assertThat(actions.size()).isEqualTo(NextAction.values().length);
        assertThat(texts(actions, "code")).containsExactly(java.util.Arrays.stream(NextAction.values()).map(Enum::name).toArray(String[]::new));
        List<String> withButtons = new ArrayList<>();
        for (JsonNode action : actions) {
            if (action.has("button")) {
                withButtons.add(action.get("code").asString() + " " + action.get("button").asString());
            }
        }
        assertThat(withButtons).containsExactly("START_DTR Continue to the questionnaire",
                "ANSWER_NEXT_QUESTION Send answers and get the next question", "SUBMIT_RESPONSE Complete the response",
                "START_PAS Continue to Submit", "DELIVER_SAMPLE_NOTIFICATION Deliver a sample", "CHECK_STATUS Send inquiry",
                "OPEN_CONFIG Open Configuration", "UNLOCK_OR_SWITCH Open Production writes");
        for (JsonNode action : actions) {
            if (action.get("code").asString().equals("DELIVER_SAMPLE_NOTIFICATION")) {
                assertThat(action.get("label").asString()).isEqualTo("In the Status step, Deliver a sample for this submission.");
            }
        }
    }

    @Test
    void theTestResultsAndWhoAnsweredHaveTheirWords() throws Exception {
        JsonNode vocabulary = vocabulary();
        assertThat(texts(vocabulary.get("testResults"), "code")).containsExactly("PASS", "FAIL", "UNVERIFIED");
        assertThat(texts(vocabulary.get("testResults"), "label")).containsExactly("Passed", "Failed", "Unverified");
        assertThat(texts(vocabulary.get("answeredBy"), "code")).containsExactly("SCENARIO", "NOBODY", "NOT_SENT");
        assertThat(texts(vocabulary.get("answeredBy"), "chip")).containsExactly("MOCK", "Nothing sent", "Not sent");
    }

    @Test
    void aQueryParameterChangesNothingAndThePathTakesNoOtherMethod() throws Exception {
        String plain = mvc.perform(get("/api/vocabulary")).andReturn().getResponse().getContentAsString();
        String asked = mvc.perform(get("/api/vocabulary").param("protocol", "crd")).andReturn().getResponse().getContentAsString();
        assertThat(asked).isEqualTo(plain);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/vocabulary"))
                .andExpect(status().is4xxClientError());
    }
}
