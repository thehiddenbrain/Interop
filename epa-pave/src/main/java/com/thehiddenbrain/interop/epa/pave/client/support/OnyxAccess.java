package com.thehiddenbrain.interop.epa.pave.client.support;

import com.thehiddenbrain.interop.epa.pave.config.OnyxProperties;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.service.config.OperationGateway;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;
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
        OnyxTokenService.TokenResult token = tokens.token();
        if (token.failure() != null) {
            return Exchanges.unavailable(Exchanges.wireRequest(call, body, options, false, token.failure()), 0L, token.failure());
        }
        return executor.execute(client, call, body, token.token() == null ? options : options.withBearerToken(token.token()));
    }
}
