package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;

/**
 * The PAS boundary. PAS is a FHIR operation, not a CDS Hooks call. No method throws for a vendor
 * fault.
 *
 * <p>Submit is a write. The only way to obtain the {@link PreparedCall} it needs is through the
 * gateway, which refuses to mint one for a production environment unless writes are unlocked.
 */
public interface OnyxPasClient {

    /** Submits a prior authorization request bundle. */
    VendorExchange submit(PreparedCall call, ResolvedText body, MockSelection selection);

    /** Asks for the status of a submitted request. */
    VendorExchange inquire(PreparedCall call, ResolvedText body, MockSelection selection);
}
