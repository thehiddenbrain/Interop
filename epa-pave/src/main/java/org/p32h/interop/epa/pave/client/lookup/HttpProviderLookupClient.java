package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Provider lookup on the live profile: one call to the provider directory for one NPI.
 *
 * <p>The response is read by {@link ProviderResponseMapping#map}, which the mock client uses too,
 * and everything this tool assumes about the directory is in that file.
 */
@Component
@Profile("live")
public class HttpProviderLookupClient implements ProviderLookupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderLookupClient.class);

    private final LookupSender sender;

    @Autowired
    public HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                    RestClient.Builder builder) {
        // The lookup timeouts, which are shorter than the protocol timeouts, for the reason given on the member client.
        this(executor, tokens, gateway, lookup,
                RestClients.withTimeouts(builder, lookup.provider().connectTimeoutMs(), lookup.provider().readTimeoutMs()));
    }

    /** For tests, which bind the client to a mock server. */
    HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                             RestClient client) {
        this.sender = new LookupSender(EpaOperation.PROVIDER_LOOKUP, LookupWords.PROVIDER, lookup.provider(), executor, tokens, gateway,
                client);
    }

    @Override
    public ProviderLookupResult lookup(String npi) {
        LookupSender.Attempt attempt = sender.send(npi);
        ProviderLookupResult result = attempt.call() == null
                ? ProviderResponseMapping.notSent(attempt.notSent(), npi, attempt.request())
                : ProviderResponseMapping.map(attempt.call(), npi);
        // The outcome and the HTTP status only. Never the NPI, never the url, which holds it, and
        // never anything the directory returned. An NPI is not member data, and the rule is kept
        // the same for both lookups so that nobody has to remember which one may log what.
        log.info("Provider lookup ended {} with HTTP status {}", result.status(),
                attempt.call() == null || attempt.call().httpStatus() == null ? "none" : attempt.call().httpStatus());
        return result;
    }
}
