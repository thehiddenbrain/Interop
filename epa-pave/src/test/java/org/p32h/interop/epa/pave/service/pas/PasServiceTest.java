package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.OnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * The PAS service driven through the real scenario files, the real mock client and the real
 * mapper, with a fake gateway, an in-memory ledger and an in-memory code list around them.
 */
class PasServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SURNAME = "Placeholder-Surname";
    private static final String CLAIM = "claim-test-0001";
    private static final String ANSWERS = "{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"qr-sleep-1\",\"status\":\"completed\","
            + "\"item\":[{\"linkId\":\"1\",\"answer\":[{\"valueString\":\"Placeholder answer about daytime sleepiness\"}]}]}";
    private static final String REPORT = "Placeholder report: attended sleep study, synthetic text.";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final OnyxPasClient mockClient = new MockOnyxPasClient(scenarios, new MockExchanges(APP));

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private PasService service(OnyxPasClient client, String mode, Map<String, String> tracking, Map<String, String> onyx) {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        OnyxProperties onyxProperties = OnyxProperties.of(onyx);
        RunSupport support = new RunSupport(epa, TrackingProperties.of(tracking), onyxProperties, ledgerProvider(), CLOCK);
        return new PasService(onyxProperties, gateway, client, codeList, support, ledgerProvider());
    }

    private PasService service() {
        return service(mockClient, "mock", Map.of(), Map.of());
    }

    private static MemberDetail member(String memberId) {
        return MemberDetail.builder().memberId(memberId).firstName("Given").lastName(SURNAME).dateOfBirth("1970-01-31")
                .gender("female").lineOfBusiness("Medicare").subscriberId("SUB-PLACEHOLDER-1").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static PasSubmitRequest.Builder submission(String memberId) {
        return PasSubmitRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20");
    }

    @Test
    void whereARunCameFromReachesTheGatewaySoARefusalCanBeWordedForAReplayOrASuite() {
        RunOrigin suite = new RunOrigin("suite-00000000-0000-4000-8000-000000000009", "case-1", null);
        service().runSubmit(submission("P32H-PAS-APPROVED").build(), suite, null);
        assertThat(gateway.lastIntent().origin()).isEqualTo(suite);
        RunOrigin replay = new RunOrigin(null, null, "run-00000000-0000-4000-8000-000000000008");
        service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(CLAIM).build(), replay, null);
        assertThat(gateway.lastIntent().origin()).isEqualTo(replay);
        service().previewSubmit(submission("P32H-PAS-APPROVED").build(), null);
        assertThat(gateway.lastIntent().origin()).isNull();
    }

    @Test
    void whateverTheNextStepOfASubmissionAsksOfAnInquiryCanBeDoneOnMockWithTheSameSentinel() {
        List<String> followed = new ArrayList<>();
        for (var summary : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (summary.sentinelMemberId() == null || summary.step(null) == null) {
                continue;
            }
            RunEnvelope<PasDetail> submitted = service().runSubmit(submission(summary.sentinelMemberId()).claimId(CLAIM).build(),
                    RunOrigin.manual(), null);
            NextStepView next = submitted.nextStep();
            if (next.action() != NextAction.CHECK_STATUS && !next.headline().contains("status") && !next.headline().contains("inquiry")) {
                continue;
            }
            // The step tells the tester to inquire, so an inquiry with the same member is answered, never refused for want of a scenario.
            RunEnvelope<PasDetail> inquired = service().runInquire(inquiry(summary.sentinelMemberId()).claimId(CLAIM).build(),
                    RunOrigin.manual(), null);
            assertThat(inquired.verdict()).as(summary.id()).isNotNull();
            followed.add(summary.id());
        }
        // Before scenarios 16 and 17 had an answer for an inquiry, their next step led to a refusal.
        assertThat(followed).contains("pas-02-pended", "pas-16-timeout", "pas-17-server-error-500");
        // The timeout one finds the submission pended, which is what a submission that may have arrived is for.
        assertThat(service().runInquire(inquiry("P32H-PAS-TIMEOUT").claimId(CLAIM).build(), RunOrigin.manual(), null).verdict().outcomeCode())
                .isEqualTo("PENDED");
        assertThat(service().runInquire(inquiry("P32H-PAS-ERR500").claimId(CLAIM).build(), RunOrigin.manual(), null).verdict().outcomeCode())
                .isEqualTo("PENDED");
    }

    private static PasInquireRequest.Builder inquiry(String memberId) {
        return PasInquireRequest.builder().member(member(memberId)).provider(provider());
    }

    private RunEnvelope<PasDetail> submit(String memberId) {
        return service().runSubmit(submission(memberId).build(), RunOrigin.manual(), null);
    }

    private static JsonNode sentBundle(RunEnvelope<PasDetail> result) {
        return JsonSafe.parse(result.wire().request().body()).node();
    }

    private static List<String> resourceTypes(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        bundle.get("entry").forEach(entry -> out.add(entry.get("resource").get("resourceType").asString()));
        return out;
    }

    /** A client that counts its calls and answers every one of them with an approval. */
    private static final class CountingClient implements OnyxPasClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public VendorExchange submit(PreparedCall call, ResolvedText body, MockSelection selection) {
            calls.incrementAndGet();
            return PasJson.response(200, PasJson.bundle(PasJson.claimResponse(PasJson.items(PasJson.decided(1, "A1")))));
        }

        @Override
        public VendorExchange inquire(PreparedCall call, ResolvedText body, MockSelection selection) {
            return submit(call, body, selection);
        }
    }

    // ---- a verdict read from a mask says so where it is read ----

    /** An approved ClaimResponse in a bundle, as a service answers a submission or an inquiry. */
    private static final String APPROVED = PasJson.bundle(PasJson.claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"approved\"",
            PasJson.items(PasJson.decided(1, "A1"))));

    private RunEnvelope<PasDetail> submitHolding(org.p32h.interop.epa.pave.model.SentSecrets secrets) {
        return service(stub(TestExchanges.holding(PasJson.response(200, APPROVED), secrets)), "live", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
    }

    private RunEnvelope<PasDetail> inquireHolding(org.p32h.interop.epa.pave.model.SentSecrets secrets) {
        return service(stub(TestExchanges.holding(PasJson.response(200, APPROVED), secrets)), "live", Map.of(), Map.of())
                .runInquire(inquiry("S123456789").claimId(CLAIM).build(), RunOrigin.manual(), null);
    }

    @Test
    void aSubmissionAndAnInquiryReadThroughAMaskedResourceTypeSaySoInTheVerdictAndTheNextStep() {
        // An environment secret whose value is the resource type the decision is read from. No request carried it.
        org.p32h.interop.epa.pave.model.SentSecrets word = org.p32h.interop.epa.pave.model.SentSecrets.builder()
                .variable("crWord", "ClaimResponse").build();
        for (RunEnvelope<PasDetail> result : List.of(submitHolding(word), inquireHolding(word))) {
            // The rules are unchanged and decide from what they read; the verdict now says what they read.
            assertThat(result.verdict().outcomeCode()).isNotEqualTo("APPROVED");
            assertThat(result.verdict().maskedReading()).startsWith("Read from a mask, not from what the service sent: resourceType was read "
                    + "as [secret crWord].").endsWith("Look at that value in the Config tab.");
            assertThat(result.nextStep().headline()).startsWith(org.p32h.interop.epa.pave.model.MaskedReading.STEP_FIRST);
            assertThat(result.findings().get(0).kind()).isEqualTo(FindingKind.MASKED_READING);
            assertThat(result.ledger().record().findings()).contains("MASKED_READING");
        }
    }

    @Test
    void aMaskHandedOnOrTypedAsTheClaimIdOrAsAnyValueHandedOnIsRefusedWithItsReasonAndNothingIsSent() {
        CountingClient client = new CountingClient();
        PasService service = service(client, "live", Map.of(), Map.of());
        // The claim id an inquiry follows, handed on from an answer that repeated a held secret.
        assertThatThrownBy(() -> service.runInquire(inquiry("S123456789").carry(Map.of(NextStepView.CARRY_CLAIM_ID,
                List.of("[secret claimWord]"))).build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("carry.claimId");
                    assertThat(error.message()).isEqualTo("Claim identifier, handed on from the step before, was given masked, as "
                            + "[secret claimWord], because the answer it came from repeats a secret this tool holds. A mask is never sent, "
                            + "so this step was not sent. Type the value on the form, or settle the secret in the Config tab.");
                }));
        // A completed questionnaire response handed on from DTR with a mask inside it.
        assertThatThrownBy(() -> service.runSubmit(submission("S123456789").carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE,
                List.of("{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"http://example.invalid/Questionnaire/[secret qWord]\"}")))
                .build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::message)
                        .anyMatch(message -> message.startsWith("Completed questionnaire responses, handed on from the step before, was given "
                                + "masked, as [secret qWord],")));
        // The same mask typed into the claim id field is said to be a mask, not an id of the wrong form.
        assertThatThrownBy(() -> service.runInquire(inquiry("S123456789").claimId("[secret claimWord]").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::message)
                        .containsExactly("This is not a claim id but a mask, [secret claimWord]: an answer gave the value masked because it "
                                + "repeats a secret this tool holds. A mask is never sent. Type the value itself, or settle the secret in the "
                                + "Config tab."));
        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theSameAnswerWithAHeldSecretItDoesNotRepeatIsReadAsApprovedWithNoMaskedReading() {
        org.p32h.interop.epa.pave.model.SentSecrets unrelated = org.p32h.interop.epa.pave.model.SentSecrets.builder()
                .variable("clientSecret", "placeholder-secret-value-7f3a").build();
        for (RunEnvelope<PasDetail> result : List.of(submitHolding(unrelated), inquireHolding(unrelated))) {
            assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
            assertThat(result.verdict().maskedReading()).isNull();
            assertThat(result.nextStep().headline()).doesNotStartWith(org.p32h.interop.epa.pave.model.MaskedReading.STEP_FIRST);
            assertThat(result.findings()).extracting(Finding::kind).doesNotContain(FindingKind.MASKED_READING);
        }
    }

    private static OnyxPasClient stub(VendorExchange exchange) {
        return new OnyxPasClient() {
            @Override
            public VendorExchange submit(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange inquire(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }
        };
    }

    // ---- the result answers the four questions ----

    @Test
    void aSubmissionReturnsTheVerdictTheTestResultTheNextStepAndTheLedgerReceipt() {
        RunEnvelope<PasDetail> result = service().runSubmit(submission("P32H-PAS-APPROVED").expected("APPROVED").testerRef("QA-17")
                .note("first submission").build(), RunOrigin.manual(), null);

        // What happened.
        assertThat(result.verdict().headline()).isEqualTo("The prior authorization is approved.");
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(result.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario pas-01-approved answered");
        assertThat(result.verdict().supporting()).contains("Authorization number: PA-PLACEHOLDER-0001.");
        // What did I expect, and did it match.
        assertThat(result.expectation().expected()).isEqualTo("APPROVED");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // What do I do next.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(result.nextStep().headline()).isEqualTo("The flow ends here. Record this result.");
        // And it is recorded, under the same run id the screen shows.
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(ledger.only().runId()).isEqualTo(result.runId());
        assertThat(result.protocol()).isEqualTo(Protocol.PAS);
        assertThat(result.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(result.profile()).isEqualTo("mock");
        assertThat(result.error()).isNull();
        assertThat(result.timing().slow()).isFalse();
        assertThat(result.detail().responseKind()).isEqualTo(PasResponseKind.BUNDLE);
        assertThat(result.detail().bodyBuiltByTool()).isTrue();
        assertThat(result.detail().requestNotes()).isNotEmpty();
        assertThat(result.additionalFields()).isEmpty();
    }

    @Test
    void theLedgerLineOfASubmissionCarriesEveryFieldTheSpecificationListsAsAlways() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        service().runSubmit(submission("P32H-PAS-APPROVED").claimId(CLAIM).expected("APPROVED").testerRef("QA-17").note("routing check").build(),
                RunOrigin.manual(), null);
        RunRecord record = ledger.only();

        assertThat(record.runId()).startsWith("run-");
        assertThat(record.timestamp()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(record.protocol()).isEqualTo(Protocol.PAS);
        assertThat(record.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(record.target()).isEqualTo("onyx");
        assertThat(record.profile()).isEqualTo("mock");
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(record.scenarioId()).isEqualTo("pas-01-approved");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(record.outcome()).isEqualTo("APPROVED");
        assertThat(record.coverageCodes()).containsExactly("claim-outcome=complete", "review-action=A1");
        assertThat(record.delegatedVendor()).isNull();
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.errorType()).isEqualTo("NONE");
        assertThat(record.expected()).isEqualTo("APPROVED");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.note()).isEqualTo("routing check");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.nextAction()).isEqualTo("FLOW_ENDS");
        assertThat(record.memberId()).isEqualTo("P32H-PAS-APPROVED");
        assertThat(record.npi()).isEqualTo("1234567893");
        assertThat(record.dateOfService()).isEqualTo("2026-03-20");
        assertThat(record.attachmentMode()).isNull();
        assertThat(record.unrecordedInputs()).isEmpty();
        // The three identifiers that tie later runs to this one.
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, CLAIM),
                Map.entry(RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-" + CLAIM),
                Map.entry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001"));
    }

    // ---- the claim id ----

    @Test
    void theClaimCarriesAnIdentifierTheToolMintedAndTheLedgerLineCarriesTheSameOne() {
        RunEnvelope<PasDetail> result = submit("P32H-PAS-PENDED");
        String claimId = result.detail().claimId();
        assertThat(claimId).matches("claim-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        JsonNode claim = sentBundle(result).get("entry").get(0).get("resource");
        assertThat(claim.get("resourceType").asString()).isEqualTo("Claim");
        assertThat(claim.get("use").asString()).isEqualTo("preauthorization");
        assertThat(claim.at("/identifier/0/value").asString()).isEqualTo(claimId);
        assertThat(ledger.only().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, claimId);
        // A second submission gets its own identifier, and so its own ClaimResponse.
        RunEnvelope<PasDetail> second = submit("P32H-PAS-PENDED");
        assertThat(second.detail().claimId()).isNotEqualTo(claimId);
        assertThat(ledger.last().correlation().get(RunRecord.CORR_CLAIM_RESPONSE_ID)).isEqualTo("claimresponse-" + second.detail().claimId());
    }

    @Test
    void aClaimIdTheTesterTypesIsUsedAndOneThatIsFreeTextIsRefused() {
        RunEnvelope<PasDetail> result = service().runSubmit(submission("P32H-PAS-APPROVED").claimId("  " + CLAIM + " ").build(), RunOrigin.manual(), null);
        assertThat(result.detail().claimId()).isEqualTo(CLAIM);
        assertThat(sentBundle(result).at("/entry/0/resource/identifier/0/value").asString()).isEqualTo(CLAIM);
        assertThat(ledger.only().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, CLAIM);
        // It is written to the ledger as it stands, so a sentence is not accepted in its place.
        assertInvalidSubmit(service(), submission("S123456789").claimId("the claim for Placeholder-Surname").build(), "claimId");
        assertInvalidSubmit(service(), submission("S123456789").carry(Map.of(NextStepView.CARRY_CLAIM_ID, List.of("two words"))).build(),
                "carry.claimId");
    }

    // ---- the decision: five answers that must never be mistaken for each other ----

    @Test
    void deniedApprovedPendedPartialAndNoDecisionEachGiveADifferentOutcomeVerdictAndLedgerLine() {
        Map<String, RunEnvelope<PasDetail>> results = new LinkedHashMap<>();
        for (String sentinel : List.of("P32H-PAS-DENIED", "P32H-PAS-APPROVED", "P32H-PAS-PENDED", "P32H-PAS-PARTIAL", "P32H-PAS-NO-DECISION")) {
            results.put(sentinel, service().runSubmit(submission(sentinel).claimId(CLAIM)
                    .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("95811"))).build(), RunOrigin.manual(), null));
        }
        Set<String> outcomes = new HashSet<>();
        Set<String> headlines = new HashSet<>();
        Set<Tone> tones = new HashSet<>();
        Set<NextAction> actions = new HashSet<>();
        results.values().forEach(result -> {
            outcomes.add(result.verdict().outcomeCode());
            headlines.add(result.verdict().headline());
            tones.add(result.verdict().tone());
            actions.add(result.nextStep().action());
        });
        assertThat(outcomes).containsExactlyInAnyOrder("DENIED", "APPROVED", "PENDED", "PARTIALLY_APPROVED", "NO_DECISION");
        assertThat(headlines).containsExactlyInAnyOrder("The prior authorization is denied.", "The prior authorization is approved.",
                "The prior authorization is pended.", "The prior authorization is partially approved.",
                "The service answered but returned no decision.");
        assertThat(tones).containsExactlyInAnyOrder(Tone.STOP, Tone.CLEAR, Tone.PENDING, Tone.ACTION, Tone.NO_ANSWER);
        assertThat(actions).containsExactlyInAnyOrder(NextAction.FLOW_ENDS, NextAction.CHECK_STATUS, NextAction.REVIEW);

        // Five ledger lines, no two alike in what they say was decided.
        assertThat(ledger.records()).extracting(RunRecord::outcome)
                .containsExactly("DENIED", "APPROVED", "PENDED", "PARTIALLY_APPROVED", "NO_DECISION");
        assertThat(ledger.records()).extracting(RunRecord::coverageCodes).doesNotHaveDuplicates();
        assertThat(ledger.records().get(0).coverageCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A3",
                "95810: review-reason=placeholder-criteria-not-met", "95811: review-action=A3", "95811: review-reason=placeholder-criteria-not-met");
        assertThat(ledger.records().get(1).coverageCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A1",
                "95811: review-action=A1");
        assertThat(ledger.records().get(2).coverageCodes()).containsExactly("claim-outcome=queued", "95810: review-action=A4",
                "95811: review-action=A4");
        assertThat(ledger.records().get(3).coverageCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A1",
                "95811: review-action=A3", "95811: review-reason=placeholder-criteria-not-met");
        // Nothing was decided, and the line says so for each code. It carries no review action at all.
        assertThat(ledger.records().get(4).coverageCodes()).containsExactly("claim-outcome=complete", "95810: (no answer)", "95811: (no answer)");
        assertThat(ledger.records()).extracting(RunRecord::nextAction)
                .containsExactly("FLOW_ENDS", "FLOW_ENDS", "CHECK_STATUS", "FLOW_ENDS", "REVIEW");
        // All five are an HTTP 200 with no transport failure: only the codes tell them apart.
        assertThat(ledger.records()).extracting(RunRecord::httpStatus).containsOnly(200);
        assertThat(ledger.records()).extracting(RunRecord::errorType).containsOnly("NONE");
    }

    @Test
    void aTesterWhoExpectsAnApprovalNeverSeesAPassOnAResponseThatCarriedNoDecision() {
        RunEnvelope<PasDetail> approved = service().runSubmit(submission("P32H-PAS-APPROVED").expected("APPROVED").build(), RunOrigin.manual(), null);
        RunEnvelope<PasDetail> noDecision = service().runSubmit(submission("P32H-PAS-NO-DECISION").expected("APPROVED").build(), RunOrigin.manual(), null);

        assertThat(approved.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(noDecision.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(noDecision.expectation().explanation()).isEqualTo(
                "Expected \"The authorization is approved\" but the outcome was \"The service returned no decision\".");
        assertThat(noDecision.verdict().supporting()).anySatisfy(line -> assertThat(line).startsWith("This is not an approval"));
        // The payer's own sentence says "Approved." It is shown, and it decides nothing.
        assertThat(noDecision.detail().claimResponses().get(0).disposition().value()).isEqualTo("Approved.");
        assertThat(noDecision.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("reads codes and never wording"));

        // Two screens: the same grid row, with a code on one and marked as not returned on the other.
        DeterminationRow approvedRow = approved.determination().stream().filter(r -> r.label().equals("Decision")).findFirst().orElseThrow();
        DeterminationRow emptyRow = noDecision.determination().stream().filter(r -> r.label().equals("Decision")).findFirst().orElseThrow();
        assertThat(approvedRow.code()).isEqualTo("A1");
        assertThat(approvedRow.display()).isEqualTo("Certified in total");
        assertThat(emptyRow.code()).isNull();
        assertThat(emptyRow.presence()).isEqualTo(Presence.ABSENT);
        assertThat(noDecision.findings()).extracting(Finding::kind).containsExactly(FindingKind.REQUEST_INCOMPLETE, FindingKind.NOT_STATED);

        // Two ledger rows.
        assertThat(ledger.records().get(0).outcome()).isEqualTo("APPROVED");
        assertThat(ledger.records().get(1).outcome()).isEqualTo("NO_DECISION");
        assertThat(ledger.records().get(0).result()).isEqualTo(TestResult.PASS);
        assertThat(ledger.records().get(1).result()).isEqualTo(TestResult.FAIL);
        assertThat(ledger.records().get(1).findings()).containsExactly("REQUEST_INCOMPLETE", "NOT_STATED");
        // A code assertion cannot pass on it either, and neither can "pended" or "denied".
        for (String expected : List.of("review-action=A1", "PENDED", "DENIED")) {
            assertThat(service().runSubmit(submission("P32H-PAS-NO-DECISION").expected(expected).build(), RunOrigin.manual(), null)
                    .expectation().result()).as(expected).isEqualTo(TestResult.FAIL);
        }
        assertThat(service().runSubmit(submission("P32H-PAS-NO-DECISION").expected("NO_DECISION").build(), RunOrigin.manual(), null)
                .expectation().result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void theAuthorizationOutcomeAndTheTestResultAreReportedSeparately() {
        // The tester expects a denial, and a denial is what comes back: a stop for the provider, a pass for the test.
        RunEnvelope<PasDetail> denied = service().runSubmit(submission("P32H-PAS-DENIED").expected("DENIED").build(), RunOrigin.manual(), null);
        assertThat(denied.verdict().headline()).isEqualTo("The prior authorization is denied.");
        assertThat(denied.verdict().outcomeLabel()).isEqualTo("The authorization is denied");
        assertThat(denied.verdict().tone()).isEqualTo(Tone.STOP);
        assertThat(denied.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(ledger.only().outcome()).isEqualTo("DENIED");
        assertThat(ledger.only().result()).isEqualTo(TestResult.PASS);
        // And the reverse: an approval nobody expected is a failed test.
        RunEnvelope<PasDetail> surprise = service().runSubmit(submission("P32H-PAS-APPROVED").expected("DENIED").build(), RunOrigin.manual(), null);
        assertThat(surprise.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(surprise.expectation().result()).isEqualTo(TestResult.FAIL);
        // With no expectation the run is unverified, never passed.
        assertThat(submit("P32H-PAS-APPROVED").expectation().result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aPartialApprovalPassesAnExpectationForOneItemAndFailsTheSameExpectationForTheOther() {
        PasSubmitRequest.Builder partial = submission("P32H-PAS-PARTIAL").procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("95811")));
        RunEnvelope<PasDetail> first = service().runSubmit(partial.expected("95810: review-action=A1").build(), RunOrigin.manual(), null);
        RunEnvelope<PasDetail> second = service().runSubmit(partial.expected("95811: review-action=A1").build(), RunOrigin.manual(), null);
        RunEnvelope<PasDetail> both = service().runSubmit(partial.expected("review-action=A1").build(), RunOrigin.manual(), null);

        assertThat(first.verdict().outcomeCode()).isEqualTo("PARTIALLY_APPROVED");
        assertThat(first.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(first.expectation().explanation()).isEqualTo("The answer for 95810 carried review-action=A1.");
        assertThat(second.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(second.expectation().explanation()).contains("the answer for 95811 carried review-action=A3");
        // With no subject the assertion is about every item, and one of them was not approved.
        assertThat(both.expectation().result()).isEqualTo(TestResult.FAIL);

        // Each item has its own line under the verdict and its own group of rows in the grid.
        assertThat(first.verdict().supporting()).contains("95810: approved (review action A1).", "95811: denied (review action A3).");
        assertThat(first.determination()).filteredOn(r -> r.label().equals("Decision")).extracting(DeterminationRow::subject, DeterminationRow::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("95810", "A1"), org.assertj.core.groups.Tuple.tuple("95811", "A3"));
        assertThat(first.detail().claimResponses().get(0).items()).extracting(item -> item.procedureCode()).containsExactly("95810", "95811");
        assertThat(ledger.records().get(0).procedureCodes()).containsExactly("95810", "95811");
    }

    @Test
    void anItemThePayerSaidNothingAboutIsNeverReportedWithTheDecisionOfAnother() {
        // The payer certifies the first of two items and does not list the second.
        VendorExchange oneOfTwo = PasJson.response(200, PasJson.bundle(PasJson.claimResponse("\"outcome\":\"complete\"",
                PasJson.items(PasJson.decided(1, "A1")))));
        RunEnvelope<PasDetail> result = service(stub(oneOfTwo), "mock", Map.of(), Map.of()).runSubmit(submission("S123456789")
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("95811"))).expected("APPROVED").build(), RunOrigin.manual(), null);

        assertThat(result.verdict().outcomeCode()).isEqualTo("MIXED");
        assertThat(result.verdict().supporting()).contains("95810: approved (review action A1).",
                "95811: no decision was returned for this item. That is not an approval.");
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(result.determination()).filteredOn(r -> "95811".equals(r.subject()) && r.label().equals("Decision"))
                .extracting(DeterminationRow::presence).containsExactly(Presence.ABSENT);
        assertThat(ledger.only().outcome()).isEqualTo("MIXED");
        assertThat(ledger.only().coverageCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A1", "95811: (no answer)");
        // A code assertion with no subject cannot pass on the one item that was answered.
        assertThat(service(stub(oneOfTwo), "mock", Map.of(), Map.of()).runSubmit(submission("S123456789")
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("95811"))).expected("review-action=A1").build(), RunOrigin.manual(), null)
                .expectation().result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void anUnrecognisedReviewActionCodeIsItsOwnOutcomeAndIsShownAsItCame() {
        RunEnvelope<PasDetail> result = submit("P32H-PAS-UNKNOWN-CODE");
        assertThat(result.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_CODE");
        assertThat(result.verdict().supporting()).contains("Unrecognised code: ZZ.");
        assertThat(result.determination()).filteredOn(r -> r.label().equals("Decision")).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("ZZ");
            assertThat(row.display()).isEqualTo("Unrecognised code: ZZ");
            assertThat(row.recognised()).isFalse();
        });
        assertThat(result.findings()).extracting(Finding::kind).containsExactly(FindingKind.REQUEST_INCOMPLETE, FindingKind.CODE_OUTSIDE_VALUE_SET);
        assertThat(ledger.only().coverageCodes()).contains("review-action=ZZ");
        assertThat(ledger.only().findings()).containsExactly("REQUEST_INCOMPLETE", "CODE_OUTSIDE_VALUE_SET");
    }

    // ---- the production guardrail ----

    private static ProdWriteBlockedException blockedWrite() {
        return new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "submit Happy Path"), "POST", "submit Happy Path");
    }

    @Test
    void aSubmissionTheGuardrailRefusesIsRecordedAsBlockedAndNothingReachesTheClient() {
        gateway.failing(blockedWrite());
        CountingClient client = new CountingClient();
        RunEnvelope<PasDetail> result = service(client, "live", Map.of(), Map.of()).runSubmit(submission("S12345678   01").expected("APPROVED")
                .testerRef("QA-17").build(), RunOrigin.manual(), null);

        assertThat(client.calls.get()).isZero();
        assertThat(RunSupport.isBlocked(result)).isTrue();
        assertThat(result.verdict().outcomeCode()).isEqualTo("BLOCKED");
        assertThat(result.verdict().tone()).isEqualTo(Tone.BLOCKED);
        assertThat(result.verdict().headline()).startsWith("Nothing was sent.");
        // Not a pass and not a fail: the payer was never asked.
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.BLOCKED);
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/Claim/$submit");
        assertThat(result.wire().request().body()).isNull();
        assertThat(result.wire().response()).isNull();
        assertThat(result.detail()).isNull();
        assertThat(result.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.PAS_SUBMIT);

        assertThat(result.ledger().recorded()).isTrue();
        RunRecord record = ledger.only();
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.outcome()).isEqualTo("BLOCKED");
        assertThat(record.errorType()).isEqualTo(RunRecord.ERROR_BLOCKED);
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.PROD);
        assertThat(record.targetSource()).isEqualTo("submit Happy Path");
        assertThat(record.httpStatus()).isNull();
        assertThat(record.memberId()).isEqualTo("S1234567801");
        assertThat(record.nextAction()).isEqualTo("UNLOCK_OR_SWITCH");
        // No claim id was given and none was sent, so the line names none. An id is not minted for a request that did not go.
        assertThat(record.correlation()).isEmpty();
    }

    @Test
    void aBlockedSubmissionNamesTheClaimAndTheRunItWasAbout() {
        gateway.failing(blockedWrite());
        CountingClient client = new CountingClient();
        PasService live = service(client, "live", Map.of(), Map.of());
        String earlier = "run-00000000-0000-4000-8000-000000000001";

        // The claim id typed on the form.
        live.runSubmit(submission("S123456789").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().outcome()).isEqualTo("BLOCKED");
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, CLAIM));

        // The claim and the run an earlier step handed on, as a resubmission with documentation carries them.
        live.runSubmit(submission("S123456789").carry(Map.of(NextStepView.CARRY_CLAIM_ID, List.of(CLAIM), NextStepView.CARRY_RUN_ID,
                List.of(earlier))).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, CLAIM),
                Map.entry(RunRecord.CORR_RUN_ID, earlier));

        // The claim a body of the tester's own names. The claim id on the form is not part of that body, so it is not the one.
        live.runSubmit(PasSubmitRequest.builder().rawBody(PasJson.request("claim-in-the-body", "95810")).claimId(CLAIM).build(),
                RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, "claim-in-the-body"));
        // A body whose claim id is still a placeholder names no claim: nothing is resolved for a request that does not go.
        live.runSubmit(PasSubmitRequest.builder().rawBody(PasJson.request("{{claimIdentifier}}", "95810")).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).isEmpty();

        // A refused inquiry names the claim it asked about in the same way.
        live.runInquire(inquiry("S123456789").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, CLAIM));

        assertThat(ledger.records()).hasSize(5).allSatisfy(record -> assertThat(record.outcome()).isEqualTo("BLOCKED"));
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void anInquiryIsLinkedToTheSubmissionThatWasSentAndNeverToALaterAttemptTheGuardrailRefused() {
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        // The same claim is submitted again and refused. Its line names the claim, and it sent nothing.
        gateway.failing(blockedWrite());
        RunEnvelope<PasDetail> refused = service().runSubmit(submission("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(RunSupport.isBlocked(refused)).isTrue();
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, CLAIM);
        gateway.failing(null);

        RunEnvelope<PasDetail> inquired = service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);

        // The newest line for that claim is the refused one. The inquiry is about the request the payer holds.
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId());
        assertThat(inquired.nextStep().first(NextStepView.CARRY_RUN_ID)).isEqualTo(submitted.runId());
    }

    @Test
    void anInquiryIsAReadAndGoesThroughOnAProductionEnvironmentWithNoUnlock() {
        gateway.environment("Onyx Production", EnvironmentKind.PROD);
        RunEnvelope<PasDetail> result = service().runInquire(inquiry("P32H-PAS-APPROVED").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(result.operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(EpaOperation.PAS_INQUIRE.write()).isFalse();
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(gateway.lastIntent().unlock()).isNull();
        assertThat(ledger.only().environmentKind()).isEqualTo(EnvironmentKind.PROD);
        assertThat(ledger.only().outcome()).isEqualTo("APPROVED");
    }

    @Test
    void theSessionsUnlockIsHandedToTheGatewayAndNothingInThePostedRequestCanStandInForIt() {
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        service().runSubmit(submission("P32H-PAS-APPROVED").build(), RunOrigin.manual(), unlock);
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);
        service().runSubmit(submission("P32H-PAS-APPROVED").build(), RunOrigin.manual(), null);
        assertThat(gateway.lastIntent().unlock()).isNull();
        service().runInquire(inquiry("P32H-PAS-APPROVED").claimId(CLAIM).build(), RunOrigin.manual(), unlock);
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);

        // A posted body that tries to carry an unlock binds it to nothing, and the request is refused for it.
        int before = gateway.intents().size();
        PasSubmitRequest posted = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-PAS-APPROVED\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"}],\"unlock\":{\"environmentName\":\"Onyx Production\",\"epoch\":0}}", PasSubmitRequest.class);
        assertThat(posted.unrecognised()).containsOnlyKeys("unlock");
        assertInvalidSubmit(service(), posted, "unlock");
        assertThat(gateway.intents()).hasSize(before);
    }

    @Test
    void aPreviewOfABlockedSubmissionShowsWhereItWouldGoAndRecordsNothing() {
        gateway.failing(blockedWrite());
        RequestPreview preview = service().previewSubmit(submission("P32H-PAS-APPROVED").build(), null);
        assertThat(preview.blocked()).isEqualTo("Writes to the production environment Onyx Production are blocked. "
                + "Unlock writes on the Config tab, or switch environment.");
        assertThat(preview.url().template()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(preview.body()).isNull();
        assertThat(ledger.records()).isEmpty();
    }

    // ---- attachments, both ways ----

    private static PasSubmitRequest.Builder withAttachment(String memberId) {
        return submission(memberId).attachments(List.of(new PasAttachment("Sleep study report", "text/plain", REPORT, null)));
    }

    @Test
    void inFhirModeTheDocumentTravelsInTheBundleAndTheLedgerLineSaysFhir() {
        RunEnvelope<PasDetail> result = service().runSubmit(withAttachment("P32H-PAS-ATTACH-OK").build(), RunOrigin.manual(), null);
        JsonNode bundle = sentBundle(result);
        assertThat(resourceTypes(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "ServiceRequest", "DocumentReference");
        assertThat(new String(Base64.getDecoder().decode(bundle.at("/entry/6/resource/content/0/attachment/data").asString()),
                StandardCharsets.UTF_8)).isEqualTo(REPORT);
        assertThat(PasBundles.resolve(bundle, bundle.at("/entry/0/fullUrl").asString(),
                bundle.at("/entry/0/resource/supportingInfo/0/valueReference/reference").asString()).get("id").asString())
                .isEqualTo("document-reference-1");
        assertThat(result.detail().attachmentMode()).isEqualTo("fhir");
        assertThat(ledger.only().attachmentMode()).isEqualTo("fhir");
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
        // The document itself is never written to the ledger line.
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.only())).doesNotContain("Placeholder report").doesNotContain("Sleep study report");
    }

    @Test
    void inX12ModeTheClaimNamesTheAttachmentTheBundleDoesNotCarryItAndTheLedgerLineSaysSo() {
        PasService x12 = service(mockClient, "mock", Map.of(), Map.of("pas.attachment-mode", "x12-275"));
        RunEnvelope<PasDetail> result = x12.runSubmit(withAttachment("P32H-PAS-ATTACH-OK").claimId(CLAIM).build(), RunOrigin.manual(), null);
        JsonNode bundle = sentBundle(result);
        assertThat(resourceTypes(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "ServiceRequest");
        assertThat(result.wire().request().body()).doesNotContain("Placeholder report")
                .doesNotContain(Base64.getEncoder().encodeToString(REPORT.getBytes(StandardCharsets.UTF_8)));
        assertThat(bundle.at("/entry/0/resource/supportingInfo/0/valueReference/identifier/value").asString()).isEqualTo(CLAIM + "-A1");
        assertThat(result.detail().attachmentMode()).isEqualTo("x12-275");
        assertThat(result.detail().requestNotes()).anySatisfy(note -> assertThat(note).contains("X12 275").contains("was not put in the bundle"));
        assertThat(ledger.only().attachmentMode()).isEqualTo("x12-275");
    }

    @Test
    void aSubmissionWithNoAttachmentRecordsNoAttachmentMode() {
        submit("P32H-PAS-APPROVED");
        assertThat(ledger.only().attachmentMode()).isNull();
        // An inquiry carries no attachment either.
        service().runInquire(inquiry("P32H-PAS-APPROVED").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().attachmentMode()).isNull();
    }

    @Test
    void anAttachmentThatIsRequiredSendsTheTesterBackToSubmitWithItUnderTheSameClaim() {
        RunEnvelope<PasDetail> required = submit("P32H-PAS-ATTACH-REQ");
        assertThat(required.verdict().outcomeCode()).isEqualTo("ATTACHMENT_REQUIRED");
        assertThat(required.verdict().headline()).isEqualTo("The payer needs more documentation before it will decide.");
        assertThat(required.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(required.nextStep().carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of(required.detail().claimId()))
                .containsEntry(NextStepView.CARRY_RUN_ID, List.of(required.runId()));
        assertThat(ledger.only().nextAction()).isEqualTo("START_PAS");

        // The next submission starts from what the first handed on, with the document added.
        RunEnvelope<PasDetail> again = service().runSubmit(withAttachment("P32H-PAS-ATTACH-OK").carry(required.nextStep().carry()).build(),
                RunOrigin.manual(), null);
        assertThat(again.detail().claimId()).isEqualTo(required.detail().claimId());
        assertThat(again.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, required.detail().claimId())
                .containsEntry(RunRecord.CORR_RUN_ID, required.runId());
        assertThat(ledger.last().attachmentMode()).isEqualTo("fhir");
        assertThat(ledger.last().unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
    }

    // ---- the chain ----

    @Test
    void aSubmissionIncludesTheQuestionnaireAnswersCarriedFromDtrAndTheLedgerSaysItUsedAPriorStep() {
        String dtrRun = "run-00000000-0000-4000-8000-0000000000d1";
        RunEnvelope<PasDetail> result = service().runSubmit(submission("P32H-PAS-APPROVED").chainId("new").carry(Map.of(
                NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS), NextStepView.CARRY_RUN_ID, List.of(dtrRun))).build(),
                RunOrigin.manual(), null);

        // The answers the tester entered are on the wire, inside the outgoing request, as they were given.
        JsonNode bundle = sentBundle(result);
        assertThat(resourceTypes(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "ServiceRequest", "QuestionnaireResponse");
        assertThat(bundle.at("/entry/6/resource/item")).isEqualTo(JsonSafe.parse(ANSWERS).node().get("item"));
        assertThat(result.wire().request().body()).contains("Placeholder answer about daytime sleepiness");
        assertThat(PasBundles.resolve(bundle, bundle.at("/entry/0/fullUrl").asString(),
                bundle.at("/entry/0/resource/supportingInfo/0/valueReference/reference").asString()).get("id").asString()).isEqualTo("qr-sleep-1");
        assertThat(PasBundles.unresolved(bundle)).isEmpty();

        RunRecord record = ledger.only();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_RUN_ID, dtrRun);
        assertThat(record.chainId()).isEqualTo(result.chainId()).startsWith("chain-");
        // Questionnaire answers are not an attachment.
        assertThat(record.attachmentMode()).isNull();
        // Their text is on the screen and never in the line that is kept.
        assertThat(JsonSafe.mapper().writeValueAsString(record)).doesNotContain("daytime sleepiness").doesNotContain("QuestionnaireResponse")
                .doesNotContain("qr-sleep-1");
    }

    @Test
    void answersHandedOnThatAreNotAQuestionnaireResponseAreRefusedAndNeverQuietlyDropped() {
        assertInvalidSubmit(service(), submission("S123456789").carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE,
                List.of("{\"resourceType\":\"Questionnaire\"}"))).build(), "carry.questionnaireResponse");
        assertInvalidSubmit(service(), submission("S123456789").carry(Map.of(NextStepView.CARRY_RUN_ID, List.of("not a run id"))).build(),
                "carry.runId");
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void anInquiryStartedFromTheCarryOfAPendedSubmissionAsksAboutThatClaimAndIsLinkedToThatRun() {
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").chainId("new").build(), RunOrigin.manual(), null);
        assertThat(submitted.verdict().outcomeCode()).isEqualTo("PENDED");
        assertThat(submitted.nextStep().action()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(submitted.nextStep().carry()).containsOnlyKeys(NextStepView.CARRY_CLAIM_ID, NextStepView.CARRY_RUN_ID,
                NextStepView.CARRY_PROCEDURE_CODES);
        assertThat(submitted.nextStep().first(NextStepView.CARRY_CLAIM_ID)).isEqualTo(submitted.detail().claimId());
        assertThat(submitted.nextStep().first(NextStepView.CARRY_RUN_ID)).isEqualTo(submitted.runId());

        // The inquiry types nothing: everything it starts from is what the submission handed on.
        RunEnvelope<PasDetail> inquired = service().runInquire(inquiry("P32H-PAS-INQUIRE").chainId(submitted.chainId())
                .procedureCodes(List.of(CodeInput.of("95810"))).carry(submitted.nextStep().carry()).expected("APPROVED").build(),
                RunOrigin.manual(), null);

        assertThat(inquired.operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(inquired.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(inquired.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(inquired.detail().claimId()).isEqualTo(submitted.detail().claimId());
        assertThat(sentBundle(inquired).at("/entry/0/resource/identifier/0/value").asString()).isEqualTo(submitted.detail().claimId());
        assertThat(inquired.wire().request().note()).contains("step \"inquire\"");
        assertThat(inquired.nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);
        // A further status check would still be linked to the submission, not to this inquiry.
        assertThat(inquired.nextStep().first(NextStepView.CARRY_RUN_ID)).isEqualTo(submitted.runId());

        RunRecord record = ledger.last();
        assertThat(record.operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, submitted.detail().claimId())
                .containsEntry(RunRecord.CORR_RUN_ID, submitted.runId())
                .containsEntry(RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-" + submitted.detail().claimId())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001");
        assertThat(record.chainId()).isEqualTo(submitted.chainId());
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        assertThat(record.coverageCodes()).containsExactly("claim-outcome=complete", "review-action=A1");
    }

    @Test
    void anInquiryByATypedClaimIdIsLinkedToItsSubmissionThroughTheLedger() {
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        RunEnvelope<PasDetail> inquired = service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId());
        assertThat(ledger.last().unrecordedInputs()).isEmpty();
        assertThat(inquired.nextStep().first(NextStepView.CARRY_RUN_ID)).isEqualTo(submitted.runId());
        // No item was named, so the inquiry asks about the request as a whole and its one decision has no subject.
        assertThat(inquired.determination()).extracting(DeterminationRow::subject).containsOnlyNulls();

        // A claim id no submission of this tool carried is asked about all the same, linked to nothing.
        service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId("claim-nobody-submitted").build(), RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, "claim-nobody-submitted")
                .doesNotContainKey(RunRecord.CORR_RUN_ID);
    }

    @Test
    void aLedgerThatCannotBeAskedLeavesTheInquiryUnlinkedAndStillAnswersIt() {
        // The submission is in the ledger. Only the question "which run submitted this claim" fails.
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        // The message names a member on purpose: a caller that copied it into a result would be caught below.
        ledger.throwingOnRead(new IllegalStateException("the ledger directory is gone for " + SURNAME));

        RunEnvelope<PasDetail> inquired = service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);

        assertThat(inquired.verdict().outcomeCode()).isEqualTo("APPROVED");
        // Writing is another matter than asking: the inquiry still has its line.
        assertThat(inquired.ledger().recorded()).isTrue();
        assertThat(ledger.last().runId()).isEqualTo(inquired.runId());
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, CLAIM).doesNotContainKey(RunRecord.CORR_RUN_ID);
        assertThat(inquired.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_RUN_ID);
        // What the ledger said when it failed is nowhere in the result.
        assertThat(JsonSafe.mapper().writeValueAsString(inquired)).doesNotContain("ledger directory").doesNotContain("is gone for");

        // Once the ledger answers again, the same inquiry is linked.
        ledger.throwingOnRead(null);
        service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId());
    }

    // ---- not found, faults: every one rendered in full ----

    @Test
    void notFoundIsPresentedAsAFindingAboutTestDataAndAFaultAsAFindingAboutTheEnvironment() {
        RunEnvelope<PasDetail> notFound = submit("P32H-PAS-NOMEM");
        assertThat(notFound.verdict().outcomeCode()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(notFound.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(notFound.verdict().supporting()).contains("This is a finding about the test data, not about the environment.");
        assertThat(notFound.nextStep().action()).isEqualTo(NextAction.FIX_TEST_DATA);
        // No error panel: the service answered. What it reported is still shown in the detail.
        assertThat(notFound.error()).isNull();
        assertThat(notFound.detail().claimResponses().get(0).errors()).hasSize(1);

        RunEnvelope<PasDetail> serverError = submit("P32H-PAS-ERR500");
        assertThat(serverError.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(serverError.verdict().headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(serverError.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.error().httpStatus()).isEqualTo(500);
        assertThat(serverError.error().issues()).isNotEmpty();
        assertThat(serverError.detail().issues()).isNotEmpty();

        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("MEMBER_NOT_FOUND", "SERVICE_ERROR");
        assertThat(ledger.records()).extracting(RunRecord::errorType).containsExactly("NONE", "SERVER_ERROR");
        assertThat(ledger.records().get(0).coverageCodes()).containsExactly("claim-outcome=error", "error-code=75");
    }

    @Test
    void aRejectionAServerErrorATimeoutAnUnreachableServiceAMalformedBodyAndAnEmptyBodyAreEachRenderedInFull() {
        RunEnvelope<PasDetail> rejected = submit("P32H-PAS-BAD400");
        assertThat(rejected.verdict().outcomeCode()).isEqualTo("REJECTED");
        assertThat(rejected.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(rejected.error().httpStatus()).isEqualTo(400);
        assertThat(rejected.error().issues()).singleElement().satisfies(issue -> assertThat(issue.code().value()).isEqualTo("invalid"));
        assertThat(rejected.detail().responseKind()).isEqualTo(PasResponseKind.OPERATION_OUTCOME);
        assertThat(rejected.nextStep().action()).isEqualTo(NextAction.FIX_REQUEST);
        assertThat(rejected.wire().response().status()).isEqualTo(400);
        assertThat(rejected.wire().request().body()).contains("\"Claim\"");

        assertThat(submit("P32H-PAS-ERR500").error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);

        RunEnvelope<PasDetail> timeout = submit("P32H-PAS-TIMEOUT");
        assertThat(timeout.verdict().headline()).isEqualTo("The service did not answer in time.");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.error().httpStatus()).isNull();
        assertThat(timeout.detail().responseKind()).isEqualTo(PasResponseKind.NO_RESPONSE);
        assertThat(timeout.wire().response().status()).isNull();
        assertThat(timeout.determination()).extracting(DeterminationRow::presence).containsOnly(Presence.ABSENT);

        RunEnvelope<PasDetail> down = submit("P32H-PAS-DOWN");
        assertThat(down.verdict().outcomeCode()).isEqualTo("UNREACHABLE");
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);

        String garbled = "{\"resourceType\": \"Bundle\", \"entry\": [";
        RunEnvelope<PasDetail> malformed = service(stub(PasJson.response(200, garbled)), "mock", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(malformed.verdict().headline()).isEqualTo("The service answered with a body that could not be read (HTTP 200).");
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(malformed.detail().responseKind()).isEqualTo(PasResponseKind.NOT_JSON);
        // The raw bytes are on screen outside the wire view as well as in it.
        assertThat(malformed.additionalFields()).singleElement().satisfies(field -> assertThat(field.value()).isEqualTo(garbled));
        assertThat(malformed.wire().response().body()).isEqualTo(garbled);
        assertThat(malformed.wire().response().parsed()).isFalse();

        RunEnvelope<PasDetail> empty = service(stub(PasJson.response(200, null)), "mock", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(empty.verdict().headline()).isEqualTo("The service answered with no body (HTTP 200).");
        assertThat(empty.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(empty.detail().responseKind()).isEqualTo(PasResponseKind.NO_BODY);

        assertThat(ledger.records()).extracting(RunRecord::outcome)
                .containsExactly("REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE", "MALFORMED_RESPONSE", "MALFORMED_RESPONSE");
        assertThat(ledger.records()).extracting(RunRecord::errorType)
                .containsExactly("BAD_REQUEST", "SERVER_ERROR", "TIMEOUT", "UNAVAILABLE", "MALFORMED_RESPONSE", "MALFORMED_RESPONSE");
        // Every one of them still says which claim was sent, so a later inquiry can be matched to it.
        assertThat(ledger.records()).allSatisfy(record -> assertThat(record.correlation()).containsKey(RunRecord.CORR_CLAIM_ID));
        // And none of them is ever a decision.
        assertThat(ledger.records()).allSatisfy(record -> assertThat(record.coverageCodes()).isEmpty());
    }

    @Test
    void aFieldNobodyAnticipatedReachesTheAdditionalFieldsOfTheResult() {
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"vendorTraceId\":\"trace-1\",\"entry\":[{\"resource\":"
                + PasJson.claimResponse("\"vendorStatus\":{\"detail\":{\"depth\":3}}", PasJson.items(PasJson.decided(1, "A1"))) + "}]}";
        RunEnvelope<PasDetail> result = service(stub(PasJson.response(200, body)), "mock", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.additionalFields()).extracting(f -> f.path())
                .containsExactly("vendorTraceId", "entry[0].resource.vendorStatus.detail.depth");
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
    }

    // ---- member data stays out of the ledger and out of the log ----

    @Test
    void theLedgerLinesOfASubmissionAndAnInquiryHoldNoMemberDataNoDispositionAndNoJson() {
        RunEnvelope<PasDetail> submitted = service().runSubmit(withAttachment("P32H-PAS-INQUIRE").diagnosisCodes(List.of(CodeInput.of("G47.33")))
                .carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build(), RunOrigin.manual(), null);
        service().runInquire(inquiry("P32H-PAS-INQUIRE").carry(submitted.nextStep().carry()).build(), RunOrigin.manual(), null);
        // The screen holds all of it: that is what the screen is for.
        assertThat(submitted.wire().request().body()).contains(SURNAME).contains("1970-01-31");
        assertThat(submitted.detail().claimResponses().get(0).disposition().value()).isEqualTo("Pended for clinical review.");

        assertThat(ledger.records()).hasSize(2);
        for (RunRecord record : ledger.records()) {
            String line = JsonSafe.mapper().writeValueAsString(record);
            assertThat(line).as(record.operation().name()).doesNotContain(SURNAME).doesNotContain("Given").doesNotContain("1970-01-31")
                    .doesNotContain("female").doesNotContain("SUB-PLACEHOLDER-1").doesNotContain("Pended for clinical review")
                    .doesNotContain("Certified in total").doesNotContain("resourceType").doesNotContain("\"entry\"")
                    .doesNotContain("daytime sleepiness").doesNotContain("Placeholder report").doesNotContain("G47.33");
        }
        // The receipt on the result carries the same checked record.
        assertThat(JsonSafe.mapper().writeValueAsString(submitted.ledger())).doesNotContain(SURNAME).doesNotContain("resourceType");
    }

    @Test
    void aResponseThatPutsMemberDataWhereCodesBelongIsShownOnScreenAndKeptOutOfTheLedgerLine() {
        // A non-conformant response: a sentence where the review action code belongs, and a
        // person's name where the authorization number belongs.
        String body = PasJson.bundle(PasJson.claimResponse("\"preAuthRef\":\"{\\\"patient\\\":\\\"Placeholder-Surname\\\"}\"",
                PasJson.items(PasJson.item(1, PasJson.actionOf("{\"url\":\"code\",\"valueString\":\"Approved for Placeholder-Surname born 1970-01-31\"},"
                        + "{\"url\":\"reasonCode\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"see note for Placeholder-Surname\"}]}}")))));
        RunEnvelope<PasDetail> result = service(stub(PasJson.response(200, body)), "mock", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);

        // On screen the tester sees exactly what came back: that is the evidence.
        assertThat(result.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_CODE");
        assertThat(result.determination().get(0).code()).isEqualTo("Approved for Placeholder-Surname born 1970-01-31");

        RunRecord record = ledger.only();
        String line = JsonSafe.mapper().writeValueAsString(record);
        assertThat(line).doesNotContain(SURNAME).doesNotContain("1970-01-31");
        assertThat(record.coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("review-action=(not a code, "));
        assertThat(record.coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("review-reason=(not a code, "));
        assertThat(record.correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).startsWith("(not recorded, ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theLogOfASubmissionAndAnInquiryHoldsNoIdentifierAndNoBody(CapturedOutput output) {
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").claimId(CLAIM).build(), RunOrigin.manual(), null);
        service().runInquire(inquiry("P32H-PAS-INQUIRE").carry(submitted.nextStep().carry()).build(), RunOrigin.manual(), null);
        // A failure of the tool after the call is the one thing this path logs.
        RunEnvelope<PasDetail> failed = failingAfterTheCall().runSubmit(submission("P32H-PAS-APPROVED").claimId(CLAIM).build(), RunOrigin.manual(), null);

        assertThat(output.getAll()).contains(failed.error().reference()).contains("java.lang.IllegalStateException")
                .doesNotContain("P32H-PAS-INQUIRE").doesNotContain("P32H-PAS-APPROVED").doesNotContain(SURNAME).doesNotContain("1970-01-31")
                .doesNotContain("SUB-PLACEHOLDER-1").doesNotContain("1234567893").doesNotContain(CLAIM).doesNotContain("PA-PLACEHOLDER")
                .doesNotContain("resourceType").doesNotContain("Certified in total").doesNotContain("the reader exploded");
    }

    // ---- a failure of this tool after the call ----

    /** A service whose reading of the answer fails, after the client has been called. */
    private PasService failingAfterTheCall() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), ledgerProvider(), CLOCK) {
            @Override
            public Finding slowFinding(Timing timing) {
                throw new IllegalStateException("the reader exploded for Placeholder-Surname");
            }
        };
        return new PasService(OnyxProperties.defaults(), gateway, mockClient, codeList, support, ledgerProvider());
    }

    @Test
    void aFailureOfThisToolAfterASubmissionStillLeavesALedgerLineThatNamesTheClaim() {
        RunEnvelope<PasDetail> result = failingAfterTheCall().runSubmit(withAttachment("P32H-PAS-APPROVED").claimId(CLAIM).expected("APPROVED").build(),
                RunOrigin.manual(), null);

        // The submission reached the payer, so the run happened and is recorded as a failure of the tool.
        RunRecord record = ledger.only();
        assertThat(record.outcome()).isEqualTo("TOOL_ERROR");
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.scenarioId()).isEqualTo("pas-01-approved");
        assertThat(record.nextAction()).isEqualTo("REVIEW");
        // Which claim went out is known before the answer is read, so the line still says it.
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, CLAIM));
        assertThat(record.attachmentMode()).isEqualTo("fhir");

        assertThat(RunSupport.isToolError(result)).isTrue();
        assertThat(result.runId()).isEqualTo(record.runId());
        assertThat(result.verdict().outcomeCode()).isEqualTo("TOOL_ERROR");
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.INTERNAL);
        assertThat(result.error().reference()).isNotBlank();
        assertThat(result.error().title() + result.error().action() + result.verdict().headline()).doesNotContain("exploded").doesNotContain(SURNAME);
        // The tester still sees what was sent and what came back.
        assertThat(result.wire().request().body()).contains(CLAIM);
        assertThat(result.wire().response().body()).contains("\"A1\"");
        assertThat(result.detail()).isNull();
    }

    // ---- validation: nothing sent, nothing recorded ----

    @Test
    void aSubmissionThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        CountingClient client = new CountingClient();
        PasService service = service(client, "mock", Map.of(), Map.of());

        assertInvalidSubmit(service, submission(" ").build(), "member.memberId");
        assertInvalidSubmit(service, submission("S123456789").member(null).build(), "member.memberId");
        assertInvalidSubmit(service, submission("S123456789").provider(ProviderDetail.builder().npi("1234567890").build()).build(), "provider.npi");
        assertInvalidSubmit(service, submission("S123456789").provider(null).build(), "provider.npi");
        assertInvalidSubmit(service, submission("S123456789").procedureCodes(List.of()).build(), "procedureCodes");
        assertInvalidSubmit(service, submission("S123456789").procedureCodes(List.of(CodeInput.of(" "))).build(), "procedureCodes[0].code");
        assertInvalidSubmit(service, submission("S123456789").diagnosisCodes(List.of(CodeInput.of("G47.33"), CodeInput.of(""))).build(),
                "diagnosisCodes[1].code");
        assertInvalidSubmit(service, submission("S123456789").dateOfService("03/20/2026").build(), "dateOfService");
        assertInvalidSubmit(service, submission("S123456789").member(MemberDetail.builder().memberId("S123456789").dateOfBirth("2026-03-11").build())
                .build(), "member.dateOfBirth");
        // An outcome only CRD has, and an element only CRD returns, are not something a submission can be checked against.
        assertInvalidSubmit(service, submission("S123456789").expected("NO_AUTH_REQUIRED").build(), "expected");
        assertInvalidSubmit(service, submission("S123456789").expected("pa-needed=no-auth").build(), "expected");
        assertInvalidSubmit(service, submission("S123456789").chainId("free text").build(), "chainId");
        assertInvalidSubmit(service, submission("S123456789").attachments(List.of(new PasAttachment(" ", "text/plain", "x", null))).build(),
                "attachments[0].title");
        assertInvalidSubmit(service, submission("S123456789").attachments(List.of(new PasAttachment("Report", null, "x", null))).build(),
                "attachments[0].contentType");
        assertInvalidSubmit(service, submission("S123456789").attachments(List.of(new PasAttachment("Report", "text/plain", null, null))).build(),
                "attachments[0]");
        assertInvalidSubmit(service, submission("S123456789").attachments(List.of(new PasAttachment("Report", "text/plain", "x",
                "https://documents.example.invalid/x"))).build(), "attachments[0]");

        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void anInquiryNeedsAClaimToAskAboutAndTheMemberAndProviderItsBundleCarries() {
        CountingClient client = new CountingClient();
        PasService service = service(client, "mock", Map.of(), Map.of());
        assertInvalidInquire(service, inquiry("S123456789").build(), "claimId");
        assertInvalidInquire(service, inquiry(" ").claimId(CLAIM).build(), "member.memberId");
        assertInvalidInquire(service, inquiry("S123456789").claimId(CLAIM).provider(null).build(), "provider.npi");
        assertInvalidInquire(service, inquiry("S123456789").claimId("two words").build(), "claimId");
        assertInvalidInquire(service, inquiry("S123456789").claimId(CLAIM).procedureCodes(List.of(CodeInput.of(""))).build(), "procedureCodes[0].code");
        assertInvalidInquire(service, inquiry("S123456789").claimId(CLAIM).expected("AUTH_REQUIRED").build(), "expected");
        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
        // The codes are optional for an inquiry, and so is everything a submission adds.
        assertThat(service.runInquire(inquiry("S123456789").claimId(CLAIM).build(), RunOrigin.manual(), null).ledger().recorded()).isTrue();
    }

    @Test
    void everyProblemWithTheFormIsReportedAtOnce() {
        assertThatThrownBy(() -> service().runSubmit(PasSubmitRequest.builder().dateOfService("tomorrow").claimId("not a claim id").build(),
                RunOrigin.manual(), null)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                .extracting(FieldError::field).containsExactly("dateOfService", "claimId", "member.memberId", "provider.npi", "procedureCodes"));
    }

    @Test
    void aRequestFieldTheToolDoesNotKnowIsRefusedAndNeverQuietlyDropped() {
        PasSubmitRequest misspelt = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-PAS-APPROVED\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"expectd\":\"APPROVED\"}", PasSubmitRequest.class);
        assertInvalidSubmit(service(), misspelt, "expectd");
        PasInquireRequest misspeltInquiry = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-PAS-APPROVED\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"claimID\":\"claim-test-0001\"}", PasInquireRequest.class);
        assertInvalidInquire(service(), misspeltInquiry, "claimID");
        assertThat(ledger.records()).isEmpty();

        // Spelt correctly, every field a browser posts binds, the ones a chain uses included.
        PasSubmitRequest correct = JsonSafe.mapper().readValue("{\"claimId\":\"claim-test-0001\",\"member\":{\"memberId\":\"P32H-PAS-APPROVED\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"diagnosisCodes\":[{\"code\":\"G47.33\"}],"
                + "\"attachments\":[{\"title\":\"Report\",\"contentType\":\"text/plain\",\"text\":\"Placeholder text\"}],"
                + "\"expected\":\"APPROVED\",\"carry\":{\"procedureCodes\":[\"95810\"]},\"configEpoch\":0}", PasSubmitRequest.class);
        assertThat(correct.unrecognised()).isEmpty();
        assertThat(correct.attachments()).hasSize(1);
        assertThat(service().runSubmit(correct, RunOrigin.manual(), null).expectation().result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void aScenarioCannotBeForcedOnLive() {
        assertInvalidSubmit(service(mockClient, "live", Map.of(), Map.of()), submission("S123456789").scenarioId("pas-03-denied").build(), "scenarioId");
        assertInvalidInquire(service(mockClient, "live", Map.of(), Map.of()), inquiry("S123456789").claimId(CLAIM).scenarioId("pas-03-denied").build(),
                "scenarioId");
        // On mock the same request answers from the chosen scenario, whatever the member.
        RunEnvelope<PasDetail> forced = service().runSubmit(submission("P32H-PAS-APPROVED").scenarioId("pas-03-denied").build(), RunOrigin.manual(), null);
        assertThat(forced.verdict().outcomeCode()).isEqualTo("DENIED");
        assertThat(ledger.only().scenarioId()).isEqualTo("pas-03-denied");
    }

    @Test
    void aTargetTheGatewayRefusesIsNotSentAndNotRecordedAndAChangedConfigurationIsRefusedToo() {
        gateway.changeConfiguration();
        assertThatThrownBy(() -> service().runSubmit(submission("P32H-PAS-APPROVED").configEpoch(0L).build(), RunOrigin.manual(), null))
                .isInstanceOf(ConfigurationChangedException.class);
        assertThat(service().runSubmit(submission("P32H-PAS-APPROVED").configEpoch(1L).build(), RunOrigin.manual(), null).ledger().recorded()).isTrue();
        assertThat(gateway.lastIntent().expectedEpoch()).isEqualTo(1L);
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.PAS_SUBMIT, "There is no url for PAS submit."));
        assertThatThrownBy(() -> submit("P32H-PAS-APPROVED")).isInstanceOf(EndpointNotConfiguredException.class);
        assertThat(ledger.records()).hasSize(1);
    }

    private void assertInvalidSubmit(PasService service, PasSubmitRequest request, String field) {
        assertThatThrownBy(() -> service.runSubmit(request, RunOrigin.manual(), null))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    private void assertInvalidInquire(PasService service, PasInquireRequest request, String field) {
        assertThatThrownBy(() -> service.runInquire(request, RunOrigin.manual(), null))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    // ---- the ledger ----

    @Test
    void aLedgerThatCannotWriteIsReportedOnTheResultAndTheResultIsStillReturned() {
        ledger.refusing("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written.");
        RunEnvelope<PasDetail> result = submit("P32H-PAS-APPROVED");
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded");
        assertThat(result.ledger().record().runId()).isEqualTo(result.runId());

        ledger.throwing(new IllegalStateException("disk full for Placeholder-Surname"));
        RunEnvelope<PasDetail> thrown = submit("P32H-PAS-APPROVED");
        assertThat(thrown.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(thrown.ledger().recorded()).isFalse();
        assertThat(thrown.ledger().message()).doesNotContain(SURNAME);
        // No ledger applied the tracking policy, so nothing the policy governs rides along in the receipt.
        assertThat(thrown.ledger().record().memberId()).isNull();
        assertThat(thrown.ledger().record().correlation()).doesNotContainKey(RunRecord.CORR_AUTHORIZATION_REF)
                .containsKey(RunRecord.CORR_CLAIM_ID);
    }

    @Test
    void aRunFromASuiteOrAReplayCarriesItsOrigin() {
        service().runSubmit(submission("P32H-PAS-APPROVED").build(), new RunOrigin("suite-1", "case-7", "run-earlier"), null);
        assertThat(ledger.only().suiteRunId()).isEqualTo("suite-1");
        assertThat(ledger.only().caseId()).isEqualTo("case-7");
        assertThat(ledger.only().replayOfRunId()).isEqualTo("run-earlier");
        service().runInquire(inquiry("P32H-PAS-APPROVED").claimId(CLAIM).build(), null, null);
        assertThat(ledger.last().suiteRunId()).isNull();
    }

    // ---- what was sent ----

    @Test
    void aSpacedMemberIdIsSentWithoutSpacesAndBothFormsAreShown() {
        RunEnvelope<PasDetail> result = submit("S12345678   01");
        assertThat(result.wire().memberId().typed()).isEqualTo("S12345678   01");
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.wire().memberId().changed()).isTrue();
        assertThat(sentBundle(result).at("/entry/1/resource/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(result.wire().request().body()).doesNotContain("S12345678   01");
        assertThat(ledger.only().memberId()).isEqualTo("S1234567801");
    }

    @Test
    void onMockTheWireViewSaysNothingWasSentAndWhichScenarioAnswered() {
        RunEnvelope<PasDetail> result = submit("P32H-PAS-DENIED");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().note()).isEqualTo("Mock profile. Answered from scenario pas-03-denied, selected by the test "
                + "member ID P32H-PAS-DENIED. Nothing left this machine.");
        assertThat(result.wire().request().method()).isEqualTo("POST");
        // FHIR JSON, not plain JSON, on the way out.
        assertThat(result.wire().request().headers()).extracting(h -> h.name() + ": " + h.value())
                .contains("Accept: application/fhir+json", "Content-Type: application/fhir+json");
        // An ordinary member gets the default scenario, and the result says it is the default.
        RunEnvelope<PasDetail> ordinary = submit("S123456789");
        assertThat(ordinary.wire().request().note()).contains("pas-01-approved").contains("the default");
        assertThat(ordinary.verdict().supporting().get(0)).contains("the default, because no scenario was chosen and no test member ID or NPI matched");
    }

    @Test
    void theMockAnswersEveryItemSoTwoCodesAreTwoDecisionsAndTheResultSaysTheAnswerWasRepeated() {
        RunEnvelope<PasDetail> result = service().runSubmit(submission("P32H-PAS-APPROVED")
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601"))).expected("APPROVED; review-action=A1").build(),
                RunOrigin.manual(), null);
        assertThat(result.verdict().supporting().get(0)).contains("repeated here for each of the 2 items sent");
        assertThat(result.verdict().supporting()).contains("This is the decision for each of: 95810, E0601.");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(result.expectation().explanation()).contains("every answer carried review-action=A1 (95810, E0601)");
        assertThat(result.nextStep().carry()).containsEntry(NextStepView.CARRY_PROCEDURE_CODES, List.of("95810", "E0601"));
    }

    @Test
    void theTestersOwnBodyIsSentAsWrittenAndTheLedgerRecordsWhatItHeld() {
        String body = PasJson.request("claim-own-body-1", "70553").replace("\"use\":", "\"vendorSampleField\":true,\"use\":");
        RunEnvelope<PasDetail> result = service().runSubmit(PasSubmitRequest.builder().rawBody(body).claimId(CLAIM)
                .procedureCodes(List.of(CodeInput.of("95810"))).build(), RunOrigin.manual(), null);

        assertThat(result.wire().request().body()).isEqualTo(body);
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        // The claim id and the code that went out are the ones in the body, not the ones left on the form.
        assertThat(result.detail().claimId()).isEqualTo("claim-own-body-1");
        assertThat(result.detail().requestNotes()).singleElement().satisfies(note -> assertThat(note).contains("were not added to the body you supplied"));
        RunRecord record = ledger.only();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY);
        assertThat(record.procedureCodes()).containsExactly("70553");
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, "claim-own-body-1");
        assertThat(record.memberId()).isNull();
        assertThat(result.wire().memberId()).isNull();
        assertThat(result.detail().claimResponses().get(0).decisions().get(0).procedureCode()).isEqualTo("70553");
    }

    @Test
    void aBodyThatIsNotJsonOrNamesNoClaimIsStillSentAndTheResultSaysSo() {
        RunEnvelope<PasDetail> garbled = service().runSubmit(PasSubmitRequest.builder().rawBody("{\"resourceType\": \"Bundle\",").build(),
                RunOrigin.manual(), null);
        assertThat(garbled.wire().request().body()).isEqualTo("{\"resourceType\": \"Bundle\",");
        assertThat(garbled.detail().requestNotes()).singleElement().satisfies(note ->
                assertThat(note).contains("not valid JSON").contains("sent exactly as written"));
        assertThat(ledger.only().correlation()).doesNotContainKey(RunRecord.CORR_CLAIM_ID);

        RunEnvelope<PasDetail> noClaim = service().runSubmit(PasSubmitRequest.builder().rawBody(PasJson.request(null, "95810")).build(),
                RunOrigin.manual(), null);
        assertThat(noClaim.detail().claimId()).isNull();
        assertThat(noClaim.detail().requestNotes()).singleElement().satisfies(note -> assertThat(note).contains("carries no claim identifier"));
        assertThat(noClaim.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_CLAIM_ID);
    }

    @Test
    void aBodyThatStillHoldsAPlaceholderIsRefusedAndNotRecorded() {
        String body = "{\"resourceType\":\"Bundle\",\"identifier\":{\"value\":\"{{bundleIdentifier}}\"}}";
        assertThatThrownBy(() -> service().runSubmit(PasSubmitRequest.builder().rawBody(body).build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> assertThat(e.variables()).containsExactly("bundleIdentifier"));
        assertThat(ledger.records()).isEmpty();
        service().runSubmit(PasSubmitRequest.builder().rawBody(body).variables(Map.of("bundleIdentifier", "bundle-1")).build(), RunOrigin.manual(), null);
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES);
    }

    @Test
    void theLedgerNamesEveryInputItCouldNotKeepAndTheUrlItWasSentTo() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX).variable("baseUrl", "https://onyx.example.invalid")
                .url(EpaOperation.PAS_SUBMIT, "{{baseUrl}}/Claim/$submit").fromRequest("submit Happy Path", true);
        RunEnvelope<PasDetail> result = service().runSubmit(submission("P32H-PAS-APPROVED").memberOverrides(List.of("lastName"))
                .providerOverrides(List.of("firstName")).variables(Map.of("claimType", "institutional"))
                .request(new RequestRef("col-1", "req-2")).build(), RunOrigin.manual(), null);
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_MEMBER_DETAILS, RunRecord.INPUT_PROVIDER_DETAILS,
                RunRecord.INPUT_RUN_VALUES, RunRecord.INPUT_CHOSEN_REQUEST);
        assertThat(gateway.lastIntent().request()).isEqualTo(new RequestRef("col-1", "req-2"));
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/Claim/$submit");
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(ledger.only().targetSource()).isEqualTo("submit Happy Path");
        // A run value reaches the body the tool builds.
        assertThat(sentBundle(result).at("/entry/0/resource/type/coding/0/code").asString()).isEqualTo("institutional");
        // The collection request carries a script this tool did not run, and the result says so.
        assertThat(result.findings()).extracting(Finding::kind).contains(FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN);
        assertThat(ledger.only().findings()).contains("PRE_REQUEST_SCRIPT_NOT_RUN");
    }

    @Test
    void aCallThatCompletesButIsSlowIsFlagged() {
        VendorExchange slow = PasJson.response(200, PasJson.bundle(PasJson.claimResponse(PasJson.items(PasJson.decided(1, "A1"))))).withElapsedMillis(16000L);
        RunEnvelope<PasDetail> result = service(stub(slow), "mock", Map.of(), Map.of()).runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.timing().slow()).isTrue();
        assertThat(result.findings()).extracting(Finding::kind).contains(FindingKind.SLOW_RESPONSE);
        assertThat(ledger.only().slow()).isTrue();
    }

    @Test
    void aSubmissionThatMayHaveReachedThePayerIsToBeAskedAboutAndOneThatCannotHaveIsSentAgain() {
        // As a live client reports it: the request had gone out, and no answer arrived in time.
        VendorExchange timeout = TestExchanges.timeout(EpaOperation.PAS_SUBMIT);
        RunEnvelope<PasDetail> result = service(stub(timeout), "live", Map.of(), Map.of()).runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.verdict().supporting()).contains("The payer may have received the submission and acted on it.");
        assertThat(result.nextStep().headline()).isEqualTo("Next: check the environment, then choose Send inquiry before sending again.");
        // The claim id to ask about is handed on, so the tester does not have to find it.
        assertThat(result.nextStep().carry()).containsKeys(NextStepView.CARRY_CLAIM_ID, NextStepView.CARRY_RUN_ID);
        // The panel beside it does not tell the tester to send again while the next step says to ask first.
        assertThat(result.error().action()).isEqualTo("The service may be slow or down. If it repeats, report the environment. The next step "
                + "says whether to send again.");

        // The connection was refused, so nothing was delivered and the same request can go again.
        RunEnvelope<PasDetail> refused = service(stub(TestExchanges.unavailable(EpaOperation.PAS_SUBMIT)), "live", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(refused.verdict().headline()).isEqualTo("The service could not be reached.");
        assertThat(refused.verdict().supporting()).containsExactly("The connection was refused or could not be opened.",
                "Nothing was submitted: the request did not reach the payer.");
        assertThat(refused.nextStep().headline()).isEqualTo("Next: check the environment, then send again.");
        assertThat(refused.nextStep().reason()).isEqualTo("Nothing was submitted, because the request did not reach the payer. It is safe to "
                + "send the same request again.");

        // An inquiry that timed out changed nothing on the far side, so it carries no such warning.
        RunEnvelope<PasDetail> inquiry = service(stub(TestExchanges.timeout(EpaOperation.PAS_INQUIRE)), "live", Map.of(), Map.of())
                .runInquire(inquiry("S123456789").claimId(CLAIM).build(), RunOrigin.manual(), null);
        assertThat(inquiry.verdict().supporting()).noneMatch(line -> line.contains("may have received") || line.contains("submitted"));
        assertThat(inquiry.nextStep().headline()).isEqualTo("Next: check the environment, then send again.");
    }

    @Test
    void onMockTheTwoTransportScenariosRehearseTheTwoAnswersALiveSubmissionCanGet() {
        // The scenario models a service that took the submission and did not answer: ask before sending again.
        RunEnvelope<PasDetail> timeout = submit("P32H-PAS-TIMEOUT");
        assertThat(timeout.verdict().outcomeCode()).isEqualTo("TIMEOUT");
        // Said as what a real timeout would mean, beside a panel that says nothing was sent.
        assertThat(timeout.verdict().supporting()).noneMatch(line -> line.contains("may have received"));
        assertThat(timeout.nextStep().headline()).isEqualTo("Next, had this been a real service: check the environment, then choose Send "
                + "inquiry before sending again.");
        assertThat(timeout.nextStep().reason()).startsWith("Had this been a real service, the payer might have received the submission");
        // The scenario models a refused connection: nothing was submitted.
        RunEnvelope<PasDetail> down = submit("P32H-PAS-DOWN");
        assertThat(down.verdict().outcomeCode()).isEqualTo("UNREACHABLE");
        assertThat(down.verdict().supporting()).contains("Nothing was submitted: the request did not reach the payer.");
        assertThat(down.nextStep().headline()).isEqualTo("Next: check the environment, then send again.");
        // Either way the mock says first that it is a mock, and the wire view that nothing left this machine.
        for (RunEnvelope<PasDetail> result : List.of(timeout, down)) {
            assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario ");
            assertThat(result.wire().request().sent()).isFalse();
            assertThat(result.wire().request().note()).contains("Nothing left this machine.");
        }
    }

    // ---- preview ----

    @Test
    void aPreviewShowsTheRequestWithoutSendingOrRecordingIt() {
        CountingClient client = new CountingClient();
        PasService service = service(client, "mock", Map.of(), Map.of());
        RequestPreview preview = service.previewSubmit(withAttachment("S12345678   01").build(), null);
        assertThat(preview.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.url().configured()).isFalse();
        assertThat(preview.memberId().sent()).isEqualTo("S1234567801");
        JsonNode bundle = JsonSafe.parse(preview.body()).node();
        assertThat(resourceTypes(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "ServiceRequest", "DocumentReference");
        // No identifier is minted for a preview, and the body says where one will go.
        assertThat(bundle.at("/entry/0/resource/identifier/0/value").asString()).isEqualTo("(a new id is generated when the request is sent)");
        assertThat(preview.notes()).isNotEmpty();
        // Nothing was sent, so no note says that something was.
        assertThat(preview.notes()).noneMatch(note -> note.matches("(?s).*\\b(was|were) sent\\b.*"))
                .anyMatch(note -> note.contains("would be sent"));
        assertThat(preview.blocked()).isNull();

        RequestPreview typed = service.previewSubmit(submission("S123456789").claimId(CLAIM).build(), null);
        assertThat(JsonSafe.parse(typed.body()).node().at("/entry/0/resource/identifier/0/value").asString()).isEqualTo(CLAIM);

        RequestPreview inquiry = service.previewInquire(inquiry("S123456789").claimId(CLAIM).build(), null);
        assertThat(inquiry.operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(JsonSafe.parse(inquiry.body()).node().at("/entry/0/resource/identifier/0/value").asString()).isEqualTo(CLAIM);

        RequestPreview own = service.previewSubmit(PasSubmitRequest.builder().rawBody("{\"id\":\"{{bundleIdentifier}}\"}").build(), null);
        assertThat(own.body()).isEqualTo("{\"id\":\"{{bundleIdentifier}}\"}");
        assertThat(own.notes()).containsExactly("The body still contains placeholders with no value: bundleIdentifier.");

        assertThatThrownBy(() -> service.previewSubmit(submission(" ").build(), null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.previewInquire(inquiry("S123456789").build(), null)).isInstanceOf(ValidationException.class);
        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
    }

    // ---- PAS 2.0.1: what the request goes out without, and the items of an inquiry ----

    @Test
    void aRequestSentWithoutWhatThe201ProfilesRequireSaysSoFirstAndCarriesOneFindingThatNamesEachElement() {
        RunEnvelope<PasDetail> result = submit("P32H-PAS-APPROVED");
        assertThat(result.verdict().outcomeCode()).isEqualTo("APPROVED");
        assertThat(result.detail().requestNotes().get(0)).startsWith("This request goes out without 5 elements PAS 2.0.1 requires");
        Finding incomplete = result.findings().get(0);
        assertThat(incomplete.kind()).isEqualTo(FindingKind.REQUEST_INCOMPLETE);
        assertThat(incomplete.title()).isEqualTo("The request left out 5 elements PAS 2.0.1 requires");
        assertThat(incomplete.detail()).contains("PASClaim insurer").contains("PASCoverage payor").contains("PASClaim item.category")
                .contains("PASBeneficiary identifier.system").contains("PASCoverage status");
        assertThat(ledger.only().findings()).contains("REQUEST_INCOMPLETE");
        // The request still went: a request that breaks a profile is a negative case a tester may want.
        assertThat(result.wire().request().body()).contains("\"Claim\"");
    }

    @Test
    void anInquiryWithNoCodesListsTheItemsTheLedgerLineOfTheSubmissionHolds() {
        RunEnvelope<PasDetail> submitted = service().runSubmit(submission("P32H-PAS-INQUIRE").procedureCodes(List.of(CodeInput.of("95810"),
                CodeInput.of("E0601"))).build(), RunOrigin.manual(), null);
        String claimId = submitted.detail().claimId();

        RunEnvelope<PasDetail> inquired = service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId(claimId).build(), RunOrigin.manual(), null);
        JsonNode claim = sentBundle(inquired).at("/entry/0/resource");
        assertThat(claim.get("item")).hasSize(2);
        assertThat(claim.at("/item/1/productOrService/coding/0/code").asString()).isEqualTo("E0601");
        assertThat(inquired.detail().requestNotes()).anySatisfy(note -> assertThat(note)
                .contains("lists the procedure codes found on the ledger line of the submission " + submitted.runId()));

        // With nothing to go on, the inquiry is still sent and says that 2.0.1 requires an item.
        RunEnvelope<PasDetail> unknown = service().runInquire(inquiry("P32H-PAS-INQUIRE").claimId("claim-never-sent").build(),
                RunOrigin.manual(), null);
        assertThat(sentBundle(unknown).at("/entry/0/resource").has("item")).isFalse();
        assertThat(unknown.findings()).extracting(Finding::kind).contains(FindingKind.REQUEST_INCOMPLETE);
        assertThat(unknown.findings().get(0).detail()).contains("PASClaimInquiry item");
    }

    @Test
    void aKeyTheViewReadsRepeatedInOneObjectRaisesAFindingWithEveryValueAsOnCrd() {
        String body = PasJson.bundle(PasJson.claimResponse(PasJson.items(PasJson.decided(1, "A1"))))
                .replace("\"type\":\"collection\"", "\"type\":\"collection\",\"type\":\"batch\"");
        RunEnvelope<PasDetail> result = service(stub(PasJson.response(200, body)), "live", Map.of(), Map.of())
                .runSubmit(submission("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.findings()).filteredOn(f -> f.title().equals("type is sent 2 times in one object")).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.kind()).isEqualTo(FindingKind.GUIDE_RULE_BROKEN);
                    assertThat(finding.detail()).contains("first \"collection\", then \"batch\"").doesNotContain("wrong");
                });
        assertThat(result.additionalFields()).extracting(f -> f.path()).contains("type (value 1 of 2)", "type (value 2 of 2)");
    }
}
