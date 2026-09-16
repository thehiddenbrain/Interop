package com.thehiddenbrain.interop.patientaccess.fhir;

import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Set;

/**
 * Which implementation-guide base URL a request belongs to. Some vendors (Onyx SAFHIR: {@code
 * /v1/api/carin-bb}, {@code /v1/api/pdex}, {@code /v1/api/formulary}, {@code /v1/api/provider-directory})
 * serve each IG under its own base with its own CapabilityStatement; an environment can map the IG keys
 * to those bases and every request is routed here. Environments with a single base ignore this.
 */
public final class IgRouting {

    public static final String C4BB = "c4bb";
    public static final String PDEX = "pdex";
    public static final String USDF = "usdf";
    public static final String PLANNET = "plannet";
    public static final List<String> KEYS = List.of(C4BB, PDEX, USDF, PLANNET);

    private static final Set<String> C4BB_TYPES = Set.of("Patient", "Coverage", "Organization", "Practitioner", "RelatedPerson");
    private static final Set<String> USDF_TYPES = Set.of("InsurancePlan", "Basic", "MedicationKnowledge");
    private static final Set<String> PLANNET_TYPES = Set.of("Location", "PractitionerRole", "HealthcareService", "Endpoint", "OrganizationAffiliation", "Network");

    private IgRouting() {
    }

    /** IG key for a resource type; EOB searches with use=preauthorization go to the PDex base. */
    public static String igFor(String resourceType, MultiValueMap<String, String> params) {
        if ("ExplanationOfBenefit".equals(resourceType)) {
            if (params != null && params.getFirst("use") != null && "preauthorization".equalsIgnoreCase(params.getFirst("use"))) {
                return PDEX;
            }
            return C4BB;
        }
        if (C4BB_TYPES.contains(resourceType)) {
            return C4BB;
        }
        if (USDF_TYPES.contains(resourceType)) {
            return USDF;
        }
        if (PLANNET_TYPES.contains(resourceType)) {
            return PLANNET;
        }
        return PDEX;
    }
}
