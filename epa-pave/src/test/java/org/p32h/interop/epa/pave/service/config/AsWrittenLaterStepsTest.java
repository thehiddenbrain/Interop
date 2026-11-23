package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.dtr.DtrNextQuestionRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.pas.PasInquireRequest;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The questionnaire package, the next question, Submit and the inquiry sent as the collection
 * writes it (D-313): each sends the stored body of the request in use with the values put in, reads
 * the answer as its own step's, says that nothing handed on was put into it, and keeps no body in
 * the ledger; a placeholder with no value is refused before the send; on production a Submit as
 * written is blocked as any Submit is.
 */
class AsWrittenLaterStepsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String PACKAGE = "{\"resourceType\": \"Parameters\", \"id\": \"{{$guid}}\", \"parameter\": [{\"name\": \"coverage\", "
            + "\"resource\": {\"resourceType\": \"Coverage\", \"subscriberId\": \"S123456789\"}}]}";
    private static final String NEXT = "{\"resourceType\": \"QuestionnaireResponse\", \"status\": \"in-progress\", \"item\": "
            + "[{\"linkId\": \"written-in-the-collection\"}]}";
    private static final String SUBMIT = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"timestamp\": \"{{$isoTimestamp}}\", "
            + "\"entry\": [{\"resource\": {\"resourceType\": \"Claim\", \"identifier\": [{\"value\": \"written-claim-1\"}], "
            + "\"use\": \"preauthorization\"}}]}";
    private static final String INQUIRE = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": "
            + "{\"resourceType\": \"Claim\", \"identifier\": [{\"value\": \"{{claim_id}}\"}]}}]}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ConfigFixture fixture(String environment, EnvironmentKind kind) {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(environment, kind);
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("Fetch Questionnaire", "POST", "https://vendor.example.invalid/r4/Questionnaire/$questionnaire-package").body(PACKAGE),
                ConfigFixture.item("Next Question", "POST", "https://vendor.example.invalid/r4/Questionnaire/$next-question").body(NEXT),
                ConfigFixture.item("Claim Submit", "POST", "https://vendor.example.invalid/r4/Claim/$submit").body(SUBMIT),
                ConfigFixture.item("Claim Status", "POST", "https://vendor.example.invalid/r4/Claim/$inquire").body(INQUIRE)));
        return fixture;
    }

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private RunSupport support() {
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                ledgerProvider(), CLOCK);
    }

    private DtrService dtr(ConfigFixture fixture) {
        RunSupport support = support();
        return new DtrService(support.epa(), fixture.gateway, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support);
    }

    private PasService pas(ConfigFixture fixture) {
        return new PasService(OnyxProperties.defaults(), fixture.gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)),
                new FakeCodeList(), support(), ledgerProvider());
    }

    private static String sent(RunEnvelope<?> result) {
        return result.wire().request().body();
    }

    private static void saysWhatWasSent(List<String> notes, String requestName) {
        assertThat(notes).anyMatch(note -> note.startsWith("The body is the collection's own, from the request \"" + requestName + "\""))
                .contains("What the step before handed on was not put into it.")
                .noneMatch(note -> note.contains("you supplied"));
    }

    private void theLedgerHoldsNoBody(String part) {
        for (RunRecord line : ledger.records()) {
            assertThat(line.unrecordedInputs()).contains(RunRecord.INPUT_BODY);
            assertThat(JsonSafe.mapper().writeValueAsString(line)).doesNotContain(part);
        }
    }

    @Test
    void theQuestionnairePackageAndTheNextQuestionSendTheCollectionsBodyAndReadTheAnswerAsTheirOwn() {
        ConfigFixture fixture = fixture(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        DtrService service = dtr(fixture);
        Map<String, List<String>> handedOn = Map.of("questionnaire", List.of("https://example.org/p32h-placeholder/Questionnaire/from-step-1"));

        RunEnvelope<DtrDetail> pack = service.runPackage(DtrPackageRequest.builder().asWritten(true).carry(handedOn).build(), RunOrigin.manual(),
                null);
        assertThat(JsonSafe.parse(sent(pack)).node().get("id").asString()).matches("[0-9a-f-]{36}");
        assertThat(sent(pack)).contains("S123456789").doesNotContain("from-step-1");
        assertThat(pack.verdict()).isNotNull();
        assertThat(pack.nextStep()).isNotNull();
        saysWhatWasSent(pack.detail().requestNotes(), "Fetch Questionnaire");

        // On mock a scenario answers a body as written, as it answers a body of the tester's own: one that covers the next question.
        RunEnvelope<DtrDetail> next = service.runNextQuestion(DtrNextQuestionRequest.builder().asWritten(true).scenarioId("dtr-02-adaptive-branching")
                .build(), RunOrigin.manual(), null);
        assertThat(sent(next)).contains("written-in-the-collection");
        assertThat(next.verdict()).isNotNull();
        saysWhatWasSent(next.detail().requestNotes(), "Next Question");
        theLedgerHoldsNoBody("written-in-the-collection");
        theLedgerHoldsNoBody("\"parameter\"");
    }

    @Test
    void submitAndTheInquirySendTheCollectionsBodyAndAPlaceholderWithNoValueIsRefusedBeforeTheSend() {
        ConfigFixture fixture = fixture(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        PasService service = pas(fixture);

        RunEnvelope<PasDetail> submit = service.runSubmit(PasSubmitRequest.builder().asWritten(true).build(), RunOrigin.manual(), null);
        assertThat(sent(submit)).contains("written-claim-1").doesNotContain("{{$isoTimestamp}}");
        assertThat(submit.verdict()).isNotNull();
        saysWhatWasSent(submit.detail().requestNotes(), "Claim Submit");

        // The inquiry's body names {{claim_id}}, which nothing defines: refused before anything is sent.
        int before = ledger.records().size();
        assertThatThrownBy(() -> service.runInquire(PasInquireRequest.builder().asWritten(true).build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, refusal -> assertThat(refusal.variables()).containsExactly("claim_id"));
        assertThat(ledger.records()).hasSize(before);
        RequestPreview preview = service.previewInquire(PasInquireRequest.builder().asWritten(true).build(), null);
        assertThat(preview.notes()).anyMatch(note -> note.startsWith("No value for {{claim_id}}. Type one in Values for this run"));

        RunEnvelope<PasDetail> inquiry = service.runInquire(PasInquireRequest.builder().asWritten(true)
                .variables(Map.of("claim_id", "written-claim-1")).build(), RunOrigin.manual(), null);
        assertThat(sent(inquiry)).contains("\"value\": \"written-claim-1\"");
        assertThat(inquiry.verdict()).isNotNull();
        theLedgerHoldsNoBody("preauthorization");
    }

    @Test
    void onProductionASubmitAsWrittenIsBlockedAsAnySubmitIs() {
        ConfigFixture fixture = fixture(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);

        RunEnvelope<PasDetail> blocked = pas(fixture).runSubmit(PasSubmitRequest.builder().asWritten(true).build(), RunOrigin.manual(), null);

        assertThat(blocked.verdict().outcomeCode()).isEqualTo("BLOCKED");
        assertThat(blocked.wire().request().sent()).isFalse();
        assertThat(blocked.wire().request().body()).isNull();
        assertThat(ledger.only().outcome()).isEqualTo("BLOCKED");
    }
}
