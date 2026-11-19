package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Provider lookup on the mock profile. It answers from the synthetic fixture files and needs no
 * configuration, no credentials and no network.
 *
 * <p>Every NPI it knows was made with the application's own check digit rule, and
 * {@link LookupFixtures} refuses a file whose NPI fails it. A fixture holds a response in the
 * shape a directory might send, and that response goes through
 * {@link ProviderResponseMapping#map}, the same method the live client uses.
 *
 * <p>An NPI with no fixture is not found. {@code 9999999995} is one on purpose, so the sentinel
 * for "provider not found" is not found here too.
 */
@Component
@Profile("mock")
public class MockProviderLookupClient implements ProviderLookupClient {

    private final LookupFixtures fixtures;
    private final LookupProperties lookup;

    public MockProviderLookupClient(LookupFixtures fixtures, LookupProperties lookup) {
        this.fixtures = fixtures;
        this.lookup = lookup;
    }

    @Override
    public ProviderLookupResult lookup(String npi) {
        LookupProperties.Endpoint endpoint = lookup.provider();
        if (!endpoint.enabled()) {
            return ProviderResponseMapping.notSent(LookupOutcomes.switchedOff(LookupWords.PROVIDER), npi, null);
        }
        LookupFixtures.Fixture fixture = fixtures.provider(npi);
        VendorExchange exchange = fixture == null ? MockLookups.unknown(endpoint.url(), LookupWords.PROVIDER)
                : MockLookups.answer(fixture, endpoint.url());
        return ProviderResponseMapping.map(exchange, npi);
    }
}
