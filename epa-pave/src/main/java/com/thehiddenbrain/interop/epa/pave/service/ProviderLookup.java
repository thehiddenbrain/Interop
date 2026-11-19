package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.ProviderLookupResult;

/** Provider lookup by NPI. One NPI returns one provider. */
public interface ProviderLookup {

    /**
     * Looks up one provider. The NPI check digit is validated first, and an NPI that fails it is a
     * validation error, not a lookup: a typing mistake is not a question worth asking the directory.
     */
    ProviderLookupResult lookup(String npi);
}
