package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * G2-3 of the last check of the guardrail: on mock, the package a scenario plays malformed
 * (P32H-DTR-MALFORMED) is headed and actioned as every other fault a scenario plays, since nothing
 * left the machine. A package that cannot be used from a live service keeps its own wording.
 */
class DtrPlayedMalformedPackageTest {

    private ErrorView malformed(String mode) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", mode)), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
        DtrService service = new DtrService(support.epa(), FakeGateway.unconfigured(),
                new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP)), new FakeCodeList(), support);
        return service.runPackage(DtrFixtures.packageRequest("P32H-DTR-MALFORMED").build(), RunOrigin.manual(), null).error();
    }

    @Test
    void onMockTheMalformedPackageIsHeadedAsPlayedAndReportsNothing() {
        ErrorView played = malformed("mock");
        assertThat(played.kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(played.heading()).isEqualTo(RunSupport.PLAYED_UNREADABLE_HEADING)
                .isEqualTo("An unreadable answer played by a mock scenario, not a fault in the environment");
        assertThat(played.title()).isEqualTo("The service answered with HTTP 200, but what it sent cannot be used as a questionnaire.");
        assertThat(played.action()).isEqualTo("Nothing left this machine: a mock scenario played this answer to show how this tool reads "
                + "it. The problems are listed with this result and the body is shown in full. From a real service it would be a defect "
                + "in the service to report.");
    }

    @Test
    void fromALiveServiceTheWordingIsUnchanged() {
        ErrorView live = malformed("live");
        assertThat(live.kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(live.heading()).doesNotContain("played");
        assertThat(live.action()).isEqualTo("The problems are listed with this result and the body is shown in full. Report it as a defect "
                + "in the service.");
    }
}
