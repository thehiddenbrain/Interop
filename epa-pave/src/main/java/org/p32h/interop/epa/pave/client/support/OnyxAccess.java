package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.service.config.Timeouts;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The one way a live client sends anything to the service: with the timeouts the call carries, and
 * with an access token when the sign-in in force gives one. Every live protocol client delegates
 * here, so none of them carries its own HTTP client, timeout or token handling.
 */
@Component
@Profile("live")
public class OnyxAccess {

    /** The note on a call sent once more after a 401 (R-03), in the plan's words. */
    static final String SENT_ONCE_MORE = "The first attempt was refused with HTTP 401; PAVE signed in again and sent it once more.";

    /** Gives the client for a call's timeouts. */
    @FunctionalInterface
    interface Clients {
        RestClient forTimeouts(Timeouts timeouts);
    }

    private final ExchangeExecutor executor;
    private final OnyxTokenService tokens;
    private final OperationGateway gateway;
    private final Clients clients;

    @org.springframework.beans.factory.annotation.Autowired
    public OnyxAccess(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, OnyxProperties onyx,
                      RestClient.Builder builder) {
        // A call always carries both values; application.yml's are there only for a call that somehow carries none.
        this(executor, tokens, gateway, (Clients) timeouts -> RestClients.cached(builder,
                timeouts == null ? onyx.connectTimeoutMs() : timeouts.connectOr(onyx.connectTimeoutMs()),
                timeouts == null ? onyx.readTimeoutMs() : timeouts.readOr(onyx.readTimeoutMs())));
    }

    /** For tests, which bind the client to a mock server whatever the call's timeouts. */
    OnyxAccess(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this(executor, tokens, gateway, (Clients) timeouts -> client);
    }

    private OnyxAccess(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, Clients clients) {
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.clients = clients;
    }

    /**
     * Sends one prepared call.
     *
     * <p>When the sign-in in force gives no token, nothing is sent and the returned exchange says
     * so: the request is described, marked as not sent, and the failure detail names the token
     * problem. That is a completed attempt and is recorded like any other.
     *
     * <p>A 401 to a call that carried a token PAVE asked for drops that token, signs in again and
     * sends the call once more (R-03): a 401 means the call was not taken, so nothing is created
     * twice, and the vendor has refused a token before its stated expiry. A second 401 is the
     * service's answer. A stated bearer token is never retried; a pasted one is let go of and the
     * 401 is the answer (R-43).
     */
    public VendorExchange send(PreparedCall call, ResolvedText body, ExchangeOptions options) {
        return send(call, call.signIn(), body, options);
    }

    /** As {@link #send(PreparedCall, ResolvedText, ExchangeOptions)}, with the sign-in given apart from the call, for tests. */
    VendorExchange send(PreparedCall call, SignInSettings signIn, ResolvedText body, ExchangeOptions options) {
        // Checked before the token is fetched: the token is resolved against the configuration that
        // is current now, so a call prepared under an earlier one must not be given it. Otherwise a
        // switch between preparing and sending could put one environment's token on a request to
        // another environment's host.
        Exchanges.requireSendable(call, body, gateway);
        RestClient client = clients.forTimeouts(call.timeouts());
        String url = call.url() == null ? null : call.url().value();
        if (!OnyxHosts.admits(gateway, url)) {
            // The token is a credential for the service it was issued for. A call that a value
            // typed for one run pointed somewhere else goes without it, and the result says so
            // when there was a token to withhold.
            boolean pastedHeld = tokens.pastedHeld();
            OnyxTokenService.TokenResult withheld = pastedHeld ? null : tokens.token(signIn);
            VendorExchange sent = executor.execute(client, call, body, options);
            if (!pastedHeld && withheld.token() == null && withheld.failure() == null) {
                return sent;
            }
            return withNote(sent, "The Onyx access token was not attached: this request goes to " + origin(url) + ", which is not an "
                    + "address the configuration in force names for an Onyx operation. The token is sent only to the service it was "
                    + "issued for.");
        }
        OnyxTokenService.TokenResult token = tokens.tokenFor(signIn);
        if (token.failure() != null) {
            return notSignedIn(call, body, options, token);
        }
        VendorExchange first = executor.execute(client, call, body, withToken(options, token));
        if (!refused(first) || token.token() == null) {
            return first;
        }
        tokens.refused(token);
        if (!token.signsInAgainOnRefusal() || gateway.epoch() != call.epoch()) {
            // A call whose configuration changed meanwhile is not sent again: the guardrail judged it under the earlier one.
            return first;
        }
        OnyxTokenService.TokenResult again = tokens.tokenFor(signIn);
        if (again.failure() != null) {
            return withNote(first, "The service refused this call with HTTP 401. PAVE signed in again to send it once more, and could "
                    + "not: " + withoutNotSent(again.failure()));
        }
        VendorExchange second = executor.execute(client, call, body, withToken(options, again));
        if (refused(second)) {
            // The second refusal is the answer; the token goes, so the next call signs in afresh.
            tokens.refused(again);
        }
        return withNote(second, SENT_ONCE_MORE);
    }

    private static boolean refused(VendorExchange exchange) {
        return exchange != null && exchange.httpStatus() != null && exchange.httpStatus() == 401;
    }

    private static ExchangeOptions withToken(ExchangeOptions options, OnyxTokenService.TokenResult token) {
        return token.token() == null ? options : options.withBearerToken(token.token());
    }

    private static VendorExchange notSignedIn(PreparedCall call, ResolvedText body, ExchangeOptions options, OnyxTokenService.TokenResult token) {
        return Exchanges.notSignedIn(Exchanges.wireRequest(call, body, options, false, token.failure()),
                Exchanges.notSignedInType(token.cause()), token.failure());
    }

    /** A token failure's sentence without its closing "The request was not sent.", which is not true of a call already sent once. */
    private static String withoutNotSent(String failure) {
        String tail = " The request was not sent.";
        return failure.endsWith(tail) ? failure.substring(0, failure.length() - tail.length()) : failure;
    }

    /** The scheme, host and port of a url, never its path or query, which can hold an identifier. */
    private static String origin(String url) {
        UrlNormal.Destination destination = UrlNormal.destination(url);
        if (destination == null) {
            return "an address this tool cannot read";
        }
        return new UrlNormal.Destination(destination.scheme(), destination.host(), destination.port(), "").shown();
    }

    static VendorExchange withNote(VendorExchange exchange, String note) {
        WireRequest request = exchange.request();
        if (request == null) {
            return exchange;
        }
        // The secrets go with it: an answer is masked by the values its request carried.
        WireRequest noted = new WireRequest(request.method(), request.url(), request.urlTemplate(), request.headers(), request.body(),
                request.sent(), request.note() == null ? note : request.note() + " " + note, request.secrets());
        return new VendorExchange(noted, exchange.httpStatus(), exchange.responseHeaders(), exchange.responseBody(), exchange.responseBytes(),
                exchange.json(), exchange.parseError(), exchange.elapsedMillis(), exchange.failureType(), exchange.failureDetail(),
                exchange.signals(), exchange.scenarioId(), exchange.scenarioNote(), exchange.bodyNotes());
    }
}
