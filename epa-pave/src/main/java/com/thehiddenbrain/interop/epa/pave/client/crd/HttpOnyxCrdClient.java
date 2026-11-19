package com.thehiddenbrain.interop.epa.pave.client.crd;

import com.thehiddenbrain.interop.epa.pave.client.MockSelection;
import com.thehiddenbrain.interop.epa.pave.client.support.ExchangeOptions;
import com.thehiddenbrain.interop.epa.pave.client.support.OnyxAccess;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The CRD client on the live profile: one CDS Hooks call over HTTP.
 *
 * <p>The mock selection is ignored entirely. Letting a tester force an outcome against the real
 * service would produce a result that did not come from the service.
 *
 * <p>Vendor-specific reading of the response is not done here but in {@link OnyxCrdConventions},
 * which the mock client applies too, so the two profiles cannot disagree about it.
 */
@Component
@Profile("live")
public class HttpOnyxCrdClient implements OnyxCrdClient {

    /** CDS Hooks is plain JSON, not FHIR JSON. */
    static final ExchangeOptions HOOK_OPTIONS = ExchangeOptions.json("application/json");
    static final ExchangeOptions DISCOVERY_OPTIONS = ExchangeOptions.read("application/json");

    private final OnyxAccess onyx;

    public HttpOnyxCrdClient(OnyxAccess onyx) {
        this.onyx = onyx;
    }

    @Override
    public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
        VendorExchange exchange = onyx.send(call, body, HOOK_OPTIONS);
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    @Override
    public VendorExchange discover(PreparedCall call) {
        return onyx.send(call, null, DISCOVERY_OPTIONS);
    }
}
