package org.p32h.interop.epa.pave.service.lookup;

import java.util.List;
import org.p32h.interop.epa.pave.client.lookup.ProviderLookupClient;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
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
        return lookup(npi, null);
    }

    /** As {@link #lookup(String)}, with the date of service a saved lookup may send (R-11): ISO, or blank for none. */
    public ProviderLookupResult lookup(String npi, String dateOfService) {
        java.time.LocalDate date = LookupDate.parse(dateOfService);
        // The same sentence as a send says for the same mistake, so one mistake reads as one message.
        // Spaces around a pasted number are not part of it. Anything inside it is left alone and fails the check.
        String problem = Npi.problem(npi);
        if (problem != null) {
            throw refused(problem);
        }
        String candidate = npi.strip();
        ProviderLookupResult result = client.lookup(candidate, date);
        if (result == null) {
            throw new IllegalStateException("The provider lookup client returned no result.");
        }
        return result;
    }

    /** The message never repeats what was typed: it names the rule that was broken. */
    private static ValidationException refused(String message) {
        return new ValidationException("This is not an NPI that can be looked up. Nothing was sent.", List.of(new FieldError("npi", message)));
    }
}
