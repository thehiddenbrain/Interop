package com.thehiddenbrain.interop.epa.pave.client.crd;

import com.thehiddenbrain.interop.epa.pave.client.MockSelection;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;

/**
 * The CRD boundary. Everything the tool knows about how the vendor's CDS Hooks service actually
 * behaves lives behind this interface.
 *
 * <p>No method throws for a vendor fault. A timeout, an HTTP error and an unreadable body are all
 * described in the returned exchange, so the tester sees the request that caused them.
 */
public interface OnyxCrdClient {

    /** Sends one CDS Hooks request for order-select or order-sign. */
    VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection);

    /** Calls CDS Hooks discovery, which lists the services the environment offers. */
    VendorExchange discover(PreparedCall call);
}
