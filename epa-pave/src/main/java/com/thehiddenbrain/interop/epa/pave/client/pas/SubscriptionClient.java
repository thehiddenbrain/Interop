package com.thehiddenbrain.interop.epa.pave.client.pas;

import com.thehiddenbrain.interop.epa.pave.client.MockSelection;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;

/**
 * The subscription boundary. Create, update and delete are writes and are guarded the same way as
 * a PAS submit. No method throws for a vendor fault.
 */
public interface SubscriptionClient {

    VendorExchange create(PreparedCall call, ResolvedText body, MockSelection selection);

    /** Reads one subscription or lists them, depending on the url the call was prepared with. */
    VendorExchange get(PreparedCall call, MockSelection selection);

    VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection);

    VendorExchange delete(PreparedCall call, MockSelection selection);
}
