package org.p32h.interop.epa.pave.service.lookup;

import org.p32h.interop.epa.pave.client.lookup.ProviderLookupClient;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.stereotype.Service;

/**
 * Provider lookup by NPI. One NPI, one provider.
 *
 * <p>The check digit is validated before the directory is asked. A number that fails it is a
 * typing mistake, not a question worth asking: the directory would answer "not found", and the
 * tester would go looking for a provider that was never missing.
 */
@Service
public class ProviderLookupService implements ProviderLookup {

    private final ProviderLookupClient client;

    public ProviderLookupService(ProviderLookupClient client) {
        this.client = client;
    }

    @Override
    public ProviderLookupResult lookup(String npi) {
        if (Texts.blank(npi)) {
            throw ValidationException.of("npi", "Enter an NPI. Nothing was looked up.");
        }
        // Spaces around a pasted number are not part of it. Anything inside it is left alone and fails the check.
        String candidate = npi.strip();
        if (!candidate.matches("\\d{10}")) {
            throw ValidationException.of("npi", "An NPI is exactly ten digits. This is probably a typing mistake, so the directory "
                    + "was not asked.");
        }
        if (!Npi.isValid(candidate)) {
            throw ValidationException.of("npi", "The check digit of this NPI does not match, so it is probably a typing mistake. The "
                    + "directory was not asked.");
        }
        ProviderLookupResult result = client.lookup(candidate);
        if (result == null) {
            throw new IllegalStateException("The provider lookup client returned no result.");
        }
        return result;
    }
}
