package com.thehiddenbrain.interop.epa.pave.client.lookup;

import com.thehiddenbrain.interop.epa.pave.model.ProviderLookupResult;

/**
 * The provider directory boundary. As with the member API, the response contract is not final and
 * the mapping sits in one marked method per implementation. Never throws for an API fault and
 * never returns null.
 */
public interface ProviderLookupClient {

    /** Looks up an NPI that has already passed check digit validation. */
    ProviderLookupResult lookup(String npi);
}
