package com.thehiddenbrain.interop.patientaccess.auth;

import com.thehiddenbrain.interop.patientaccess.secrets.Secret;

import java.time.Instant;
import java.util.Map;

/**
 * A token obtained for an environment, stored encrypted. {@code context} keeps the non-secret parts
 * of the token response (scope, patient, token_type, expires_in, id_token claims summary).
 */
public record AccessToken(
        String environmentId,
        Secret accessToken,
        Secret refreshToken,
        Secret idToken,
        String tokenType,
        String scope,
        String patient,
        Instant obtainedAt,
        Instant expiresAt,
        String source,
        Map<String, Object> context) {

    public boolean expired(Instant now, long skewSeconds) {
        return expiresAt != null && !now.plusSeconds(skewSeconds).isBefore(expiresAt);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && refreshToken.isSet();
    }
}
