package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.secrets.Secret;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored authorization settings of an environment. Secrets are encrypted ({@link Secret}); endpoints
 * left empty are discovered from {@code /.well-known/smart-configuration} when {@code discoverEndpoints} is on.
 */
public record AuthConfig(
        AuthMode mode,
        boolean discoverEndpoints,
        String authorizationEndpoint,
        String tokenEndpoint,
        String clientId,
        Secret clientSecret,
        ClientAuthMethod clientAuthMethod,
        String scopes,
        String audience,
        Secret staticToken,
        Secret privateKeyJwk,
        String signingAlgorithm,
        String redirectUri,
        boolean usePkce,
        Map<String, String> extraTokenParams,
        Map<String, String> extraAuthorizeParams) {

    public AuthConfig {
        mode = mode == null ? AuthMode.NONE : mode;
        clientAuthMethod = clientAuthMethod == null ? ClientAuthMethod.CLIENT_SECRET_BASIC : clientAuthMethod;
        signingAlgorithm = signingAlgorithm == null || signingAlgorithm.isBlank() ? "RS384" : signingAlgorithm;
        extraTokenParams = extraTokenParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraTokenParams);
        extraAuthorizeParams = extraAuthorizeParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraAuthorizeParams);
    }

    public static AuthConfig none() {
        return new AuthConfig(AuthMode.NONE, true, null, null, null, null, null, null, null, null, null, null, null, true, null, null);
    }
}
