package com.thehiddenbrain.interop.patientaccess.environment;

/** HTTP / FHIR behaviour of an environment. Nulls mean "use the application default". */
public record FhirOptions(
        Integer pageSize,
        Integer maxPages,
        String acceptHeader,
        boolean sendCountParam,
        boolean trustAllCertificates,
        boolean allowNextLinkHostMismatch,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        boolean preferHandlingLenient) {

    public static FhirOptions defaults() {
        return new FhirOptions(null, null, "application/fhir+json", true, false, false, null, null, false);
    }

    public String acceptHeaderOrDefault() {
        return acceptHeader == null || acceptHeader.isBlank() ? "application/fhir+json" : acceptHeader;
    }
}
