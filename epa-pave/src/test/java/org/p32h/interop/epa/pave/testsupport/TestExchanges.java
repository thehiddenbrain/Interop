package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import java.util.List;

/** Builds exchanges for mapper tests through the same classification the clients use. */
public final class TestExchanges {

    private TestExchanges() {
    }

    private static WireRequest request(EpaOperation operation) {
        return Exchanges.wireRequest(TestPreparedCalls.to(operation, "https://onyx.example.invalid/x"), ResolvedText.plain("{}"),
                ExchangeOptions.json("application/json"), true, null);
    }

    /** A CRD response with the given status and body, with the vendor signals applied as the clients apply them. */
    public static VendorExchange crd(int status, String body) {
        VendorExchange exchange = Exchanges.fromResponse(request(EpaOperation.CRD_ORDER_SIGN), status, List.of(), body, 12L, true);
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    /** A response for any operation, with no vendor signals. */
    public static VendorExchange of(EpaOperation operation, int status, String body, boolean bodyRequired) {
        return Exchanges.fromResponse(request(operation), status, List.of(), body, 12L, bodyRequired);
    }

    public static VendorExchange timeout(EpaOperation operation) {
        return Exchanges.timeout(request(operation), 15000L);
    }

    public static VendorExchange unavailable(EpaOperation operation) {
        return Exchanges.unavailable(request(operation), 3L, "The connection was refused or could not be opened.");
    }
}
