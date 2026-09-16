package com.thehiddenbrain.interop.patientaccess.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thehiddenbrain.interop.patientaccess.auth.Pkce;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The demo authorization server's state: the single registered client, pending authorization codes and the
 * tokens it issued, all in memory with expiry (a restart forgets them, which is fine for a demo). Tokens from
 * the authorization-code flow carry the patient the tester signed in as; client-credentials and
 * backend-services tokens carry none and see every patient.
 */
@Component
@DemoEnabled
public class DemoAuthService {

    /** Always accepted, so a curl or a browser plugin can hit the demo FHIR API without a token dance. */
    public static final String STATIC_TOKEN = "demo-static-token";
    public static final String DEFAULT_SYSTEM_SCOPE = "system/*.rs";
    public static final String DEFAULT_PATIENT_SCOPE = "launch/patient openid fhirUser offline_access patient/*.rs";
    public static final long TOKEN_TTL_SECONDS = 3600;
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration REFRESH_TTL = Duration.ofHours(24);

    public record Token(String accessToken, String refreshToken, String clientId, String scope, String patientId, Instant expiresAt, String grant) {

        public boolean patientBound() {
            return patientId != null;
        }
    }

    record PendingCode(String code, String clientId, String redirectUri, String codeChallenge, String codeChallengeMethod, String scope,
                       String patientId, Instant expiresAt) {
    }

    /** An RFC 6749 token-endpoint error: {@code error} / {@code error_description} in the body with the given status. */
    public static class OAuthException extends RuntimeException {
        private final int status;
        private final String error;

        public OAuthException(int status, String error, String description) {
            super(description);
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    private final WorkbenchProperties.Demo demo;
    private final Clock clock;
    private final Map<String, Token> byAccessToken = new ConcurrentHashMap<>();
    private final Map<String, Token> byRefreshToken = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    /** Random per process: id_tokens are HS256-signed only so they parse as compact JWTs; nobody can verify them (see /jwks). */
    private final byte[] idTokenKey = new byte[32];

    public DemoAuthService(WorkbenchProperties properties, Clock clock) {
        this.demo = properties.demo();
        this.clock = clock;
        new SecureRandom().nextBytes(idTokenKey);
    }

    public String clientId() {
        return demo.clientId();
    }

    public boolean clientSecretMatches(String clientId, String secret) {
        return clientId != null && secret != null && clientId.equals(demo.clientId())
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), demo.clientSecret().getBytes(StandardCharsets.UTF_8));
    }

    public Token issueSystemToken(String clientId, String scope, String grant) {
        purge();
        Token t = new Token(Ids.next(40), null, clientId, scope == null || scope.isBlank() ? DEFAULT_SYSTEM_SCOPE : scope.trim(), null,
                clock.instant().plusSeconds(TOKEN_TTL_SECONDS), grant);
        byAccessToken.put(t.accessToken(), t);
        return t;
    }

    public String createCode(String clientId, String redirectUri, String codeChallenge, String codeChallengeMethod, String scope, String patientId) {
        purge();
        String code = Ids.next(32);
        codes.put(code, new PendingCode(code, clientId, redirectUri, codeChallenge, codeChallengeMethod,
                scope == null || scope.isBlank() ? DEFAULT_PATIENT_SCOPE : scope.trim(), patientId, clock.instant().plus(CODE_TTL)));
        return code;
    }

    /** One-time exchange of an authorization code; verifies client, redirect URI and the PKCE S256 challenge. */
    public Token redeemCode(String code, String clientId, String redirectUri, String codeVerifier) {
        purge();
        PendingCode pending = code == null ? null : codes.remove(code);
        if (pending == null) {
            throw new OAuthException(400, "invalid_grant", "authorization code is unknown, expired or already used");
        }
        if (clientId == null || !pending.clientId().equals(clientId)) {
            throw new OAuthException(400, "invalid_grant", "authorization code was issued to another client");
        }
        if (redirectUri == null || !pending.redirectUri().equals(redirectUri)) {
            throw new OAuthException(400, "invalid_grant", "redirect_uri does not match the authorization request");
        }
        if (pending.codeChallenge() != null) {
            if (codeVerifier == null || codeVerifier.isBlank()) {
                throw new OAuthException(400, "invalid_grant", "code_verifier is required (the authorization request used PKCE)");
            }
            String expected = "plain".equals(pending.codeChallengeMethod()) ? codeVerifier : Pkce.challenge(codeVerifier);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), pending.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
                throw new OAuthException(400, "invalid_grant", "code_verifier does not match the code_challenge");
            }
        }
        return issuePatientToken(pending.clientId(), pending.scope(), pending.patientId(), "authorization_code");
    }

    public Token refresh(String refreshToken, String clientId) {
        purge();
        Token previous = refreshToken == null ? null : byRefreshToken.remove(refreshToken);
        if (previous == null) {
            throw new OAuthException(400, "invalid_grant", "refresh token is unknown or expired");
        }
        if (clientId != null && !previous.clientId().equals(clientId)) {
            throw new OAuthException(400, "invalid_grant", "refresh token belongs to another client");
        }
        byAccessToken.remove(previous.accessToken());
        return issuePatientToken(previous.clientId(), previous.scope(), previous.patientId(), "refresh_token");
    }

    private Token issuePatientToken(String clientId, String scope, String patientId, String grant) {
        Token t = new Token(Ids.next(40), Ids.next(40), clientId, scope, patientId, clock.instant().plusSeconds(TOKEN_TTL_SECONDS), grant);
        byAccessToken.put(t.accessToken(), t);
        byRefreshToken.put(t.refreshToken(), t);
        return t;
    }

    /** The token behind a bearer value, if it is valid; the static token yields an unbound (system) token. */
    public Optional<Token> authenticate(String bearer) {
        if (bearer == null || bearer.isBlank()) {
            return Optional.empty();
        }
        if (STATIC_TOKEN.equals(bearer)) {
            return Optional.of(new Token(STATIC_TOKEN, null, demo.clientId(), DEFAULT_SYSTEM_SCOPE, null, Instant.MAX, "static"));
        }
        Token t = byAccessToken.get(bearer);
        if (t == null || !t.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    /** Compact HS256 id_token for the SMART flow: iss, sub, aud = client id, fhirUser = the patient's URL. */
    public String idToken(Token token, String issuer, String fhirBase) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(token.patientId())
                .audience(token.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(token.expiresAt()))
                .claim("fhirUser", fhirBase + "/Patient/" + token.patientId())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
            jwt.sign(new MACSigner(idTokenKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign the demo id_token", e);
        }
    }

    private void purge() {
        Instant now = clock.instant();
        codes.values().removeIf(c -> !c.expiresAt().isAfter(now));
        byAccessToken.values().removeIf(t -> !t.expiresAt().isAfter(now));
        byRefreshToken.values().removeIf(t -> !t.expiresAt().plus(REFRESH_TTL).isAfter(now));
    }
}
