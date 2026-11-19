package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.springframework.web.client.RestClient;

/**
 * Sends one lookup on the live profile, the same way for a member and for a provider.
 *
 * <p>The target comes from the gateway and from nowhere else: it resolves the url template, puts
 * the identifier into the {@code {id}} token percent-encoded, and returns the headers configured
 * for the lookup. The request leaves through the shared executor. Nothing here builds a url, adds
 * a configured header or opens a connection.
 *
 * <p>Everything that stops a lookup before it is sent is an answer and not an exception. A lookup
 * that is switched off, has no url, or is configured with a placeholder nobody gave a value is
 * DISABLED: the tester types the details by hand, and the message says what is missing.
 *
 * <p>Nothing here logs. The identifier is in the url, and the two clients log the outcome only.
 */
final class LookupSender {

    /** A read that expects JSON back. A header of the same name configured for the lookup replaces this one. */
    static final ExchangeOptions OPTIONS = ExchangeOptions.read("application/json");

    /**
     * What came of one attempt. Either the call as it happened, or the reason nothing was sent.
     *
     * @param call    the call as it happened, whatever its outcome. Null when nothing was sent
     * @param notSent why nothing was sent. Null when a request went out
     * @param request the request that was prepared and not sent, when there is one worth showing
     */
    record Attempt(VendorExchange call, LookupOutcomes.Ending notSent, WireRequest request) {

        static Attempt of(VendorExchange call) {
            return new Attempt(call, null, null);
        }

        static Attempt notSent(LookupOutcomes.Ending why) {
            return new Attempt(null, why, null);
        }
    }

    private final EpaOperation operation;
    private final LookupWords words;
    private final LookupProperties.Endpoint endpoint;
    private final ExchangeExecutor executor;
    private final OnyxTokenService tokens;
    private final OperationGateway gateway;
    private final RestClient client;

    /** @param client built with the timeouts of this lookup, which are shorter than the protocol timeouts */
    LookupSender(EpaOperation operation, LookupWords words, LookupProperties.Endpoint endpoint, ExchangeExecutor executor,
                 OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this.operation = operation;
        this.words = words;
        this.endpoint = endpoint;
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.client = client;
    }

    /** @param identifier the member ID in its no-space form, or the NPI */
    Attempt send(String identifier) {
        // Checked here, before the gateway is asked anything: a lookup that is switched off has no target to resolve.
        if (!endpoint.enabled()) {
            return Attempt.notSent(LookupOutcomes.switchedOff(words));
        }
        PreparedCall call;
        try {
            call = gateway.prepare(CallIntent.of(operation).withUrlValue("id", identifier));
        } catch (EndpointNotConfiguredException e) {
            return Attempt.notSent(LookupOutcomes.notConfigured(words));
        } catch (UnresolvedVariablesException e) {
            return Attempt.notSent(LookupOutcomes.incomplete(words, e.variables(), "its url", e.environmentName(), e.reasons()));
        } catch (BadInputException e) {
            // The gateway refuses a url value it has no token for. Its message is not used: it may name the value, which is the identifier.
            return Attempt.notSent(LookupOutcomes.noPlaceForTheIdentifier(words));
        } catch (ProdWriteBlockedException e) {
            return Attempt.notSent(LookupOutcomes.refusedByTheGuardrail(words, e.environment() == null ? null : e.environment().name()));
        }
        // A gateway that hands back an incomplete target without refusing it gets the same answers.
        ResolvedUrl url = call.url();
        if (url == null || !url.configured()) {
            return Attempt.notSent(LookupOutcomes.notConfigured(words));
        }
        if (!url.resolved()) {
            return Attempt.notSent(LookupOutcomes.incomplete(words, url.unresolved(), "its url", call.environment().name(), null));
        }

        ExchangeOptions options = OPTIONS;
        try {
            if (endpoint.useOnyxAuth() && !carriesItsOwnCredential(call)) {
                // Before the token is fetched, for the reason OnyxAccess gives: the token belongs to the
                // configuration that is current now, and a call prepared under an earlier one must not get it.
                Exchanges.requireSendable(call, null, gateway);
                OnyxTokenService.TokenResult token = tokens.token();
                if (token.failure() != null) {
                    return new Attempt(null, LookupOutcomes.noAccessToken(words, token.failure()),
                            Exchanges.wireRequest(call, null, options, false, token.failure()));
                }
                if (token.token() != null) {
                    options = options.withBearerToken(token.token());
                }
            }
            return Attempt.of(executor.execute(client, call, null, options));
        } catch (UnresolvedVariablesException e) {
            // A configured header still holds a placeholder. The executor refuses to send without it.
            return Attempt.notSent(LookupOutcomes.incomplete(words, e.variables(),
                    e.template() != null && e.template().startsWith("the header ") ? e.template() : "one of its headers",
                    e.environmentName(), e.reasons()));
        } catch (EndpointNotConfiguredException e) {
            return Attempt.notSent(LookupOutcomes.urlCannotBeCalled(words));
        } catch (BadInputException e) {
            return Attempt.notSent(LookupOutcomes.notValidHttp(words));
        } catch (ConfigurationChangedException e) {
            return new Attempt(null, LookupOutcomes.configurationChanged(words), Exchanges.wireRequest(call, null, options, false,
                    "Not sent. The configuration changed after this request was prepared."));
        }
    }

    /** A lookup configured with its own Authorization header is sent with that, and no token is fetched for it. */
    private static boolean carriesItsOwnCredential(PreparedCall call) {
        return call.headers().stream().anyMatch(header -> "authorization".equalsIgnoreCase(header.name()));
    }
}
