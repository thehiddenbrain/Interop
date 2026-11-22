package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Obtains and caches the access token for calls to the service.
 *
 * <p>Three concerns are kept apart so that changing how a token is obtained touches one method:
 * {@link #requestToken} obtains a token, {@link #token(SignInSettings)} decides which sign-in
 * applies, caches the token and applies the refresh skew, and the executor applies it to a request.
 * An environment that uses private-key JWT, which is common for SMART backend services, replaces
 * {@link #requestToken} and nothing else.
 *
 * <p>Which sign-in applies (R-03): a token the tester pasted for this session (R-43), while it is
 * held, ahead of everything; else the active environment's own sign-in when it states a method;
 * else {@code onyx.auth} as before. Settings may be placeholders. {@code onyx.auth} is resolved
 * through the gateway against the active environment each time a token is needed; an environment's
 * settings arrive resolved with the call, and anything still a placeholder is resolved the same way.
 * The cache is keyed on the configuration epoch, the token url and the client id, so a token never
 * outlives the configuration it was issued for and a save signs in again.
 */
@Component
@Profile("live")
public class OnyxTokenService implements org.p32h.interop.epa.pave.service.config.HeldAccessToken {

    private static final Logger log = LoggerFactory.getLogger(OnyxTokenService.class);

    /** A token is replaced this long before it expires, so one is never sent in its last moments. */
    static final long REFRESH_SKEW_SECONDS = 60;

    /** How many issued tokens are kept at once. A lookup on onyx.auth and a call on the environment's sign-in can each hold one. */
    private static final int CACHE_ENTRIES = 8;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

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

    /** Where a token came from, which decides what a 401 to a call that carried it does. */
    public enum TokenSource {
        /** No token: authentication is off, or none could be obtained. */
        NONE,
        /** Issued by the token endpoint for client credentials: a 401 drops it and PAVE signs in again once. */
        ISSUED,
        /** A bearer token stated in the environment's settings: sent as it is, never retried. */
        CONFIGURED,
        /** A token the tester pasted for this session: a 401 drops it and is the answer. */
        PASTED
    }

    /**
     * The answer to "what credential goes on this call".
     *
     * @param token   the access token, or null when authentication is off or no token could be obtained
     * @param failure why no token could be obtained, or null. When set, the call must not be sent
     * @param cause   which kind of failure that is, or null when there is a token or authentication is off
     * @param source  where the token came from; {@link TokenSource#NONE} when there is none
     */
    public record TokenResult(String token, String failure, TokenFailure cause, TokenSource source) {

        public TokenResult {
            if (source == null) {
                source = token == null ? TokenSource.NONE : TokenSource.ISSUED;
            }
        }

        /** A result whose token, if any, was issued by the token endpoint. */
        public TokenResult(String token, String failure, TokenFailure cause) {
            this(token, failure, cause, null);
        }

        static TokenResult none() {
            return new TokenResult(null, null, null, TokenSource.NONE);
        }

        static TokenResult failed(String reason, TokenFailure cause) {
            return new TokenResult(null, reason, cause, TokenSource.NONE);
        }

        /**
         * True when a 401 to a call carrying this token is answered by signing in again and sending
         * once more (R-03): only a token PAVE asked for, since a stated or pasted one would be the
         * same token again.
         */
        public boolean signsInAgainOnRefusal() {
            return token != null && source == TokenSource.ISSUED;
        }

        /** Redacted, because it holds an access token. */
        @Override
        public String toString() {
            return "TokenResult[" + (failure == null ? "token redacted, " + source : "failed") + "]";
        }
    }

    /** What the token endpoint is asked with. */
    protected record TokenRequest(String tokenUrl, String clientId, String clientSecret, String scopes, boolean credentialsInBody) {

        /** Credentials in a Basic header, the way this tool always sent them (D-233). */
        protected TokenRequest(String tokenUrl, String clientId, String clientSecret, String scopes) {
            this(tokenUrl, clientId, clientSecret, scopes, false);
        }

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

    /** @param refreshAt when it is replaced; null when the endpoint stated no lifetime and it is not reused */
    private record Cached(String token, Instant refreshAt) {

        /** Redacted, because it holds an access token. */
        @Override
        public String toString() {
            return "Cached[redacted]";
        }
    }

    /** A token the tester pasted, held for the environment and the configuration it was pasted under. */
    private record Pasted(String token, String environmentName, long epoch) {

        @Override
        public String toString() {
            return "Pasted[redacted]";
        }
    }

    /** The sign-in the last call was given, so that the state answered without a call reads the same settings. */
    private record Seen(long epoch, SignInSettings settings) {
    }

    private final OnyxProperties onyx;
    private final OperationGateway gateway;
    private final RestClient client;
    private final Clock clock;

    /** Issued tokens by epoch, token url, client id and where the settings came from; guarded by this. */
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /** The last failure to sign in, by the same key, for the state. Holds a sentence, never a value. Guarded by this. */
    private final Map<String, String> failures = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    // Volatile so that addTo reads them without the lock a token request holds while it waits for the token endpoint.
    private volatile Cached latest;
    private volatile Pasted pasted;
    private volatile Seen seen;

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
     * Adds the token held in the cache, the last one issued and a pasted one, whatever their age,
     * so that a service repeating one in a notification or in any answer is shown with it masked.
     */
    @Override
    public void addTo(org.p32h.interop.epa.pave.model.SentSecrets.Builder secrets) {
        // Not synchronized: every answer is masked with the token held, and an answer to a call
        // that carried no token must not wait for another thread's token request to end.
        Cached held = latest;
        if (held != null) {
            secrets.accessToken(held.token());
        }
        secrets.accessToken(lastIssued);
        Pasted given = pasted;
        if (given != null) {
            secrets.accessToken(given.token());
        }
    }

    // ------------------------------------------------------------------ the pasted token (R-43)

    /**
     * Holds a token the tester pasted, for the active environment, until a 401, a restart or a
     * change of the configuration. Moves no epoch: nothing stored changes. The caller has checked
     * that the token is not blank and that the environment named is the active one.
     */
    public void paste(String token, String environmentName) {
        pasted = new Pasted(token.strip(), environmentName, gateway.epoch());
        // Outcome only: the token is never logged, nor its length.
        log.info("A token was pasted for this session");
    }

    /** Lets go of the pasted token, if one is held. The environment's own sign-in applies again. */
    public void forgetPasted() {
        pasted = null;
    }

    /** True while a pasted token is held under the configuration in force. */
    public boolean pastedHeld() {
        return currentPasted() != null;
    }

    /** The pasted token when it was pasted under the configuration in force; a change of the configuration drops it. */
    private Pasted currentPasted() {
        Pasted given = pasted;
        if (given != null && given.epoch() != gateway.epoch()) {
            // Dropped here rather than on the change itself: the epoch is the one place every change moves.
            pasted = null;
            return null;
        }
        return given;
    }

    // ------------------------------------------------------------------ which token goes on a call

    /**
     * The token for a call sent to one of the environment's own addresses: a pasted token while it
     * is held, else the token {@link #token(SignInSettings)} gives. Never throws.
     *
     * @param signIn the call's sign-in ({@code PreparedCall.signIn()}); null when the environment states none
     */
    public TokenResult tokenFor(SignInSettings signIn) {
        seen = new Seen(gateway.epoch(), signIn);
        Pasted given = currentPasted();
        if (given != null) {
            return new TokenResult(given.token(), null, null, TokenSource.PASTED);
        }
        return token(signIn);
    }

    /**
     * Tells the service that a call carrying this token was answered with HTTP 401. A pasted token
     * is let go of (R-43); an issued one is dropped from the cache, so the next token is asked for.
     * A stated bearer token is kept: it is the tester's setting, and the same token would come back.
     */
    public synchronized void refused(TokenResult used) {
        if (used == null || used.token() == null) {
            return;
        }
        if (used.source() == TokenSource.PASTED) {
            Pasted given = pasted;
            if (given != null && given.token().equals(used.token())) {
                pasted = null;
            }
            return;
        }
        if (used.source() == TokenSource.ISSUED) {
            cache.values().removeIf(entry -> entry.token().equals(used.token()));
            Cached held = latest;
            if (held != null && held.token().equals(used.token())) {
                latest = null;
            }
        }
    }

    /** The token to send under {@code onyx.auth}, from the cache when one is still good. Never throws. */
    public synchronized TokenResult token() {
        OnyxProperties.Auth auth = onyx.auth();
        if (!auth.enabled()) {
            return TokenResult.none();
        }
        Prepared prepared = fromApplication(auth);
        if (prepared.failure() != null) {
            return prepared.failure();
        }
        return grant(prepared.key(), prepared.request());
    }

    /**
     * The token for this sign-in: {@code onyx.auth} when it is null (the environment states no
     * method), nothing for {@code NONE}, the stated token for {@code BEARER_TOKEN} without asking
     * anyone, and for {@code CLIENT_CREDENTIALS} a token from the cache or from the token url, with
     * the client authenticated as the settings say. Never throws, never logs a value.
     */
    public synchronized TokenResult token(SignInSettings settings) {
        if (settings == null) {
            return token();
        }
        switch (settings.method()) {
            case NONE:
                return TokenResult.none();
            case BEARER_TOKEN: {
                String token = setting(settings.bearerToken());
                if (token == null) {
                    return TokenResult.failed("This environment signs in with a bearer token, but none is stored, or it names a placeholder "
                            + "the active environment does not define. Set it under Sign-in in Configuration > Environments. The request "
                            + "was not sent.", TokenFailure.SETTING_MISSING);
                }
                return new TokenResult(token, null, null, TokenSource.CONFIGURED);
            }
            default: {
                Prepared prepared = fromEnvironment(settings);
                if (prepared.failure() != null) {
                    return prepared.failure();
                }
                return grant(prepared.key(), prepared.request());
            }
        }
    }

    /** A token request ready to send, under its cache key, or the reason none can be. */
    private record Prepared(String key, TokenRequest request, TokenResult failure) {
    }

    private Prepared fromApplication(OnyxProperties.Auth auth) {
        ResolvedText tokenUrl = gateway.resolve(auth.tokenUrl(), Map.of());
        ResolvedText clientId = gateway.resolve(auth.clientId(), Map.of());
        ResolvedText clientSecret = gateway.resolve(auth.clientSecret(), Map.of());
        ResolvedText scopes = gateway.resolve(auth.scopes(), Map.of());
        if (Texts.blank(auth.tokenUrl()) || !tokenUrl.resolved() || Texts.blank(tokenUrl.value())) {
            return refused("Authentication is switched on but onyx.auth.token-url is not set, or it contains a placeholder "
                    + "the active environment does not define. The request was not sent.");
        }
        // A url that is not an absolute address is the tester's setting, found before anything is sent. It is named by the
        // setting, never by its value, which an environment can fill with something that should not be repeated.
        if (!absoluteHttpAddress(tokenUrl.value())) {
            // Said as what an address this tool can use is, so the sentence is true of a port no connection can use as well.
            return refused(TOKEN_URL_NOT_USABLE);
        }
        String production = gateway.tokenUrlRefusal(tokenUrl.value());
        if (production != null) {
            return refused(production);
        }
        boolean noId = !clientId.resolved() || Texts.blank(clientId.value());
        boolean noSecret = !clientSecret.resolved() || Texts.blank(clientSecret.value());
        if (noId || noSecret) {
            // The setting is named, as Configuration > Advanced > In force now names it, so the tester knows which one to give. Only the
            // names of the settings are said, never a value.
            String which = noId && noSecret ? "onyx.auth.client-id and onyx.auth.client-secret are" : noId ? "onyx.auth.client-id is"
                    : "onyx.auth.client-secret is";
            return refused("Authentication is switched on but " + which + " not set, or a placeholder the active environment "
                    + "does not define. Configuration > Advanced > In force now says where it is read from. The request was not sent.");
        }
        String key = "application|" + gateway.epoch() + "|" + tokenUrl.value() + "|" + clientId.value();
        return new Prepared(key, new TokenRequest(tokenUrl.value(), clientId.value(), clientSecret.value(),
                scopes.resolved() ? scopes.value() : null), null);
    }

    private Prepared fromEnvironment(SignInSettings settings) {
        String tokenUrl = setting(settings.tokenUrl());
        if (tokenUrl == null) {
            return refused("This environment signs in with client credentials, but its Token url is not set, or it names a placeholder "
                    + "the active environment does not define. Set it under Sign-in in Configuration > Environments. The request was "
                    + "not sent.");
        }
        if (!absoluteHttpAddress(tokenUrl)) {
            return refused(ENVIRONMENT_TOKEN_URL_NOT_USABLE);
        }
        // Asked at the moment of sending, whatever was stored: an older store or a value changed
        // underneath can point the sign-in at a production call's address (I-28).
        String production = gateway.tokenUrlRefusal(tokenUrl);
        if (production != null) {
            return refused(production);
        }
        String clientId = setting(settings.clientId());
        String clientSecret = setting(settings.clientSecret());
        if (clientId == null || clientSecret == null) {
            // Named by the field the tester fills in, never by a value.
            String which = clientId == null && clientSecret == null ? "its Client id and Client secret are" : clientId == null
                    ? "its Client id is" : "its Client secret is";
            return refused("This environment signs in with client credentials, but " + which + " not set, or a placeholder the active "
                    + "environment does not define. Set it under Sign-in in Configuration > Environments. The request was not sent.");
        }
        boolean inBody = settings.clientAuthentication() == SignInSettings.ClientAuthentication.BODY;
        String key = "environment|" + gateway.epoch() + "|" + tokenUrl + "|" + clientId + "|" + (inBody ? "body" : "basic");
        return new Prepared(key, new TokenRequest(tokenUrl, clientId, clientSecret, setting(settings.scope()), inBody), null);
    }

    private Prepared refused(String sentence) {
        return new Prepared(null, null, TokenResult.failed(sentence, TokenFailure.SETTING_MISSING));
    }

    /**
     * A setting's value to use, or null when it is blank or still names a placeholder no layer
     * defines. A value that still holds a placeholder is resolved against the configuration in
     * force; one that cannot be is never sent as written, since a placeholder sent as a secret
     * would be refused far away with nothing to show why.
     */
    private String setting(String value) {
        if (Texts.blank(value)) {
            return null;
        }
        if (!value.contains("{{")) {
            return value.strip();
        }
        ResolvedText resolved = gateway.resolve(value, Map.of());
        if (!resolved.resolved() || Texts.blank(resolved.value()) || resolved.value().contains("{{")) {
            return null;
        }
        return resolved.value().strip();
    }

    private TokenResult grant(String key, TokenRequest request) {
        Cached hit = cache.get(key);
        if (hit != null && hit.refreshAt() != null && clock.instant().isBefore(hit.refreshAt())) {
            return new TokenResult(hit.token(), null, null, TokenSource.ISSUED);
        }
        TokenGrant grant = requestToken(request);
        if (grant.failure() != null || Texts.blank(grant.accessToken())) {
            cache.remove(key);
            // Outcome only. The token url, the client id and the response are not logged.
            log.warn("Access token request failed");
            // A grant with neither a token nor a failure is the endpoint's doing, never the tester's settings.
            String sentence = Texts.orElse(grant.failure(), "The token endpoint answered without an access token.") + " The request was not sent.";
            failures.put(key, sentence);
            return TokenResult.failed(sentence, grant.cause() == null ? TokenFailure.ENDPOINT_FAULT : grant.cause());
        }
        failures.remove(key);
        lastIssued = grant.accessToken();
        // Without a stated lifetime the token is used for this call and not reused: guessing a
        // lifetime would mean sending an expired token at some point and reporting it as a vendor fault.
        Instant refreshAt = grant.expiresInSeconds() == null || grant.expiresInSeconds() <= REFRESH_SKEW_SECONDS ? null
                : clock.instant().plusSeconds(grant.expiresInSeconds() - REFRESH_SKEW_SECONDS);
        Cached issued = new Cached(grant.accessToken(), refreshAt);
        cache.put(key, issued);
        latest = issued;
        return new TokenResult(grant.accessToken(), null, null, TokenSource.ISSUED);
    }

    // ------------------------------------------------------------------ the state, in words (plan 7.5)

    /**
     * How PAVE signs in now, for {@code /api/mode} and Access token. Reads the sign-in the last call
     * under the configuration in force was given; before any such call, {@code onyx.auth}. A caller
     * that knows the active environment's sign-in passes it to {@link #state(SignInSettings)}.
     * Asks no token endpoint.
     */
    public SignInState state() {
        Seen last = seen;
        return state(last != null && last.epoch() == gateway.epoch() ? last.settings() : null);
    }

    /**
     * How PAVE signs in now under this sign-in (null: the environment states none, onyx.auth
     * applies). Asks no token endpoint and names no value.
     */
    public synchronized SignInState state(SignInSettings settings) {
        Pasted given = currentPasted();
        if (given != null) {
            return SignInState.of(SignInState.Code.PASTED, "A token pasted on Configuration > Access token, held in memory for "
                    + Texts.orElse(Names.shown(given.environmentName()), "the active environment")
                    + " until a 401, a restart or a change of the configuration.");
        }
        if (settings == null) {
            OnyxProperties.Auth auth = onyx.auth();
            if (!auth.enabled()) {
                return SignInState.of(SignInState.Code.NONE, "This environment states no sign-in, and onyx.auth in application.yml is "
                        + "switched off, so calls go without a token.");
            }
            return clientCredentialsState(fromApplication(auth), "Client credentials, from onyx.auth in application.yml");
        }
        switch (settings.method()) {
            case NONE:
                return SignInState.of(SignInState.Code.NONE, "This environment's Sign-in says None, so calls go without a token.");
            case BEARER_TOKEN: {
                TokenResult token = token(settings);
                if (token.failure() != null) {
                    return SignInState.of(SignInState.Code.FAILED, token.failure());
                }
                return SignInState.of(SignInState.Code.BEARER, "A bearer token stated in this environment's Sign-in, sent as it is and "
                        + "never renewed.");
            }
            default:
                return clientCredentialsState(fromEnvironment(settings), "Client credentials, from this environment's Sign-in");
        }
    }

    private SignInState clientCredentialsState(Prepared prepared, String method) {
        if (prepared.failure() != null) {
            return SignInState.of(SignInState.Code.FAILED, prepared.failure().failure());
        }
        String failure = failures.get(prepared.key());
        if (failure != null) {
            return SignInState.of(SignInState.Code.FAILED, failure);
        }
        Cached hit = cache.get(prepared.key());
        if (hit == null) {
            return SignInState.of(SignInState.Code.FIRST_CALL, method + ". No token yet: PAVE asks for one on the first call.");
        }
        if (hit.refreshAt() == null) {
            return SignInState.of(SignInState.Code.SIGNED_IN, method + ". The token endpoint stated no lifetime, so PAVE asks for a "
                    + "new token on every call.");
        }
        if (!clock.instant().isBefore(hit.refreshAt())) {
            return SignInState.of(SignInState.Code.FIRST_CALL, method + ". The last token is due for renewal: PAVE asks for a new one "
                    + "on the next call.");
        }
        Instant expires = hit.refreshAt().plusSeconds(REFRESH_SKEW_SECONDS);
        return SignInState.of(SignInState.Code.SIGNED_IN, method + ". The token is good until " + TIME.format(expires)
                + " UTC and is renewed before then.");
    }

    // ------------------------------------------------------------------ the token endpoint

    /** The refusal of a token url this tool cannot use. It names the setting and never its value. */
    static final String TOKEN_URL_NOT_USABLE = "Authentication is switched on but onyx.auth.token-url, as configured, is not an address "
            + "this tool can use: it must be an absolute http or https address with a host, and a port from 1 to 65535 if it names one. "
            + "The request was not sent.";

    /** The same refusal for a token url an environment's Sign-in states. */
    static final String ENVIRONMENT_TOKEN_URL_NOT_USABLE = "This environment's Token url, as set under Sign-in in Configuration > "
            + "Environments, is not an address this tool can use: it must be an absolute http or https address with a host, and a port "
            + "from 1 to 65535 if it names one. The request was not sent.";

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
     * <p>This implementation is the OAuth2 client credentials grant, with the client authenticated
     * by HTTP Basic or, when the environment's settings say so, by client_id and client_secret in
     * the form body. A signed JWT assertion would replace this method.
     */
    protected TokenGrant requestToken(TokenRequest request) {
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (request.credentialsInBody()) {
            form.append("&client_id=").append(java.net.URLEncoder.encode(request.clientId(), StandardCharsets.UTF_8))
                    .append("&client_secret=").append(java.net.URLEncoder.encode(request.clientSecret(), StandardCharsets.UTF_8));
        }
        if (Texts.present(request.scopes())) {
            form.append("&scope=").append(java.net.URLEncoder.encode(request.scopes().strip(), StandardCharsets.UTF_8));
        }
        record Raw(int status, String body) {
        }
        try {
            RestClient.RequestBodySpec post = client.post().uri(java.net.URI.create(request.tokenUrl().strip()));
            if (!request.credentialsInBody()) {
                String basic = Base64.getEncoder().encodeToString((request.clientId() + ":" + request.clientSecret())
                        .getBytes(StandardCharsets.UTF_8));
                post = post.header("Authorization", "Basic " + basic);
            }
            Raw raw = post.contentType(MediaType.APPLICATION_FORM_URLENCODED)
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
