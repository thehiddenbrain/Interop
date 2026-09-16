package com.thehiddenbrain.interop.patientaccess.environment;

/**
 * An identifier namespace the payer uses for members (Patient.identifier.system), e.g. the member id
 * system of the plan, the Medicare MBI system or a Medicaid id. Used by the member-id search.
 */
public record IdentifierSystem(String label, String system, String typeCode, boolean defaultForMemberId) {
}
