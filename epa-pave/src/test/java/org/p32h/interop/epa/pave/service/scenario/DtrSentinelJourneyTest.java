package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * F-CHAIN-DTR of the second fixture audit: every DTR sentinel, taken from CRD into DTR the way the
 * tool hands it on, gets the outcome its DTR file claims. Before, CRD named one questionnaire for
 * every DTR sentinel, six DTR packages did not hold it, and DTR reported the package incomplete,
 * which only the two synthetic data sets caused. Through the real endpoints of the application as
 * it starts on a fresh checkout, by hand and as suite rows of the protocol chain.
 */
@SpringBootTest
class DtrSentinelJourneyTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired ScenarioRepository scenarios;

    private JsonNode json(RequestBuilder request, int expectedStatus) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        return JsonSafe.parse(mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).node();
    }

    /** Every own sentinel member ID of a DTR file, with the outcome the file claims for its package. */
    private Map<String, Scenario> dtrSentinels() {
        Map<String, Scenario> out = new LinkedHashMap<>();
        for (Scenario scenario : scenarios.list("dtr")) {
            for (String sentinel : scenario.sentinelMemberIds()) {
                out.put(sentinel, scenario);
            }
        }
        assertThat(out).hasSizeGreaterThanOrEqualTo(15).containsKeys("P32H-DTR-ADAPT", "P32H-DTR-PREPOP", "P32H-DTR-REQUIRED",
                "P32H-DTR-TWO", "P32H-DTR-NOTHING", "P32H-DTR-LOOPERR");
        return out;
    }

    private static String form(String memberId) {
        return "{\"member\":{\"memberId\":\"" + memberId + "\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-02\"}";
    }

    @Test
    void everyDtrSentinelCarriedFromCrdIntoDtrGetsTheOutcomeItsDtrFileClaims() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Scenario> entry : dtrSentinels().entrySet()) {
            String sentinel = entry.getKey();
            Scenario dtr = entry.getValue();
            JsonNode crd = json(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(form(sentinel)), 200);
            assertThat(crd.at("/verdict/outcomeCode").stringValue()).as(sentinel).isEqualTo("DOCUMENTATION_REQUIRED");
            assertThat(crd.at("/nextStep/action").stringValue()).as(sentinel).isEqualTo("START_DTR");
            JsonNode carry = crd.at("/nextStep/carry");
            List<String> named = JsonSafe.elements(carry, "questionnaire").stream().map(JsonNode::stringValue).toList();
            List<String> held = org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient.questionnairesHeldBy(dtr);
            if (!held.isEmpty()) {
                // What CRD names is what the DTR package holds, read from the DTR file and written nowhere else.
                assertThat(named).as(sentinel).containsExactlyElementsOf(held);
            }
            ObjectNode withCarry = (ObjectNode) JsonSafe.parse(form(sentinel)).node();
            withCarry.set("carry", carry);
            JsonNode package_ = json(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content(withCarry.toString()), 200);
            assertThat(package_.at("/ledger/record/scenarioId").stringValue()).as(sentinel).isEqualTo(dtr.id());
            String outcome = package_.at("/verdict/outcomeCode").stringValue();
            if (!dtr.expect().outcome().equals(outcome)) {
                wrong.add(sentinel + " " + dtr.id() + ": expected " + dtr.expect().outcome() + ", got " + outcome);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyDtrSentinelAsASuiteRowOfTheProtocolChainReachesTheOutcomeItsDtrFileClaims() throws Exception {
        Map<String, Scenario> sentinels = dtrSentinels();
        StringBuilder csv = new StringBuilder("caseId,protocol,memberId,npi,procedureCodes,dateOfService\n");
        int row = 1;
        for (String sentinel : sentinels.keySet()) {
            csv.append("d").append(row++).append(",chain,").append(sentinel).append(",1234567893,95810,2026-03-02\n");
        }
        MockMultipartFile file = new MockMultipartFile("file", "dtr-journeys.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        String id = json(multipart("/api/suites").file(file), 202).get("suiteRunId").stringValue();
        JsonNode run = null;
        for (int i = 0; i < 2000; i++) {
            run = json(get("/api/suites/" + id), 200);
            if (!"RUNNING".equals(run.get("state").stringValue())) {
                break;
            }
            Thread.sleep(25);
        }
        assertThat(run.get("state").stringValue()).isEqualTo("COMPLETED");
        List<String> wrong = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Scenario> entry : sentinels.entrySet()) {
            JsonNode result = run.get("results").get(index++);
            JsonNode steps = result.get("steps");
            assertThat(steps.size()).as(entry.getKey()).isGreaterThanOrEqualTo(2);
            assertThat(steps.get(0).get("outcome").stringValue()).as(entry.getKey()).isEqualTo("DOCUMENTATION_REQUIRED");
            String dtrOutcome = steps.get(1).get("outcome").stringValue();
            if (!entry.getValue().expect().outcome().equals(dtrOutcome)) {
                wrong.add(entry.getKey() + " " + entry.getValue().id() + ": expected " + entry.getValue().expect().outcome() + ", got "
                        + dtrOutcome + " (" + JsonSafe.text(result, "reason") + ")");
            }
        }
        assertThat(wrong).isEmpty();
    }
}
