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
 * The subscription client on the live profile: the FHIR create, read, search, update and delete
 * interactions on Subscription.
 *
 * <p>The mock selection is ignored entirely. Create, update and delete are writes, and the
 * prepared call each one needs has already passed the production guardrail in the gateway.
 */
@Component
@Profile("live")
public class HttpSubscriptionClient implements SubscriptionClient {

    /**
     * A create or an update may answer with the resource or with nothing but a status and a
     * Location header, depending on what the service returns by default. Neither is malformed.
     */
    static final ExchangeOptions WRITE_OPTIONS = ExchangeOptions.json(OnyxPasConventions.FHIR_JSON).withBodyRequired(false);
    /** A read or a list with nothing in it is not an answer. */
    static final ExchangeOptions READ_OPTIONS = ExchangeOptions.read(OnyxPasConventions.FHIR_JSON);
    /** A delete normally answers with no content. */
    static final ExchangeOptions DELETE_OPTIONS = ExchangeOptions.read(OnyxPasConventions.FHIR_JSON).withBodyRequired(false);

    private final OnyxAccess onyx;

    public HttpSubscriptionClient(OnyxAccess onyx) {
        this.onyx = onyx;
    }

    @Override
    public VendorExchange create(PreparedCall call, ResolvedText body, MockSelection selection) {
        return onyx.send(call, body, WRITE_OPTIONS);
    }

    @Override
    public VendorExchange get(PreparedCall call, MockSelection selection) {
        return onyx.send(call, null, READ_OPTIONS);
    }

    @Override
    public VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection) {
        return onyx.send(call, body, WRITE_OPTIONS);
    }

    @Override
    public VendorExchange delete(PreparedCall call, MockSelection selection) {
        return onyx.send(call, null, DELETE_OPTIONS);
    }
}
