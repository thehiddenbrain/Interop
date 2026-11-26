package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.pas.SubscriptionRequest;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** What every protocol service shares around a call, tested on its own where a rule is the shared type's and not a protocol's. */
class RunSupportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String RUN = "run-00000000-0000-4000-8000-000000000001";

    private final InMemoryLedger ledger = new InMemoryLedger();

    private RunSupport support() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", "live")), TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    private static ProdWriteBlockedException refusal(EpaOperation operation) {
        return new ProdWriteBlockedException(operation, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Subscription/{id}", "https://onyx.example.invalid/fhir/Subscription/subscription-placeholder-0001",
                        "https://onyx.example.invalid/fhir/Subscription/subscription-placeholder-0001", List.of(), List.of(), "subscription sample"),
                operation.defaultMethod(), "subscription sample");
    }

    private static SubscriptionRequest request() {
        return SubscriptionRequest.builder().testerRef("QA-17").build();
    }

    @Test
    void aBlockedWriteNamesWhatItWasAboutOnItsLedgerLine() {
        Map<String, String> about = new LinkedHashMap<>();
        about.put(RunRecord.CORR_SUBSCRIPTION_ID, "subscription-placeholder-0001");
        about.put(RunRecord.CORR_RUN_ID, RUN);

        RunEnvelope<Object> result = support().blocked(EpaOperation.SUBSCRIPTION_DELETE, refusal(EpaOperation.SUBSCRIPTION_DELETE), request(),
                RunOrigin.manual(), about);

        RunRecord record = ledger.only();
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, "subscription-placeholder-0001"),
                Map.entry(RunRecord.CORR_RUN_ID, RUN));
        // Everything else about a blocked run is as it was.
        assertThat(record.outcome()).isEqualTo("BLOCKED");
        assertThat(record.errorType()).isEqualTo(RunRecord.ERROR_BLOCKED);
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.nextAction()).isEqualTo(NextAction.UNLOCK_OR_SWITCH.name());
        assertThat(record.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_DELETE);
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.httpStatus()).isNull();
        assertThat(RunSupport.isBlocked(result)).isTrue();
        // The receipt the tester is shown carries the same line.
        assertThat(result.ledger().record().correlation()).isEqualTo(record.correlation());
        assertThat(result.runId()).isEqualTo(record.runId());
    }

    @Test
    void aBlockedWriteThatNamesNothingStillWritesItsLine() {
        RunSupport support = support();
        support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(), RunOrigin.manual());
        support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(), RunOrigin.manual(), Map.of());
        support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(), RunOrigin.manual(), null);
        assertThat(ledger.records()).hasSize(3).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo("BLOCKED");
            assertThat(record.correlation()).isEmpty();
        });
    }

    @Test
    void aBlockedReplayOrSuiteRowIsNotToldToUnlockOnTheResultOrItsLine() {
        RunSupport support = support();
        RunEnvelope<Object> replay = support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(),
                new RunOrigin(null, null, RUN));
        RunEnvelope<Object> suite = support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(),
                new RunOrigin("suite-0001", "case-1", null));
        RunEnvelope<Object> manual = support.blocked(EpaOperation.SUBSCRIPTION_CREATE, refusal(EpaOperation.SUBSCRIPTION_CREATE), request(),
                RunOrigin.manual());

        assertThat(replay.nextStep().action()).isEqualTo(NextAction.SEND_ON_ITS_OWN);
        assertThat(suite.nextStep().action()).isEqualTo(NextAction.SEND_ON_ITS_OWN);
        assertThat(manual.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(ledger.records()).extracting(RunRecord::nextAction)
                .containsExactly("SEND_ON_ITS_OWN", "SEND_ON_ITS_OWN", "UNLOCK_OR_SWITCH");
        // The refusal here was worded for a screen and opens with the unlock. A replay or a suite row cannot use it, so its
        // headline is the words of its own action, beside which it sits; a screen keeps the gateway's step.
        assertThat(replay.nextStep().headline()).isEqualTo(NextAction.SEND_ON_ITS_OWN.label()).doesNotStartWith("Unlock");
        assertThat(suite.nextStep().headline()).isEqualTo(NextAction.SEND_ON_ITS_OWN.label());
        assertThat(manual.nextStep().headline()).isEqualTo("Unlock writes on the Config tab, or switch environment.");
        // The verdict says why unlocking does not help, once, when the refusal does not say it already.
        assertThat(replay.verdict().supporting()).containsExactly("This run is a replay, and a replay never carries the production "
                + "unlock, so unlocking writes on the Config tab does not let it through.");
        assertThat(suite.verdict().supporting()).containsExactly("This run is a suite row, and a suite row never carries the production "
                + "unlock, so unlocking writes on the Config tab does not let it through.");
        assertThat(manual.verdict().supporting()).isEmpty();
        ProdWriteBlockedException toldTheOrigin = new ProdWriteBlockedException(EpaOperation.SUBSCRIPTION_CREATE,
                new EnvironmentRef("Onyx Production", EnvironmentKind.PROD), "Subscription create is blocked: \"Onyx Production\" is a "
                + "production environment, and a replay never carries the production unlock, so its writes to production are always "
                + "blocked.", "Nothing was sent, and unlocking does not change this.", null, "POST", null);
        assertThat(support.blocked(EpaOperation.SUBSCRIPTION_CREATE, toldTheOrigin, request(), new RunOrigin(null, null, RUN))
                .verdict().supporting()).isEmpty();

        // A refusal with no action of its own: the same words.
        ProdWriteBlockedException bare = new ProdWriteBlockedException(EpaOperation.SUBSCRIPTION_CREATE,
                new EnvironmentRef("Onyx Production", EnvironmentKind.PROD), "Writes are blocked.", null, null, "POST", null);
        assertThat(support.blocked(EpaOperation.SUBSCRIPTION_CREATE, bare, request(), new RunOrigin(null, null, RUN)).nextStep().headline())
                .isEqualTo(NextAction.SEND_ON_ITS_OWN.label());
    }

    @Test
    void theRecordStillChecksWhatABlockedWriteIsCorrelatedBy() {
        RunSupport support = support();
        // A key that is not one of the record's own is a programming error, refused before anything is written.
        assertThatThrownBy(() -> support.blocked(EpaOperation.SUBSCRIPTION_DELETE, refusal(EpaOperation.SUBSCRIPTION_DELETE), request(),
                RunOrigin.manual(), Map.of("memberName", "Placeholder-Surname"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.records()).isEmpty();

        // A value that is not one line of plain text is not kept. What was there is said, and the text is not.
        support.blocked(EpaOperation.SUBSCRIPTION_DELETE, refusal(EpaOperation.SUBSCRIPTION_DELETE), request(), RunOrigin.manual(),
                Map.of(RunRecord.CORR_SUBSCRIPTION_ID, "{\"name\":\"Placeholder-Surname\"}"));
        assertThat(ledger.only().correlation().get(RunRecord.CORR_SUBSCRIPTION_ID)).startsWith("(not recorded, ").doesNotContain("Placeholder");
    }

    // ------------------------------------------------------------------------------------------
    // D1b: the words of a timeout and the member ID forms follow the environment's settings
    // ------------------------------------------------------------------------------------------

    /** A request that names a member and nothing else, for the member ID line of a wire view. */
    private record MemberOnly(org.p32h.interop.epa.pave.model.MemberDetail member) implements RunRequest {
        public List<String> memberOverrides() { return List.of(); }
        public org.p32h.interop.epa.pave.model.ProviderDetail provider() { return null; }
        public List<String> providerOverrides() { return List.of(); }
        public List<org.p32h.interop.epa.pave.model.CodeInput> procedureCodes() { return List.of(); }
        public String dateOfService() { return null; }
        public String scenarioId() { return null; }
        public String expected() { return null; }
        public String testerRef() { return null; }
        public String note() { return null; }
        public String chainId() { return null; }
        public org.p32h.interop.epa.pave.service.config.RequestRef request() { return null; }
        public String rawBody() { return null; }
        public Map<String, String> variables() { return Map.of(); }
        public Map<String, List<String>> carry() { return Map.of(); }
        public Long configEpoch() { return null; }
        public Map<String, Object> unrecognised() { return Map.of(); }
    }

    /** An 11-character placeholder member ID, the length the spaced rule applies to. */
    private static final String MEMBER_ID = "S1234567890";

    private static MemberOnly member() {
        return new MemberOnly(org.p32h.interop.epa.pave.model.MemberDetail.builder().memberId(MEMBER_ID).build());
    }

    /** A live store whose active environment states a read timeout of 60000 ms and the spaced rule. */
    private static org.p32h.interop.epa.pave.service.config.ConfigFixture statedSettings(java.nio.file.Path directory) throws Exception {
        org.p32h.interop.epa.pave.service.config.ConfigFixture fixture = org.p32h.interop.epa.pave.service.config.ConfigFixture.on(directory)
                .live().onyx("crd.url", "https://sandbox.onyx.example.invalid").build();
        org.springframework.test.web.servlet.MockMvc mvc = org.p32h.interop.epa.pave.testsupport.TestMvc.standalone(
                new org.p32h.interop.epa.pave.web.ConfigController(fixture.service));
        String body = "{\"kind\": \"sandbox\", \"acknowledgeNotProd\": true, \"environmentExport\": {\"fileName\": \"s.json\", \"text\": "
                + org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(org.p32h.interop.epa.pave.postman.PostmanFiles.text(
                org.p32h.interop.epa.pave.postman.PostmanFiles.SANDBOX_ENVIRONMENT))
                + "}, \"timeouts\": {\"readMs\": 60000}, \"memberIdRule\": \"SPACED\", \"signIn\": {\"method\": \"NONE\"}}";
        int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/config/environments/settings")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        fixture.service.activate("Onyx Sandbox", null);
        return fixture;
    }

    private RunSupport supportOver(org.p32h.interop.epa.pave.service.config.OperationGateway gateway) {
        RunSupport support = support();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        support.gateway(beans.getBeanProvider(org.p32h.interop.epa.pave.service.config.OperationGateway.class));
        return support;
    }

    private static org.p32h.interop.epa.pave.model.VendorExchange failed(org.p32h.interop.epa.pave.model.OnyxFailureType failure) {
        org.p32h.interop.epa.pave.model.WireRequest request = new org.p32h.interop.epa.pave.model.WireRequest("POST",
                "https://sandbox.onyx.example.invalid/x", "https://sandbox.onyx.example.invalid/x", List.of(), "{}", true, null);
        return new org.p32h.interop.epa.pave.model.VendorExchange(request, null, List.of(), null, null, null, null, 5, failure,
                "No access token was obtained. The request was not sent.", java.util.Set.of(), null, null, List.of());
    }

    @Test
    void aTimeoutNamesTheReadTimeoutOfTheCallAndWhereItIsChanged(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        org.p32h.interop.epa.pave.service.config.ConfigFixture fixture = statedSettings(directory);
        RunSupport support = supportOver(fixture.gateway);
        org.p32h.interop.epa.pave.service.config.PreparedCall call = fixture.gateway.prepare(
                org.p32h.interop.epa.pave.service.config.CallIntent.of(EpaOperation.CRD_DISCOVERY));
        org.p32h.interop.epa.pave.model.VendorExchange timedOut = failed(org.p32h.interop.epa.pave.model.OnyxFailureType.TIMEOUT);
        String stated = "No response arrived within the read timeout of 60000 ms (the read timeout this environment states in "
                + "Configuration > Environments).";
        assertThat(support.faultView(timedOut, List.of(), call).title()).isEqualTo(stated);
        // Without the call at hand, the gateway's answer for the active environment says the same.
        assertThat(support.faultView(timedOut, List.of()).title()).isEqualTo(stated);
        // With nothing stated, application.yml's value and its property.
        assertThat(support().faultView(timedOut, List.of()).title()).isEqualTo("No response arrived within the read timeout of "
                + OnyxProperties.defaults().readTimeoutMs() + " ms (onyx.read-timeout-ms).");
    }

    @Test
    void theWireViewNamesTheMemberIdAsTheRuleOfTheCallSendsIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        org.p32h.interop.epa.pave.service.config.ConfigFixture fixture = statedSettings(directory);
        RunSupport support = supportOver(fixture.gateway);
        org.p32h.interop.epa.pave.service.config.PreparedCall call = fixture.gateway.prepare(
                org.p32h.interop.epa.pave.service.config.CallIntent.of(EpaOperation.CRD_DISCOVERY));
        org.p32h.interop.epa.pave.model.VendorExchange notSignedIn = failed(org.p32h.interop.epa.pave.model.OnyxFailureType.NOT_SIGNED_IN);
        RunEnvelope<Object> unsigned = support.notSignedIn(EpaOperation.CRD_DISCOVERY, call, notSignedIn,
                support.draft(EpaOperation.CRD_DISCOVERY, call, notSignedIn, RunOrigin.manual()), member());
        assertThat(unsigned.wire().memberId().sent()).isEqualTo("S12345678   90");
        assertThat(unsigned.wire().memberId().rule()).isEqualTo("spaced");
        // The environment signs in as its own settings say, so the panel points at its form, not at onyx.auth.
        assertThat(unsigned.error().action()).startsWith("Check the sign-in of the active environment in Configuration > Environments");

        // A blocked run never became a call: the active environment's rule is the one it would have gone by.
        RunEnvelope<Object> blocked = support.blocked(EpaOperation.PAS_SUBMIT, refusal(EpaOperation.PAS_SUBMIT), member(), null);
        assertThat(blocked.wire().memberId().rule()).isEqualTo("spaced");
        assertThat(blocked.wire().memberId().sent()).isEqualTo("S12345678   90");
        // The ledger keeps the packed form, so one member stays one key.
        assertThat(ledger.records().get(ledger.records().size() - 1).memberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void aCallUnderAnEnvironmentWhoseSignInIsNoneIsToldToChooseItsSignInNotToSwitchOnOnyxAuth(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        org.p32h.interop.epa.pave.service.config.ConfigFixture fixture = statedSettings(directory);
        RunSupport support = supportOver(fixture.gateway);
        org.p32h.interop.epa.pave.model.WireRequest request = new org.p32h.interop.epa.pave.model.WireRequest("POST",
                "https://sandbox.onyx.example.invalid/x", "https://sandbox.onyx.example.invalid/x", List.of(), "{}", true, null);
        org.p32h.interop.epa.pave.model.VendorExchange refused = new org.p32h.interop.epa.pave.model.VendorExchange(request, 401, List.of(),
                "{}", 2L, null, null, 5, org.p32h.interop.epa.pave.model.OnyxFailureType.BAD_REQUEST, null, java.util.Set.of(), null, null,
                List.of());
        assertThat(support.faultView(refused, List.of()).action()).startsWith("This tool sent no Authorization header: the active "
                + "environment's sign-in is None");
        org.p32h.interop.epa.pave.model.NextStepView step = support.unsignedNextStep(org.p32h.interop.epa.pave.model.NextStepView.of(
                NextAction.REVIEW, "Next: read it.", "Because."), refused);
        assertThat(step.headline()).isEqualTo("Next: choose the environment's sign-in, then send again.");
    }
}
