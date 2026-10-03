package com.thehiddenbrain.interop.memberid.domain;

public enum CoverageReason {
    /** A span covers the date of service. */
    COVERED,
    /** The record has no readable, non-void spans at all. */
    NO_COVERAGE_RECORDS,
    /** The date of service is before the earliest span. */
    NOT_YET_EFFECTIVE,
    /** The date of service is after the latest span. */
    COVERAGE_ENDED,
    /** The date of service falls between two spans. */
    COVERAGE_GAP
}
