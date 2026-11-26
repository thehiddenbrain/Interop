package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.suite.SuiteTestBed.suite;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.suite.SuiteService;
import org.p32h.interop.epa.pave.service.suite.SuiteTestBed;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The suite endpoints as a browser sees them: the status of each kind of answer and the shape of
 * its body. The real suite service, case runner, CRD service and scenario files are behind the
 * controller.
 */
class SuiteControllerTest {

    private static final String ROW = "TC-%d,crd,%s,1234567893,95810,%s";

    private final SuiteTestBed bed = new SuiteTestBed();

    private MockMvc mvc() {
        return TestMvc.standalone(new SuiteController(bed.service()));
    }

    private static MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String twoCases() {
        return suite(ROW.formatted(1, "P32H-NO-AUTH", "NO_AUTH_REQUIRED"), ROW.formatted(2, "P32H-EMPTY", "NO_AUTH_REQUIRED"));
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentType()).startsWith("application/json");
        return JsonSafe.parse(response.getContentAsString()).node();
    }

    private JsonNode start(MockMvc mvc, MockMultipartHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return json(mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse());
    }

    @Test
    void aPreviewAnswers200AndSendsNothing() throws Exception {
        JsonNode preview = json(mvc().perform(multipart("/api/suites/preview").file(file("cases.csv", twoCases())))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(preview.get("runnable").booleanValue()).isTrue();
        assertThat(preview.get("headline").stringValue()).isEqualTo("2 cases, and every one can be run.");
        assertThat(preview.get("total").intValue()).isEqualTo(2);
        assertThat(preview.get("kinds").get(0).get("what").stringValue()).isEqualTo("CRD order-sign");
        assertThat(preview.get("kinds").get(0).get("count").intValue()).isEqualTo(2);
        assertThat(preview.get("mode").stringValue()).isEqualTo("mock");
        assertThat(preview.get("environment").get("active").booleanValue()).isFalse();
        assertThat(bed.ledger.records()).isEmpty();
        // A file with problems is still a 200: the problems are the answer to the question asked.
        JsonNode broken = json(mvc().perform(multipart("/api/suites/preview").file(file("cases.csv", "caseId,memberId,expectd\nTC-1,S123456789,X")))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(broken.get("runnable").booleanValue()).isFalse();
        assertThat(broken.get("problems").get(0).stringValue()).startsWith("This tool does not know the column \"expectd\".");
    }

    @Test
    void startingASuiteAnswers202WithTheRunAndTheRunCanThenBeRead() throws Exception {
        MockMvc mvc = mvc();
        JsonNode started = start(mvc, multipart("/api/suites").file(file("cases.csv", twoCases())).param("testerRef", "QA-17"), 202);
        String id = started.get("suiteRunId").stringValue();
        assertThat(id).startsWith("suite-");
        assertThat(started.get("testerRef").stringValue()).isEqualTo("QA-17");

        JsonNode run = json(mvc.perform(get("/api/suites/" + id)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(run.get("state").stringValue()).isEqualTo("COMPLETED");
        assertThat(run.get("headline").stringValue()).isEqualTo("Finished: 2 cases. 1 passed, 1 failed.");
        assertThat(run.get("counts").get("total").intValue()).isEqualTo(2);
        assertThat(run.get("timing").get("answered").intValue()).isEqualTo(2);
        JsonNode first = run.get("results").get(0);
        assertThat(first.get("caseId").stringValue()).isEqualTo("TC-1");
        assertThat(first.get("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(first.get("state").stringValue()).isEqualTo("DONE");
        // The outcome and the test result are two fields, and the result is written the way the ledger writes it.
        assertThat(first.get("outcome").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(first.get("outcomeLabel").stringValue()).isEqualTo("Prior authorization is not required");
        assertThat(first.get("result").stringValue()).isEqualTo("pass");
        assertThat(first.get("runId").stringValue()).isEqualTo(bed.ledger.records().get(0).runId());
        JsonNode second = run.get("results").get(1);
        assertThat(second.get("outcome").stringValue()).isEqualTo("NO_DETERMINATION");
        assertThat(second.get("result").stringValue()).isEqualTo("fail");
        // Each row names the count it is in, and each count comes with its words, so the browser keeps no copy of them.
        assertThat(first.get("countedIn").stringValue()).isEqualTo("passed");
        assertThat(second.get("countedIn").stringValue()).isEqualTo("failed");
        JsonNode groups = run.get("countGroups");
        // Twelve: a case that could not sign in has a group of its own, apart from "nothing was declared".
        assertThat(groups).hasSize(12);
        assertThat(groups.get(0).get("group").stringValue()).isEqualTo("passed");
        assertThat(groups.get(0).get("label").stringValue()).isEqualTo("Passed");
        assertThat(groups.get(0).get("count").intValue()).isEqualTo(1);
        assertThat(groups.get(1).get("group").stringValue()).isEqualTo("failed");
        assertThat(groups.get(1).get("count").intValue()).isEqualTo(1);
        assertThat(groups.get(3).get("group").stringValue()).isEqualTo("blocked");
        assertThat(groups.get(3).get("label").stringValue()).isEqualTo("Blocked by the production guardrail, not failures");
        assertThat(groups.get(3).get("count").intValue()).isZero();
        int counted = 0;
        for (JsonNode group : groups) {
            // Every group is a count of the counts object, with the same number.
            assertThat(run.get("counts").get(group.get("group").stringValue()).intValue()).isEqualTo(group.get("count").intValue());
            counted += group.get("count").intValue();
        }
        assertThat(counted).isEqualTo(run.get("counts").get("total").intValue());

        // rows=false leaves the rows out, for a screen that polls for the progress count.
        JsonNode summary = json(mvc.perform(get("/api/suites/" + id).param("rows", "false")).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(summary.has("results")).isFalse();
        assertThat(summary.get("counts").get("done").intValue()).isEqualTo(2);

        JsonNode list = json(mvc.perform(get("/api/suites")).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(list.get("runs")).hasSize(1);
        assertThat(list.get("runs").get(0).get("suiteRunId").stringValue()).isEqualTo(id);
        assertThat(list.get("runs").get(0).has("results")).isFalse();
    }

    @Test
    void theResultsExportIsACsvDownloadNamedAfterTheRun() throws Exception {
        MockMvc mvc = mvc();
        String id = start(mvc, multipart("/api/suites").file(file("cases.csv", twoCases())), 202).get("suiteRunId").stringValue();
        MockHttpServletResponse response = mvc.perform(get("/api/suites/" + id + "/results.csv")).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"suite-results-" + id + ".csv\"");
        String csv = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFsuiteRunId,suiteName,suiteState,");
        assertThat(csv.split("\r\n")).hasSize(3);
        assertThat(csv).doesNotContain("P32H-NO-AUTH");
    }

    @Test
    void aRunningSuiteCanBeStoppedAndASecondOneIsA409() throws Exception {
        bed.hold = true;
        MockMvc mvc = mvc();
        JsonNode started = start(mvc, multipart("/api/suites").file(file("first.csv", twoCases())), 202);
        String id = started.get("suiteRunId").stringValue();
        assertThat(started.get("state").stringValue()).isEqualTo("RUNNING");
        assertThat(started.get("headline").stringValue()).isEqualTo("Running: 0 of 2 cases done.");
        assertThat(started.get("results").get(0).get("state").stringValue()).isEqualTo("PENDING");

        JsonNode busy = start(mvc, multipart("/api/suites").file(file("second.csv", twoCases())), 409);
        assertThat(busy.get("title").stringValue()).startsWith("A suite is already running: first.csv");
        assertThat(busy.get("action").stringValue()).isEqualTo("Wait for it to finish, or stop it, then start this one.");

        JsonNode stopping = json(mvc.perform(post("/api/suites/" + id + "/stop")).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(stopping.get("state").stringValue()).isEqualTo("RUNNING");
        bed.queued.remove().run();
        JsonNode stopped = json(mvc.perform(get("/api/suites/" + id)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(stopped.get("state").stringValue()).isEqualTo("STOPPED");
        assertThat(stopped.get("stopReason").stringValue()).isEqualTo("Stopped on request.");
        assertThat(stopped.get("results").get(0).get("state").stringValue()).isEqualTo("NOT_RUN");
        assertThat(bed.ledger.records()).isEmpty();
    }

    @Test
    void aFileThatCannotBeRunIsA422ThatSaysWhy() throws Exception {
        MockMvc mvc = mvc();
        JsonNode unknownColumn = start(mvc, multipart("/api/suites").file(file("cases.csv", "caseId,memberId,expectd\nTC-1,S123456789,X")), 422);
        assertThat(unknownColumn.get("kind").stringValue()).isEqualTo("VALIDATION");
        assertThat(unknownColumn.get("title").stringValue()).isEqualTo("The suite file cannot be run. Nothing was sent.");
        assertThat(unknownColumn.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("file");
        assertThat(unknownColumn.get("fieldErrors").get(0).get("message").stringValue()).contains("expectd");

        JsonNode empty = start(mvc, multipart("/api/suites").file(file("cases.csv", "")), 422);
        assertThat(empty.get("fieldErrors").get(0).get("message").stringValue()).isEqualTo("The file is empty.");

        // Saved by a spreadsheet as a plain CSV, with a letter that is one byte there and two in UTF-8.
        MockMultipartFile windows = new MockMultipartFile("file", "cases.csv", "text/csv",
                "caseId,protocol,memberId,note\nTC-1,crd,S123456789,café".getBytes(StandardCharsets.ISO_8859_1));
        JsonNode notUtf8 = start(mvc, multipart("/api/suites").file(windows), 422);
        assertThat(notUtf8.get("title").stringValue()).isEqualTo("The suite file is not UTF-8 text.");
        assertThat(notUtf8.get("action").stringValue()).contains("CSV UTF-8");

        JsonNode noFile = start(mvc, multipart("/api/suites"), 422);
        assertThat(noFile.get("kind").stringValue()).isEqualTo("VALIDATION");

        JsonNode badEpoch = start(mvc, multipart("/api/suites").file(file("cases.csv", twoCases())).param("configEpoch", "now"), 422);
        assertThat(badEpoch.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("configEpoch");
        assertThat(bed.ledger.records()).isEmpty();
    }

    @Test
    void aSuiteStartedFromAStaleScreenIsA409AndAnUnknownRunIsA404() throws Exception {
        MockMvc mvc = mvc();
        bed.gateway.changeConfiguration();
        JsonNode stale = start(mvc, multipart("/api/suites").file(file("cases.csv", twoCases())).param("configEpoch", "0"), 409);
        assertThat(stale.get("kind").stringValue()).isEqualTo("CONFIGURATION");
        assertThat(bed.ledger.records()).isEmpty();

        for (String path : new String[] {"/api/suites/suite-unknown", "/api/suites/suite-unknown/results.csv"}) {
            JsonNode missing = json(mvc.perform(get(path)).andExpect(status().isNotFound()).andReturn().getResponse());
            assertThat(missing.get("kind").stringValue()).isEqualTo("NOT_FOUND");
            assertThat(missing.get("title").stringValue()).contains("in Audit");
        }
        mvc.perform(post("/api/suites/suite-unknown/stop")).andExpect(status().isNotFound());
        JsonNode badFlag = json(mvc.perform(get("/api/suites/suite-unknown").param("rows", "maybe")).andExpect(status().is(422))
                .andReturn().getResponse());
        assertThat(badFlag.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("rows");
        JsonNode unknownEarlier = start(mvc, multipart("/api/suites").file(file("cases.csv", twoCases())).param("compareWith", "suite-unknown"), 404);
        assertThat(unknownEarlier.get("title").stringValue()).contains("nothing to compare with");
    }

    private static String body(String fileName, String text, String... more) {
        StringBuilder out = new StringBuilder("{\"fileName\": ").append(JsonSafe.mapper().writeValueAsString(fileName))
                .append(", \"text\": ").append(JsonSafe.mapper().writeValueAsString(text));
        for (String extra : more) {
            out.append(", ").append(extra);
        }
        return out.append("}").toString();
    }

    private JsonNode posted(MockMvc mvc, String path, String json, int expectedStatus) throws Exception {
        return json(mvc.perform(post(path).contentType("application/json").content(json)).andExpect(status().is(expectedStatus))
                .andReturn().getResponse());
    }

    /**
     * The page reads the suite file itself and posts its text (R-06, R-30): the preview and the run
     * answer as they do for the uploaded file, the run's reference and comparison come in the same
     * body, and the multipart form stays for curl.
     */
    @Test
    void theSuiteFileCanBePostedAsTheTextThePageRead() throws Exception {
        MockMvc mvc = mvc();
        JsonNode preview = posted(mvc, "/api/suites/preview", body("cases.csv", twoCases()), 200);
        assertThat(preview.get("headline").stringValue()).isEqualTo("2 cases, and every one can be run.");
        assertThat(preview.get("name").stringValue()).isEqualTo("cases.csv");
        assertThat(bed.ledger.records()).isEmpty();
        // The same body the run takes: a preview never refuses what the run would accept.
        JsonNode same = posted(mvc, "/api/suites/preview", body("cases.csv", twoCases(), "\"testerRef\": \"QA-17\"", "\"configEpoch\": 0"), 200);
        assertThat(same.get("total").intValue()).isEqualTo(2);

        JsonNode started = posted(mvc, "/api/suites", body("cases.csv", twoCases(), "\"testerRef\": \"QA-17\""), 202);
        String id = started.get("suiteRunId").stringValue();
        assertThat(started.get("testerRef").stringValue()).isEqualTo("QA-17");
        JsonNode run = json(mvc.perform(get("/api/suites/" + id)).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(run.get("headline").stringValue()).isEqualTo("Finished: 2 cases. 1 passed, 1 failed.");
        assertThat(bed.ledger.records()).hasSize(2);

        JsonNode compared = posted(mvc, "/api/suites", body("again.csv", twoCases(), "\"compareWith\": " + JsonSafe.mapper().writeValueAsString(id)), 202);
        assertThat(compared.get("comparedWith").stringValue()).isEqualTo(id);
        // A JSON file is told by its name, as an uploaded one is.
        JsonNode jsonFile = posted(mvc, "/api/suites/preview", body("cases.json",
                "[{\"caseId\": \"TC-1\", \"protocol\": \"crd\", \"memberId\": \"P32H-NO-AUTH\", \"npi\": \"1234567893\"}]"), 200);
        assertThat(jsonFile.get("format").stringValue()).isEqualTo("JSON");
    }

    @Test
    void aPostedSuiteIsHeldToTheRulesOfAnUploadedOne() throws Exception {
        MockMvc mvc = mvc();
        JsonNode unknownColumn = posted(mvc, "/api/suites", body("cases.csv", "caseId,memberId,expectd\nTC-1,S123456789,X"), 422);
        assertThat(unknownColumn.get("title").stringValue()).isEqualTo("The suite file cannot be run. Nothing was sent.");
        assertThat(unknownColumn.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("file");

        JsonNode noText = posted(mvc, "/api/suites", "{\"fileName\": \"cases.csv\"}", 422);
        assertThat(noText.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("text");
        assertThat(noText.get("fieldErrors").get(0).get("message").stringValue()).isEqualTo("The suite file holds no text. Choose it again.");
        posted(mvc, "/api/suites/preview", "{\"fileName\": \"cases.csv\"}", 422);

        // Read strictly: a misspelt property is named, never dropped in silence.
        JsonNode misspelt = posted(mvc, "/api/suites", body("cases.csv", twoCases(), "\"testerReference\": \"QA-1\""), 422);
        assertThat(misspelt.toString()).contains("testerReference");

        // Over the size an uploaded file may have, counted in the same UTF-8 bytes.
        JsonNode large = posted(mvc, "/api/suites/preview", body("cases.csv", "x".repeat(5 * 1024 * 1024 + 1)), 422);
        assertThat(large.get("title").stringValue()).isEqualTo("The suite file is larger than 5 MB, which is more than this tool reads as a suite.");

        bed.gateway.changeConfiguration();
        JsonNode stale = posted(mvc, "/api/suites", body("cases.csv", twoCases(), "\"configEpoch\": 0"), 409);
        assertThat(stale.get("kind").stringValue()).isEqualTo("CONFIGURATION");
        assertThat(bed.ledger.records()).isEmpty();
        // Neither multipart nor JSON: refused as a type the endpoint does not take.
        mvc.perform(post("/api/suites").contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType());
    }
}
