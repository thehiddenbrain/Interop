package com.thehiddenbrain.interop.patientaccess.auth;

import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;

import java.time.Instant;
import java.util.Map;

/** What the API tells the UI about the current token of an environment (never the token itself). */
public record TokenStatus(String environmentId, AuthMode mode, boolean present, boolean expired, Instant obtainedAt, Instant expiresAt,
                          String tokenType, String scope, String patient, boolean refreshable, String source, Map<String, Object> context,
                          String tokenHint) {
}
