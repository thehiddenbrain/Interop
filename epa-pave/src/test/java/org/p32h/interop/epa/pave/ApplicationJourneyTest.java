package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * One case followed through the application as it starts on a fresh checkout: no profile set, no
 * file edited, nothing replaced by a stand-in. Only the two storage folders are pointed at a
 * temporary place.
 *
 * <p>The tests of each part build that part by hand. This one is what proves the parts are wired
 * to each other: a run made over HTTP is written by the real ledger, read back by the history
 * endpoint, grouped by the chain view, run again by replay through the real case runner, and a
 * suite file goes through the real CSV reader, the real lookups and the real code list.
 */
@SpringBootTest
class ApplicationJourneyTest {

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

    private JsonNode json(RequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc().perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonSafe.parse(body).node();
    }

    private JsonNode crd(String memberId, String expected, String chainId) throws Exception {
        String body = "{\"member\":{\"memberId\":\"" + memberId + "\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\",\"expected\":\"" + expected + "\""
                + (chainId == null ? "" : ",\"chainId\":\"" + chainId + "\"") + "}";
        return json(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body), 200);
    }

    @Test
    void theToolSaysWhatItIsAndThatItIsOnMock() throws Exception {
        JsonNode mode = json(get("/api/mode"), 200);
        assertThat(mode.get("name").stringValue()).isEqualTo("PAVE");
        assertThat(mode.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(mode.get("mode").stringValue()).isEqualTo("mock");
        assertThat(mode.get("environment").get("active").booleanValue()).isFalse();
        assertThat(mode.get("writeAccess").get("reason").stringValue()).isNotBlank();
        // The same epoch everywhere it is stated, so a screen can tell when the configuration moved.
        assertThat(json(get("/api/config/effective"), 200).get("epoch").longValue()).isEqualTo(mode.get("epoch").longValue());
    }

    @Test
    void aRunIsRecordedReadBackGroupedIntoItsChainAndRunAgain() throws Exception {
        JsonNode run = crd("P32H-NO-AUTH", "NO_AUTH_REQUIRED", "new");
        String runId = run.get("runId").stringValue();
        String chainId = run.get("chainId").stringValue();
        assertThat(run.get("verdict").get("outcomeCode").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(run.get("expectation").get("result").stringValue()).isEqualTo("pass");
        assertThat(run.get("ledger").get("recorded").booleanValue()).isTrue();

        // The line is on disk, written by the real ledger, and holds the outcome and the result as two facts.
        List<String> lines;
        try (var files = Files.list(home.resolve("run-history"))) {
            Path file = files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        String line = lines.stream().filter(l -> l.contains(runId)).findFirst().orElseThrow();
        assertThat(line).contains("\"outcome\":\"NO_AUTH_REQUIRED\"").contains("\"result\":\"pass\"").contains("\"chainId\":\"" + chainId + "\"");
        // Nothing of the wire is on the line.
        assertThat(line).doesNotContain("hookInstance").doesNotContain("draftOrders").doesNotContain("cards");

        JsonNode read = json(get("/api/runs/" + runId), 200);
        assertThat(read.get("outcome").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(read.get("result").stringValue()).isEqualTo("pass");
        assertThat(json(get("/api/runs").param("chainId", chainId), 200).get("count").intValue()).isEqualTo(1);

        JsonNode chain = json(get("/api/chains/" + chainId), 200);
        assertThat(chain.get("ended").booleanValue()).isTrue();
        assertThat(chain.get("stages").get(0).get("state").stringValue()).isEqualTo("DONE");
        assertThat(chain.get("stages").get(1).get("state").stringValue()).isEqualTo("NOT_NEEDED");

        // Replay goes through the real case runner: the member is looked up again and the same service method is called.
        JsonNode replay = json(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"runIds\":[\"" + runId + "\"]}"), 200);
        assertThat(replay.get("runnerAvailable").booleanValue()).isTrue();
        assertThat(replay.get("results").get(0).get("status").stringValue()).isEqualTo("same");
        assertThat(replay.get("results").get(0).get("replayed").get("replayOfRunId").stringValue()).isEqualTo(runId);
    }

    @Test
    void noAuthAndNoDeterminationAreTwoDifferentAnswersAndTwoDifferentLedgerLines() throws Exception {
        JsonNode noAuth = crd("P32H-NO-AUTH", "NO_AUTH_REQUIRED", null);
        JsonNode empty = crd("P32H-EMPTY", "NO_AUTH_REQUIRED", null);
        assertThat(noAuth.get("verdict").get("headline").stringValue()).isEqualTo("Prior authorization is not required.");
        assertThat(empty.get("verdict").get("headline").stringValue()).isEqualTo("The service answered but returned no determination.");
        assertThat(noAuth.get("expectation").get("result").stringValue()).isEqualTo("pass");
        assertThat(empty.get("expectation").get("result").stringValue()).isEqualTo("fail");
        JsonNode noAuthLine = json(get("/api/runs/" + noAuth.get("runId").stringValue()), 200);
        JsonNode emptyLine = json(get("/api/runs/" + empty.get("runId").stringValue()), 200);
        assertThat(noAuthLine.get("outcome").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(emptyLine.get("outcome").stringValue()).isEqualTo("NO_DETERMINATION");
        assertThat(noAuthLine.get("result").stringValue()).isEqualTo("pass");
        assertThat(emptyLine.get("result").stringValue()).isEqualTo("fail");
    }

    @Test
    void aSuiteFileWrittenByASpreadsheetRunsThroughTheRealReaderLookupsAndCodeList() throws Exception {
        // A byte order mark, CRLF line endings, a header with capitals and spaces, and a quoted cell holding a comma.
        String csv = "﻿Case ID,Protocol,Member ID,NPI,Procedure Codes,Expected Outcome,Note\r\n"
                + "TC-1,crd,P32H-NO-AUTH,1234567893,95810,NO_AUTH_REQUIRED,\"sleep study, attended\"\r\n"
                + "TC-2,crd,P32H-EMPTY,1234567893,95810,NO_AUTH_REQUIRED,\r\n"
                + "TC-3,crd,P32H-NOMEM,1234567893,95810,MEMBER_NOT_FOUND,\r\n"
                + "TC-4,crd,P32H-NO-AUTH,12,95810,,\r\n";
        MockMultipartFile file = new MockMultipartFile("file", "suite.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        JsonNode preview = json(multipart("/api/suites/preview").file(file), 200);
        assertThat(preview.get("headline").stringValue())
                .isEqualTo("4 cases: 3 can be run, and 1 has a problem and will be reported as not valid.");

        String id = json(multipart("/api/suites").file(file), 202).get("suiteRunId").stringValue();
        JsonNode run = null;
        for (int i = 0; i < 400; i++) {
            run = json(get("/api/suites/" + id), 200);
            if (!"RUNNING".equals(run.get("state").stringValue())) {
                break;
            }
            Thread.sleep(25);
        }
        assertThat(run.get("state").stringValue()).isEqualTo("COMPLETED");
        assertThat(run.get("headline").stringValue()).isEqualTo("Finished: 4 cases. 2 passed, 1 failed, 1 not valid.");
        JsonNode rows = run.get("results");
        assertThat(rows.get(0).get("result").stringValue()).isEqualTo("pass");
        assertThat(rows.get(1).get("outcome").stringValue()).isEqualTo("NO_DETERMINATION");
        assertThat(rows.get(1).get("result").stringValue()).isEqualTo("fail");
        // The mock lookup does not know this member, so the case went out with the ID alone, and says so.
        assertThat(rows.get(2).get("outcome").stringValue()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(rows.get(2).get("notes").get(0).stringValue()).contains("member lookup");
        assertThat(rows.get(3).get("status").stringValue()).isEqualTo("INVALID");
        // Every case that ran is in the ledger under the suite run id, and nothing else is.
        assertThat(json(get("/api/runs").param("suiteRunId", id), 200).get("count").intValue()).isEqualTo(3);
    }

    @Test
    void theLookupsAndTheCodePickerAnswerFromTheSyntheticData() throws Exception {
        assertThat(json(get("/api/members/P32H-NO-AUTH"), 200).get("status").stringValue()).isEqualTo("FOUND");
        // A member nobody knows is an answer, not an error.
        assertThat(json(get("/api/members/S000000000"), 200).get("status").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(json(get("/api/providers/1234567893"), 200).get("status").stringValue()).isEqualTo("FOUND");
        // A check digit that does not match is a typing mistake, refused before any lookup.
        assertThat(json(get("/api/providers/1234567890"), 422).get("kind").stringValue()).isEqualTo("VALIDATION");
        assertThat(json(get("/api/procedure-codes").param("q", "958"), 200).get("codes").size()).isGreaterThan(0);
        assertThat(json(get("/api/procedure-codes/self-check"), 200).get("placeholder").booleanValue()).isTrue();
        assertThat(json(get("/api/crd/scenarios"), 200).get("scenarios")).hasSize(14);
    }
}
