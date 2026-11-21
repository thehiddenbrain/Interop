package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one way a live client sends anything to Onyx: with the Onyx timeouts, and with an access
 * token when authentication is switched on. Every live protocol client delegates here, so none of
 * them carries its own HTTP client, timeout or token handling.
 */
@Component
@Profile("live")
public class OnyxAccess {

    private final ExchangeExecutor executor;
    private final OnyxTokenService tokens;
    private final OperationGateway gateway;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public OnyxAccess(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, OnyxProperties onyx,
                      RestClient.Builder builder) {
        this(executor, tokens, gateway, RestClients.withTimeouts(builder, onyx.connectTimeoutMs(), onyx.readTimeoutMs()));
    }

    /** For tests, which bind the client to a mock server. */
    OnyxAccess(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.client = client;
    }

    /**
     * Sends one prepared call.
     *
     * <p>When authentication is on and no token can be obtained, nothing is sent and the returned
     * exchange says so: the request is described, marked as not sent, and the failure detail names
     * the token problem. That is a completed attempt and is recorded like any other.
     */
    public VendorExchange send(PreparedCall call, ResolvedText body, ExchangeOptions options) {
        // Checked before the token is fetched: the token is resolved against the configuration that
        // is current now, so a call prepared under an earlier one must not be given it. Otherwise a
        // switch between preparing and sending could put one environment's token on a request to
        // another environment's host.
        Exchanges.requireSendable(call, body, gateway);
        String url = call.url() == null ? null : call.url().value();
        if (!OnyxHosts.admits(gateway, url)) {
            // The token is a credential for the service it was issued for. A call that a value
            // typed for one run pointed somewhere else goes without it, and the result says so
            // when there was a token to withhold.
            OnyxTokenService.TokenResult withheld = tokens.token();
            VendorExchange sent = executor.execute(client, call, body, options);
            if (withheld.token() == null && withheld.failure() == null) {
                return sent;
            }
            return withNote(sent, "The Onyx access token was not attached: this request goes to " + origin(url) + ", which is not an "
                    + "address the configuration in force names for an Onyx operation. The token is sent only to the service it was "
                    + "issued for.");
        }
        OnyxTokenService.TokenResult token = tokens.token();
        if (token.failure() != null) {
            return Exchanges.notSignedIn(Exchanges.wireRequest(call, body, options, false, token.failure()),
                    Exchanges.notSignedInType(token.cause()), token.failure());
        }
        return executor.execute(client, call, body, token.token() == null ? options : options.withBearerToken(token.token()));
    }

    /** The scheme, host and port of a url, never its path or query, which can hold an identifier. */
    private static String origin(String url) {
        UrlNormal.Destination destination = UrlNormal.destination(url);
        if (destination == null) {
            return "an address this tool cannot read";
        }
        return new UrlNormal.Destination(destination.scheme(), destination.host(), destination.port(), "").shown();
    }

    private static VendorExchange withNote(VendorExchange exchange, String note) {
        WireRequest request = exchange.request();
        if (request == null) {
            return exchange;
        }
        WireRequest noted = new WireRequest(request.method(), request.url(), request.urlTemplate(), request.headers(), request.body(),
                request.sent(), request.note() == null ? note : request.note() + " " + note);
        return new VendorExchange(noted, exchange.httpStatus(), exchange.responseHeaders(), exchange.responseBody(), exchange.responseBytes(),
                exchange.json(), exchange.parseError(), exchange.elapsedMillis(), exchange.failureType(), exchange.failureDetail(),
                exchange.signals(), exchange.scenarioId(), exchange.scenarioNote(), exchange.bodyNotes());
    }
}
