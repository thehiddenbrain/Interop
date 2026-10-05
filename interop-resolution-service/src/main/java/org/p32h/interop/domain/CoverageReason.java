package org.p32h.interop.domain;

public enum CoverageReason {
    /** One continuous coverage period covers every day asked about. */
    COVERED,
    /** The record has no readable, non-void spans at all. */
    NO_COVERAGE_RECORDS,
    /** The (first) date of service is before the earliest coverage period. */
    NOT_YET_EFFECTIVE,
    /** The (first) date of service is after the latest coverage period. */
    COVERAGE_ENDED,
    /** The (first) date of service falls between two coverage periods. */
    COVERAGE_GAP,
    /** A coverage period covers the first date of service but ends before the last one asked about. */
    COVERAGE_ENDS_WITHIN_PERIOD
}
