package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Obtains and caches the access token for calls to Onyx.
 *
 * <p>Three concerns are kept apart so that changing how a token is obtained touches one method:
 * {@link #requestToken} obtains a token, {@link #token()} caches it and applies the refresh skew,
 * and the executor applies it to a request. An environment that uses private-key JWT, which is
 * common for SMART backend services, replaces {@link #requestToken} and nothing else.
 *
 * <p>The authentication settings may be placeholders. They are resolved through the gateway
 * against the active environment each time a token is needed, so switching environment switches
 * credentials, and the cache is keyed on the configuration epoch so a token never outlives the
 * environment it was issued for.
 */
@Component
@Profile("live")
public class OnyxTokenService implements org.p32h.interop.epa.pave.service.config.HeldAccessToken {

    private static final Logger log = LoggerFactory.getLogger(OnyxTokenService.class);

    /** A token is replaced this long before it expires, so one is never sent in its last moments. */
    static final long REFRESH_SKEW_SECONDS = 60;

    /**
     * Why no token could be obtained, so that a call that was not sent is said as the tester's
     * configuration or as the environment, which are different findings with different next steps.
     */
    public enum TokenFailure {
        /** A sign-in setting is not given, or names a placeholder the active environment does not define: the tester's configuration. */
        SETTING_MISSING,
        /**
         * The token endpoint answered with a 4xx status: it refused the client. A wrong client id or
         * secret, or a wrong path in the token url, is the tester's configuration.
         */
        CLIENT_REFUSED,
        /**
         * The token endpoint could not be reached, timed out, failed with a 5xx status, answered with
         * a status that is neither success nor refusal, or answered success with no usable token:
         * a fault in the environment at the token endpoint.
         */
        ENDPOINT_FAULT
    }

    /**
     * The answer to "what credential goes on this call".
     *
     * @param token   the access token, or null when authentication is off or no token could be obtained
     * @param failure why no token could be obtained, or null. When set, the call must not be sent
     * @param cause   which kind of failure that is, or null when there is a token or authentication is off
     */
    public record TokenResult(String token, String failure, TokenFailure cause) {

        static TokenResult none() {
            return new TokenResult(null, null, null);
        }

        static TokenResult failed(String reason, TokenFailure cause) {
            return new TokenResult(null, reason, cause);
        }

        /** Redacted, because it holds an access token. */
        @Override
        public String toString() {
            return "TokenResult[" + (failure == null ? "token redacted" : "failed") + "]";
        }
    }

    /** What the token endpoint is asked with. */
    protected record TokenRequest(String tokenUrl, String clientId, String clientSecret, String scopes) {

        @Override
        public String toString() {
            return "TokenRequest[redacted]";
        }
    }

    /**
     * What the token endpoint answered.
     *
     * @param cause which kind of failure, or null when a token was granted
     */
    protected record TokenGrant(String accessToken, Long expiresInSeconds, String failure, TokenFailure cause) {

        @Override
        public String toString() {
            return "TokenGrant[redacted]";
        }
    }

    private record Cached(String key, String token, Instant refreshAt) {

        /** Redacted, because it holds an access token. */
        @Override
        public String toString() {
            return "Cached[redacted]";
        }
    }

    private final OnyxProperties onyx;
    private final OperationGateway gateway;
    private final RestClient client;
    private final Clock clock;
    // Volatile so that addTo reads them without the lock a token request holds while it waits for the token endpoint.
    private volatile Cached cached;

    /** The last token issued, cached or not: a service can repeat a token it was sent once. Never shown or logged. */
    private volatile String lastIssued;

    @org.springframework.beans.factory.annotation.Autowired
    public OnyxTokenService(OnyxProperties onyx, OperationGateway gateway, RestClient.Builder builder, Clock clock) {
        this(onyx, gateway, RestClients.withTimeouts(builder, onyx.connectTimeoutMs(), onyx.readTimeoutMs()), clock);
    }

    /** For tests, which bind the client to a mock server. */
    OnyxTokenService(OnyxProperties onyx, OperationGateway gateway, RestClient client, Clock clock) {
        this.onyx = onyx;
        this.gateway = gateway;
        this.client = client;
        this.clock = clock;
    }

    /**
     * Adds the token held in the cache and the last one issued, whatever their age, so that a
     * service repeating one in a notification or in any answer is shown with it masked.
     */
    @Override
    public void addTo(org.p32h.interop.epa.pave.model.SentSecrets.Builder secrets) {
        // Not synchronized: every answer is masked with the token held, and an answer to a call
        // that carried no token must not wait for another thread's token request to end.
        Cached held = cached;
        if (held != null) {
            secrets.accessToken(held.token());
        }
        secrets.accessToken(lastIssued);
    }

    /** The token to send, from the cache when one is still good. Never throws. */
    public synchronized TokenResult token() {
        OnyxProperties.Auth auth = onyx.auth();
        if (!auth.enabled()) {
            return TokenResult.none();
        }
        ResolvedText tokenUrl = gateway.resolve(auth.tokenUrl(), Map.of());
        ResolvedText clientId = gateway.resolve(auth.clientId(), Map.of());
        ResolvedText clientSecret = gateway.resolve(auth.clientSecret(), Map.of());
        ResolvedText scopes = gateway.resolve(auth.scopes(), Map.of());
        if (Texts.blank(auth.tokenUrl()) || !tokenUrl.resolved() || Texts.blank(tokenUrl.value())) {
            return TokenResult.failed("Authentication is switched on but onyx.auth.token-url is not set, or it contains a placeholder "
                    + "the active environment does not define. The request was not sent.", TokenFailure.SETTING_MISSING);
        }
        // A url that is not an absolute address is the tester's setting, found before anything is sent. It is named by the
        // setting, never by its value, which an environment can fill with something that should not be repeated.
        if (!absoluteHttpAddress(tokenUrl.value())) {
            return TokenResult.failed("Authentication is switched on but onyx.auth.token-url, as configured, is not an absolute http or "
                    + "https address, so this tool could not use it. The request was not sent.", TokenFailure.SETTING_MISSING);
        }
        boolean noId = !clientId.resolved() || Texts.blank(clientId.value());
        boolean noSecret = !clientSecret.resolved() || Texts.blank(clientSecret.value());
        if (noId || noSecret) {
            // The setting is named, as Ready to send on the Config tab names it, so the tester knows which one to give. Only the
            // names of the settings are said, never a value.
            String which = noId && noSecret ? "onyx.auth.client-id and onyx.auth.client-secret are" : noId ? "onyx.auth.client-id is"
                    : "onyx.auth.client-secret is";
            return TokenResult.failed("Authentication is switched on but " + which + " not set, or a placeholder the active environment "
                    + "does not define. Ready to send, on the Config tab, says where it reads from. The request was not sent.",
                    TokenFailure.SETTING_MISSING);
        }
        String key = gateway.epoch() + "|" + tokenUrl.value() + "|" + clientId.value();
        if (cached != null && cached.key().equals(key) && clock.instant().isBefore(cached.refreshAt())) {
            return new TokenResult(cached.token(), null, null);
        }
        TokenGrant grant = requestToken(new TokenRequest(tokenUrl.value(), clientId.value(), clientSecret.value(),
                scopes.resolved() ? scopes.value() : null));
        if (grant.failure() != null || Texts.blank(grant.accessToken())) {
            cached = null;
            // Outcome only. The token url, the client id and the response are not logged.
            log.warn("Access token request failed");
            // A grant with neither a token nor a failure is the endpoint's doing, never the tester's settings.
            return TokenResult.failed(Texts.orElse(grant.failure(), "The token endpoint answered without an access token.")
                    + " The request was not sent.", grant.cause() == null ? TokenFailure.ENDPOINT_FAULT : grant.cause());
        }
        // Without a stated lifetime the token is used for this call and not cached: guessing a
        // lifetime would mean sending an expired token at some point and reporting it as a vendor fault.
        lastIssued = grant.accessToken();
        cached = grant.expiresInSeconds() == null || grant.expiresInSeconds() <= REFRESH_SKEW_SECONDS ? null
                : new Cached(key, grant.accessToken(), clock.instant().plusSeconds(grant.expiresInSeconds() - REFRESH_SKEW_SECONDS));
        return new TokenResult(grant.accessToken(), null, null);
    }

    /** True for an absolute http or https url with a host and a usable port; false for anything else, a text that is no url at all included. */
    static boolean absoluteHttpAddress(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        // A port outside the range a connection can use parses, and the client then fails in a way this tool cannot place, which
        // would be said of the endpoint; it is the address as configured that cannot be used.
        int port = uri.getPort();
        boolean usablePort = port == -1 || (port >= 1 && port <= 65535);
        return uri.isAbsolute() && (scheme.equals("http") || scheme.equals("https")) && Texts.present(uri.getHost()) && usablePort;
    }

    /**
     * THE ONE METHOD TO REPLACE for another way of obtaining a token.
     *
     * <p>This implementation is the OAuth2 client credentials grant with the client authenticated
     * by HTTP Basic. Whether the vendor wants that, credentials in the form body, or a signed JWT
     * assertion is an open item that the uploaded environment usually settles.
     */
    protected TokenGrant requestToken(TokenRequest request) {
        String basic = Base64.getEncoder().encodeToString((request.clientId() + ":" + request.clientSecret()).getBytes(StandardCharsets.UTF_8));
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (Texts.present(request.scopes())) {
            form.append("&scope=").append(java.net.URLEncoder.encode(request.scopes().strip(), StandardCharsets.UTF_8));
        }
        record Raw(int status, String body) {
        }
        try {
            Raw raw = client.post().uri(java.net.URI.create(request.tokenUrl().strip()))
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form.toString().getBytes(StandardCharsets.UTF_8))
                    .exchange((req, res) -> new Raw(res.getStatusCode().value(), new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (raw.status() < 200 || raw.status() >= 300) {
                // A 4xx is the endpoint refusing this client, which the tester's settings decide; anything else is the endpoint.
                TokenFailure cause = raw.status() >= 400 && raw.status() < 500 ? TokenFailure.CLIENT_REFUSED : TokenFailure.ENDPOINT_FAULT;
                return new TokenGrant(null, null, "No access token: the token endpoint answered HTTP " + raw.status() + ".", cause);
            }
            // A success with nothing usable in it is the endpoint's fault: the client was accepted.
            JsonSafe.Parsed parsed = JsonSafe.parse(raw.body());
            if (!parsed.ok()) {
                return new TokenGrant(null, null, "No access token: the token endpoint answered with a body that is not JSON.",
                        TokenFailure.ENDPOINT_FAULT);
            }
            String token = JsonSafe.presentText(parsed.node(), "access_token");
            if (token == null) {
                return new TokenGrant(null, null, "No access token: the token endpoint answered without one.", TokenFailure.ENDPOINT_FAULT);
            }
            return new TokenGrant(token, JsonSafe.longValue(parsed.node(), "expires_in"), null, null);
        } catch (RuntimeException e) {
            // The exception message quotes the token url, so only the type is used. An endpoint that took the connection and then
            // dropped it was reached, so it is not said to be unreachable, and the two can be told apart.
            // Only a failure whose type means that an open connection ended without an answer is said to be one; a failure this
            // tool cannot place keeps "could not be reached", which claims less.
            OnyxFailureType type = ExchangeExecutor.classifyType(e);
            if (type == OnyxFailureType.UNAVAILABLE && ExchangeExecutor.closedWithoutAnswer(e)) {
                return new TokenGrant(null, null, "No access token: the token endpoint closed the connection without answering.",
                        TokenFailure.ENDPOINT_FAULT);
            }
            return new TokenGrant(null, null, "No access token: the token endpoint could not be reached ("
                    + type.name().toLowerCase(java.util.Locale.ROOT) + ").", TokenFailure.ENDPOINT_FAULT);
        }
    }
}
