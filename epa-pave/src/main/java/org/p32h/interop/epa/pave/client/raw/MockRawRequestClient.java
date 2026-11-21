package org.p32h.interop.epa.pave.client.raw;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Sending a request raw on the mock profile. Nothing is sent. The mock does not know what an
 * unmapped request means, so it answers every one with the same fixed body, which says so.
 */
@Component
@Profile("mock")
public class MockRawRequestClient implements RawRequestClient {

    /** The directory under {@code scenarios/} that holds the one raw scenario. */
    public static final String SCENARIOS = "raw";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockRawRequestClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange send(PreparedCall call, ResolvedText body) {
        return exchanges.answer(call, body, HttpRawRequestClient.OPTIONS, scenarios.select(SCENARIOS, MockSelection.of(null, null, null)), null);
    }
}
