package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * F-MOCK-500-HEADING of the honesty audit: on the mock profile nothing leaves this machine, so a
 * fault of the service there is one a scenario played. It is headed as played, and its action
 * never tells the tester to report a fault in an environment. On live nothing changes.
 */
class PlayedFaultHeadingTest {

    private static RunSupport support(String mode) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        return new RunSupport(EpaProperties.of(Map.of("mode", mode)), TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC));
    }

    private static final String OUTCOME = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"exception\"}]}";

    /** Every fault the tool reads from a service, played by a scenario, with the heading it must carry on mock. */
    private static final List<Object[]> FAULTS = List.of(
            new Object[] {TestExchanges.crd(500, OUTCOME).withScenario("crd-12-server-error-500", null), RunSupport.PLAYED_FAILURE_HEADING},
            new Object[] {TestExchanges.crd(400, OUTCOME).withScenario("crd-11-bad-request-400", null), RunSupport.PLAYED_REJECTION_HEADING},
            new Object[] {TestExchanges.crd(401, OUTCOME).withScenario("x", null), RunSupport.PLAYED_REJECTION_HEADING},
            new Object[] {TestExchanges.unavailable(EpaOperation.PAS_SUBMIT).withScenario("pas-13-transport-failure", null),
                    RunSupport.PLAYED_UNREACHABLE_HEADING},
            new Object[] {TestExchanges.of(EpaOperation.PAS_SUBMIT, 200, "this is not json", true).withScenario("x", null),
                    RunSupport.PLAYED_UNREADABLE_HEADING},
            new Object[] {TestExchanges.of(EpaOperation.PAS_SUBMIT, 200, null, true).withScenario("x", null), RunSupport.PLAYED_UNREADABLE_HEADING});

    @Test
    void onMockEveryPlayedFaultIsHeadedAsPlayedAndReportsNoEnvironment() {
        RunSupport mock = support(EpaProperties.MOCK);
        for (Object[] fault : FAULTS) {
            VendorExchange exchange = (VendorExchange) fault[0];
            ErrorView view = mock.faultView(exchange, List.of());
            assertThat(view.heading()).as(exchange.failureType().name()).isEqualTo(fault[1]).doesNotStartWith("A fault in the environment");
            assertThat(view.action()).as(exchange.failureType().name())
                    .startsWith("Nothing left this machine: a mock scenario played this answer to show how this tool reads it. ")
                    .doesNotContain("This is a fault in the environment").doesNotContain("Note the time and the run id and report it");
            // The kind, and so the colour of the panel, stays what the fault is.
            assertThat(view.kind()).isEqualTo(support(EpaProperties.LIVE).faultView(exchange, List.of()).kind());
        }
        assertThat(mock.faultView(TestExchanges.crd(500, OUTCOME), List.of()).action()).isEqualTo("Nothing left this machine: a mock "
                + "scenario played this answer to show how this tool reads it. From a real service it would be a fault in the environment, "
                + "to report with the time and the run id.");
    }

    @Test
    void onLiveNothingChanges() {
        RunSupport live = support(EpaProperties.LIVE);
        ErrorView failed = live.faultView(TestExchanges.crd(500, OUTCOME), List.of());
        assertThat(failed.kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(failed.heading()).isEqualTo("A fault in the environment: the service failed");
        assertThat(failed.action()).isEqualTo("This is a fault in the environment, not in the request. Note the time and the run id and report it.");
        assertThat(live.faultView(TestExchanges.unavailable(EpaOperation.PAS_SUBMIT), List.of()).heading())
                .isEqualTo("A fault in the environment: the service could not be reached");
        assertThat(live.faultView(TestExchanges.of(EpaOperation.PAS_SUBMIT, 200, "this is not json", true), List.of()).heading())
                .isEqualTo("A fault in the environment: the answer could not be read");
        assertThat(live.faultView(TestExchanges.crd(400, OUTCOME), List.of()).heading()).isEqualTo("The service rejected the request");
    }
}
