package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
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
public class OnyxTokenService {

    private static final Logger log = LoggerFactory.getLogger(OnyxTokenService.class);

    /** A token is replaced this long before it expires, so one is never sent in its last moments. */
    static final long REFRESH_SKEW_SECONDS = 60;

    /**
     * The answer to "what credential goes on this call".
     *
     * @param token   the access token, or null when authentication is off or no token could be obtained
     * @param failure why no token could be obtained, or null. When set, the call must not be sent
     */
    public record TokenResult(String token, String failure) {

        static TokenResult none() {
            return new TokenResult(null, null);
        }

        static TokenResult failed(String reason) {
            return new TokenResult(null, reason);
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

    /** What the token endpoint answered. */
    protected record TokenGrant(String accessToken, Long expiresInSeconds, String failure) {

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
    private Cached cached;

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
                    + "the active environment does not define. The request was not sent.");
        }
        if (!clientId.resolved() || Texts.blank(clientId.value()) || !clientSecret.resolved() || Texts.blank(clientSecret.value())) {
            return TokenResult.failed("Authentication is switched on but the client id or the client secret is not set, or is a "
                    + "placeholder the active environment does not define. The request was not sent.");
        }
        String key = gateway.epoch() + "|" + tokenUrl.value() + "|" + clientId.value();
        if (cached != null && cached.key().equals(key) && clock.instant().isBefore(cached.refreshAt())) {
            return new TokenResult(cached.token(), null);
        }
        TokenGrant grant = requestToken(new TokenRequest(tokenUrl.value(), clientId.value(), clientSecret.value(),
                scopes.resolved() ? scopes.value() : null));
        if (grant.failure() != null || Texts.blank(grant.accessToken())) {
            cached = null;
            // Outcome only. The token url, the client id and the response are not logged.
            log.warn("Access token request failed");
            return TokenResult.failed(Texts.orElse(grant.failure(), "The token endpoint answered without an access token.")
                    + " The request was not sent.");
        }
        // Without a stated lifetime the token is used for this call and not cached: guessing a
        // lifetime would mean sending an expired token at some point and reporting it as a vendor fault.
        cached = grant.expiresInSeconds() == null || grant.expiresInSeconds() <= REFRESH_SKEW_SECONDS ? null
                : new Cached(key, grant.accessToken(), clock.instant().plusSeconds(grant.expiresInSeconds() - REFRESH_SKEW_SECONDS));
        return new TokenResult(grant.accessToken(), null);
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
                return new TokenGrant(null, null, "No access token: the token endpoint answered HTTP " + raw.status() + ".");
            }
            JsonSafe.Parsed parsed = JsonSafe.parse(raw.body());
            if (!parsed.ok()) {
                return new TokenGrant(null, null, "No access token: the token endpoint answered with a body that is not JSON.");
            }
            String token = JsonSafe.presentText(parsed.node(), "access_token");
            if (token == null) {
                return new TokenGrant(null, null, "No access token: the token endpoint answered without one.");
            }
            return new TokenGrant(token, JsonSafe.longValue(parsed.node(), "expires_in"), null);
        } catch (RuntimeException e) {
            // The exception message quotes the token url, so only the type is used.
            return new TokenGrant(null, null, "No access token: the token endpoint could not be reached ("
                    + ExchangeExecutor.classifyType(e).name().toLowerCase(java.util.Locale.ROOT) + ").");
        }
    }
}
