package com.thehiddenbrain.interop.patientaccess.vendor;

import com.thehiddenbrain.interop.patientaccess.environment.HeaderEntry;
import com.thehiddenbrain.interop.patientaccess.environment.IdentifierSystem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of {@code vendors.yaml}: how a vendor platform lays out its Patient Access API, as a template
 * the Environments form fills in from a tenant host. Values may contain {@code {host}}. The {@code auth}
 * and {@code fhir} maps carry environment fields by name so a new field needs no code here.
 */
public record VendorPreset(
        String key,
        String name,
        String docs,
        String hostHint,
        Map<String, String> tierPatterns,
        String fhirBaseUrl,
        Map<String, String> igBaseUrls,
        Map<String, Object> auth,
        Map<String, Object> fhir,
        List<IdentifierSystem> identifierSystems,
        List<HeaderEntry> headers,
        String note) {

    public VendorPreset {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a vendor preset needs a key");
        }
        if (!key.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("vendor key '" + key + "' must be lower-case letters, digits and dashes");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vendor preset '" + key + "' needs a name");
        }
        tierPatterns = tierPatterns == null ? Map.of() : new LinkedHashMap<>(tierPatterns);
        igBaseUrls = igBaseUrls == null ? Map.of() : new LinkedHashMap<>(igBaseUrls);
        auth = auth == null ? Map.of() : new LinkedHashMap<>(auth);
        fhir = fhir == null ? Map.of() : new LinkedHashMap<>(fhir);
        identifierSystems = identifierSystems == null ? List.of() : List.copyOf(identifierSystems);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }
}
