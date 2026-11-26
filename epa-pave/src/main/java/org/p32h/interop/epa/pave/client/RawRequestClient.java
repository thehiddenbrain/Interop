package org.p32h.interop.epa.pave.client;

import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;

/**
 * Sends a collection request as written, with placeholders resolved and no protocol handling. For
 * a request the tool could not map to an operation. On the mock profile nothing is sent and the
 * exchange says so.
 */
public interface RawRequestClient {

    VendorExchange send(PreparedCall call, ResolvedText body);
}
