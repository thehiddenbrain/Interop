package org.p32h.interop.epa.pave.client.dtr;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.OnyxAccess;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The DTR client on the live profile: the two FHIR operations over HTTP.
 *
 * <p>The mock selection is ignored entirely. Letting a tester force an outcome against the real
 * service would produce a result that did not come from the service.
 *
 * <p>Vendor-specific reading of the response is not done here but in {@link OnyxDtrConventions},
 * which the mock client applies too, so the two profiles cannot disagree about it.
 */
@Component
@Profile("live")
public class HttpOnyxDtrClient implements OnyxDtrClient {

    /** FHIR JSON in and FHIR JSON out. A success with no body is not an answer for either operation. */
    static final ExchangeOptions OPTIONS = ExchangeOptions.json(OnyxDtrConventions.FHIR_JSON);

    private final OnyxAccess onyx;

    public HttpOnyxDtrClient(OnyxAccess onyx) {
        this.onyx = onyx;
    }

    @Override
    public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
        VendorExchange exchange = onyx.send(call, body, OPTIONS);
        return exchange.withSignals(OnyxDtrConventions.signals(exchange));
    }

    @Override
    public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
        VendorExchange exchange = onyx.send(call, body, OPTIONS);
        return exchange.withSignals(OnyxDtrConventions.signals(exchange));
    }
}
