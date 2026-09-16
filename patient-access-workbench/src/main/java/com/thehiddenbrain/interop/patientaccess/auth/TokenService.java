package com.thehiddenbrain.interop.patientaccess.auth;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.ClientAuthMethod;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpExecutor;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hands out the bearer token for an environment according to its auth mode: static token, client
 * credentials, backend services (JWT assertion) or the token obtained through the SMART
 * authorization-code flow (refreshed with the refresh token when it expires).
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    /** Tokens are renewed this many seconds before they expire. */
    static final long SKEW_SECONDS = 60;

    private final TokenStore store;
    private final SecretCrypto crypto;
    private final HttpExecutor http;
    private final SmartDiscoveryService discovery;
    private final EnvironmentService environments;
    private final Clock clock;

    public TokenService(TokenStore store, SecretCrypto crypto, HttpExecutor http, SmartDiscoveryService discovery,
                        EnvironmentService environments, Clock clock) {
        this.store = store;
        this.crypto = crypto;
        this.http = http;
        this.discovery = discovery;
        this.environments = environments;
        this.clock = clock;
        environments.addListener(new EnvironmentService.Listener() {
            @Override
            public void authChanged(Environment before, Environment after) {
                if (store.find(after.id()).isPresent()) {
                    log.info("environment '{}' changed its auth settings or base URL; forgetting its token", after.name());
                    store.remove(after.id());
                }
                discovery.evict(after.id());
            }

            @Override
            public void deleted(String environmentId) {
                store.remove(environmentId);
                discovery.evict(environmentId);
            }
        });
    }

    /** The token to send, obtaining or refreshing it when needed; empty for AuthMode.NONE. */
    public Optional<String> bearerFor(Environment env) {
        AuthConfig auth = env.auth();
        switch (auth.mode()) {
            case NONE:
                return Optional.empty();
            case STATIC_TOKEN:
                String stored = crypto.reveal(auth.staticToken());
                if (stored == null || stored.isBlank()) {
                    throw new WorkbenchException(ErrorCode.AUTH_FAILED, "environment '" + env.name() + "' uses STATIC_TOKEN but none is set");
                }
                return Optional.of(stored.trim());
            default:
                return Optional.of(crypto.reveal(current(env).accessToken()));
        }
    }

    /** Obtains a token, reading the previous one inside the lock (a rotated refresh token is never reused). */
    public synchronized AccessToken obtain(Environment env, String correlationId) {
        return obtain(env, usable(env.id()).orElse(null), correlationId);
    }

    /** The stored token, unless it cannot be decrypted any more (master key changed): then it is dropped. */
    private Optional<AccessToken> usable(String environmentId) {
        Optional<AccessToken> stored = store.find(environmentId);
        if (stored.isPresent()) {
            try {
                crypto.reveal(stored.get().accessToken());
                if (stored.get().hasRefreshToken()) {
                    crypto.reveal(stored.get().refreshToken());
                }
            } catch (WorkbenchException e) {
                log.warn("stored token of environment {} cannot be decrypted (master key changed?); forgetting it", environmentId);
                store.remove(environmentId);
                return Optional.empty();
            }
        }
        return stored;
    }

    /** Valid cached token, or a freshly obtained one. */
    public synchronized AccessToken current(Environment env) {
        Optional<AccessToken> cached = usable(env.id());
        Instant now = clock.instant();
        if (cached.isPresent() && !cached.get().expired(now, SKEW_SECONDS)) {
            return cached.get();
        }
        return obtain(env, cached.orElse(null), null);
    }

    /** Forces a new token (refresh if possible for the authorization-code flow). */
    public synchronized AccessToken obtain(Environment env, AccessToken previous, String correlationId) {
        AuthConfig auth = env.auth();
        AccessToken token = switch (auth.mode()) {
            case CLIENT_CREDENTIALS -> clientCredentials(env, correlationId);
            case BACKEND_SERVICES -> backendServices(env, correlationId);
            case SMART_AUTHORIZATION_CODE -> refreshOrFail(env, previous, correlationId);
            case STATIC_TOKEN, NONE -> throw new WorkbenchException(ErrorCode.NOT_SUPPORTED,
                    "environment '" + env.name() + "' uses " + auth.mode() + "; there is no token to obtain");
        };
        store.put(token);
        log.info("token obtained for environment '{}' via {} (expires {})", env.name(), token.source(), token.expiresAt());
        return token;
    }

    public TokenStatus status(Environment env) {
        Optional<AccessToken> t = usable(env.id());
        Instant now = clock.instant();
        if (env.auth().mode() == AuthMode.STATIC_TOKEN) {
            boolean set = env.auth().staticToken() != null && env.auth().staticToken().isSet();
            return new TokenStatus(env.id(), env.auth().mode(), set, false, null, null, "Bearer", null, null, false, "static",
                    Map.of(), set ? crypto.view(env.auth().staticToken()).hint() : null);
        }
        if (t.isEmpty()) {
            return new TokenStatus(env.id(), env.auth().mode(), false, false, null, null, null, null, null, false, null, Map.of(), null);
        }
        AccessToken a = t.get();
        return new TokenStatus(env.id(), env.auth().mode(), true, a.expired(now, 0), a.obtainedAt(), a.expiresAt(), a.tokenType(),
                a.scope(), a.patient(), a.hasRefreshToken(), a.source(), a.context(), crypto.view(a.accessToken()).hint());
    }

    /** Stores a token pasted by the tester for this environment (any mode). */
    public synchronized AccessToken storeManual(Environment env, String accessToken, Long expiresInSeconds, String patient) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "accessToken is required");
        }
        Instant now = clock.instant();
        Instant expires = expiresInSeconds == null ? now.plusSeconds(3600) : now.plusSeconds(expiresInSeconds);
        AccessToken token = new AccessToken(env.id(), crypto.seal(accessToken.trim()), null, null, "Bearer", null, patient, now, expires,
                "manual", new LinkedHashMap<>(TokenResponseParser.accessTokenClaims(accessToken.trim())));
        store.put(token);
        return token;
    }

    public synchronized void store(AccessToken token) {
        store.put(token);
    }

    public synchronized void forget(String environmentId) {
        store.remove(environmentId);
    }

    public Optional<AccessToken> stored(String environmentId) {
        return store.find(environmentId);
    }

    // ------------------------------------------------------------------ flows

    private AccessToken clientCredentials(Environment env, String correlationId) {
        AuthConfig auth = env.auth();
        String tokenEndpoint = tokenEndpoint(env, correlationId);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (auth.scopes() != null && !auth.scopes().isBlank()) {
            form.put("scope", auth.scopes().trim());
        }
        if (auth.audience() != null && !auth.audience().isBlank()) {
            form.put("audience", auth.audience().trim());
        }
        Map<String, String> headers = baseHeaders(env);
        String secret = crypto.reveal(auth.clientSecret());
        if (auth.clientAuthMethod() == ClientAuthMethod.CLIENT_SECRET_POST) {
            form.put("client_id", auth.clientId());
            form.put("client_secret", secret == null ? "" : secret);
        } else {
            headers.put("Authorization", basicCredentials(auth.clientId(), secret == null ? "" : secret));
        }
        form.putAll(auth.extraTokenParams());
        HttpResult r = postForm(env, tokenEndpoint, headers, form, correlationId);
        return TokenResponseParser.parse(env.id(), r, crypto, clock.instant(), "client_credentials", null);
    }

    private AccessToken backendServices(Environment env, String correlationId) {
        AuthConfig auth = env.auth();
        String tokenEndpoint = tokenEndpoint(env, correlationId);
        String assertion = ClientAssertions.build(auth.clientId(), tokenEndpoint, crypto.reveal(auth.privateKeyJwk()),
                auth.signingAlgorithm(), clock.instant());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.put("client_assertion", assertion);
        form.put("scope", auth.scopes() == null || auth.scopes().isBlank() ? "system/*.rs" : auth.scopes().trim());
        if (auth.audience() != null && !auth.audience().isBlank()) {
            form.put("audience", auth.audience().trim());
        }
        form.putAll(auth.extraTokenParams());
        HttpResult r = postForm(env, tokenEndpoint, baseHeaders(env), form, correlationId);
        return TokenResponseParser.parse(env.id(), r, crypto, clock.instant(), "backend_services", null);
    }

    private AccessToken refreshOrFail(Environment env, AccessToken previous, String correlationId) {
        if (previous == null || !previous.hasRefreshToken()) {
            throw new WorkbenchException(ErrorCode.AUTH_FAILED, "environment '" + env.name()
                    + "' needs a SMART login: start the authorization-code flow (Auth tab) to obtain a token"
                    + (previous == null ? "" : "; the previous token expired and has no refresh token"));
        }
        AuthConfig auth = env.auth();
        String tokenEndpoint = tokenEndpoint(env, correlationId);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", crypto.reveal(previous.refreshToken()));
        if (auth.scopes() != null && !auth.scopes().isBlank()) {
            form.put("scope", auth.scopes().trim());
        }
        Map<String, String> headers = baseHeaders(env);
        addClientAuth(env, headers, form);
        form.putAll(auth.extraTokenParams());
        HttpResult r = postForm(env, tokenEndpoint, headers, form, correlationId);
        return TokenResponseParser.parse(env.id(), r, crypto, clock.instant(), "refresh_token", previous);
    }

    /** Exchanges an authorization code (used by the SMART flow). */
    AccessToken exchangeCode(Environment env, String code, String redirectUri, String codeVerifier, String correlationId) {
        AuthConfig auth = env.auth();
        String tokenEndpoint = tokenEndpoint(env, correlationId);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        if (codeVerifier != null) {
            form.put("code_verifier", codeVerifier);
        }
        Map<String, String> headers = baseHeaders(env);
        addClientAuth(env, headers, form);
        form.putAll(auth.extraTokenParams());
        HttpResult r = postForm(env, tokenEndpoint, headers, form, correlationId);
        return TokenResponseParser.parse(env.id(), r, crypto, clock.instant(), "authorization_code", null);
    }

    /** Confidential clients authenticate with their secret; public clients only send client_id. */
    private void addClientAuth(Environment env, Map<String, String> headers, Map<String, String> form) {
        AuthConfig auth = env.auth();
        String secret = crypto.reveal(auth.clientSecret());
        if (secret == null || secret.isBlank()) {
            form.put("client_id", auth.clientId());
        } else if (auth.clientAuthMethod() == ClientAuthMethod.CLIENT_SECRET_POST) {
            form.put("client_id", auth.clientId());
            form.put("client_secret", secret);
        } else {
            headers.put("Authorization", basicCredentials(auth.clientId(), secret));
        }
    }

    /** client_secret_basic: id and secret are form-url-encoded before the Base64 (RFC 6749 section 2.3.1). */
    static String basicCredentials(String clientId, String secret) {
        String pair = java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":" + java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    public String tokenEndpoint(Environment env, String correlationId) {
        OAuthEndpoints endpoints = discovery.endpointsFor(env, correlationId);
        if (endpoints.tokenEndpoint() == null || endpoints.tokenEndpoint().isBlank()) {
            throw SmartDiscoveryService.missingTokenEndpoint(env);
        }
        return endpoints.tokenEndpoint();
    }

    private Map<String, String> baseHeaders(Environment env) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.putAll(environments.resolvedHeaders(env));
        return headers;
    }

    private HttpResult postForm(Environment env, String url, Map<String, String> headers, Map<String, String> form, String correlationId) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return http.execute(env, HttpExecutor.Call.postForm(url, headers, body.toString(), "auth", correlationId,
                environments.secretHeaderNames(env)));
    }
}
