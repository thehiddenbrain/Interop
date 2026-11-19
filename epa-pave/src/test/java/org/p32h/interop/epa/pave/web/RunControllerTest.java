package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.ledger.JsonlRunLedger;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MovableClock;
import org.p32h.interop.epa.pave.service.ledger.RunCsv;
import org.p32h.interop.epa.pave.service.ledger.RunReplay;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Every ledger endpoint over HTTP, against the real ledger in a temporary folder: the status, the
 * shape of the answer, what a bad request is told and what an unknown run is told.
 */
class RunControllerTest {

    private static final String CHAIN = "chain-00000000-0000-4000-8000-000000000001";

    @TempDir
    Path home;

    private final MovableClock clock = LedgerFixtures.clock();
    private final List<CaseSpec> askedOfTheRunner = new ArrayList<>();
    private UnaryOperator<RunRecord.Builder> changeOnReplay = same -> same;
    private JsonlRunLedger ledger;
    private MockMvc mvc;
    private MockMvc mvcWithNoRunner;

    @BeforeEach
    void threeRunsOverTwoDays() {
        ledger = recordingIds(home, clock);
        ledger.record(draft("run-1", clock).chainId(CHAIN).testerRef("QA-17").build());
        ledger.record(draft("run-2", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_INQUIRE).outcome("APPROVED")
                .result(TestResult.FAIL).suiteRunId("suite-1").build());
        clock.advance(Duration.ofDays(1));
        ledger.record(draft("run-3", clock).build());

        // Stands in for the runner a later wave writes: it runs the case and gets the original's answer, changed as a test says.
        CaseRunner runner = spec -> {
            askedOfTheRunner.add(spec);
            RunRecord original = ledger.find(spec.replayOfRunId()).orElseThrow();
            RunRecord again = ledger.record(changeOnReplay.apply(original.toBuilder().runId("replay-of-" + original.runId())
                    .timestamp(RunStamps.timestamp(clock)).replayOfRunId(original.runId()).chainId(null).suiteRunId(null)).build()).record();
            return new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(again));
        };
        StaticListableBeanFactory withRunner = new StaticListableBeanFactory();
        withRunner.addBean("runner", runner);
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        mvc = TestMvc.standalone(new RunController(ledger, new RunReplay(ledger, withRunner.getBeanProvider(CaseRunner.class),
                withRunner.getBeanProvider(OperationGateway.class), clock)));
        mvcWithNoRunner = TestMvc.standalone(new RunController(ledger, new RunReplay(ledger, empty.getBeanProvider(CaseRunner.class),
                empty.getBeanProvider(OperationGateway.class), clock)));
    }

    // ---- GET /api/runs ----

    @Test
    void theQueryReturnsTheRunsNewestFirstAndTheCount() throws Exception {
        mvc.perform(get("/api/runs")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.limit").doesNotExist())
                .andExpect(jsonPath("$.runs", hasSize(3)))
                .andExpect(jsonPath("$.runs[0].runId").value("run-3"))
                .andExpect(jsonPath("$.runs[2].runId").value("run-1"))
                .andExpect(jsonPath("$.runs[2].recordType").value("run"))
                .andExpect(jsonPath("$.runs[2].timestamp").value("2026-03-10T11:00:00-04:00"))
                .andExpect(jsonPath("$.runs[2].protocol").value("CRD"))
                .andExpect(jsonPath("$.runs[2].operation").value("CRD_ORDER_SIGN"))
                .andExpect(jsonPath("$.runs[2].outcome").value("NO_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.runs[2].result").value("pass"))
                .andExpect(jsonPath("$.runs[2].nextAction").value("FLOW_ENDS"))
                .andExpect(jsonPath("$.runs[2].memberId").value(MEMBER_ID))
                .andExpect(jsonPath("$.runs[2].chainId").value(CHAIN))
                // The ledger record is the one type that writes its nulls, so every row has the same columns.
                .andExpect(jsonPath("$.runs[2].note").value((Object) null))
                .andExpect(jsonPath("$.runs[2].delegatedVendor").value((Object) null));
    }

    @Test
    void everyFilterOfTheQueryIsApplied() throws Exception {
        mvc.perform(get("/api/runs").param("protocol", "pas")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-2"));
        mvc.perform(get("/api/runs").param("operation", "pas-inquire")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-2"));
        mvc.perform(get("/api/runs").param("operation", "CRD_ORDER_SIGN")).andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/runs").param("result", "FAIL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-2"));
        mvc.perform(get("/api/runs").param("outcome", "APPROVED")).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/runs").param("chainId", CHAIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-1"));
        mvc.perform(get("/api/runs").param("suiteRunId", "suite-1")).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.runs[0].runId").value("run-2"));
        mvc.perform(get("/api/runs").param("from", "2026-03-11").param("to", "2026-03-11")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-3"));
        mvc.perform(get("/api/runs").param("to", "2026-03-10")).andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/runs").param("from", "2026-03-10").param("to", "2026-03-10").param("protocol", "crd").param("result", "pass"))
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.runs[0].runId").value("run-1"));
        // Nothing matches: an empty list, which is an answer and not an error.
        mvc.perform(get("/api/runs").param("protocol", "dtr")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.runs", hasSize(0)));
        // A parameter a form sends empty is not a filter.
        mvc.perform(get("/api/runs").param("protocol", "").param("operation", " ").param("result", "").param("outcome", "").param("chainId", "")
                        .param("suiteRunId", "").param("from", "").param("to", "").param("limit", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void aLimitKeepsTheNewestAndTheAnswerSaysWhenThereAreMore() throws Exception {
        mvc.perform(get("/api/runs").param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.runs[0].runId").value("run-3"))
                .andExpect(jsonPath("$.runs[1].runId").value("run-2"));
        mvc.perform(get("/api/runs").param("limit", "3")).andExpect(jsonPath("$.count").value(3)).andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(get("/api/runs").param("limit", "0")).andExpect(jsonPath("$.count").value(3)).andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.limit").doesNotExist());
        mvc.perform(get("/api/runs").param("limit", "2147483647")).andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void aMalformedQueryParameterIs422AndNamesTheParameterInThisToolsOwnWords() throws Exception {
        mvc.perform(get("/api/runs").param("from", "10/03/2026")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter the date as YYYY-MM-DD, for example 2026-01-31."));
        mvc.perform(get("/api/runs").param("to", "2026-02-30")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mvc.perform(get("/api/runs").param("protocol", "fax")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("protocol"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("The value given for protocol is not valid. It must be one of crd, dtr, pas, subscription, lookup or raw."));
        mvc.perform(get("/api/runs").param("operation", "PAS_CANCEL")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operation"))
                .andExpect(jsonPath("$.fieldErrors[0].message", containsString("PAS_SUBMIT, PAS_INQUIRE")));
        mvc.perform(get("/api/runs").param("result", "maybe")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("result"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The value given for result is not valid. It must be pass, fail or unverified."));
        mvc.perform(get("/api/runs").param("limit", "ten")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The value given for limit is not valid. It must be a whole number."));
        mvc.perform(get("/api/runs").param("limit", "-1")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        // A number is not a position in the list of protocols.
        mvc.perform(get("/api/runs").param("protocol", "2")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aRangeTheWrongWayRoundIs422AndEveryBadParameterIsNamedAtOnce() throws Exception {
        mvc.perform(get("/api/runs").param("from", "2026-03-11").param("to", "2026-03-10")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The from date is after the to date."));
        mvc.perform(get("/api/runs").param("from", "yesterday").param("protocol", "fax").param("result", "maybe").param("limit", "x"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request has 4 problem(s). Nothing was done."))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.contains("from", "protocol", "result", "limit")));
    }

    // ---- GET /api/runs/{runId} ----

    @Test
    void oneRunIsReturnedByItsId() throws Exception {
        mvc.perform(get("/api/runs/run-2")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value("run-2"))
                .andExpect(jsonPath("$.protocol").value("PAS"))
                .andExpect(jsonPath("$.operation").value("PAS_INQUIRE"))
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.result").value("fail"))
                .andExpect(jsonPath("$.suiteRunId").value("suite-1"))
                .andExpect(jsonPath("$.elapsedMillis").value(42));
    }

    @Test
    void aRunThatDoesNotExistIs404InTheSharedErrorShape() throws Exception {
        mvc.perform(get("/api/runs/run-404")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("There is no run with id run-404."))
                .andExpect(jsonPath("$.action").isNotEmpty());
        // Text that is not an id is not repeated back.
        mvc.perform(get("/api/runs/{runId}", "<b>not an id</b>".replace("/", ""))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("There is no run with that id."));
    }

    // ---- POST /api/runs/{runId}/note ----

    @Test
    void aNoteIsAddedAndTheAnswerHoldsTheReceiptAndTheRun() throws Exception {
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"retest after the fix, see \\\"ticket 12\\\"\",\"testerRef\":\"QA-18\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.recorded").value(true))
                .andExpect(jsonPath("$.receipt.message").doesNotExist())
                .andExpect(jsonPath("$.receipt.runsToday").value(1))
                .andExpect(jsonPath("$.receipt.record.runId").value("run-1"))
                .andExpect(jsonPath("$.run.runId").value("run-1"))
                .andExpect(jsonPath("$.run.note").value("retest after the fix, see \"ticket 12\""))
                .andExpect(jsonPath("$.run.testerRef").value("QA-18"));
        mvc.perform(get("/api/runs/run-1")).andExpect(jsonPath("$.note").value("retest after the fix, see \"ticket 12\""))
                .andExpect(jsonPath("$.testerRef").value("QA-18"));

        // Amended: only what is given changes, and a blank value clears.
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"closed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.note").value("closed")).andExpect(jsonPath("$.run.testerRef").value("QA-18"));
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.note").value((Object) null))
                .andExpect(jsonPath("$.run.testerRef").value("QA-18"));
        assertThat(ledger.find("run-1").orElseThrow().note()).isNull();
        // The other runs were not touched.
        assertThat(ledger.find("run-2").orElseThrow().note()).isNull();
    }

    @Test
    void aNoteWithNothingInItOrTooMuchInItIs422() throws Exception {
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":null,\"testerRef\":null}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        String tooLong = "x".repeat(RunController.LONGEST_NOTE + 1);
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + tooLong + "\",\"testerRef\":\"" + "y".repeat(RunController.LONGEST_TESTER_REF + 1) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"))
                .andExpect(jsonPath("$.fieldErrors[0].message", startsWith("A note can be at most 4000 characters, and this one has 4001.")))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("testerRef"));
        // The longest note that is allowed is allowed.
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "x".repeat(RunController.LONGEST_NOTE) + "\"}")).andExpect(status().isOk());
        // No body, and a body that is not JSON.
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":"))
                .andExpect(status().isUnprocessableContent());
        assertThat(ledger.find("run-1").orElseThrow().note()).isEqualTo("x".repeat(RunController.LONGEST_NOTE));
    }

    @Test
    void aMisspeltPropertyInANoteIs422AndIsNamed() throws Exception {
        // Dropped without a word, the tester reference would not be saved and nobody would be told.
        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"a note\",\"testerReference\":\"QA-18\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("testerReference"));
        assertThat(ledger.find("run-1").orElseThrow().note()).isNull();
    }

    @Test
    void whatWasTypedIntoANoteIsNotPrintedWithTheRequest() {
        // A note is free text, so a request that is logged one day must not take the note with it.
        assertThat(new RunController.NoteRequest("Placeholder-Surname rang about this one", "QA-17").toString())
                .isEqualTo("NoteRequest[note given, testerRef given]");
        assertThat(new RunController.NoteRequest(null, null).toString()).isEqualTo("NoteRequest[note not given, testerRef not given]");
    }

    @Test
    void aNoteOnARunThatDoesNotExistIs404() throws Exception {
        mvc.perform(post("/api/runs/run-404/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"a note\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("There is no run with id run-404."));
    }

    @Test
    void aNoteThatCouldNotBeWrittenAnswers200WithAReceiptThatSaysSo() throws Exception {
        // A folder where the next day's file should be, so the write fails for an administrator too.
        clock.advance(Duration.ofDays(1));
        Files.createDirectory(dayFile(home, LocalDate.of(2026, 3, 12)));

        mvc.perform(post("/api/runs/run-1/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"this one is lost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.recorded").value(false))
                .andExpect(jsonPath("$.receipt.message", startsWith("The note was NOT recorded: the ledger file ")))
                .andExpect(jsonPath("$.receipt.runsToday").doesNotExist())
                // The run as the ledger still has it, without a note that was not kept.
                .andExpect(jsonPath("$.run.runId").value("run-1"))
                .andExpect(jsonPath("$.run.note").value((Object) null));
    }

    // ---- GET /api/runs/export.csv ----

    @Test
    void theExportIsCsvWithAHeaderRowAndOneRowForEachRunInTheRange() throws Exception {
        ledger.addNote("run-1", "=1+1, said the tester", "QA-17");

        MvcResult all = mvc.perform(get("/api/runs/export.csv")).andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"pave-runs-all.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        byte[] file = all.getResponse().getContentAsByteArray();
        assertThat(java.util.Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String[] rows = new String(file, 3, file.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(rows).hasSize(4);
        assertThat(rows[0]).isEqualTo(String.join(",", RunCsv.header()));
        assertThat(rows[1]).startsWith("run,run-3,2026-03-11T11:00:00-04:00,CRD,CRD_ORDER_SIGN,");
        assertThat(rows[2]).startsWith("run,run-2,");
        // The note is quoted because of its comma, and cannot be run as a formula.
        assertThat(rows[3]).startsWith("run,run-1,").contains(",\"'=1+1, said the tester\",QA-17,");

        MvcResult tenth = mvc.perform(get("/api/runs/export.csv").param("from", "2026-03-10").param("to", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"pave-runs-from-2026-03-10-to-2026-03-10.csv\""))
                .andReturn();
        String[] tenthRows = tenth.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(tenthRows).hasSize(3);
        assertThat(tenthRows[1]).startsWith("run,run-2,");
        assertThat(tenthRows[2]).startsWith("run,run-1,");

        // A range with no runs is a file with a header and nothing else.
        MvcResult none = mvc.perform(get("/api/runs/export.csv").param("to", "2026-01-31")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"pave-runs-to-2026-01-31.csv\"")).andReturn();
        assertThat(none.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("\uFEFF" + String.join(",", RunCsv.header()) + "\r\n");
        mvc.perform(get("/api/runs/export.csv").param("from", "2026-03-11"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"pave-runs-from-2026-03-11.csv\""));
    }

    @Test
    void aBadDateOnTheExportIs422InTheSharedErrorShapeAndNotACsv() throws Exception {
        mvc.perform(get("/api/runs/export.csv").param("from", "March")).andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
        mvc.perform(get("/api/runs/export.csv").param("from", "2026-03-11").param("to", "2026-03-10"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The from date is after the to date."));
    }

    // ---- POST /api/runs/replay ----

    @Test
    void aReplayRunsTheSelectedRunsAgainAndReportsOnEachOne() throws Exception {
        changeOnReplay = again -> "run-2".equals(again.build().replayOfRunId()) ? again.outcome("DENIED").result(TestResult.PASS) : again;

        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-03-10\",\"to\":\"2026-03-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").value("2026-03-11T11:00:00-04:00"))
                .andExpect(jsonPath("$.from").value("2026-03-10"))
                .andExpect(jsonPath("$.to").value("2026-03-10"))
                .andExpect(jsonPath("$.runnerAvailable").value(true))
                .andExpect(jsonPath("$.selected").value(2))
                .andExpect(jsonPath("$.notes", hasSize(0)))
                .andExpect(jsonPath("$.summary.same").value(1))
                .andExpect(jsonPath("$.summary.changed").value(1))
                .andExpect(jsonPath("$.summary['not-replayable']").value(0))
                .andExpect(jsonPath("$.summary.blocked").value(0))
                .andExpect(jsonPath("$.summary['not-configured']").value(0))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].runId").value("run-1"))
                .andExpect(jsonPath("$.results[0].status").value("same"))
                .andExpect(jsonPath("$.results[0].replayRunId").value("replay-of-run-1"))
                .andExpect(jsonPath("$.results[0].differences", hasSize(0)))
                .andExpect(jsonPath("$.results[0].reason").doesNotExist())
                .andExpect(jsonPath("$.results[0].original.runId").value("run-1"))
                .andExpect(jsonPath("$.results[0].replayed.replayOfRunId").value("run-1"))
                .andExpect(jsonPath("$.results[1].runId").value("run-2"))
                .andExpect(jsonPath("$.results[1].status").value("changed"))
                .andExpect(jsonPath("$.results[1].differences", hasSize(2)))
                .andExpect(jsonPath("$.results[1].differences[0].field").value("outcome"))
                .andExpect(jsonPath("$.results[1].differences[0].was").value("APPROVED"))
                .andExpect(jsonPath("$.results[1].differences[0].now").value("DENIED"))
                .andExpect(jsonPath("$.results[1].differences[1].field").value("result"))
                .andExpect(jsonPath("$.results[1].differences[1].was").value("fail"))
                .andExpect(jsonPath("$.results[1].differences[1].now").value("pass"));
        assertThat(askedOfTheRunner).extracting(CaseSpec::replayOfRunId).containsExactly("run-1", "run-2");

        // By id, and both together.
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":[\"run-3\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(1)).andExpect(jsonPath("$.results[0].runId").value("run-3"))
                .andExpect(jsonPath("$.from").doesNotExist());
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-03-10\",\"to\":\"2026-03-10\",\"runIds\":[\"run-2\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(1)).andExpect(jsonPath("$.results[0].runId").value("run-2"));
    }

    @Test
    void aReplayWithNeitherARangeNorIdsIs422() throws Exception {
        for (String body : List.of("{}", "{\"runIds\":[]}", "{\"from\":\"\",\"to\":null,\"runIds\":null}")) {
            mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.title").value("Nothing was selected to run again. Nothing was sent."))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("runIds"));
        }
        assertThat(askedOfTheRunner).isEmpty();
    }

    @Test
    void aReplayWithABadDateABlankIdOrAnIdOutsideItsRangeIs422() throws Exception {
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"last Tuesday\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2026-03-11\",\"to\":\"2026-03-10\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The from date is after the to date."));
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":[\"run-1\",\"\"]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("runIds[1]"));
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-03-10\",\"to\":\"2026-03-10\",\"runIds\":[\"run-3\"]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("runIds[0]"));
        // A value of the wrong kind for a field.
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":{\"id\":\"run-1\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("runIds"));
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableContent());
        assertThat(askedOfTheRunner).isEmpty();
    }

    @Test
    void aMisspeltPropertyInAReplayIs422AndIsNamed() throws Exception {
        // Dropped without a word, this would be a replay of nothing, or of a whole range where three runs were meant.
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-03-10\",\"runIDs\":[\"run-1\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("runIDs"));
        assertThat(askedOfTheRunner).isEmpty();
    }

    @Test
    void aReplayOfARunThatDoesNotExistIs404AndNothingIsSent() throws Exception {
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":[\"run-1\",\"run-404\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("There is no run with id run-404. Nothing was sent."));
        assertThat(askedOfTheRunner).isEmpty();
    }

    @Test
    void withNoRunnerInTheBuildAReplayStillAnswersAndSaysThatNothingCouldBeRun() throws Exception {
        mvcWithNoRunner.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":[\"run-1\",\"run-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerAvailable").value(false))
                .andExpect(jsonPath("$.notes[0]").value("Nothing in this build can run a case again, so no run was sent."))
                .andExpect(jsonPath("$.selected").value(2))
                .andExpect(jsonPath("$.summary['not-replayable']").value(2))
                .andExpect(jsonPath("$.results[0].status").value("not-replayable"))
                .andExpect(jsonPath("$.results[0].reason").value("Nothing in this build can run a case again, so no run was sent."))
                .andExpect(jsonPath("$.results[1].status").value("not-replayable"));
    }

    // ---- GET /api/runs/health ----

    @Test
    void healthSaysThatTrackingIsAliveAndHowManyRunsWereRecordedToday() throws Exception {
        mvc.perform(get("/api/runs/health")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.directory").value(LedgerFixtures.ledgerDirectory(home).toString()))
                .andExpect(jsonPath("$.currentFile").value("runs-2026-03-11.jsonl"))
                .andExpect(jsonPath("$.runsToday").value(1))
                .andExpect(jsonPath("$.unreadableLines").value(0))
                .andExpect(jsonPath("$.lastWriteAt").value("2026-03-11T11:00:00-04:00"))
                .andExpect(jsonPath("$.failedWrites").value(0))
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void healthSaysWhenARunWentUnrecorded() throws Exception {
        clock.advance(Duration.ofDays(1));
        Files.createDirectory(dayFile(home, LocalDate.of(2026, 3, 12)));
        ledger.record(draft("run-lost", clock).build());

        mvc.perform(get("/api/runs/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.writable").value(false))
                .andExpect(jsonPath("$.failedWrites").value(1))
                .andExpect(jsonPath("$.lastError", startsWith("This run was NOT recorded: the ledger file ")))
                .andExpect(jsonPath("$.lastErrorAt").value("2026-03-12T11:00:00-04:00"))
                .andExpect(jsonPath("$.lastWriteAt").value("2026-03-11T11:00:00-04:00"));
    }
}
