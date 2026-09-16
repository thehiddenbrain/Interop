package com.thehiddenbrain.interop.patientaccess.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWTParser;
import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns a token endpoint response into an {@link AccessToken}; keeps non-secret context for the UI. */
final class TokenResponseParser {

    private static final long DEFAULT_LIFETIME_SECONDS = 300;

    private TokenResponseParser() {
    }

    static AccessToken parse(String environmentId, HttpResult r, SecretCrypto crypto, Instant now, String source, AccessToken previous) {
        JsonNode json = r.json();
        if (!r.ok() || json == null || !json.hasNonNull("access_token")) {
            String detail = json != null && json.hasNonNull("error")
                    ? json.get("error").asText() + (json.hasNonNull("error_description") ? ": " + json.get("error_description").asText() : "")
                    : r.errorSummary();
            throw new WorkbenchException(ErrorCode.AUTH_FAILED, "token endpoint " + r.url() + " answered " + r.status() + ": " + detail,
                    List.of(), new ApiError.Upstream(r.status(), r.url(), json != null ? "(token response redacted)" : r.errorSummary(), r.requestId()), null);
        }
        String access = json.get("access_token").asText();
        String refresh = json.hasNonNull("refresh_token") ? json.get("refresh_token").asText()
                : previous != null && previous.hasRefreshToken() ? crypto.reveal(previous.refreshToken()) : null;
        String idToken = json.hasNonNull("id_token") ? json.get("id_token").asText() : null;
        long lifetime = json.hasNonNull("expires_in") ? json.get("expires_in").asLong(DEFAULT_LIFETIME_SECONDS) : DEFAULT_LIFETIME_SECONDS;
        Map<String, Object> context = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = json.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String k = f.getKey();
            if (k.equals("access_token") || k.equals("refresh_token") || k.equals("id_token")) {
                continue;
            }
            context.put(k, f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString());
        }
        if (idToken != null) {
            context.put("id_token_claims", idTokenClaims(idToken));
        }
        context.put("access_token_claims", accessTokenClaims(access));
        return new AccessToken(environmentId, crypto.seal(access), refresh == null ? null : crypto.seal(refresh),
                idToken == null ? null : crypto.seal(idToken), json.path("token_type").asText("Bearer"), json.path("scope").asText(null),
                json.path("patient").asText(null), now, now.plusSeconds(lifetime), source, context);
    }

    /** Standard OIDC claims of the id_token (no signature check: this is diagnostics, not authentication). */
    static Map<String, Object> idTokenClaims(String jwt) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var claims = JWTParser.parse(jwt).getJWTClaimsSet();
            out.put("iss", claims.getIssuer());
            out.put("sub", claims.getSubject());
            out.put("aud", claims.getAudience());
            out.put("exp", claims.getExpirationTime());
            out.put("iat", claims.getIssueTime());
            Object fhirUser = claims.getClaim("fhirUser");
            if (fhirUser != null) {
                out.put("fhirUser", fhirUser);
            }
        } catch (Exception e) {
            out.put("error", "id_token is not a parseable JWT: " + e.getMessage());
        }
        return out;
    }

    /** If the access token is a JWT, its non-secret claims help debugging scope/audience problems. */
    static Map<String, Object> accessTokenClaims(String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (token == null || token.chars().filter(c -> c == '.').count() != 2) {
            out.put("format", "opaque");
            return out;
        }
        try {
            var claims = JWTParser.parse(token).getJWTClaimsSet();
            out.put("format", "jwt");
            out.put("iss", claims.getIssuer());
            out.put("sub", claims.getSubject());
            out.put("aud", claims.getAudience());
            out.put("exp", claims.getExpirationTime());
            Object scope = claims.getClaim("scope");
            if (scope != null) {
                out.put("scope", scope);
            }
            Object scp = claims.getClaim("scp");
            if (scp != null) {
                out.put("scp", scp);
            }
        } catch (Exception e) {
            out.put("format", "opaque");
        }
        return out;
    }
}
