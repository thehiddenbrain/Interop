package com.thehiddenbrain.interop.patientaccess.environment;

import java.time.Instant;
import java.util.List;

/**
 * A vendor FHIR environment (e.g. Onyx SAFHIR UAT or Prod for one payer) as stored on disk. Secret
 * material inside {@link AuthConfig} and {@link HeaderEntry} is encrypted; the API never returns it.
 */
public record Environment(
        String id,
        String name,
        String vendor,
        EnvironmentTier tier,
        String fhirBaseUrl,
        AuthConfig auth,
        List<HeaderEntry> headers,
        List<IdentifierSystem> identifierSystems,
        FhirOptions fhir,
        List<String> implementationGuides,
        String notes,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Environment {
        auth = auth == null ? AuthConfig.none() : auth;
        headers = headers == null ? List.of() : List.copyOf(headers);
        identifierSystems = identifierSystems == null ? List.of() : List.copyOf(identifierSystems);
        fhir = fhir == null ? FhirOptions.defaults() : fhir;
        implementationGuides = implementationGuides == null ? List.of() : List.copyOf(implementationGuides);
    }

    /** Base URL without a trailing slash. */
    public String baseUrl() {
        String url = fhirBaseUrl == null ? "" : fhirBaseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public Environment withVersion(long newVersion, Instant now) {
        return new Environment(id, name, vendor, tier, fhirBaseUrl, auth, headers, identifierSystems, fhir,
                implementationGuides, notes, enabled, newVersion, createdAt, now);
    }
}
