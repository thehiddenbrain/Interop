package org.p32h.interop.epa.pave.client.raw;

import java.util.List;
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
    private final RestClient client;

    @Autowired
    public HttpRawRequestClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, OnyxProperties onyx,
                                RestClient.Builder builder) {
        this(executor, tokens, gateway, RestClients.withTimeouts(builder, onyx.connectTimeoutMs(), onyx.readTimeoutMs()));
    }

    /** For tests, which bind the client to a mock server. */
    HttpRawRequestClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.client = client;
    }

    @Override
    public VendorExchange send(PreparedCall call, ResolvedText body) {
        // Checked before a token is fetched, for the reason OnyxAccess gives: the token belongs to
        // the configuration that is current now, and a call prepared under an earlier one must not get it.
        Exchanges.requireSendable(call, body, gateway);
        if (!tokenApplies(call, gateway)) {
            return executor.execute(client, call, body, OPTIONS);
        }
        OnyxTokenService.TokenResult token = tokens.token();
        if (token.failure() != null) {
            return Exchanges.notSignedIn(Exchanges.wireRequest(call, body, OPTIONS, false, token.failure()),
                    Exchanges.notSignedInType(token.cause()), token.failure());
        }
        return executor.execute(client, call, body, token.token() == null ? OPTIONS : OPTIONS.withBearerToken(token.token()));
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
