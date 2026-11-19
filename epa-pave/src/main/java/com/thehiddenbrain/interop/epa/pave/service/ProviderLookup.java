package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.ProviderLookupResult;

/** Provider lookup by NPI. One NPI returns one provider. */
public interface ProviderLookup {

    /**
     * Looks up one provider. The NPI check digit is validated first: a typing mistake is not a
     * question worth asking the directory.
     *
     * @throws com.thehiddenbrain.interop.epa.pave.service.error.ValidationException with field
     *         {@code npi} when the text is not ten digits or the check digit fails. Answered with 422
     */
    ProviderLookupResult lookup(String npi);
}
