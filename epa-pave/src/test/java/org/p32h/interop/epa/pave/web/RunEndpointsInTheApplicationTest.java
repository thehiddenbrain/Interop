package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.ledger.JsonlRunLedger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ledger endpoints in the application as it starts on a fresh checkout, with no profile set.
 *
 * <p>The other controller tests build the controller by hand. This one proves what they cannot:
 * that the ledger is a bean on the default profile, that the endpoints are mapped, that their
 * arguments bind by name, and that the application's own JSON mapper writes and reads these
 * bodies. Only the two storage folders are pointed at a temporary place.
 */
@SpringBootTest
class RunEndpointsInTheApplicationTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired RunLedger ledger;
    @Autowired TrackingProperties tracking;
    @Autowired Clock clock;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private LedgerReceipt record(String runId) {
        return ledger.record(RunRecord.builder().runId(runId).timestamp(RunStamps.timestamp(clock)).protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).target("onyx").profile("mock").outcome(CrdOutcome.NO_AUTH_REQUIRED)
                .procedureCodes(List.of("95810")).result(TestResult.UNVERIFIED).memberId("S123456789").npi("1234567893").build());
    }

    @Test
    void theLedgerIsWiredOnTheDefaultProfileAndWritesWhereItIsConfiguredTo() throws Exception {
        assertThat(ledger).isInstanceOf(JsonlRunLedger.class);
        Path directory = home.resolve("run-history");

        LedgerReceipt receipt = record("run-wired-1");

        assertThat(receipt.recorded()).isTrue();
        // Mock records member IDs, because every one of them is synthetic.
        assertThat(tracking.recordMemberId()).isTrue();
        assertThat(receipt.record().memberId()).isEqualTo("S123456789");
        // The salt is in the config store, and the ledger directory holds day files and nothing else.
        assertThat(Files.size(home.resolve("config-store").resolve("installation-salt"))).isEqualTo(32);
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.toList();
        }
        assertThat(files).isNotEmpty().allMatch(path -> path.getFileName().toString().matches("runs-\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
        StringBuilder written = new StringBuilder();
        for (Path file : files) {
            written.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        assertThat(written.toString()).contains("\"runId\":\"run-wired-1\"");
    }

    @Test
    void theEndpointsAnswerThroughTheApplicationsOwnMapperAndErrorHandler() throws Exception {
        record("run-wired-2");
        MockMvc mvc = mvc();

        mvc.perform(get("/api/runs/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.directory").value(home.resolve("run-history").toString()))
                .andExpect(jsonPath("$.currentFile").value(org.hamcrest.Matchers.matchesPattern("runs-\\d{4}-\\d{2}-\\d{2}\\.jsonl")))
                .andExpect(jsonPath("$.failedWrites").value(0));

        String one = mvc.perform(get("/api/runs/run-wired-2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // The ledger record writes its nulls whichever mapper serialises it, and in the order of a ledger line.
        assertThat(one).startsWith("{\"recordType\":\"run\",\"runId\":\"run-wired-2\",\"timestamp\":").contains("\"note\":null")
                .contains("\"delegatedVendor\":null").contains("\"result\":\"unverified\"");

        mvc.perform(get("/api/runs").param("protocol", "crd").param("limit", "500")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[?(@.runId == 'run-wired-2')].outcome").value("NO_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(get("/api/runs").param("result", "maybe")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION")).andExpect(jsonPath("$.fieldErrors[0].field").value("result"));
        mvc.perform(get("/api/runs/run-nobody-made")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"));

        mvc.perform(post("/api/runs/run-wired-2/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"seen\",\"testerRef\":\"QA-17\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receipt.recorded").value(true)).andExpect(jsonPath("$.run.note").value("seen"));
        // The application reads a body strictly: a property it does not know is refused and named.
        mvc.perform(post("/api/runs/run-wired-2/note").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\",\"tester\":\"QA-17\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("tester"));
        mvc.perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIDs\":[\"run-wired-2\"]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("runIDs"));

        mvc.perform(get("/api/runs/export.csv")).andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("run,run-wired-2,")));
        // A refused export is the shared JSON error and not a CSV, though the address ends in .csv
        // and a browser following a download link asks for no JSON.
        mvc.perform(get("/api/runs/export.csv").param("from", "March").accept(MediaType.TEXT_HTML, MediaType.ALL))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("VALIDATION")).andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
        mvc.perform(get("/api/runs/export.csv").accept(MediaType.TEXT_HTML, MediaType.ALL)).andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"));
    }

    @Test
    void aReplayAnswersWithAnEntryForTheRunWhateverThisBuildCanRun() throws Exception {
        record("run-wired-3");

        // Which status it gets depends on whether this build has a case runner. That the run is
        // in the report, and that the counts add up, does not.
        mvc().perform(post("/api/runs/replay").contentType(MediaType.APPLICATION_JSON).content("{\"runIds\":[\"run-wired-3\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(1))
                .andExpect(jsonPath("$.results[0].runId").value("run-wired-3"))
                .andExpect(jsonPath("$.results[0].status").isNotEmpty())
                .andExpect(jsonPath("$.results[0].original.runId").value("run-wired-3"))
                .andExpect(jsonPath("$.summary").isMap());
    }
}
