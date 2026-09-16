package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.secrets.Secret;

import java.util.List;
import java.util.Map;

/**
 * What the API accepts when creating or updating an environment. Secret fields are plain strings
 * here: {@code null} keeps the stored value, {@code ""} clears it, anything else replaces it.
 */
public record EnvironmentInput(
        String name,
        String vendor,
        EnvironmentTier tier,
        String fhirBaseUrl,
        AuthInput auth,
        List<HeaderInput> headers,
        List<IdentifierSystem> identifierSystems,
        FhirOptions fhir,
        List<String> implementationGuides,
        String notes,
        Boolean enabled,
        Long version) {

    public record AuthInput(
            AuthMode mode,
            Boolean discoverEndpoints,
            String authorizationEndpoint,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            ClientAuthMethod clientAuthMethod,
            String scopes,
            String audience,
            String staticToken,
            String privateKeyJwk,
            String signingAlgorithm,
            String redirectUri,
            Boolean usePkce,
            Map<String, String> extraTokenParams,
            Map<String, String> extraAuthorizeParams) {
    }

    /** {@code value} is the plain header value; for a secret header {@code null} keeps the stored value. */
    public record HeaderInput(String name, String value, Boolean secret) {
    }

    /** Helper used by the service: stored secret, replaced or cleared according to the input rule. */
    static Secret mergeSecret(Secret current, String input, java.util.function.Function<String, Secret> seal) {
        if (input == null) {
            return current;
        }
        if (input.isEmpty()) {
            return null;
        }
        return seal.apply(input);
    }
}
