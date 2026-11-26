package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.model.ProviderLookupResult;

/**
 * The provider directory boundary. As with the member API, the response contract is not final and
 * the mapping sits in one marked method per implementation. Never throws for an API fault and
 * never returns null.
 */
public interface ProviderLookupClient {

    /** Looks up an NPI that has already passed check digit validation. */
    ProviderLookupResult lookup(String npi);

    /** The same, with the case's date of service for a lookup saved on screen that sends one (R-11); null when the case has none. */
    default ProviderLookupResult lookup(String npi, java.time.LocalDate dateOfService) {
        return lookup(npi);
    }
}
