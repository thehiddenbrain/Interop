package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
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
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The CRD service driven through the real scenario files, the real mock client and the real
 * mapper, with a fake gateway, an in-memory ledger and an in-memory code list around them.
 */
class CrdServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SURNAME = "Placeholder-Surname";
    private static final String CHAIN = "chain-00000000-0000-4000-8000-000000000001";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final OnyxCrdClient mockClient = new MockOnyxCrdClient(scenarios, new MockExchanges(APP));

    private CrdService service(OnyxCrdClient client, String mode, Map<String, String> tracking) {
        return service(client, mode, tracking, codeList);
    }

    private CrdService service(OnyxCrdClient client, String mode, Map<String, String> tracking, ProcedureCodeDirectory directory) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = new RunSupport(epa, TrackingProperties.of(tracking), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(epa, gateway, client, directory, support);
    }

    private CrdService service() {
        return service(mockClient, "mock", Map.of());
    }

    private static CrdRunRequest.Builder request(String memberId) {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId(memberId).firstName("Given").lastName(SURNAME).dateOfBirth("1970-01-31")
                        .gender("female").lineOfBusiness("Medicare").subscriberId("SUB-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService("2026-03-20");
    }

    private RunEnvelope<CrdDetail> run(String memberId) {
        return service().run(request(memberId).build(), RunOrigin.manual(), null);
    }

    // ---- the result answers the four questions ----

    @Test
    void aRunReturnsTheVerdictTheTestResultTheNextStepAndTheLedgerReceipt() {
        RunEnvelope<CrdDetail> result = service().run(request("P32H-PA-REQ").expected("AUTH_REQUIRED").testerRef("QA-17")
                .note("first run").chainId(CHAIN).build(), RunOrigin.manual(), null);

        // What happened.
        assertThat(result.verdict().headline()).isEqualTo("Prior authorization is required.");
        assertThat(result.verdict().outcomeCode()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.verdict().tone()).isEqualTo(Tone.ACTION);
        // What did I expect, and did it match.
        assertThat(result.expectation().expected()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // What do I do next.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(result.nextStep().headline()).isEqualTo("Next: submit the prior authorization request in PAS.");
        // And it is recorded, under the same run id the screen shows.
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(ledger.only().runId()).isEqualTo(result.runId());
        assertThat(result.protocol()).isEqualTo(Protocol.CRD);
        assertThat(result.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(result.chainId()).isEqualTo(CHAIN);
        assertThat(result.profile()).isEqualTo("mock");
        assertThat(result.error()).isNull();
        assertThat(result.timing().slow()).isFalse();
    }

    @Test
    void theLedgerRecordCarriesEveryFieldTheSpecificationListsAsAlways() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        service().run(request("P32H-DELEG").expected("AUTH_REQUIRED").testerRef("QA-17").note("routing check").build(), RunOrigin.manual(), null);
        RunRecord record = ledger.only();

        assertThat(record.runId()).startsWith("run-");
        assertThat(record.timestamp()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(record.protocol()).isEqualTo(Protocol.CRD);
        assertThat(record.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(record.target()).isEqualTo("onyx");
        assertThat(record.profile()).isEqualTo("mock");
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(record.scenarioId()).isEqualTo("crd-10-delegated-vendor");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(record.outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(record.coverageCodes()).containsExactly("covered=covered", "pa-needed=auth-needed");
        assertThat(record.delegatedVendor()).isEqualTo("Evolent");
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(record.errorType()).isEqualTo("NONE");
        assertThat(record.expected()).isEqualTo("AUTH_REQUIRED");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.note()).isEqualTo("routing check");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.hook()).isEqualTo("order-sign");
        assertThat(record.memberId()).isEqualTo("P32H-DELEG");
        assertThat(record.npi()).isEqualTo("1234567893");
        assertThat(record.dateOfService()).isEqualTo("2026-03-20");
        assertThat(record.unrecordedInputs()).isEmpty();
    }

    // ---- the distinction this tool exists for, on screen and in the ledger ----

    @Test
    void noAuthAndAnEmptyResponseGiveTwoDifferentScreensAndTwoDifferentLedgerRows() {
        RunEnvelope<CrdDetail> noAuth = service().run(request("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").build(), RunOrigin.manual(), null);
        RunEnvelope<CrdDetail> empty = service().run(request("P32H-EMPTY").expected("NO_AUTH_REQUIRED").build(), RunOrigin.manual(), null);

        // Two screens.
        assertThat(noAuth.verdict().headline()).isEqualTo("Prior authorization is not required.");
        assertThat(empty.verdict().headline()).isEqualTo("The service answered but returned no determination.");
        assertThat(empty.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("not the same as an answer that prior authorization is not required"));
        assertThat(noAuth.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(empty.verdict().tone()).isEqualTo(Tone.NO_ANSWER);

        DeterminationRow noAuthRow = noAuth.determination().stream().filter(r -> r.label().equals("Prior authorization")).findFirst().orElseThrow();
        DeterminationRow emptyRow = empty.determination().stream().filter(r -> r.label().equals("Prior authorization")).findFirst().orElseThrow();
        assertThat(noAuthRow.code()).isEqualTo("no-auth");
        assertThat(noAuthRow.presence()).isEqualTo(Presence.VALUE);
        assertThat(noAuthRow.display()).isEqualTo("No prior authorization is needed");
        // The empty response still gets the row, marked as not returned, so the grid is not simply blank.
        assertThat(emptyRow.code()).isNull();
        assertThat(emptyRow.presence()).isEqualTo(Presence.ABSENT);

        assertThat(noAuth.nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(empty.nextStep().action()).isEqualTo(NextAction.REVIEW);

        // The same expectation passes for one and fails for the other.
        assertThat(noAuth.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(empty.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(empty.expectation().explanation()).isEqualTo(
                "Expected \"Prior authorization is not required\" but the outcome was \"The service returned no determination\".");

        // Two ledger rows.
        RunRecord noAuthRecord = ledger.records().get(0);
        RunRecord emptyRecord = ledger.records().get(1);
        assertThat(noAuthRecord.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(emptyRecord.outcome()).isEqualTo("NO_DETERMINATION");
        assertThat(noAuthRecord.coverageCodes()).containsExactly("covered=covered", "pa-needed=no-auth");
        assertThat(emptyRecord.coverageCodes()).isEmpty();
        assertThat(noAuthRecord.result()).isEqualTo(TestResult.PASS);
        assertThat(emptyRecord.result()).isEqualTo(TestResult.FAIL);
        // Neither is a transport failure: the difference is in what the service said, not in whether it answered.
        assertThat(noAuthRecord.errorType()).isEqualTo("NONE");
        assertThat(emptyRecord.errorType()).isEqualTo("NONE");
        assertThat(noAuthRecord.httpStatus()).isEqualTo(200);
        assertThat(emptyRecord.httpStatus()).isEqualTo(200);
        // CRD 2.0.1 does not require coverage information, so its absence is information (CRD201-15).
        // The form here gives the member ID alone, so the request went without what CRD 2.0.1 requires of a Patient (REQUEST_INCOMPLETE).
        assertThat(emptyRecord.findings()).containsExactly("NOT_STATED", "REQUEST_INCOMPLETE");
        // The code list in this test has no row for the code, and the result says the two were not
        // compared. No finding at all would have read as agreement.
        assertThat(noAuthRecord.findings()).containsExactly("CODE_LIST_NOT_COMPARED", "REQUEST_INCOMPLETE");
    }

    @Test
    void theAuthorizationOutcomeAndTheTestResultAreReportedSeparately() {
        // The tester expects "not covered", and that is what comes back: a stop for the provider, a pass for the test.
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NOCOV").expected("NOT_COVERED").build(), RunOrigin.manual(), null);
        assertThat(result.verdict().tone()).isEqualTo(Tone.STOP);
        assertThat(result.verdict().headline()).isEqualTo("This service is not a covered benefit.");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // And the reverse: a clean "not required" that the tester did not expect is a failed test.
        RunEnvelope<CrdDetail> surprise = service().run(request("P32H-NO-AUTH").expected("AUTH_REQUIRED").build(), RunOrigin.manual(), null);
        assertThat(surprise.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(surprise.expectation().result()).isEqualTo(TestResult.FAIL);
        // With no expectation the run is unverified, never passed.
        assertThat(run("P32H-NO-AUTH").expectation().result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aCodeAssertionIsCheckedAgainstTheStandardCodes() {
        assertThat(service().run(request("P32H-DOC-DTR").expected("pa-needed=auth-needed; doc-needed=clinical").build(), RunOrigin.manual(), null)
                .expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(service().run(request("P32H-DELEG").expected("delegated-vendor=Evolent").build(), RunOrigin.manual(), null)
                .expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(service().run(request("P32H-DELEG").expected("delegated-vendor=eviCore").build(), RunOrigin.manual(), null)
                .expectation().result()).isEqualTo(TestResult.FAIL);
    }

    // ---- an order of several codes ----

    @Test
    void anOrderOfTwoCodesGetsAnAnswerForEachAndEveryPartOfTheResultNamesTheCode() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("E0601", AuthRequirement.NOT_REQUIRED, "Medicare", null);
        RunEnvelope<CrdDetail> result = service().run(request("P32H-PA-REQ")
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601"))).expected("AUTH_REQUIRED; pa-needed=auth-needed").build(),
                RunOrigin.manual(), null);

        assertThat(result.verdict().headline()).isEqualTo("Prior authorization is required.");
        assertThat(result.verdict().supporting()).contains("This is the answer for each of: 95810, E0601.");
        assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario crd-01-pa-required answered")
                .contains("repeated here for each of the 2 orders sent");
        assertThat(result.detail().coverageInformation()).extracting(v -> v.subject()).containsExactly("95810", "E0601");
        // Every row of the grid says which code it is about.
        assertThat(result.determination()).extracting(DeterminationRow::subject).containsOnly("95810", "E0601");
        assertThat(result.determination()).filteredOn(r -> r.label().equals("Prior authorization")).extracting(DeterminationRow::subject)
                .containsExactly("95810", "E0601");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(result.expectation().explanation()).contains("every answer carried pa-needed=auth-needed (95810, E0601)");
        assertThat(result.nextStep().carry()).containsEntry("procedureCodes", List.of("95810", "E0601"));
        // The code list is compared code by code: it agrees on the first and disagrees on the second.
        assertThat(result.findings()).filteredOn(f -> f.kind() == FindingKind.CODE_LIST_DISAGREES_ON_AUTH).extracting(Finding::subject)
                .containsExactly("E0601");

        RunRecord record = ledger.only();
        assertThat(record.procedureCodes()).containsExactly("95810", "E0601");
        assertThat(record.coverageCodes()).contains("95810: pa-needed=auth-needed", "E0601: pa-needed=auth-needed");
    }

    @Test
    void aCodeTheServiceLeftUnansweredIsNeverReportedWithTheAnswerOfTheOther() {
        // The service answers the first of two codes with no-auth and says nothing about the second.
        String partial = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                + "\"extension\":[{\"url\":\"http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information\",\"extension\":["
                + "{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"}]}]}}]}";
        RunEnvelope<CrdDetail> result = service(stub(TestExchanges.crd(200, partial)), "mock", Map.of()).run(request("S123456789")
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601"))).expected("NO_AUTH_REQUIRED").build(), RunOrigin.manual(), null);

        assertThat(result.verdict().outcomeCode()).isEqualTo("MIXED");
        assertThat(result.verdict().headline()).isEqualTo("The codes in this order did not all receive the same answer.");
        assertThat(result.verdict().supporting()).contains("95810: prior authorization is not required.",
                "E0601: no answer was returned for this code. That is not an answer that prior authorization is not required.");
        // The tester expected "not required" for the order. That is not what came back, so the test fails.
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        // The unanswered code has its own rows in the grid, marked as not returned.
        assertThat(result.determination()).filteredOn(r -> "E0601".equals(r.subject())).extracting(DeterminationRow::presence)
                .containsExactly(Presence.ABSENT, Presence.ABSENT);
        assertThat(result.determination()).filteredOn(r -> "95810".equals(r.subject()) && r.label().equals("Prior authorization"))
                .extracting(DeterminationRow::code).containsExactly("no-auth");
        assertThat(ledger.only().outcome()).isEqualTo("MIXED");
        assertThat(ledger.only().coverageCodes()).containsExactly("95810: covered=covered", "95810: pa-needed=no-auth", "E0601: (no answer)");
    }

    // ---- the hand-off between steps, and the configuration the screen was drawn from ----

    @Test
    void theNextStepIsWrittenToTheLedgerSoHistoryCanStillSayWhatToDoNext() {
        run("P32H-NO-AUTH");
        run("P32H-DOC-DTR");
        run("P32H-PA-REQ");
        run("P32H-EMPTY");
        assertThat(ledger.records()).extracting(RunRecord::nextAction).containsExactly("FLOW_ENDS", "START_DTR", "START_PAS", "REVIEW");
    }

    @Test
    void aChainIsStartedByTheServiceAndItsIdComesBackOnTheResult() {
        RunEnvelope<CrdDetail> first = service().run(request("P32H-DOC-DTR").chainId("new").build(), RunOrigin.manual(), null);
        assertThat(RunStamps.isChainId(first.chainId())).isTrue();
        assertThat(ledger.last().chainId()).isEqualTo(first.chainId());
        // The next step posts the id back, and it is kept as given.
        RunEnvelope<CrdDetail> second = service().run(request("P32H-DOC-DTR").chainId(first.chainId()).build(), RunOrigin.manual(), null);
        assertThat(second.chainId()).isEqualTo(first.chainId());
        // No chain is the default, and free text is refused: it would be written to the ledger as it stands.
        assertThat(run("P32H-NO-AUTH").chainId()).isNull();
        assertInvalid(service(), request("S123456789").chainId("Placeholder-Surname follow-up").build(), "chainId");
        assertInvalid(service(), request("S123456789").chainId("chain-1").build(), "chainId");
    }

    @Test
    void aRunThatUsedSomethingAnEarlierStepHandedOnSaysSoInTheLedger() {
        service().run(request("P32H-NO-AUTH").carry(Map.of(NextStepView.CARRY_PROCEDURE_CODES, List.of("95810"))).build(),
                RunOrigin.manual(), null);
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
    }

    @Test
    void aRunIsRefusedWhenTheConfigurationChangedAfterItsScreenWasDrawn() {
        // The tab was drawn at epoch 0 and still shows that environment. Another tab has since
        // activated a different one. Sending would go somewhere the tester is not looking at.
        gateway.changeConfiguration();
        assertThatThrownBy(() -> service().run(request("P32H-NO-AUTH").configEpoch(0L).build(), RunOrigin.manual(), null))
                .isInstanceOf(ConfigurationChangedException.class).hasMessageContaining("Reload");
        assertThat(ledger.records()).isEmpty();
        // With the current epoch it is sent, and with none the check is not made.
        assertThat(service().run(request("P32H-NO-AUTH").configEpoch(1L).build(), RunOrigin.manual(), null).ledger().recorded()).isTrue();
        assertThat(gateway.lastIntent().expectedEpoch()).isEqualTo(1L);
        assertThat(run("P32H-NO-AUTH").ledger().recorded()).isTrue();
        assertThat(gateway.lastIntent().expectedEpoch()).isNull();
    }

    @Test
    void aRequestFieldTheToolDoesNotKnowIsRefusedAndNeverQuietlyDropped() {
        // "expectd" for "expected": dropped silently, this run would be recorded as unverified.
        CrdRunRequest misspelt = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-NO-AUTH\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"expectd\":\"NO_AUTH_REQUIRED\"}",
                CrdRunRequest.class);
        assertThat(misspelt.unrecognised()).containsOnlyKeys("expectd");
        assertThatThrownBy(() -> service().run(misspelt, RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("expectd");
                    assertThat(error.message()).contains("does not know the field \"expectd\"");
                }));
        assertThat(ledger.records()).isEmpty();

        // The same request spelt correctly binds every field it posts, including the two a chain uses.
        CrdRunRequest correct = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-NO-AUTH\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"expected\":\"NO_AUTH_REQUIRED\","
                + "\"carry\":{\"procedureCodes\":[\"95810\"]},\"configEpoch\":0,\"hook\":\"order-sign\"}", CrdRunRequest.class);
        assertThat(correct.unrecognised()).isEmpty();
        assertThat(correct.carry()).containsEntry("procedureCodes", List.of("95810"));
        assertThat(correct.configEpoch()).isZero();
        assertThat(service().run(correct, RunOrigin.manual(), null).expectation().result()).isEqualTo(TestResult.PASS);
    }

    // ---- the production guardrail ----

    private static ProdWriteBlockedException blockedWrite() {
        return new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "PAS submit sample"), "POST", "PAS submit sample");
    }

    @Test
    void aWriteTheGuardrailRefusesIsRecordedAndReturnedAsAResultThatSaysNothingWasSent() {
        // A CRD hook is a read, but a tester can point the run at any collection request, and the
        // gateway judges that request for what it is.
        gateway.failing(blockedWrite());
        AtomicInteger calls = new AtomicInteger();
        OnyxCrdClient counting = new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return TestExchanges.crd(200, "{\"cards\":[]}");
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return TestExchanges.crd(200, "{}");
            }
        };
        RunEnvelope<CrdDetail> result = service(counting, "live", Map.of()).run(request("S12345678   01").expected("AUTH_REQUIRED")
                .testerRef("QA-17").build(), RunOrigin.manual(), null);

        assertThat(calls.get()).isZero();
        assertThat(RunSupport.isBlocked(result)).isTrue();
        assertThat(result.verdict().tone()).isEqualTo(Tone.BLOCKED);
        assertThat(result.verdict().outcomeCode()).isEqualTo("BLOCKED");
        assertThat(result.verdict().headline()).startsWith("Nothing was sent.");
        // Not a pass and not a fail: the service said nothing.
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.expectation().expected()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.BLOCKED);
        // Where it would have gone is shown, marked as not sent, with both forms of the member ID.
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/Claim/$submit");
        assertThat(result.wire().request().method()).isEqualTo("POST");
        assertThat(result.wire().request().body()).isNull();
        assertThat(result.wire().response()).isNull();
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.environment().name()).isEqualTo("Onyx Production");

        // The attempt has its line, and the result carries the receipt so the tester is told.
        assertThat(result.ledger().recorded()).isTrue();
        RunRecord record = ledger.only();
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.outcome()).isEqualTo("BLOCKED");
        assertThat(record.errorType()).isEqualTo(RunRecord.ERROR_BLOCKED);
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.environment()).isEqualTo("Onyx Production");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.PROD);
        assertThat(record.targetSource()).isEqualTo("PAS submit sample");
        assertThat(record.httpStatus()).isNull();
        assertThat(record.memberId()).isEqualTo("S1234567801");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.nextAction()).isEqualTo("UNLOCK_OR_SWITCH");
    }

    @Test
    void theSessionsUnlockIsHandedToTheGatewayAndNothingElseDecidesWhetherItHolds() {
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        service().run(request("P32H-NO-AUTH").build(), RunOrigin.manual(), unlock);
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);
        service().run(request("P32H-NO-AUTH").build(), RunOrigin.manual(), null);
        assertThat(gateway.lastIntent().unlock()).isNull();
    }

    @Test
    void whereARunCameFromReachesTheGatewaySoARefusalCanBeWordedForAReplayOrASuite() {
        RunOrigin suite = new RunOrigin("suite-00000000-0000-4000-8000-000000000009", "case-1", null);
        service().run(request("P32H-NO-AUTH").build(), suite, null);
        assertThat(gateway.lastIntent().origin()).isEqualTo(suite);
        RunOrigin replay = new RunOrigin(null, null, "run-00000000-0000-4000-8000-000000000008");
        service().run(request("P32H-NO-AUTH").build(), replay, null);
        assertThat(gateway.lastIntent().origin()).isEqualTo(replay);
        // A preview is no run, and says nothing about where one came from.
        service().preview(request("P32H-NO-AUTH").build(), null);
        assertThat(gateway.lastIntent().origin()).isNull();
    }

    @Test
    void aNextStepToDtrOrPasHandsOnThisRunSoTheLaterLineIsLinkedToIt() {
        // Suites VS-11: a manual chain's later line names the CRD run it followed, as a suite chain row's does.
        RunEnvelope<CrdDetail> toDtr = run("P32H-DOC-DTR");
        assertThat(toDtr.nextStep().action()).isEqualTo(NextAction.START_DTR);
        assertThat(toDtr.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(toDtr.runId());
        assertThat(ledger.last().runId()).isEqualTo(toDtr.runId());
        RunEnvelope<CrdDetail> toPas = run("P32H-PA-REQ");
        assertThat(toPas.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(toPas.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(toPas.runId());
        // A step that starts nothing hands on no run.
        assertThat(run("P32H-NO-AUTH").nextStep().carry()).doesNotContainKey(NextStepView.CARRY_RUN_ID);
        assertThat(run("P32H-EMPTY").nextStep().carry()).doesNotContainKey(NextStepView.CARRY_RUN_ID);
    }

    // ---- not found, faults ----

    @Test
    void notFoundIsPresentedAsAFindingAboutTestDataAndAFaultAsAFindingAboutTheEnvironment() {
        RunEnvelope<CrdDetail> notFound = run("P32H-NOMEM");
        assertThat(notFound.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(notFound.verdict().supporting()).contains("This is a finding about the test data, not about the environment.");
        assertThat(notFound.nextStep().action()).isEqualTo(NextAction.FIX_TEST_DATA);
        // No error panel: the service answered. Its issues are still shown in the detail.
        assertThat(notFound.error()).isNull();
        assertThat(notFound.detail().issues()).hasSize(1);

        RunEnvelope<CrdDetail> serverError = run("P32H-ERR500");
        assertThat(serverError.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(serverError.verdict().headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(serverError.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.error().httpStatus()).isEqualTo(500);
        assertThat(serverError.error().issues()).hasSize(1);

        assertThat(ledger.records().get(0).outcome()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(ledger.records().get(1).outcome()).isEqualTo("SERVICE_ERROR");
        assertThat(ledger.records().get(1).errorType()).isEqualTo("SERVER_ERROR");
    }

    @Test
    void aTimeoutFromAServiceIsNotSaidToBeSimulated() {
        assertThat(service(stub(TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN)), "mock", Map.of()).run(request("S123456789").build(),
                RunOrigin.manual(), null).verdict().supporting()).noneMatch(line -> line.contains("simulated"));
    }

    @Test
    void theFourKindsOfFaultAreDistinguishedInTheErrorPanel() {
        assertThat(run("P32H-BAD400").error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(run("P32H-ERR500").error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        RunEnvelope<CrdDetail> timeout = run("P32H-TIMEOUT");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.error().httpStatus()).isNull();
        assertThat(timeout.verdict().headline()).isEqualTo("The service did not answer in time.");
        // On mock the timeout is a scenario's short delay, and the result says so beside its response time (frame review FR-16).
        assertThat(timeout.verdict().supporting()).anySatisfy(line -> assertThat(line).contains(org.p32h.interop.epa.pave.model.VendorExchange
                .SIMULATED_TIMEOUT));
        OnyxCrdClient unreachable = stub(TestExchanges.unavailable(EpaOperation.CRD_ORDER_SIGN));
        RunEnvelope<CrdDetail> down = service(unreachable, "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        OnyxCrdClient garbled = stub(TestExchanges.crd(200, "{\"cards\": ["));
        RunEnvelope<CrdDetail> malformed = service(garbled, "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        // The raw bytes are kept for the screen even though they could not be parsed.
        assertThat(malformed.wire().response().body()).isEqualTo("{\"cards\": [");
        assertThat(malformed.wire().response().parsed()).isFalse();
        assertThat(ledger.records()).extracting(RunRecord::errorType)
                .containsExactly("BAD_REQUEST", "SERVER_ERROR", "TIMEOUT", "UNAVAILABLE", "MALFORMED_RESPONSE");
    }

    @Test
    void everyResultSaysWhichRulesOfCrd201TheCheckerAppliedFaultsAndEmptyAnswersIncluded() {
        // V-02 and CRD201-08: silence about a rule the checker does not apply is not read as a pass.
        for (String sentinel : List.of("P32H-PA-REQ", "P32H-EMPTY", "P32H-ERR500", "P32H-BAD400")) {
            assertThat(run(sentinel).detail().conformance()).as(sentinel).isEqualTo(CrdConformanceChecker.SCOPE);
        }
        OnyxCrdClient unreachable = stub(TestExchanges.unavailable(EpaOperation.CRD_ORDER_SIGN));
        assertThat(service(unreachable, "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual(), null).detail()
                .conformance()).isEqualTo(CrdConformanceChecker.SCOPE);
        assertThat(CrdConformanceChecker.SCOPE.version()).isEqualTo("CRD 2.0.1");
    }

    // ---- validation: nothing sent, nothing recorded ----

    @Test
    void aFormThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        AtomicInteger calls = new AtomicInteger();
        OnyxCrdClient counting = new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return TestExchanges.crd(200, "{\"cards\":[]}");
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return TestExchanges.crd(200, "{}");
            }
        };
        CrdService service = service(counting, "mock", Map.of());

        assertInvalid(service, request(" ").build(), "member.memberId");
        assertInvalid(service, CrdRunRequest.builder().provider(ProviderDetail.builder().npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("95810"))).build(), "member.memberId");
        assertInvalid(service, request("S123456789").provider(ProviderDetail.builder().npi("1234567890").build()).build(), "provider.npi");
        assertInvalid(service, request("S123456789").provider(null).build(), "provider.npi");
        assertInvalid(service, request("S123456789").procedureCodes(List.of()).build(), "procedureCodes");
        assertInvalid(service, request("S123456789").procedureCodes(List.of(CodeInput.of(" "))).build(), "procedureCodes[0].code");
        assertInvalid(service, request("S123456789").dateOfService("03/20/2026").build(), "dateOfService");
        assertInvalid(service, request("S123456789").dateOfService("2026-02-30").build(), "dateOfService");
        assertInvalid(service, request("S123456789").member(MemberDetail.builder().memberId("S123456789").dateOfBirth("2026-03-11").build()).build(),
                "member.dateOfBirth");
        assertInvalid(service, request("S123456789").member(MemberDetail.builder().memberId("S123456789").dateOfBirth("31-01-1970").build()).build(),
                "member.dateOfBirth");
        assertInvalid(service, request("S123456789").expected("APPROVED").build(), "expected");
        assertInvalid(service, request("S123456789").hook("order-dispatch").build(), "hook");
        // A misspelt element name is refused here. Sent, it could only come back as a failed test with a false reason.
        assertInvalid(service, request("S123456789").expected("pa_needed=no-auth").build(), "expected");

        assertThat(calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void everyProblemWithTheFormIsReportedAtOnce() {
        assertThatThrownBy(() -> service().run(CrdRunRequest.builder().dateOfService("tomorrow").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field)
                        .containsExactly("member.memberId", "provider.npi", "procedureCodes", "dateOfService"));
    }

    @Test
    void aScenarioCannotBeForcedOnLive() {
        assertInvalid(service(mockClient, "live", Map.of()), request("S123456789").scenarioId("crd-02-no-auth").build(), "scenarioId");
        // On mock the same request runs and answers from the chosen scenario, whatever the member.
        RunEnvelope<CrdDetail> forced = service().run(request("P32H-PA-REQ").scenarioId("crd-02-no-auth").build(), RunOrigin.manual(), null);
        assertThat(forced.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(ledger.only().scenarioId()).isEqualTo("crd-02-no-auth");
    }

    private void assertInvalid(CrdService service, CrdRunRequest request, String field) {
        assertThatThrownBy(() -> service.run(request, RunOrigin.manual(), null))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    @Test
    void aTargetTheGatewayRefusesIsNotSentAndNotRecorded() {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        assertThatThrownBy(() -> run("P32H-PA-REQ")).isInstanceOf(EndpointNotConfiguredException.class);
        assertThat(ledger.records()).isEmpty();
    }

    // ---- the ledger ----

    @Test
    void aLedgerThatCannotWriteIsReportedOnTheResultAndTheResultIsStillReturned() {
        ledger.refusing("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written.");
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded");
        assertThat(result.ledger().record().runId()).isEqualTo(result.runId());
    }

    @Test
    void aLedgerThatThrowsDoesNotCostTheTesterTheirResult() {
        ledger.throwing(new IllegalStateException("disk full for Placeholder-Surname"));
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded").doesNotContain(SURNAME);
        // The receipt still carries the record, so a suite row or a replay does not lose the run.
        // No ledger applied the tracking policy to it, so everything the policy governs is left out.
        RunRecord unkept = result.ledger().record();
        assertThat(unkept.runId()).isEqualTo(result.runId());
        assertThat(unkept.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(unkept.memberId()).isNull();
        assertThat(unkept.npi()).isNull();
        assertThat(unkept.dateOfService()).isNull();
        assertThat(result.ledger().runsToday()).isNull();
    }

    @Test
    void anAuthorizationNumberDoesNotRideAlongInAReceiptTheLedgerNeverSaw() {
        ledger.throwing(new IllegalStateException("disk full"));
        RunEnvelope<CrdDetail> result = run("P32H-PA-SAT");
        assertThat(result.ledger().record().correlation()).doesNotContainKey(RunRecord.CORR_AUTHORIZATION_REF);
    }

    @Test
    void theRecordHandedToTheLedgerHoldsNoMemberDataAndNoWireJson() {
        service().run(request("P32H-DOC-DTR").build(), RunOrigin.manual(), null);
        String line = JsonSafe.mapper().writeValueAsString(ledger.only());
        assertThat(line).doesNotContain(SURNAME).doesNotContain("Given").doesNotContain("1970-01-31").doesNotContain("female")
                .doesNotContain("SUB-PLACEHOLDER-1").doesNotContain("resourceType").doesNotContain("hookInstance")
                .doesNotContain("systemActions").doesNotContain("Clinical documentation is required");
    }

    @Test
    void aResponseThatPutsMemberDataWhereCodesBelongIsShownOnScreenAndKeptOutOfTheLedgerLine() {
        // A non-conformant response: a sentence in place of the pa-needed code, an object in place
        // of the doc-needed code, and a person where the delegated vendor should be.
        String ext = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
        String body = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                + "\"extension\":[{\"url\":\"" + ext + "\",\"extension\":["
                + "{\"url\":\"pa-needed\",\"valueString\":\"Auth needed for Placeholder-Surname born 1970-01-01\"},"
                + "{\"url\":\"doc-needed\",\"valueCoding\":{\"code\":{\"patient\":\"Placeholder-Surname\",\"subscriberId\":\"SUB-1\"}}},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"see {\\\"patient\\\":\\\"Placeholder-Surname\\\"}\"},"
                + "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"delegated-vendor\"}]}},"
                + "{\"url\":\"value\",\"valueString\":\"Placeholder-Surname, authorized representative, 1 Placeholder Street\"}]}]}]}}]}";
        RunEnvelope<CrdDetail> result = service(stub(TestExchanges.crd(200, body)), "mock", Map.of()).run(request("S123456789").build(),
                RunOrigin.manual(), null);

        // On screen the tester sees exactly what came back: that is the evidence.
        assertThat(result.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_CODE");
        assertThat(result.detail().coverageInformation().get(0).paNeeded().code()).isEqualTo("Auth needed for Placeholder-Surname born 1970-01-01");
        assertThat(result.detail().coverageInformation().get(0).delegatedVendor()).contains("Placeholder Street");

        // The line that is kept holds none of it, and still says that something was there.
        RunRecord record = ledger.only();
        String line = JsonSafe.mapper().writeValueAsString(record);
        assertThat(line).doesNotContain("Placeholder-Surname").doesNotContain("1970-01-01").doesNotContain("SUB-1")
                .doesNotContain("Placeholder Street").doesNotContain("subscriberId");
        assertThat(record.outcome()).isEqualTo("UNRECOGNISED_CODE");
        assertThat(record.coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("pa-needed=(not a code, "));
        assertThat(record.delegatedVendor()).startsWith("(vendor not on the known list, ");
        assertThat(record.correlation().get(RunRecord.CORR_QUESTIONNAIRE)).startsWith("(not recorded, ");
        // The receipt on the result carries the same checked record, not the raw one.
        assertThat(JsonSafe.mapper().writeValueAsString(result.ledger())).doesNotContain("Placeholder-Surname").doesNotContain("SUB-1");
    }

    @Test
    void aRunFromASuiteOrAReplayCarriesItsOrigin() {
        service().run(request("P32H-NO-AUTH").build(), new RunOrigin("suite-1", "case-7", "run-earlier"), null);
        assertThat(ledger.only().suiteRunId()).isEqualTo("suite-1");
        assertThat(ledger.only().caseId()).isEqualTo("case-7");
        assertThat(ledger.only().replayOfRunId()).isEqualTo("run-earlier");
        service().run(request("P32H-NO-AUTH").build(), null, null);
        assertThat(ledger.last().suiteRunId()).isNull();
    }

    @Test
    void aFailureOfThisToolAfterTheCallStillLeavesALedgerLine() {
        // The code is given its system, so the request is built without the code list. The code
        // list is then consulted only after the call, where this failure hits.
        codeList.failing(new IllegalStateException("code list exploded"));
        CrdRunRequest request = request("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED")
                .procedureCodes(List.of(new CodeInput("95810", "http://www.ama-assn.org/go/cpt", null))).build();
        RunEnvelope<CrdDetail> result = service().run(request, RunOrigin.manual(), null);

        // The service was called, so the run happened and is recorded as a failure of the tool.
        assertThat(ledger.only().outcome()).isEqualTo("TOOL_ERROR");
        assertThat(ledger.only().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(ledger.only().httpStatus()).isEqualTo(200);
        assertThat(ledger.only().scenarioId()).isEqualTo("crd-02-no-auth");
        assertThat(ledger.only().nextAction()).isEqualTo("REVIEW");
        assertThat(ledger.only().memberId()).isEqualTo("P32H-NO-AUTH");

        // And the tester is not left with "internal error" alone: the result ties the failure to
        // its ledger line and still shows what was sent and what came back.
        assertThat(RunSupport.isToolError(result)).isTrue();
        assertThat(result.runId()).isEqualTo(ledger.only().runId());
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(result.verdict().outcomeCode()).isEqualTo("TOOL_ERROR");
        assertThat(result.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario crd-02-no-auth answered");
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.expectation().expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.INTERNAL);
        assertThat(result.error().reference()).isNotBlank();
        // The exception's own message is never shown: it can quote a body.
        assertThat(result.error().title() + result.error().action() + result.verdict().headline()).doesNotContain("code list exploded");
        assertThat(result.wire().request().body()).contains("\"hook\"");
        assertThat(result.wire().response().body()).contains("\"no-auth\"");
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.detail()).isNull();
    }

    @Test
    void aFailureOfThisToolAfterTheCallIsStillReportedWhenTheLedgerCannotWriteEither() {
        codeList.failing(new IllegalStateException("code list exploded"));
        ledger.refusing("This run was NOT recorded: the ledger file could not be written.");
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NO-AUTH")
                .procedureCodes(List.of(new CodeInput("95810", "http://www.ama-assn.org/go/cpt", null))).build(), RunOrigin.manual(), null);
        assertThat(RunSupport.isToolError(result)).isTrue();
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded");
    }

    @Test
    void aFailureBeforeTheCallLeavesNoLedgerLineBecauseNoRunHappened() {
        // Here the code list fails while the request is being built, so nothing was sent.
        codeList.failing(new IllegalStateException("code list exploded"));
        assertThatThrownBy(() -> run("P32H-NO-AUTH")).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.records()).isEmpty();
    }

    // ---- what was sent ----

    @Test
    void aSpacedMemberIdIsSentWithoutSpacesAndBothFormsAreShown() {
        RunEnvelope<CrdDetail> result = service().run(request("S12345678   01").build(), RunOrigin.manual(), null);
        assertThat(result.wire().memberId().typed()).isEqualTo("S12345678   01");
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.wire().memberId().changed()).isTrue();
        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.at("/prefetch/patient/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(result.wire().request().body()).doesNotContain("S12345678   01");
        assertThat(ledger.only().memberId()).isEqualTo("S1234567801");
        // An unspaced id goes through the same path unchanged.
        RunEnvelope<CrdDetail> unspaced = service().run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(unspaced.wire().memberId().changed()).isFalse();
        assertThat(unspaced.wire().memberId().sent()).isEqualTo("S123456789");
    }

    @Test
    void onMockTheWireViewSaysNothingWasSentAndWhichScenarioAnswered() {
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().note()).isEqualTo("Mock profile. Answered from scenario crd-02-no-auth, selected by the sentinel "
                + "member ID P32H-NO-AUTH. Nothing left this machine.");
        assertThat(result.wire().request().method()).isEqualTo("POST");
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.wire().response().body()).contains("\"no-auth\"");
        // An ordinary member gets the default scenario, and the note says it is the default.
        assertThat(run("S123456789").wire().request().note()).contains("crd-01-pa-required").contains("the default");
    }

    @Test
    void orderSelectIsSentAsItsOwnOperationWithItsSelections() {
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NO-AUTH").hook("order-select").build(), RunOrigin.manual(), null);
        assertThat(result.operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.get("hook").asString()).isEqualTo("order-select");
        assertThat(sent.at("/context/selections/0").asString()).isEqualTo("ServiceRequest/service-request-1");
        assertThat(ledger.only().hook()).isEqualTo("order-select");
    }

    @Test
    void theTestersOwnBodyIsSentAsWrittenAndTheLedgerSaysTheBodyWasNotKept() {
        String body = "{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"P32H-NO-AUTH\"},\"vendorSampleField\":true}";
        RunEnvelope<CrdDetail> result = service().run(CrdRunRequest.builder().rawBody(body).build(), RunOrigin.manual(), null);

        assertThat(result.wire().request().body()).isEqualTo(body);
        // The form held no member, so the sentinel typed into the body selected the scenario.
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.wire().request().note()).contains("found in the request body");
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        assertThat(result.detail().requestNotes()).isEmpty();
        assertThat(result.wire().memberId()).isNull();
        RunRecord record = ledger.only();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY);
        assertThat(record.memberId()).isNull();
        assertThat(record.procedureCodes()).isEmpty();
    }

    @Test
    void aBodyThatIsNotJsonIsStillSentAsWrittenAndTheResultSaysSo() {
        // Sending a malformed body is a negative case a tester is entitled to try.
        RunEnvelope<CrdDetail> result = service().run(CrdRunRequest.builder().rawBody("{\"hook\": \"order-sign\",").build(),
                RunOrigin.manual(), null);
        assertThat(result.wire().request().body()).isEqualTo("{\"hook\": \"order-sign\",");
        assertThat(result.detail().requestNotes()).singleElement().satisfies(note ->
                assertThat(note).contains("not valid JSON").contains("sent exactly as written"));
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY);
    }

    @Test
    void onMockABodyThatIsNotJsonOffersNoNextStepFromTheScenarioThatAnsweredItAndOnLiveTheAnswerDecides() {
        // Case CASE-06: the sentinel in the text chose a scenario that requires prior authorization. That answer says nothing
        // about a body no service could read, so PAS is not offered from it and nothing is handed on.
        String unreadable = "{\"hook\":\"order-sign\", not json P32H-PA-REQ";
        RunEnvelope<CrdDetail> onMock = service().run(CrdRunRequest.builder().rawBody(unreadable).build(), RunOrigin.manual(), null);
        assertThat(onMock.verdict().outcomeCode()).isEqualTo("AUTH_REQUIRED");
        assertThat(onMock.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(onMock.nextStep().headline()).isEqualTo("Next: correct the body and send it again. Nothing is handed on from this answer.");
        assertThat(onMock.nextStep().reason()).isEqualTo("On the mock profile a scenario answered, although the body you supplied is not "
                + "valid JSON. That answer says nothing about the body, so no next step is offered from it. A service would read the body "
                + "as it stands.");
        assertThat(onMock.nextStep().carry()).isEmpty();
        assertThat(ledger.last().nextAction()).isEqualTo(NextAction.REVIEW.name());
        // A body that is JSON keeps the next step its answer gives.
        assertThat(service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"P32H-PA-REQ\"}}")
                .build(), RunOrigin.manual(), null).nextStep().action()).isEqualTo(NextAction.START_PAS);
        // On live the service itself answered the body, so its answer decides the next step.
        RunEnvelope<CrdDetail> onLive = service(mockClient, "live", Map.of()).run(CrdRunRequest.builder().rawBody(unreadable).build(),
                RunOrigin.manual(), null);
        assertThat(onLive.nextStep().action()).isEqualTo(NextAction.START_PAS);
    }

    @Test
    void aBodyWhosePlaceholdersOnlyResolveIntoJsonIsAccepted() {
        // Unquoted, as a vendor sample writes a number. It is not JSON until the value is filled in.
        String body = "{\"hook\":\"order-sign\",\"context\":{\"encounterNumber\":{{encounterNumber}}}}";
        RunEnvelope<CrdDetail> result = service().run(CrdRunRequest.builder().rawBody(body).variables(Map.of("encounterNumber", "42")).build(),
                RunOrigin.manual(), null);
        assertThat(result.wire().request().body()).isEqualTo("{\"hook\":\"order-sign\",\"context\":{\"encounterNumber\":42}}");
        assertThat(result.detail().requestNotes()).isEmpty();
    }

    @Test
    void theCodesInATestersOwnBodyAreTheOnesRecordedAndTiedToTheAnswers() {
        String body = "{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"P32H-NO-AUTH\",\"draftOrders\":{\"resourceType\":\"Bundle\",\"entry\":["
                + "{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\",\"code\":{\"coding\":[{\"code\":\"70553\"}]}}}]}}}";
        RunEnvelope<CrdDetail> result = service().run(CrdRunRequest.builder().rawBody(body)
                .procedureCodes(List.of(CodeInput.of("95810"))).build(), RunOrigin.manual(), null);
        // The form still held another code. What went out is what is recorded.
        assertThat(ledger.only().procedureCodes()).containsExactly("70553");
        assertThat(result.detail().coverageInformation().get(0).procedureCodes()).containsExactly("70553");
    }

    @Test
    void anOrderInATestersOwnBodyIsNamedByItsCodeOrItsPositionAndNeverByAnIdAndAPatientIsNoOrder() {
        // Ledger m6: ids in a body the tester wrote are free text, and a Patient in draftOrders is not an order CRD 2.0.1 profiles.
        String body = "{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"P32H-NO-AUTH\",\"draftOrders\":{\"resourceType\":\"Bundle\","
                + "\"entry\":[{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"Zzlastname-1961-07-23\",\"code\":{\"coding\":"
                + "[{\"code\":\"70553\"}]}}},{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"Zzfirstname.Zzlastname\"}},"
                + "{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p1\",\"identifier\":[{\"value\":\"S123456789\"}]}}]}}}";
        service().run(CrdRunRequest.builder().rawBody(body).build(), RunOrigin.manual(), null);
        String line = JsonSafe.mapper().writeValueAsString(ledger.only());
        assertThat(line).doesNotContain("Zzfirstname").doesNotContain("Zzlastname").doesNotContain("1961-07-23").doesNotContain("Patient/");
        assertThat(ledger.only().procedureCodes()).containsExactly("70553");
        assertThat(SentOrder.fromBody(body)).extracting(SentOrder::label).containsExactly("70553", "order 2 (no code)");
    }

    @Test
    void aBodyThatStillHoldsAPlaceholderIsRefusedAndNotRecorded() {
        assertThatThrownBy(() -> service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"fhirServer\":\"{{fhirServer}}\"}").build(),
                RunOrigin.manual(), null)).isInstanceOfSatisfying(UnresolvedVariablesException.class,
                e -> assertThat(e.variables()).containsExactly("fhirServer"));
        assertThat(ledger.records()).isEmpty();
        // Typed for this run, it resolves, and the ledger notes that a run value was used.
        service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"fhirServer\":\"{{fhirServer}}\"}")
                .variables(Map.of("fhirServer", "https://ehr.example.invalid/fhir")).build(), RunOrigin.manual(), null);
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES);
    }

    @Test
    void theLedgerNamesEveryInputItCouldNotKeep() {
        service().run(request("P32H-NO-AUTH").memberOverrides(List.of("lastName")).providerOverrides(List.of("firstName"))
                .variables(Map.of("fhirServer", "https://ehr.example.invalid/fhir")).request(new RequestRef("col-1", "req-2")).build(),
                RunOrigin.manual(), null);
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_MEMBER_DETAILS, RunRecord.INPUT_PROVIDER_DETAILS,
                RunRecord.INPUT_RUN_VALUES, RunRecord.INPUT_CHOSEN_REQUEST);
        assertThat(gateway.lastIntent().request()).isEqualTo(new RequestRef("col-1", "req-2"));
        assertThat(gateway.lastIntent().runValues()).containsEntry("fhirServer", "https://ehr.example.invalid/fhir");
    }

    @Test
    void theResolvedUrlAndTheCollectionRequestAreShownAndRecorded() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX).variable("baseUrl", "https://onyx.example.invalid")
                .variable("crdOrderSignServiceId", "placeholder-service")
                .url(EpaOperation.CRD_ORDER_SIGN, "{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}")
                .fromRequest("order-sign Happy Path", false);
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/cds-services/placeholder-service");
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}");
        assertThat(result.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(ledger.only().targetSource()).isEqualTo("order-sign Happy Path");
    }

    // ---- findings ----

    @Test
    void aRequestWithAPreRequestScriptIsFlaggedOnTheResultAndInTheLedger() {
        gateway.url(EpaOperation.CRD_ORDER_SIGN, "https://onyx.example.invalid/cds-services/x").fromRequest("order-sign Happy Path", true);
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        Finding script = result.findings().stream().filter(f -> f.kind() == FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN).findFirst().orElseThrow();
        assertThat(script.subject()).isEqualTo("order-sign Happy Path");
        assertThat(ledger.only().findings()).contains("PRE_REQUEST_SCRIPT_NOT_RUN");
    }

    @Test
    void aCallThatCompletesButIsSlowIsFlaggedAndATimeoutIsNot() {
        VendorExchange slow = TestExchanges.crd(200, "{\"cards\":[]}").withElapsedMillis(16000L);
        RunEnvelope<CrdDetail> result = service(stub(slow), "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.timing().elapsedMillis()).isEqualTo(16000L);
        assertThat(result.timing().thresholdMillis()).isEqualTo(15000L);
        assertThat(result.timing().slow()).isTrue();
        assertThat(result.findings()).extracting(Finding::kind).contains(FindingKind.SLOW_RESPONSE);
        assertThat(ledger.only().slow()).isTrue();

        RunEnvelope<CrdDetail> configured = service(stub(slow), "mock", Map.of("slow-threshold-ms", "20000")).run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(configured.timing().slow()).isFalse();

        RunEnvelope<CrdDetail> timedOut = service(stub(TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN)), "mock", Map.of())
                .run(request("S123456789").build(), RunOrigin.manual(), null);
        assertThat(timedOut.timing().slow()).isFalse();
        assertThat(timedOut.findings()).extracting(Finding::kind).doesNotContain(FindingKind.SLOW_RESPONSE);
    }

    @Test
    void aDisagreementWithTheCodeListIsAFindingThatStatesBothSides() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore");
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        Finding finding = result.findings().stream().filter(f -> f.kind() == FindingKind.CODE_LIST_DISAGREES_ON_AUTH).findFirst().orElseThrow();
        assertThat(finding.subject()).isEqualTo("95810");
        assertThat(finding.codeListSays()).isEqualTo("The code list says prior authorization is required (rows for Medicare).");
        assertThat(finding.serviceSays()).isEqualTo("The service answered pa-needed=no-auth.");
        assertThat(finding.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which.");
        // A finding is not a failure of the call, and it does not change the verdict or the test result.
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.error()).isNull();
        assertThat(ledger.only().findings()).contains("CODE_LIST_DISAGREES_ON_AUTH");
    }

    // ---- the placeholder code list, which must never read as the P32H list ----

    private static final String PLACEHOLDER_NOTE = "The code list here is the synthetic placeholder that ships with the tool and not a P32H list.";

    /** The list as it loads on a fresh checkout, with codelist.file blank: the synthetic rows bundled with the tool. */
    private static ProcedureCodeService bundledPlaceholder() {
        ProcedureCodeService bundled = new ProcedureCodeService(CodelistProperties.defaults());
        assertThat(bundled.selfCheck().placeholder()).isTrue();
        return bundled;
    }

    private static String noAuthFor(int order) {
        return "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-" + order + "\","
                + "\"extension\":[{\"url\":\"http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information\",\"extension\":["
                + "{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/coverage-1\"}},"
                + "{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"},"
                + "{\"url\":\"date\",\"valueDate\":\"2026-01-15\"},{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-" + order + "\"}]}]}}";
    }

    private static List<Finding> aboutTheCodeList(RunEnvelope<CrdDetail> result) {
        return result.findings().stream().filter(f -> f.kind().name().startsWith("CODE_LIST_")).toList();
    }

    @Test
    void onLiveThePlaceholderCodeListIsNeverComparedWithTheServiceAndTheResultSaysSoOnce() {
        // The placeholder says 95810 and E0470 need authorization for Medicare. The service, which
        // on live is a real one, answers no-auth for both. Compared, that would be two findings
        // that the code list and the service disagree, and a tester would take them to the vendor.
        // They would be statements about rows somebody made up.
        String noAuthForBoth = "{\"systemActions\":[" + noAuthFor(1) + "," + noAuthFor(2) + "]}";
        RunEnvelope<CrdDetail> result = service(stub(TestExchanges.crd(200, noAuthForBoth)), "live", Map.of(), bundledPlaceholder())
                .run(request("S123456789").procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0470"))).build(), RunOrigin.manual(), null);

        assertThat(result.profile()).isEqualTo("live");
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        // One finding for the run, not one for each code, and it says what to do.
        assertThat(aboutTheCodeList(result)).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_NOT_COMPARED);
            assertThat(f.title()).isEqualTo("The code list was not compared with the service: no P32H code list is loaded");
            assertThat(f.detail()).isEqualTo("No P32H code list is loaded. The tool is using the synthetic placeholder list that ships "
                    + "with it, which says nothing about real codes, so nothing was compared with the service's answer. Set codelist.file "
                    + "to the P32H export and restart the tool.");
            // It is about the run, not about one code, and it states no position for either side.
            assertThat(f.subject()).isNull();
            assertThat(f.codeListSays()).isNull();
            assertThat(f.serviceSays()).isNull();
        });
        assertThat(result.findings()).extracting(Finding::kind)
                .doesNotContain(FindingKind.CODE_LIST_DISAGREES_ON_AUTH, FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
        // The ledger line says the same, so a history row or a suite count cannot report a disagreement either.
        assertThat(ledger.only().profile()).isEqualTo("live");
        assertThat(ledger.only().findings()).containsExactly("CODE_LIST_NOT_COMPARED", "REQUEST_INCOMPLETE");
    }

    @Test
    void onMockThePlaceholderIsComparedAndEveryFindingThatRestsOnItSaysWhatItIs() {
        // On mock both sides are synthetic and showing a disagreement is the point. The mock answers
        // no-auth for both orders. The placeholder says 95810 needs authorization and has no row for 99999.
        RunEnvelope<CrdDetail> result = service(mockClient, "mock", Map.of(), bundledPlaceholder())
                .run(request("P32H-NO-AUTH").procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("99999"))).build(), RunOrigin.manual(), null);

        assertThat(result.profile()).isEqualTo("mock");
        assertThat(aboutTheCodeList(result)).extracting(Finding::kind, Finding::subject).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FindingKind.CODE_LIST_DISAGREES_ON_AUTH, "95810"),
                org.assertj.core.groups.Tuple.tuple(FindingKind.CODE_LIST_NOT_COMPARED, "99999"));
        assertThat(aboutTheCodeList(result).get(0).detail()).isEqualTo("One of them is wrong, and this tool cannot tell which. " + PLACEHOLDER_NOTE);
        assertThat(aboutTheCodeList(result).get(0).codeListSays()).isEqualTo("The code list says prior authorization is required (rows for Medicare).");
        assertThat(aboutTheCodeList(result).get(1).detail()).isEqualTo("The code list has no row for 99999 on 2026-03-20. " + PLACEHOLDER_NOTE);
        assertThat(ledger.only().findings()).containsExactly("CODE_LIST_DISAGREES_ON_AUTH", "CODE_LIST_NOT_COMPARED", "REQUEST_INCOMPLETE");
    }

    @Test
    void aRealCodeListIsComparedTheSameWayOnBothProfilesAndNothingIsSaidAboutAPlaceholder() {
        // The in-memory list stands for a real export. The same answer, the same row, each profile.
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null);
        String noAuth = "{\"systemActions\":[" + noAuthFor(1) + "]}";
        for (String mode : List.of("mock", "live")) {
            RunEnvelope<CrdDetail> result = service(stub(TestExchanges.crd(200, noAuth)), mode, Map.of()).run(request("S123456789").build(),
                    RunOrigin.manual(), null);
            assertThat(aboutTheCodeList(result)).as(mode).singleElement().satisfies(f -> {
                assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
                assertThat(f.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which.");
            });
        }
    }

    // ---- preview and discovery ----

    @Test
    void aPreviewShowsTheRequestWithoutSendingOrRecordingIt() {
        RequestPreview preview = service().preview(request("S12345678   01").build(), null);
        assertThat(preview.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.url().configured()).isFalse();
        assertThat(preview.memberId().sent()).isEqualTo("S1234567801");
        assertThat(JsonSafe.parse(preview.body()).node().get("hook").asString()).isEqualTo("order-sign");
        assertThat(preview.notes()).isNotEmpty();
        assertThat(preview.blocked()).isNull();
        assertThat(ledger.records()).isEmpty();
        assertThatThrownBy(() -> service().preview(request(" ").build(), null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aPreviewCarriesARealHookInstanceAndSaysANewOneIsMadeWhenTheRequestIsSent() {
        // CRD201-22: the preview may be sent as the tester's own body, so its hookInstance must be a real id, not a sentence.
        RequestPreview preview = service().preview(request("S123456789").build(), null);
        String hookInstance = JsonSafe.parse(preview.body()).node().get("hookInstance").asString();
        assertThat(java.util.UUID.fromString(hookInstance).toString()).isEqualTo(hookInstance);
        assertThat(preview.notes()).contains("The hookInstance " + hookInstance + " was made for this preview. A new one is made each time "
                + "the request is sent.");
        // What CRD 2.0.1 requires and was not supplied is counted first, as on a run.
        assertThat(preview.notes().get(0)).startsWith("This request leaves out ").contains("that the CRD 2.0.1 profiles require");
    }

    @Test
    void aHookThisToolDoesNotBuildIsRefusedWithWhatItDoesBuild() {
        // CRD201-22: say plainly which hooks and order types are not built.
        assertThatThrownBy(() -> service().run(request("S123456789").hook("order-dispatch").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("hook");
                    assertThat(error.message()).isEqualTo("This tool builds order-sign and order-select requests only. CRD 2.0.1 also "
                            + "lists the hooks appointment-book, encounter-start, encounter-discharge and order-dispatch, and this tool "
                            + "builds none of them. Its orders are ServiceRequests only: it does not build a DeviceRequest, "
                            + "MedicationRequest, NutritionOrder, CommunicationRequest, Appointment or VisionPrescription.");
                }));
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aRunWhoseRequestLeftOutWhatCrd201RequiresCarriesOneFindingThatNamesIt() {
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NO-AUTH").build(), RunOrigin.manual(), null);
        assertThat(result.findings()).filteredOn(f -> f.kind() == FindingKind.REQUEST_INCOMPLETE).singleElement().satisfies(f -> {
            assertThat(f.title()).startsWith("The request was sent without ").endsWith(" CRD 2.0.1 requires");
            assertThat(f.detail()).contains("Patient.identifier:MRIdentifier (CRD 2.0.1 profile-patient), supplied by the variables "
                    + "patientMrn and patientMrnSystem");
        });
        assertThat(result.detail().requestNotes().get(0)).startsWith("This request leaves out ");
        // A body of the tester's own was not built by this tool, so nothing is said about what it left out.
        RunEnvelope<CrdDetail> own = service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\"}").build(), RunOrigin.manual(), null);
        assertThat(own.findings()).extracting(Finding::kind).doesNotContain(FindingKind.REQUEST_INCOMPLETE);
    }

    @Test
    void aPreviewOfABlockedWriteStillShowsWhereItWouldGoAndSaysItIsBlocked() {
        gateway.failing(blockedWrite());
        RequestPreview preview = service().preview(request("P32H-PA-REQ").build(), null);
        assertThat(preview.blocked()).isEqualTo("Writes to the production environment Onyx Production are blocked. "
                + "Unlock writes on the Config tab, or switch environment.");
        assertThat(preview.url().template()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.body()).isNull();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void discoveryListsTheServicesAndIsNotRecordedAsARun() {
        CrdDiscovery discovery = service().discover();
        assertThat(discovery.services()).hasSize(2);
        assertThat(discovery.services()).extracting(s -> s.hook().value()).containsExactly("order-sign", "order-select");
        assertThat(discovery.services().get(0).id().value()).startsWith("placeholder-");
        assertThat(discovery.error()).isNull();
        assertThat(discovery.wire().request().sent()).isFalse();
        // On mock the list is a placeholder, and the result says so where the tester will see it.
        assertThat(discovery.note()).contains("placeholder services").contains("Nothing left this machine");
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.CRD_DISCOVERY);
        assertThat(ledger.records()).isEmpty();

        String real = "{\"services\":[{\"hook\":\"order-sign\",\"id\":\"crd-order-sign\",\"title\":\"T\",\"description\":\"D\","
                + "\"prefetch\":{\"patient\":\"Patient/{{context.patientId}}\"},\"usageRequirements\":\"none\"}],\"vendorField\":1}";
        CrdDiscovery fromService = service(stub(TestExchanges.crd(200, real)), "mock", Map.of()).discover();
        assertThat(fromService.services().get(0).prefetch()).singleElement().satisfies(p -> {
            assertThat(p.key()).isEqualTo("patient");
            assertThat(p.template().value()).isEqualTo("Patient/{{context.patientId}}");
        });
        assertThat(fromService.additionalFields()).extracting(f -> f.path()).containsExactly("services[0].usageRequirements", "vendorField");
        assertThat(fromService.note()).isNull();
    }

    @Test
    void discoveryShowsTheMaskWhereTheServiceRepeatsASecretOfTheRequest() {
        String token = "placeholder-access-token-ABCDEF";
        String url = "https://example.org/p32h-placeholder/cds-services";
        String body = "{\"services\":[{\"hook\":\"order-sign\",\"id\":\"crd-" + token + "\",\"title\":\"T\",\"description\":\"D\","
                + "\"prefetch\":{\"patient\":\"Patient/{{context.patientId}}\"},\"x-" + token + "\":\"echo " + token + "\"}]}";
        VendorExchange echoed = Exchanges.fromResponse(new WireRequest("GET", url, url, List.of(), null, true, null,
                SentSecrets.builder().accessToken(token).build()), 200, List.of(), body, 5L, true);
        CrdDiscovery discovery = service(stub(echoed), "mock", Map.of()).discover();

        assertThat(discovery.services()).singleElement().satisfies(service ->
                assertThat(service.id().value()).isEqualTo("crd-" + Secrets.mask("access token")));
        assertThat(JsonSafe.mapper().writeValueAsString(discovery.services())).doesNotContain(token);
        assertThat(JsonSafe.mapper().writeValueAsString(discovery.additionalFields())).doesNotContain(token);
        assertThat(discovery.additionalFields()).anySatisfy(field -> assertThat(field.path()).contains("x-" + Secrets.mask("access token")));
    }

    private static OnyxCrdClient stub(VendorExchange exchange) {
        return new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return exchange;
            }
        };
    }
}
