package org.p32h.interop.epa.pave.service.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A case run from its inputs alone, as a suite row and a replay run it. The real CRD service, mock
 * client and scenario files are behind the runner, with fakes for the gateway, the ledger, the
 * code list and the two lookups.
 */
class ProtocolCaseRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SURNAME = "Placeholder-Surname";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private LookupStatus memberStatus = LookupStatus.FOUND;
    private LookupStatus providerStatus = LookupStatus.FOUND;

    private final MemberLookup members = id -> new MemberLookupResult(memberStatus, MemberIds.forms(id),
            memberStatus == LookupStatus.FOUND ? MemberDetail.builder().memberId(id).lastName(SURNAME).dateOfBirth("1970-01-31")
                    .lineOfBusiness("Medicare").build() : null, List.of(), List.of(), List.of(), null, null, "message");
    private final ProviderLookup providers = npi -> {
        if (!org.p32h.interop.epa.pave.model.Npi.isValid(npi)) {
            throw ValidationException.of("npi", "This is not a valid NPI: its check digit does not match.");
        }
        return new ProviderLookupResult(providerStatus, npi, providerStatus == LookupStatus.FOUND
                ? ProviderDetail.builder().npi(npi).lastName("Provider").build() : null, List.of(), List.of(), List.of(), null, null, "message");
    };

    private ProtocolCaseRunner runner() {
        return runner(members);
    }

    private ProtocolCaseRunner runner(MemberLookup members) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        CrdService crd = new CrdService(epa, gateway, new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                new FakeCodeList(), support);
        return new ProtocolCaseRunner(members, providers)
                .register(EpaOperation.CRD_ORDER_SIGN, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SIGN))
                .register(EpaOperation.CRD_ORDER_SELECT, new CrdCaseStep(crd, EpaOperation.CRD_ORDER_SELECT));
    }

    private static CaseSpec.Builder crdCase(String memberId) {
        return CaseSpec.builder().caseId("case-1").operation(EpaOperation.CRD_ORDER_SIGN).memberId(memberId).npi("1234567893")
                .procedureCodes(List.of("95810")).dateOfService("2026-03-20").suiteRunId("suite-1");
    }

    @Test
    void aCaseTakesThePathAManualRunTakesAndWritesTheSameKindOfLedgerLine() {
        CaseOutcome outcome = runner().run(crdCase("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").testerRef("QA-17").build());

        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.notRecorded()).isEmpty();
        assertThat(outcome.notes()).isEmpty();
        RunRecord record = outcome.finalRun();
        assertThat(record).isEqualTo(ledger.only());
        assertThat(record.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.suiteRunId()).isEqualTo("suite-1");
        assertThat(record.caseId()).isEqualTo("case-1");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(record.hook()).isEqualTo("order-sign");
        // The member was resolved from the source again, so the line needs nothing a re-run could not rebuild.
        assertThat(record.unrecordedInputs()).isEmpty();
        // A case never carries a production unlock.
        assertThat(gateway.lastIntent().unlock()).isNull();
        // The member's name was looked up for the request and is nowhere in what the case returns.
        assertThat(JsonSafe.mapper().writeValueAsString(outcome)).doesNotContain(SURNAME).doesNotContain("1970-01-31");
    }

    /**
     * A member whose surname holds a word that is also an outcome code: the expectation that is
     * exactly that code is the tool's own word and is never refused as the name. Every other text
     * is checked as before. Where the lookup sends nothing (mock), the check before sending makes
     * it too, in the run's own words, so a preview counts what the run refuses.
     */
    @Test
    void anExpectationThatIsExactlyAnOutcomeCodeIsNotANameAndTheCheckBeforeSendingRefusesWhatTheRunRefuses() {
        MemberLookup inactive = id -> new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(id), MemberDetail.builder().memberId(id)
                .firstName("Gray").lastName("Placeholder-Inactive").lineOfBusiness("Medicare").build(), List.of(), List.of(), List.of(),
                null, null, "message");
        ProtocolCaseRunner onMock = runner(inactive).previewMembers(inactive);
        CaseSpec right = crdCase("P32H-INACTIVE").procedureCodes(List.of("T1030")).expected("COVERAGE_INACTIVE").build();
        assertThat(onMock.refusals(right)).isEmpty();
        CaseOutcome ran = onMock.run(right);
        assertThat(ran.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(ran.finalRun().outcome()).isEqualTo("COVERAGE_INACTIVE");
        assertThat(ran.finalRun().result()).isEqualTo(TestResult.PASS);
        // Written in lower case, which the comparison reads as the same code: run, compared and passed, not refused as a name.
        CaseSpec lower = crdCase("P32H-INACTIVE").procedureCodes(List.of("T1030")).expected("coverage_inactive").build();
        assertThat(onMock.refusals(lower)).isEmpty();
        CaseOutcome ranLower = onMock.run(lower);
        assertThat(ranLower.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(ranLower.finalRun().result()).isEqualTo(TestResult.PASS);

        for (CaseSpec named : List.of(crdCase("P32H-INACTIVE").expected("COVERAGE_INACTIVE").note("asked Inactive about it").build(),
                crdCase("P32H-INACTIVE").expected("COVERAGE_INACTIVE").testerRef("Gray Inactive").build())) {
            List<String> before = onMock.refusals(named);
            assertThat(before).hasSize(1);
            assertThat(before.get(0)).contains("the member's last name").endsWith("Nothing was sent.");
            CaseOutcome refused = onMock.run(named);
            assertThat(refused.status()).isEqualTo(CaseStatus.INVALID);
            assertThat(refused.reason()).contains(before.get(0));
            // Where a lookup would leave this machine, nothing is looked up before sending, and the run still refuses it.
            assertThat(runner(inactive).refusals(named)).isEmpty();
            assertThat(runner(inactive).run(named).status()).isEqualTo(CaseStatus.INVALID);
        }
    }

    /**
     * Every line of a case keeps its case id, so a case id that holds the looked-up member's name is
     * refused before anything is sent, in the preview (mock) and in the run (both profiles) with the
     * same words, and no line is written. A case id that is exactly one of the tool's bundled case
     * ids is its own word and is never refused, though the mock surname is made from the same word.
     */
    @Test
    void aCaseIdThatHoldsTheMembersNameIsRefusedBeforeSendingAndABundledCaseIdIsNot() {
        MemberLookup inactive = id -> new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(id), MemberDetail.builder().memberId(id)
                .firstName("Gray").lastName("Placeholder-Inactive").lineOfBusiness("Medicare").build(), List.of(), List.of(), List.of(),
                null, null, "message");
        java.util.Set<String> bundled = ScenarioRepository.bundledIds();
        assertThat(bundled).contains("crd-08-coverage-inactive", "crd-13-timeout", "pas-02-pended");
        ProtocolCaseRunner onMock = runner(inactive).previewMembers(inactive).ownCaseIds(bundled);
        ProtocolCaseRunner onLive = runner(inactive).ownCaseIds(bundled);

        CaseSpec named = crdCase("P32H-INACTIVE").caseId("row-Placeholder-Inactive").procedureCodes(List.of("T1030"))
                .expected("COVERAGE_INACTIVE").build();
        String words = "caseId: The case id holds the member's last name. The ledger keeps it as written and may not hold member data, "
                + "so leave it out. Nothing was sent.";
        assertThat(onMock.refusals(named)).containsExactly(words);
        for (ProtocolCaseRunner runner : List.of(onMock, onLive)) {
            CaseOutcome refused = runner.run(named);
            assertThat(refused.status()).isEqualTo(CaseStatus.INVALID);
            assertThat(refused.reason()).isEqualTo(words);
            assertThat(refused.runs()).isEmpty();
        }
        assertThat(ledger.records()).isEmpty();
        // The first name, and the member ID, are refused the same way.
        assertThat(onLive.run(crdCase("P32H-INACTIVE").caseId("asked-gray").build()).reason()).contains("the member's first name");
        assertThat(onLive.run(crdCase("P32H-INACTIVE").caseId("x-P32H-INACTIVE").build()).reason()).contains("this run's member ID");

        CaseSpec own = crdCase("P32H-INACTIVE").caseId("crd-08-coverage-inactive").procedureCodes(List.of("T1030"))
                .expected("COVERAGE_INACTIVE").build();
        assertThat(onMock.refusals(own)).isEmpty();
        CaseOutcome ran = onLive.run(own);
        assertThat(ran.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(ran.finalRun().caseId()).isEqualTo("crd-08-coverage-inactive");
        // Only the exact id is the tool's own: written another way it is the tester's own text, and is held to the check.
        assertThat(onLive.run(crdCase("P32H-INACTIVE").caseId("CRD-08-COVERAGE-INACTIVE").build()).status()).isEqualTo(CaseStatus.INVALID);
    }

    @Test
    void theHookOfTheCaseIsTheHookThatIsSent() {
        runner().run(crdCase("P32H-NO-AUTH").operation(EpaOperation.CRD_ORDER_SELECT).build());
        assertThat(ledger.only().operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(ledger.only().hook()).isEqualTo("order-select");
    }

    @Test
    void anUnexpectedAnswerIsACompletedCaseWithAFailedTest() {
        CaseOutcome outcome = runner().run(crdCase("P32H-EMPTY").expected("NO_AUTH_REQUIRED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.finalRun().outcome()).isEqualTo("NO_DETERMINATION");
        assertThat(outcome.finalRun().result()).isEqualTo(TestResult.FAIL);
        // And with nothing declared the case is unverified, never passed.
        assertThat(runner().run(crdCase("P32H-NO-AUTH").build()).finalRun().result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aMemberTheLookupCannotGiveIsSentWithTheIdAloneAndTheResultSaysSo() {
        // Nothing is made up to fill the gap, and the gap is stated, for each way the lookup can come back empty.
        Map<LookupStatus, String> expected = Map.of(
                LookupStatus.NOT_FOUND, "The member lookup does not know this member ID. The request was sent with the member ID alone.",
                LookupStatus.DISABLED, "The member lookup is not configured. The request was sent with the member ID alone.",
                LookupStatus.FAULT, "The member lookup failed. The request was sent with the member ID alone.");
        expected.forEach((status, note) -> {
            memberStatus = status;
            CaseOutcome outcome = runner().run(crdCase("P32H-NOMEM").build());
            assertThat(outcome.status()).as(status.name()).isEqualTo(CaseStatus.COMPLETED);
            assertThat(outcome.notes()).as(status.name()).containsExactly(note);
            assertThat(outcome.finalRun().outcome()).isEqualTo("MEMBER_NOT_FOUND");
            assertThat(outcome.finalRun().lineOfBusiness()).isNull();
        });
        memberStatus = LookupStatus.FOUND;
        providerStatus = LookupStatus.NOT_FOUND;
        assertThat(runner().run(crdCase("P32H-NO-AUTH").build()).notes())
                .containsExactly("The provider lookup does not know this NPI. The request was sent with the NPI alone.");
    }

    @Test
    void aWriteTheGuardrailRefusesIsReportedAsBlockedAndNeverAsAFailure() {
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment."));
        CaseOutcome outcome = runner().run(crdCase("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(outcome.reason()).contains("Nothing was sent");
        // The attempt has its ledger line, and its test result is unverified, not failed.
        assertThat(outcome.finalRun().outcome()).isEqualTo("BLOCKED");
        assertThat(outcome.finalRun().result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aConfigurationProblemIsNotConfiguredAndTheRecordIsNotBlamed() {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        CaseOutcome noUrl = runner().run(crdCase("P32H-NO-AUTH").build());
        assertThat(noUrl.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(noUrl.reason()).isEqualTo("There is no url for CRD order-sign.");
        assertThat(noUrl.runs()).isEmpty();

        gateway.failing(new UnresolvedVariablesException(EpaOperation.CRD_ORDER_SIGN, List.of("baseUrl"), "Onyx Sandbox", "{{baseUrl}}/x"));
        CaseOutcome unresolved = runner().run(crdCase("P32H-NO-AUTH").build());
        assertThat(unresolved.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(unresolved.reason()).contains("baseUrl");

        // A suite pins the configuration it started under. If it changes, the case does not run somewhere else.
        gateway.failing(null).changeConfiguration();
        CaseOutcome moved = runner().run(crdCase("P32H-NO-AUTH").configEpoch(0L).build());
        assertThat(moved.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(moved.reason()).isEqualTo("The configuration was changed after the suite started, and a case is held to the "
                + "configuration its suite started under, so this one was not sent.");
        assertThat(ledger.records()).isEmpty();
        assertThat(runner().run(crdCase("P32H-NO-AUTH").configEpoch(1L).build()).status()).isEqualTo(CaseStatus.COMPLETED);
    }

    @Test
    void inputsThatAreNotValidAreInvalidWithTheFieldNamedAndNothingIsSent() {
        CaseOutcome badNpi = runner().run(crdCase("P32H-NO-AUTH").npi("1234567890").build());
        assertThat(badNpi.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(badNpi.reason()).contains("npi").contains("check digit");

        CaseOutcome noMember = runner().run(crdCase(" ").build());
        assertThat(noMember.status()).isEqualTo(CaseStatus.INVALID);
        // Named by the column of a suite file, which is where the tester has to look, not by a field of a form.
        assertThat(noMember.reason()).isEqualTo("memberId: Enter a member ID.");

        CaseOutcome badExpectation = runner().run(crdCase("P32H-NO-AUTH").expected("APPROVED").build());
        assertThat(badExpectation.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(badExpectation.reason()).contains("expected");

        // A key this tool does not know. Dropped in silence, "expectd" would make the case unverified.
        CaseSpec misspelt = JsonSafe.mapper().readValue("{\"caseId\":\"c\",\"operation\":\"CRD_ORDER_SIGN\",\"memberId\":\"P32H-NO-AUTH\","
                + "\"npi\":\"1234567893\",\"procedureCodes\":[\"95810\"],\"expectd\":\"NO_AUTH_REQUIRED\"}", CaseSpec.class);
        CaseOutcome unknownKey = runner().run(misspelt);
        assertThat(unknownKey.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(unknownKey.reason()).contains("expectd");

        assertThat(runner().run(CaseSpec.builder().caseId("c").memberId("P32H-NO-AUTH").build()).status()).isEqualTo(CaseStatus.INVALID);
        assertThat(runner().run(null).status()).isEqualTo(CaseStatus.INVALID);
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void whatCannotBeRunFromACaseIsNotRunnableWithTheReason() {
        // Something that arrived, or a step completed inside the tool: there is nothing to send again.
        for (EpaOperation inbound : new EpaOperation[] {EpaOperation.SUBSCRIPTION_NOTIFICATION, EpaOperation.DTR_RESPONSE}) {
            CaseOutcome outcome = runner().run(crdCase("P32H-NO-AUTH").operation(inbound).build());
            assertThat(outcome.status()).as(inbound.name()).isEqualTo(CaseStatus.NOT_RUNNABLE);
            assertThat(outcome.reason()).contains("nothing to send again");
        }
        // An operation nobody registered, and a chain before the chain exists.
        assertThat(runner().run(crdCase("P32H-NO-AUTH").operation(EpaOperation.PAS_SUBMIT).build()).status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        assertThat(runner().run(crdCase("P32H-NO-AUTH").chain(true).build()).status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aRunTheLedgerDidNotKeepIsStillInTheOutcomeWithTheLedgersMessage() {
        ledger.refusing("This run was NOT recorded: the ledger file could not be written.");
        CaseOutcome outcome = runner().run(crdCase("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        // A suite row must not go blank because a write failed.
        assertThat(outcome.finalRun().outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(outcome.finalRun().result()).isEqualTo(TestResult.PASS);
        assertThat(outcome.notRecorded()).containsExactly("This run was NOT recorded: the ledger file could not be written.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theRunnerNeverThrowsAndADefectIsLoggedWithoutTheCase(CapturedOutput output) {
        ProtocolCaseRunner broken = new ProtocolCaseRunner(id -> {
            throw new IllegalStateException("lookup exploded for " + SURNAME + " S123456789");
        }, providers).register(EpaOperation.CRD_ORDER_SIGN, context -> null);
        CaseOutcome outcome = broken.run(crdCase("S123456789").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        // A failure of the tool is told apart from a case that cannot be run, and never says nothing was sent.
        assertThat(outcome.toolFailed()).isTrue();
        assertThat(outcome.reason()).startsWith("This tool failed while running CRD order-sign. Whether a request left for it is not known")
                .endsWith("Reference " + outcome.faultReference() + ".").doesNotContain("Nothing was sent");
        assertThat(outcome.reason()).doesNotContain(SURNAME).doesNotContain("S123456789");
        // The first line of the log entry, so the screen can name the cause with the reference.
        assertThat(outcome.faultCause()).isEqualTo("java.lang.IllegalStateException");
        assertThat(output.getAll()).contains("reference " + outcome.faultReference() + ": java.lang.IllegalStateException");
        assertThat(output.getAll()).contains("A case failed inside this tool").doesNotContain(SURNAME).doesNotContain("S123456789");
    }

    @Test
    void mockScenariosThatCannotBeUsedAreSaidSoAndNothingAFileHoldsIsNamed() {
        ProtocolCaseRunner runner = new ProtocolCaseRunner(members, providers).register(EpaOperation.CRD_ORDER_SIGN, context -> {
            throw new org.p32h.interop.epa.pave.service.scenario.ScenarioSetUnusableException("crd",
                    List.of("crd-99-edited.json: the member " + SURNAME + " S123456789 is not a sentinel."));
        });
        CaseOutcome outcome = runner.run(crdCase("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").build());

        // The row is not at fault, and the tool did not fail: the scenario files did.
        assertThat(outcome.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(outcome.toolFailed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("The mock scenarios for crd cannot be used, so the mock did not answer and nothing was "
                + "sent. The case itself is not at fault. The scenario list for crd names each file and its problem; put them right, "
                + "then run the case again.");
        assertThat(JsonSafe.mapper().writeValueAsString(outcome)).doesNotContain("crd-99-edited").doesNotContain(SURNAME)
                .doesNotContain("S123456789");
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aCaseThatWasNotSentNeverSaysItWasSentWithTheIdAlone() {
        memberStatus = LookupStatus.NOT_FOUND;
        providerStatus = LookupStatus.DISABLED;
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        CaseOutcome notConfigured = runner().run(crdCase("S000000001").build());
        assertThat(notConfigured.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(notConfigured.notes()).containsExactly("The member lookup does not know this member ID.",
                "The provider lookup is not configured.");
        gateway.failing(null);
        CaseOutcome invalid = runner().run(crdCase("S000000001").procedureCodes(List.of()).build());
        assertThat(invalid.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(invalid.notes()).noneMatch(note -> note.contains("was sent"));
        // Sent, the same lookups say how it went out, and the default mock scenario that answered is named after them.
        List<String> sent = runner().run(crdCase("S000000001").build()).notes();
        assertThat(sent).hasSize(3);
        assertThat(sent.subList(0, 2)).containsExactly(
                "The member lookup does not know this member ID. The request was sent with the member ID alone.",
                "The provider lookup is not configured. The request was sent with the NPI alone.");
        // The CRD mock goes on to say how it builds its answer, which is its own sentence to word.
        assertThat(sent.get(2)).startsWith(
                "Mock scenario crd-01-pa-required answered: the default, because no scenario was chosen and no test member ID or NPI matched.");
    }
}
