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

    private JsonNode runSuite(String name, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        String id = json(multipart("/api/suites").file(file), 202).get("suiteRunId").stringValue();
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
    void aChainRunsTheWholeJourneyAsFarAsAToolCanGoAndSaysWhyItStopped() throws Exception {
        JsonNode run = runSuite("chains.csv", "caseId,protocol,memberId,npi,codes,expected\n"
                + "CH-1,chain,P32H-NO-AUTH,1234567893,95810,crd: NO_AUTH_REQUIRED\n"
                + "CH-2,chain,P32H-PA-REQ,1234567893,95810,crd: AUTH_REQUIRED; pas: APPROVED\n"
                + "CH-3,chain,P32H-DOC-DTR,1234567893,95810,\n"
                + "CH-4,chain,P32H-NO-AUTH,1234567893,95810,crd: NO_AUTH_REQUIRED; pas: APPROVED\n");
        JsonNode rows = run.get("results");

        // The payer says no authorization is needed: the journey ends at CRD, which is itself the result.
        assertThat(rows.get(0).get("runIds")).hasSize(1);
        assertThat(rows.get(0).get("outcome").stringValue()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(rows.get(0).get("result").stringValue()).isEqualTo("pass");
        assertThat(rows.get(0).get("reason").stringValue()).startsWith("The flow ended at CRD.");

        // Authorization is needed and no documentation: CRD, then the submission, with the codes carried over.
        assertThat(rows.get(1).get("runIds")).hasSize(2);
        assertThat(rows.get(1).get("outcome").stringValue()).isEqualTo("APPROVED");
        assertThat(rows.get(1).get("result").stringValue()).isEqualTo("pass");
        String chainId = rows.get(1).get("chainId").stringValue();
        JsonNode chain = json(get("/api/chains/" + chainId), 200);
        assertThat(chain.get("runCount").intValue()).isEqualTo(2);
        assertThat(chain.get("ended").booleanValue()).isTrue();
        assertThat(chain.get("stages").get(0).get("state").stringValue()).isEqualTo("DONE");
        assertThat(chain.get("stages").get(1).get("state").stringValue()).isEqualTo("NOT_NEEDED");
        assertThat(chain.get("stages").get(2).get("state").stringValue()).isEqualTo("DONE");
        // One chain id on both ledger lines, so the journey is one row in history.
        JsonNode lines = json(get("/api/runs").param("chainId", chainId), 200).get("runs");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("protocol").stringValue()).isEqualTo("PAS");
        assertThat(lines.get(1).get("protocol").stringValue()).isEqualTo("CRD");
        assertThat(lines.get(0).get("procedureCodes").get(0).stringValue()).isEqualTo("95810");

        // Documentation is needed: the questionnaire is fetched, and the chain stops where a person has to answer.
        assertThat(rows.get(2).get("runIds")).hasSize(2);
        assertThat(rows.get(2).get("reason").stringValue()).startsWith("The chain stopped at DTR: the questionnaire has to be answered by a person.");
        assertThat(rows.get(2).get("result").stringValue()).isEqualTo("unverified");
        JsonNode waiting = json(get("/api/chains/" + rows.get(2).get("chainId").stringValue()), 200);
        assertThat(waiting.get("nextStage").stringValue()).isEqualTo("DTR");
        assertThat(waiting.get("stages").get(2).get("state").stringValue()).isEqualTo("NOT_REACHED");

        // The case expected an answer from PAS and the journey ended at CRD. That is a failed case, not a passed one
        // with a step left out.
        assertThat(rows.get(3).get("runIds")).hasSize(1);
        assertThat(rows.get(3).get("result").stringValue()).isEqualTo("fail");
        assertThat(rows.get(3).get("reason").stringValue())
                .startsWith("The case expected an answer from PAS, and the chain did not get that far. The flow ended at CRD.");
        assertThat(run.get("headline").stringValue()).isEqualTo("Finished: 4 cases. 2 passed, 1 failed, 1 unverified.");
    }

    /** One step of a journey a person drives: what the form holds, plus whatever the step before handed on. */
    private JsonNode step(String path, String memberId, JsonNode before, String more) throws Exception {
        String body = "{\"member\":{\"memberId\":\"" + memberId + "\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\""
                + (before == null ? ",\"chainId\":\"new\"" : ",\"chainId\":\"" + before.get("chainId").stringValue() + "\",\"carry\":"
                + before.get("nextStep").get("carry"))
                + (more == null ? "" : "," + more) + "}";
        return json(post(path).contentType(MediaType.APPLICATION_JSON).content(body), 200);
    }

    @Test
    void theAnswersAPersonGivesInDtrTravelInsideTheRequestThatIsSentToPas() throws Exception {
        // The payer asks for documentation, so the journey goes through DTR and a person answers.
        JsonNode crd = step("/api/crd", "P32H-DOC-DTR", null, null);
        assertThat(crd.get("nextStep").get("action").stringValue()).isEqualTo("START_DTR");

        JsonNode fetched = step("/api/dtr/package", "P32H-DOC-DTR", crd, null);
        assertThat(fetched.get("verdict").get("outcomeCode").stringValue()).isEqualTo("PACKAGE_LOADED");
        assertThat(fetched.get("nextStep").get("action").stringValue()).isEqualTo("SUBMIT_RESPONSE");
        JsonNode questionnaire = fetched.get("detail").get("questionnaires").get(0);

        // The screen posts the questionnaire back with the answers: the tool keeps nothing between steps.
        JsonNode answered = step("/api/dtr/response", "P32H-DOC-DTR", fetched, "\"questionnaire\":" + questionnaire.get("json")
                + ",\"answers\":[{\"linkId\":\"sleep-1\",\"values\":[\"true\"]},{\"linkId\":\"sleep-2\",\"values\":[\"14\"]},"
                + "{\"linkId\":\"sleep-3\",\"values\":[\"snoring\"]}]");
        assertThat(answered.get("verdict").get("outcomeCode").stringValue()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(answered.get("nextStep").get("action").stringValue()).isEqualTo("START_PAS");
        // The response step sends nothing, and says so by having no wire view.
        assertThat(answered.has("wire")).isFalse();

        JsonNode submitted = step("/api/pas/submit", "P32H-DOC-DTR", answered, null);
        assertThat(submitted.get("verdict").get("outcomeCode").stringValue()).isEqualTo("APPROVED");

        // The proof is the request as it went out, not anything the tool says about it.
        JsonNode bundle = JsonSafe.parse(submitted.get("wire").get("request").get("body").stringValue()).node();
        JsonNode claim = null;
        JsonNode response = null;
        java.util.Set<String> inBundle = new java.util.TreeSet<>();
        for (JsonNode entry : bundle.get("entry")) {
            JsonNode resource = entry.get("resource");
            inBundle.add(resource.get("resourceType").stringValue() + "/" + resource.get("id").stringValue());
            if (resource.get("resourceType").stringValue().equals("Claim")) {
                claim = resource;
            }
            if (resource.get("resourceType").stringValue().equals("QuestionnaireResponse")) {
                response = resource;
            }
        }
        assertThat(response).as("the QuestionnaireResponse inside the PAS bundle").isNotNull();
        assertThat(response.get("status").stringValue()).isEqualTo("completed");
        assertThat(response.get("questionnaire").stringValue()).startsWith(questionnaire.get("url").get("value").stringValue());
        // The answers are the ones that were given, as the types the questionnaire asks for.
        assertThat(response.get("item").get(0).get("linkId").stringValue()).isEqualTo("sleep-1");
        assertThat(response.get("item").get(0).get("answer").get(0).get("valueBoolean").booleanValue()).isTrue();
        assertThat(response.get("item").get(1).get("answer").get(0).get("valueInteger").intValue()).isEqualTo(14);
        assertThat(response.get("item").get(2).get("answer").get(0).get("valueCoding").get("code").stringValue()).isEqualTo("snoring");
        // The claim names it, and it is about the member, the coverage and the provider of this same bundle.
        String named = claim.get("supportingInfo").get(0).get("valueReference").get("reference").stringValue();
        assertThat(named).isEqualTo("QuestionnaireResponse/" + response.get("id").stringValue());
        assertThat(inBundle).contains(named, response.get("subject").get("reference").stringValue(), "Coverage/coverage-1",
                "Practitioner/practitioner-1");
        assertThat(response.toString()).contains("Coverage/coverage-1").contains("Practitioner/practitioner-1");

        // One journey in history: four lines under one chain id, and every stage done.
        JsonNode chain = json(get("/api/chains/" + submitted.get("chainId").stringValue()), 200);
        assertThat(chain.get("runCount").intValue()).isEqualTo(4);
        assertThat(chain.get("ended").booleanValue()).isTrue();
        for (JsonNode stage : chain.get("stages")) {
            assertThat(stage.get("state").stringValue()).isEqualTo("DONE");
        }
        // Nothing of the answers is in the ledger: a line holds no wire JSON and no response text.
        String ledger = json(get("/api/runs").param("chainId", submitted.get("chainId").stringValue()), 200).toString();
        assertThat(ledger).doesNotContain("sleep-1").doesNotContain("snoring").doesNotContain("QuestionnaireResponse\"");
    }

    @Test
    void aSubmissionAndAPackageFetchRunFromASuiteAndAChainRowIsCheckedBeforeAnythingIsSent() throws Exception {
        JsonNode run = runSuite("single.csv", "caseId,protocol,memberId,npi,codes,expected\n"
                + "P-1,pas,P32H-PA-REQ,1234567893,95810,APPROVED\n"
                + "D-1,dtr,P32H-DOC-DTR,1234567893,95810,\n"
                + "CH-9,chain,P32H-PA-REQ,1234567893,95810,APPROVED\n");
        JsonNode rows = run.get("results");
        assertThat(rows.get(0).get("what").stringValue()).isEqualTo("PAS submit");
        assertThat(rows.get(0).get("outcome").stringValue()).isEqualTo("APPROVED");
        assertThat(rows.get(0).get("result").stringValue()).isEqualTo("pass");
        assertThat(rows.get(1).get("what").stringValue()).isEqualTo("DTR questionnaire package");
        assertThat(rows.get(1).get("outcome").stringValue()).isEqualTo("PACKAGE_LOADED");
        // An expectation for a chain has to say which step it is about. This one does not, so nothing was sent for the row.
        assertThat(rows.get(2).get("status").stringValue()).isEqualTo("INVALID");
        assertThat(rows.get(2).get("reason").stringValue()).contains("does not say which step of the chain it is about");
        assertThat(rows.get(2).has("runId")).isFalse();
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
