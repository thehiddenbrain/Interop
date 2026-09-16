package com.thehiddenbrain.interop.patientaccess.environment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, String> igBaseUrls,
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
        igBaseUrls = igBaseUrls == null ? Map.of() : Map.copyOf(igBaseUrls);
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

    /** Base URL for an IG key (c4bb, pdex, usdf, plannet) when the vendor serves each IG separately, else the default base. */
    public String baseUrlFor(String igKey) {
        String url = igKey == null ? null : igBaseUrls.get(igKey);
        if (url == null || url.isBlank()) {
            return baseUrl();
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** The default base plus every IG-specific base (targets the gateway may call). */
    public List<String> allBaseUrls() {
        List<String> out = new java.util.ArrayList<>();
        out.add(baseUrl());
        for (String key : igBaseUrls.keySet()) {
            String u = baseUrlFor(key);
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    public Environment withVersion(long newVersion, Instant now) {
        return new Environment(id, name, vendor, tier, fhirBaseUrl, auth, headers, identifierSystems, fhir, igBaseUrls,
                implementationGuides, notes, enabled, newVersion, createdAt, now);
    }
}
