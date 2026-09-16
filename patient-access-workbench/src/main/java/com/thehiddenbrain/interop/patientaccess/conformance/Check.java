package com.thehiddenbrain.interop.patientaccess.conformance;

/**
 * One automated conformance check. Implementations are stateless; everything they need (environment,
 * patient, gateway, catalog, shared fetch cache) comes from the {@link CheckContext}.
 */
public interface Check {

    /** Stable id such as {@code eob.search.patient}. */
    String id();

    /** Group key (discovery, smart, security, patient, coverage, eob, priorauth, clinical, paging, errors, provenance, formulary, performance). */
    String group();

    String title();

    /** What is asserted and where the IG says so. */
    String description();

    Severity severity();

    /** Citation, e.g. "C4BB 2.1.0 CapabilityStatement: ExplanationOfBenefit search patient SHALL". */
    default String citation() {
        return "";
    }

    /** True when the check needs a patient in context; it is skipped otherwise. */
    default boolean needsPatient() {
        return false;
    }

    CheckResult run(CheckContext ctx);
}
