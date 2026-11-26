package org.p32h.interop.epa.pave.client.dtr;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;

/**
 * The DTR boundary. The vendor exposes DTR as server-side FHIR operations; there is no SMART
 * launch behind this interface. No method throws for a vendor fault.
 */
public interface OnyxDtrClient {

    /** Fetches the questionnaire package for an order. */
    VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection);

    /** One step of the adaptive loop: sends the answers so far and receives the next question. */
    VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection);
}
