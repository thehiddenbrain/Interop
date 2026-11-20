package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/**
 * Whole journeys through the application as it starts on a fresh checkout: a chain run as a suite
 * row, its case result written to the ledger and read back by History and by the chain view, and a
 * preview that counts what will really run. Only the two storage folders are pointed elsewhere.
 */
@SpringBootTest
class ChainJourneyTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String text(RequestBuilder request, int expectedStatus) throws Exception {
        return mvc().perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(RequestBuilder request, int expectedStatus) throws Exception {
        return JsonSafe.parse(text(request, expectedStatus)).node();
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "journey.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Starts a suite and reads it until it has finished. The suite runs on its own thread, so it is asked again until it is done. */
    private JsonNode runSuite(String csv) throws Exception {
        String id = json(multipart("/api/suites").file(file(csv)), 202).get("suiteRunId").stringValue();
        JsonNode run = null;
        for (int i = 0; i < 800; i++) {
            run = json(get("/api/suites/" + id), 200);
            if (!"RUNNING".equals(run.get("state").stringValue())) {
                break;
            }
            Thread.sleep(25);
        }
        assertThat(run.get("state").stringValue()).isEqualTo("COMPLETED");
        return run;
    }

    @Test
    void aChainThatFailedAsAWholeIsFoundAmongTheFailedRunsAndItsChainSaysSo() throws Exception {
        JsonNode run = runSuite("caseId,protocol,memberId,npi,procedureCodes,expected\n"
                + "E05,chain,P32H-NO-AUTH,1234567893,95810,crd: NO_AUTH_REQUIRED; pas: APPROVED\n"
                + "E01,chain,P32H-PA-REQ,1234567893,95810,crd: AUTH_REQUIRED; pas: APPROVED\n");
        String suiteRunId = run.get("suiteRunId").stringValue();
        JsonNode e05 = run.get("results").get(0);
        assertThat(e05.get("result").stringValue()).isEqualTo("fail");
        assertThat(e05.get("statusLine").stringValue()).isEqualTo("Failed: the journey is not what the row expected. The reason names the step.");
        assertThat(e05.get("reason").stringValue()).startsWith("The case expected an answer from PAS, and the chain did not get that far.");
        assertThat(e05.get("steps").get(0).get("result").stringValue()).isEqualTo("pass");
        JsonNode e01 = run.get("results").get(1);
        assertThat(e01.get("result").stringValue()).isEqualTo("pass");
        assertThat(e01.get("steps").size()).isEqualTo(2);

        // History's filter for failed runs finds the chain that failed, although the one step it made passed.
        JsonNode failed = json(get("/api/runs").param("suiteRunId", suiteRunId).param("result", "fail"), 200);
        assertThat(failed.get("count").intValue()).isEqualTo(1);
        JsonNode line = failed.get("runs").get(0);
        assertThat(line.get("caseId").stringValue()).isEqualTo("E05");
        assertThat(line.get("result").stringValue()).isEqualTo("pass");
        assertThat(line.get("caseResult").stringValue()).isEqualTo("fail");
        // A case line is not a run: the suite's three steps are the runs it holds.
        assertThat(json(get("/api/runs").param("suiteRunId", suiteRunId), 200).get("count").intValue()).isEqualTo(3);

        JsonNode chain = json(get("/api/chains/" + e05.get("chainId").stringValue()), 200);
        assertThat(chain.get("caseResult").stringValue()).isEqualTo("fail");
        assertThat(chain.get("caseExpected").stringValue()).isEqualTo("crd: NO_AUTH_REQUIRED; pas: APPROVED");
        assertThat(chain.get("caseLine").stringValue()).startsWith("Run as a case, the journey failed");
        // E05 ended at CRD, so nothing in it used what a step before it handed on, and nothing says a line of it cannot be replayed.
        assertThat(chain.has("replayNote")).isFalse();
        // E01's submission used what CRD handed on, which a replay refuses, and its chain says so.
        JsonNode e01Chain = json(get("/api/chains/" + e01.get("chainId").stringValue()), 200);
        assertThat(e01Chain.get("replayNote").stringValue()).startsWith("A step of this chain that used what the step before it handed on");

        // The export for sign-off carries each step and the state of the suite.
        String csv = text(get("/api/suites/" + suiteRunId + "/results.csv"), 200);
        assertThat(csv).contains(",COMPLETED,").contains("CRD order-sign NO_AUTH_REQUIRED pass")
                .contains("CRD order-sign AUTH_REQUIRED pass; PAS submit APPROVED pass");
        assertThat(csv).doesNotContain("P32H-NO-AUTH").doesNotContain("P32H-PA-REQ");
    }

    @Test
    void whenCrdCallsForDocumentationTheChainViewSaysDtrComesFirst() throws Exception {
        String body = "{\"member\":{\"memberId\":\"P32H-DOC-DTR\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\",\"chainId\":\"new\"}";
        String chainId = json(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body), 200).get("chainId").stringValue();
        JsonNode chain = json(get("/api/chains/" + chainId), 200);
        assertThat(chain.get("stages").get(0).get("state").stringValue()).isEqualTo("DONE");
        assertThat(chain.get("stages").get(1).get("state").stringValue()).isEqualTo("NEXT");
        assertThat(chain.get("stages").get(2).get("stateLine").stringValue()).isEqualTo("Not reached: DTR comes first.");
        assertThat(chain.has("caseResult")).isFalse();
    }

    @Test
    void aDamagedLedgerLineIsSaidOnTheChainBecauseItMayBeOneOfItsSteps() throws Exception {
        String body = "{\"member\":{\"memberId\":\"P32H-NO-AUTH\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\",\"chainId\":\"new\"}";
        String chainId = json(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body), 200).get("chainId").stringValue();
        Path day;
        try (var files = Files.list(home.resolve("run-history"))) {
            day = files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
        Files.writeString(day, "{\"recordType\":\"run\",\"runId\":\"run-torn\",\"chainId\":\"" + chainId + "\"\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        JsonNode chain = json(get("/api/chains/" + chainId), 200);
        assertThat(chain.get("runCount").intValue()).isEqualTo(1);
        assertThat(chain.get("notes").toString()).contains("line it cannot read. A run of this chain may be among them")
                .doesNotContain("self-check");
    }

    @Test
    void aChainStartedAtPasIsNotSaidToBeUnreplayableAndItsSubmissionReplays() throws Exception {
        String body = "{\"member\":{\"memberId\":\"S123456789\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"70553\"}],\"dateOfService\":\"2026-03-20\",\"chainId\":\"new\"}";
        JsonNode submitted = json(post("/api/pas/submit").contentType(MediaType.APPLICATION_JSON).content(body), 200);
        JsonNode chain = json(get("/api/chains/" + submitted.get("chainId").stringValue()), 200);
        assertThat(chain.has("replayNote")).isFalse();
        JsonNode replay = json(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"runIds\":[\"" + submitted.get("runId").stringValue() + "\"]}"), 200);
        assertThat(replay.get("summary").get("not-replayable").intValue()).isZero();
    }

    @Test
    void thePreviewCountsWhatThisBuildWillRunAndNothingElse() throws Exception {
        JsonNode preview = json(multipart("/api/suites/preview").file(file("caseId,protocol,operation,memberId,npi,procedureCodes\n"
                + "c1,crd,,P32H-NO-AUTH,1234567893,95810\n"
                + "c2,pas,,P32H-PA-REQ,1234567893,95810\n"
                + "c3,dtr,,P32H-DOC-DTR,1234567893,95810\n"
                + "c4,chain,,P32H-PA-REQ,1234567893,95810\n"
                + "c5,,SUBSCRIPTION_GET,P32H-PA-REQ,1234567893,95810\n"
                + "c6,crd,,P32H-NO-AUTH,1234567893,\n")), 200);
        assertThat(preview.get("headline").stringValue()).isEqualTo("6 cases: 4 can be run, 1 cannot be run in this build and will be "
                + "reported as not runnable, and 1 has a problem and will be reported as not valid.");
        assertThat(preview.get("cannotRun").get(0).get("caseId").stringValue()).isEqualTo("c5");
        assertThat(preview.get("rowProblems").get(0).get("problems").get(0).stringValue())
                .isEqualTo("procedureCodes: Choose at least one procedure code.");
    }
}
