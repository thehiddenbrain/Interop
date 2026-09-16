package com.thehiddenbrain.interop.patientaccess.auth;

import java.util.List;

/** Resolved authorization server endpoints for an environment (configured, or discovered from SMART metadata). */
public record OAuthEndpoints(String authorizationEndpoint, String tokenEndpoint, String issuer, String jwksUri,
                             String registrationEndpoint, String introspectionEndpoint, String revocationEndpoint,
                             List<String> capabilities, List<String> scopesSupported, List<String> codeChallengeMethods,
                             List<String> grantTypesSupported, List<String> tokenEndpointAuthMethods, String source) {
}
