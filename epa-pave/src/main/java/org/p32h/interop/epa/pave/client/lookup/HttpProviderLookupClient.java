package org.p32h.interop.epa.pave.client.lookup;

import java.time.LocalDate;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.p32h.interop.epa.pave.service.lookup.SavedLookups;
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
 * and everything this tool assumes about the directory is in that file. A lookup saved on screen
 * (R-11) replaces application.yml's wholly.
 */
@Component
@Profile("live")
public class HttpProviderLookupClient implements ProviderLookupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderLookupClient.class);

    private final LookupSender sender;
    private final SavedLookups saved;

    @Autowired
    public HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                    RestClient.Builder builder, SavedLookups saved) {
        // The lookup timeouts, which are shorter than the protocol timeouts, for the reason given on the member client.
        this.sender = new LookupSender(EpaOperation.PROVIDER_LOOKUP, LookupWords.PROVIDER, lookup.provider(), executor, tokens, gateway,
                RestClients.withTimeouts(builder, lookup.provider().connectTimeoutMs(), lookup.provider().readTimeoutMs()), builder);
        this.saved = saved;
    }

    /** As the application's constructor with no lookup saved on screen, for tests that build the client the application's way. */
    public HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                    RestClient.Builder builder) {
        this(executor, tokens, gateway, lookup, builder, SavedLookups.NONE);
    }

    /** For tests, which bind the client to a mock server. */
    HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                             RestClient client) {
        this(executor, tokens, gateway, lookup, client, SavedLookups.NONE);
    }

    /** For tests of a lookup saved on screen, bound to one client. */
    HttpProviderLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                             RestClient client, SavedLookups saved) {
        this.sender = new LookupSender(EpaOperation.PROVIDER_LOOKUP, LookupWords.PROVIDER, lookup.provider(), executor, tokens, gateway,
                client);
        this.saved = saved;
    }

    @Override
    public ProviderLookupResult lookup(String npi) {
        return lookup(npi, null);
    }

    @Override
    public ProviderLookupResult lookup(String npi, LocalDate dateOfService) {
        LookupSettings settings = saved.saved(LookupKind.PROVIDER);
        LookupSender.Attempt attempt = settings == null ? sender.send(npi) : sender.send(settings, npi, dateOfService);
        LookupReading reading = settings == null ? LookupReading.usual(LookupWords.PROVIDER)
                : LookupReading.saved(LookupWords.PROVIDER, LookupKind.PROVIDER, settings, npi);
        ProviderLookupResult result = attempt.call() == null
                ? ProviderResponseMapping.notSent(attempt.notSent(), npi, attempt.request())
                : ProviderResponseMapping.map(attempt.call(), npi, reading);
        // The outcome and the HTTP status only. Never the NPI, never the url or the body, which hold
        // it, and never anything the directory returned. An NPI is not member data, and the rule is
        // kept the same for both lookups so that nobody has to remember which one may log what.
        log.info("Provider lookup ended {} with HTTP status {}", result.status(),
                attempt.call() == null || attempt.call().httpStatus() == null ? "none" : attempt.call().httpStatus());
        return result;
    }
}
