package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.secrets.SecretView;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** An environment as returned by the API: same shape as the input, secrets masked. */
public record EnvironmentView(
        String id,
        String name,
        String vendor,
        EnvironmentTier tier,
        String fhirBaseUrl,
        AuthView auth,
        List<HeaderView> headers,
        List<IdentifierSystem> identifierSystems,
        FhirOptions fhir,
        List<String> implementationGuides,
        String notes,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record AuthView(
            AuthMode mode,
            boolean discoverEndpoints,
            String authorizationEndpoint,
            String tokenEndpoint,
            String clientId,
            SecretView clientSecret,
            ClientAuthMethod clientAuthMethod,
            String scopes,
            String audience,
            SecretView staticToken,
            SecretView privateKeyJwk,
            String signingAlgorithm,
            String redirectUri,
            boolean usePkce,
            Map<String, String> extraTokenParams,
            Map<String, String> extraAuthorizeParams) {
    }

    public record HeaderView(String name, String value, boolean secret, SecretView secretValue) {
    }
}
