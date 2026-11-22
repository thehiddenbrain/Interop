package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.config.TrailProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireResponse;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.ledger.RunTrail;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * What the trail keeps of a run (R-21): the result as the browser was answered and the case as
 * posted less what can hold a secret or a document; nothing for a refused write; and an answer
 * that never changes because keeping failed.
 */
@ExtendWith(OutputCaptureExtension.class)
class TrailRecorderTest {

    private static final String RUN_ID = "run-00000000-0000-0000-0000-00000000000a";
    private static final String CHAIN_ID = "chain-00000000-0000-0000-0000-00000000000b";
    private static final String HELD = "ZZTRAIL-SECRET-VALUE-0001";

    @TempDir
    Path directory;

    /** Answers each route with a result of the shape a service gives, chosen by the route. */
    @RestController
    public static class Runs {

        @PostMapping("/t/submit")
        public ResponseEntity<RunEnvelope<String>> submit(@RequestBody PasSubmitRequest request) {
            return RunResponses.of(envelope(request.chainId(), wire(), null));
        }

        @PostMapping("/t/refused")
        public ResponseEntity<RunEnvelope<String>> refused(@RequestBody PasSubmitRequest request) {
            return RunResponses.of(envelope(request.chainId(), new WireView(new WireRequest("POST", "https://payer.example.invalid/Claim/$submit",
                    null, List.of(), "{}", false, "Not sent."), null, null),
                    ErrorView.of(ErrorKind.BLOCKED, "This environment is production.", "Unlock it first.")));
        }

        @PostMapping("/t/nowire")
        public ResponseEntity<RunEnvelope<String>> noWire(@RequestBody PasSubmitRequest request) {
            return RunResponses.of(envelope(request.chainId(), null, null));
        }

        @GetMapping("/t/list")
        public ResponseEntity<RunEnvelope<String>> list() {
            return RunResponses.of(envelope(null, wire(), null));
        }
    }

    private static WireView wire() {
        return new WireView(new WireRequest("POST", "https://payer.example.invalid/Claim/$submit", null,
                List.of(new WireHeader("Authorization", "[secret access token]")), "{\"member\":\"S123456789\"}", true, null),
                new WireResponse(200, List.of(), "{\"resourceType\":\"Bundle\"}", 26L, true, null, 0, null), null);
    }

    private static RunEnvelope<String> envelope(String chainId, WireView wire, ErrorView error) {
        RunRecord record = RunRecord.builder().runId(RUN_ID).timestamp("2026-11-22T10:00:00.000Z").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome(CrdOutcome.REJECTED).result(TestResult.UNVERIFIED).build();
        return RunEnvelope.<String>builder().runId(RUN_ID).chainId(chainId).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT)
                .environment(new EnvironmentRef("vendor-sandbox", EnvironmentKind.SANDBOX)).profile("live")
                .verdict(VerdictView.of(CrdOutcome.REJECTED, "The service did not accept the request as sent.", List.of()))
                .expectation(Expectations.assess(null, CrdOutcome.REJECTED, null, null, CrdOutcome.values(), List.of()))
                .nextStep(NextStepView.of(NextAction.FIX_REQUEST, "Next.", "Because.")).detail("the protocol's own detail")
                .ledger(new LedgerReceipt(true, record, null, 1)).wire(wire).error(error).build();
    }

    private static final String CASE = "{\"claimId\":\"CLM-1\",\"member\":{\"memberId\":\"S123456789\",\"firstName\":\"Given\","
            + "\"lastName\":\"Placeholder-Surname\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
            + "\"dateOfService\":\"2026-03-20\",\"chainId\":\"" + CHAIN_ID + "\",\"variables\":{\"token\":\"" + HELD + "\"},"
            + "\"rawBody\":\"{\\\"own\\\":\\\"" + HELD + "\\\"}\",\"carry\":{\"claimId\":[\"CLM-0\"]},"
            + "\"request\":{\"collectionId\":\"c-1\",\"requestId\":\"r-1\"},"
            + "\"attachments\":[{\"title\":\"Sleep study\",\"contentType\":\"application/pdf\",\"data\":\"JVBERi0xLjQK\",\"size\":9},"
            + "{\"title\":\"Clinical note\",\"contentType\":\"text/plain\",\"text\":\"The whole note as typed.\"}]}";

    private RunTrail trail(Map<String, String> settings, Path history) {
        return new RunTrail(TrailProperties.of(settings), TrackingProperties.of(Map.of("directory", history.toString())),
                Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
    }

    private static MockMvc mvc(TrailRecorder recorder) {
        return MockMvcBuilders.standaloneSetup(new Runs())
                .setControllerAdvice(new ApiExceptionHandler(), recorder)
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter(),
                        new ResourceHttpMessageConverter(), new JacksonJsonHttpMessageConverter(TestMvc.mapper()))
                .build();
    }

    private static MvcResult post(MockMvc mvc, String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json").content(body)).andReturn();
    }

    @Test
    void anEntryHoldsTheResultAsAnsweredAndTheCaseLessWhatCanHoldASecretOrADocument() throws Exception {
        RunTrail trail = trail(Map.of(), directory);
        MvcResult answered = post(mvc(new TrailRecorder(trail, TestMvc.mapper())), "/t/submit", CASE);
        assertThat(answered.getResponse().getStatus()).isEqualTo(200);

        RunTrail.Lookup kept = trail.find(RUN_ID);
        assertThat(kept.kept()).isTrue();
        JsonNode entry = kept.entry();
        assertThat(entry.get("runId").asString()).isEqualTo(RUN_ID);
        assertThat(entry.get("chainId").asString()).isEqualTo(CHAIN_ID);
        assertThat(entry.get("protocol").asString()).isEqualTo("PAS");
        assertThat(entry.get("operation").asString()).isEqualTo("PAS_SUBMIT");
        assertThat(entry.get("environment").get("name").asString()).isEqualTo("vendor-sandbox");
        assertThat(entry.get("environment").get("kind").asString()).isEqualTo("sandbox");
        assertThat(entry.get("profile").asString()).isEqualTo("live");
        // The result exactly as the browser received it.
        assertThat(entry.get("envelope")).isEqualTo(JsonSafe.parse(answered.getResponse().getContentAsString()).node());

        JsonNode posted = entry.get("case");
        assertThat(posted.get("claimId").asString()).isEqualTo("CLM-1");
        assertThat(posted.get("member").get("memberId").asString()).isEqualTo("S123456789");
        assertThat(posted.get("member").get("lastName").asString()).isEqualTo("Placeholder-Surname");
        assertThat(posted.get("dateOfService").asString()).isEqualTo("2026-03-20");
        for (String gone : List.of("variables", "rawBody", "carry", "request", "unrecognised")) {
            assertThat(posted.has(gone)).as(gone).isFalse();
        }
        JsonNode attachments = posted.get("attachments");
        assertThat(attachments.get(0).get("title").asString()).isEqualTo("Sleep study");
        assertThat(attachments.get(0).get("size").asInt()).isEqualTo(9);
        assertThat(attachments.get(0).has("data")).isFalse();
        assertThat(attachments.get(1).get("title").asString()).isEqualTo("Clinical note");
        assertThat(attachments.get(1).has("text")).isFalse();

        String file = new String(trail.bytes(RUN_ID), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(file).doesNotContain(HELD).doesNotContain("JVBERi0xLjQK").doesNotContain("The whole note as typed.")
                .contains("S123456789");
    }

    @Test
    void aRunWithNoPostedCaseIsKeptWithoutOne() throws Exception {
        RunTrail trail = trail(Map.of(), directory);
        MvcResult answered = mvc(new TrailRecorder(trail, TestMvc.mapper())).perform(get("/t/list")).andReturn();
        assertThat(answered.getResponse().getStatus()).isEqualTo(200);
        JsonNode entry = trail.find(RUN_ID).entry();
        assertThat(entry.has("case")).isFalse();
        assertThat(entry.has("chainId")).isFalse();
        assertThat(entry.get("envelope").get("wire").get("request").get("method").asString()).isEqualTo("POST");
    }

    @Test
    void aGuardrailRefusalWithNothingSentIsNotKeptNorARunWithNoWireRequest() throws Exception {
        RunTrail trail = trail(Map.of(), directory);
        MockMvc mvc = mvc(new TrailRecorder(trail, TestMvc.mapper()));
        assertThat(post(mvc, "/t/refused", CASE).getResponse().getStatus()).isEqualTo(403);
        assertThat(post(mvc, "/t/nowire", CASE).getResponse().getStatus()).isEqualTo(200);
        assertThat(trail.summary().count()).isZero();
        assertThat(Files.exists(directory.resolve("trail"))).isFalse();
    }

    @Test
    void switchedOffNothingIsKept() throws Exception {
        RunTrail trail = trail(Map.of("enabled", "false"), directory);
        assertThat(post(mvc(new TrailRecorder(trail, TestMvc.mapper())), "/t/submit", CASE).getResponse().getStatus()).isEqualTo(200);
        assertThat(Files.exists(directory.resolve("trail"))).isFalse();
    }

    @Test
    void aTrailThatCannotBeWrittenNeverTouchesTheAnswerAndLogsTypesOnly(CapturedOutput output) throws Exception {
        // A file where the history folder should be: the trail's folder cannot be made under it.
        Path blocked = Files.writeString(directory.resolve("not-a-folder"), "x");
        RunTrail trail = trail(Map.of(), blocked);
        MvcResult withTrail = post(mvc(new TrailRecorder(trail, TestMvc.mapper())), "/t/submit", CASE);
        MvcResult without = post(mvc(new TrailRecorder(trail(Map.of("enabled", "false"), directory), TestMvc.mapper())), "/t/submit", CASE);
        assertThat(withTrail.getResponse().getStatus()).isEqualTo(200);
        assertThat(withTrail.getResponse().getContentAsString()).isEqualTo(without.getResponse().getContentAsString());
        assertThat(output.getAll()).contains("A run could not be kept in the trail (java.nio.file.");
        assertThat(output.getAll()).doesNotContain(blocked.toString()).doesNotContain("S123456789").doesNotContain(RUN_ID)
                .doesNotContain(HELD);
    }

    @Test
    void theCaseIsHeldOnlyForARunRequest() {
        TrailRecorder recorder = new TrailRecorder(trail(Map.of(), directory), TestMvc.mapper());
        assertThat(recorder.supports(null, PasSubmitRequest.class, JacksonJsonHttpMessageConverter.class)).isTrue();
        assertThat(recorder.supports(null, Map.class, JacksonJsonHttpMessageConverter.class)).isFalse();
        assertThat(TrailRecorder.keeps(envelope(null, wire(), null))).isTrue();
        assertThat(TrailRecorder.keeps(envelope(null, new WireView(null, null, null), null))).isFalse();
    }
}
