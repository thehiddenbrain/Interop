package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns a scenario step into the same {@link VendorExchange} a live call would have produced, for
 * every mock client.
 *
 * <p>The request is described exactly as the live path would send it, and the response is
 * classified by the same rules, so what a tester learns on mock about a status and a body holds on
 * live. The only differences are stated on the request: nothing was sent, and which scenario
 * answered and why.
 */
@Component
public class MockExchanges {

    private final AppProperties app;
    private final ObjectProvider<OperationGateway> gateway;

    /** For tests that exercise a mock client alone. No configuration epoch is checked. */
    public MockExchanges(AppProperties app) {
        this(app, null);
    }

    @Autowired
    public MockExchanges(AppProperties app, ObjectProvider<OperationGateway> gateway) {
        this.app = app;
        this.gateway = gateway;
    }

    /**
     * Answers a call from one step of the selected scenario.
     *
     * @param stepName the named step to use, or null for the scenario's main step
     */
    public VendorExchange answer(PreparedCall call, ResolvedText body, ExchangeOptions options, ScenarioSelection selection,
                                 String stepName) {
        // The same refusals the live path applies, so they can be seen and tested without a network.
        Exchanges.requireSendable(call, body, gateway == null ? null : gateway.getIfAvailable());
        ScenarioStep step = selection.scenario().step(stepName);
        if (step == null) {
            // A tester's choice of scenario does not fit the call they made. That is theirs to
            // correct, so it is a validation error that names both, not an internal failure.
            throw ValidationException.of("scenarioId", "Scenario " + selection.scenario().id() + " has no answer for "
                    + (stepName == null ? "this call" : "the step \"" + stepName + "\"") + ". Choose a scenario that covers it.");
        }
        String note = "Mock profile. Answered from scenario " + selection.scenario().id()
                + (stepName == null ? "" : ", step \"" + stepName + "\"") + ", " + selection.how() + ". Nothing left this machine.";
        WireRequest request = Exchanges.wireRequest(call, body, options, false, note);

        long started = System.nanoTime();
        pause(step.delayMillis());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;

        VendorExchange exchange = switch (step.behavior()) {
            case RESPOND -> Exchanges.fromResponse(request, step.httpStatus(),
                    step.body() == null ? List.of() : List.of(new WireHeader("Content-Type", "application/json")), step.body(),
                    elapsed, options != null && options.bodyRequired());
            // The mock reports a timeout after the scenario's own delay. It does not hold the tester
            // for the full configured read timeout to make the point.
            case TIMEOUT -> Exchanges.timeout(request, elapsed);
            case UNAVAILABLE -> Exchanges.unavailable(request, elapsed, "The connection was refused or could not be opened.");
        };
        return exchange.withScenario(selection.scenario().id(), selection.how());
    }

    private void pause(long millis) {
        if (!app.mockLatencyEnabled() || millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
