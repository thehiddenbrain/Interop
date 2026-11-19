package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.OnyxAccess;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The PAS client on the live profile: one FHIR operation over HTTP for a submission, one for an
 * inquiry.
 *
 * <p>The mock selection is ignored entirely. Letting a tester force an outcome against the real
 * service would produce a result that did not come from the service.
 *
 * <p>Whether a submission may be sent at all is not decided here. The only way to obtain the
 * prepared call this client needs is through the gateway, which has already applied the production
 * guardrail to it. Vendor-specific reading of the response is in {@link OnyxPasConventions}, which
 * the mock client applies too, so the two profiles cannot disagree about it.
 */
@Component
@Profile("live")
public class HttpOnyxPasClient implements OnyxPasClient {

    /** A FHIR operation is sent and answered as FHIR JSON, and an answer with no body is not an answer. */
    static final ExchangeOptions FHIR_OPTIONS = ExchangeOptions.json(OnyxPasConventions.FHIR_JSON);

    private final OnyxAccess onyx;

    public HttpOnyxPasClient(OnyxAccess onyx) {
        this.onyx = onyx;
    }

    @Override
    public VendorExchange submit(PreparedCall call, ResolvedText body, MockSelection selection) {
        return send(call, body);
    }

    @Override
    public VendorExchange inquire(PreparedCall call, ResolvedText body, MockSelection selection) {
        return send(call, body);
    }

    private VendorExchange send(PreparedCall call, ResolvedText body) {
        VendorExchange exchange = onyx.send(call, body, FHIR_OPTIONS);
        return exchange.withSignals(OnyxPasConventions.signals(exchange));
    }
}
