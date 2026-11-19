package com.thehiddenbrain.interop.epa.pave.client.crd;

import com.thehiddenbrain.interop.epa.pave.client.MockSelection;
import com.thehiddenbrain.interop.epa.pave.client.support.ExchangeOptions;
import com.thehiddenbrain.interop.epa.pave.client.support.Exchanges;
import com.thehiddenbrain.interop.epa.pave.client.support.MockExchanges;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.model.WireHeader;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;
import com.thehiddenbrain.interop.epa.pave.service.scenario.ScenarioRepository;
import com.thehiddenbrain.interop.epa.pave.service.scenario.ScenarioSelection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The CRD client on the mock profile. It answers from the scenario files under
 * {@code scenarios/crd} and needs no configuration, no credentials and no network.
 *
 * <p>Every scenario is synthetic. None was captured from the live service, and the files that
 * model behaviour the published profile does not define say what they assume.
 */
@Component
@Profile("mock")
public class MockOnyxCrdClient implements OnyxCrdClient {

    /** The directory under {@code scenarios/} that holds the CRD scenario files. */
    public static final String SCENARIOS = "crd";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxCrdClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
        ScenarioSelection chosen = scenarios.select(SCENARIOS, selection);
        VendorExchange exchange = exchanges.answer(call, body, HttpOnyxCrdClient.HOOK_OPTIONS, chosen, null);
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    /**
     * Discovery on mock lists one placeholder service for each hook, so the Config and CRD tabs
     * have something to show. The ids are not the vendor's: those are not known.
     */
    @Override
    public VendorExchange discover(PreparedCall call) {
        String body = """
                {
                  "services": [
                    {
                      "hook": "order-sign",
                      "id": "placeholder-order-sign-service",
                      "title": "Mock coverage requirements discovery, order-sign",
                      "description": "Placeholder entry from the mock profile. The service ids of the live service are not known."
                    },
                    {
                      "hook": "order-select",
                      "id": "placeholder-order-select-service",
                      "title": "Mock coverage requirements discovery, order-select",
                      "description": "Placeholder entry from the mock profile. The service ids of the live service are not known."
                    }
                  ]
                }""";
        ExchangeOptions options = HttpOnyxCrdClient.DISCOVERY_OPTIONS;
        return Exchanges.fromResponse(
                Exchanges.wireRequest(call, null, options, false, "Mock profile. Discovery is answered with placeholder services. Nothing left this machine."),
                200, List.of(new WireHeader("Content-Type", "application/json")), body, 0L, true);
    }
}
