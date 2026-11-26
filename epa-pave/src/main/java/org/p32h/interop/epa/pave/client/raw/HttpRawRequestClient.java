package org.p32h.interop.epa.pave.client.raw;

import java.util.List;
import java.util.function.Function;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.OnyxHosts;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.Timeouts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends a collection request as written, on the live profile.
 *
 * <p>The request goes wherever its collection points, so the Onyx access token is attached only
 * when its scheme, host and port are those of an address one of the Onyx operations resolves to,
 * and only when the request carries no
 * Authorization header of its own. See {@link OnyxHosts} for why.
 */
@Component
@Profile("live")
public class HttpRawRequestClient implements RawRequestClient {

    /**
     * A raw request declares its own headers. These two apply only where it declares none of that
     * name. A success with no body is not a fault: the tool does not know what the request returns.
     */
    static final ExchangeOptions OPTIONS = new ExchangeOptions(List.of(
            new ResolvedHeader("Accept", "application/json, */*;q=0.8", "application/json, */*;q=0.8"),
            new ResolvedHeader("Content-Type", "application/json", "application/json")), null, false);

    private final ExchangeExecutor executor;
    private final OnyxTokenService tokens;
    private final OperationGateway gateway;
    private final Function<Timeouts, RestClient> clients;

    @Autowired
    public HttpRawRequestClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, OnyxProperties onyx,
                                RestClient.Builder builder) {
        // A call always carries both values; application.yml's are there only for a call that somehow carries none.
        this(executor, tokens, gateway, timeouts -> RestClients.cached(builder,
                timeouts == null ? onyx.connectTimeoutMs() : timeouts.connectOr(onyx.connectTimeoutMs()),
                timeouts == null ? onyx.readTimeoutMs() : timeouts.readOr(onyx.readTimeoutMs())));
    }

    /** For tests, which bind the client to a mock server whatever the call's timeouts. */
    HttpRawRequestClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this(executor, tokens, gateway, timeouts -> client);
    }

    private HttpRawRequestClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway,
                                 Function<Timeouts, RestClient> clients) {
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.clients = clients;
    }

    /**
     * Sends the request as written. A 401 to it is the answer: a request sent as written is sent
     * once. The token it carried is still dropped (a pasted one let go of, an issued one taken out
     * of the cache), so the next call signs in afresh.
     */
    @Override
    public VendorExchange send(PreparedCall call, ResolvedText body) {
        // Checked before a token is fetched, for the reason OnyxAccess gives: the token belongs to
        // the configuration that is current now, and a call prepared under an earlier one must not get it.
        Exchanges.requireSendable(call, body, gateway);
        RestClient client = clients.apply(call.timeouts());
        if (!tokenApplies(call, gateway)) {
            return executor.execute(client, call, body, OPTIONS);
        }
        OnyxTokenService.TokenResult token = tokens.tokenFor(call.signIn());
        if (token.failure() != null) {
            return Exchanges.notSignedIn(Exchanges.wireRequest(call, body, OPTIONS, false, token.failure()),
                    Exchanges.notSignedInType(token.cause()), token.failure());
        }
        VendorExchange sent = executor.execute(client, call, body, token.token() == null ? OPTIONS : OPTIONS.withBearerToken(token.token()));
        if (sent.httpStatus() != null && sent.httpStatus() == 401) {
            tokens.refused(token);
        }
        return sent;
    }

    /**
     * True when the Onyx token would be attached to this call. For the result to say so in words.
     * The scheme, host and port must all be those of an Onyx address: another port of the same host
     * can be another service, and plain http would send the token in clear.
     */
    public static boolean tokenApplies(PreparedCall call, OperationGateway gateway) {
        boolean ownCredential = call.headers().stream().anyMatch(h -> "authorization".equalsIgnoreCase(h.name()));
        return !ownCredential && OnyxHosts.admits(gateway, call.url() == null ? null : call.url().value());
    }
}
