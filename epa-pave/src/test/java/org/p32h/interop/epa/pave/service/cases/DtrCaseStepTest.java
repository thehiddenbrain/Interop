package org.p32h.interop.epa.pave.service.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The DTR package fetch run as a case, through the real DTR service and the mock client. */
class DtrCaseStepTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final EpaProperties EPA = EpaProperties.of(Map.of("mode", "mock"));

    private final InMemoryLedger ledger = new InMemoryLedger();

    private ProtocolCaseRunner runner() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EPA, TrackingProperties.defaults(), OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class),
                CLOCK);
        DtrService dtr = new DtrService(EPA, FakeGateway.unconfigured(), new MockOnyxDtrClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                new FakeCodeList(), support);
        return new ProtocolCaseRunner(
                id -> new MemberLookupResult(LookupStatus.NOT_FOUND, MemberIds.forms(id), null, List.of(), List.of(), List.of(), null, null, "m"),
                npi -> new ProviderLookupResult(LookupStatus.FOUND, npi, ProviderDetail.builder().npi(npi).lastName("Provider").build(),
                        List.of(), List.of(), List.of(), null, null, "m"))
                .register(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, new DtrCaseStep(dtr));
    }

    private static CaseSpec.Builder packageCase() {
        return CaseSpec.builder().caseId("d1").operation(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE).memberId("P32H-DOC-DTR").npi("1234567893")
                .procedureCodes(List.of("95810")).dateOfService("2026-03-20");
    }

    @Test
    void aReplayedFetchAsksForTheQuestionnairesItsLineNames() {
        // The service checks each questionnaire it is asked for before sending. A name that is not a url is refused, which is only
        // possible if the questionnaires of the line reached it.
        CaseOutcome named = runner().run(packageCase().correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE,
                "https://example.org/Questionnaire/sleep|1.0 not-a-url")).build());
        assertThat(named.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(named.reason()).contains("questionnaires[1]").doesNotContain("questionnaires[0]");
        assertThat(ledger.records()).isEmpty();
        // A row that names none sends the package request for the order alone.
        CaseOutcome none = runner().run(packageCase().build());
        assertThat(none.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(none.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
    }

    @Test
    void aStatementInPlaceOfAnAddressIsNeverCutIntoAddressesAndTheCaseIsNotRun() {
        // Without the check the statement's words would be sent as questionnaires, and "(not" would be refused as one of them.
        for (String statement : List.of("(not a url, 12 characters)", "(not recorded, 40 characters)")) {
            CaseOutcome outcome = runner().run(packageCase().correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE,
                    "https://example.org/Questionnaire/sleep|1.0 " + statement)).build());
            assertThat(outcome.status()).as(statement).isEqualTo(CaseStatus.NOT_RUNNABLE);
            assertThat(outcome.reason()).as(statement).isEqualTo("The questionnaires this case names hold a statement that something "
                    + "was there in place of an address, because what was there did not have the shape of one. Sent without it, the "
                    + "request would not be the same one.");
        }
        assertThat(ledger.records()).isEmpty();
        assertThat(DtrCaseStep.addresses("https://example.org/Q/a|1 (not a url, 12 characters)  https://example.org/Q/b"))
                .containsExactly("https://example.org/Q/a|1", "https://example.org/Q/b");
    }
}
