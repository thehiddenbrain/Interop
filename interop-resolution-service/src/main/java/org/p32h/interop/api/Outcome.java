package org.p32h.interop.api;

/** The one field Onyx branches on in a 200 response. */
public enum Outcome {
    /** Member verified; coverage active on the date of service. Send each vendor its {@code vendorMemberIds} entry. */
    ACTIVE,
    /** Member verified; no coverage on the date of service. */
    INACTIVE,
    /** MMI has no record for this id. */
    NOT_FOUND,
    /** Several persons match (a family behind a 9-digit id) and no date of birth settled it. */
    AMBIGUOUS
}
