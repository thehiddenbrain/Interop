package org.p32h.interop.domain;

public enum CoverageReason {
    /** One continuous coverage period covers the date of service (the first day of a period of service). */
    COVERED,
    /** The record has no readable, non-void spans at all. */
    NO_COVERAGE_RECORDS,
    /** The date of service is before the earliest coverage period. */
    NOT_YET_EFFECTIVE,
    /** The date of service is after the latest coverage period. */
    COVERAGE_ENDED,
    /** The date of service falls between two coverage periods. */
    COVERAGE_GAP
}
