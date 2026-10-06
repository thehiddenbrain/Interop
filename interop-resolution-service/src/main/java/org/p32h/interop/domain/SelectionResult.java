package org.p32h.interop.domain;

/** Outcome of {@link MemberSelector}: one record, or AMBIGUOUS when several persons match and nothing in the request tells them apart. */
public sealed interface SelectionResult {

    /**
     * @param record          the record whose stored id is returned
     * @param coverage        evaluated over every readable span of the person (all records of a converted pair)
     * @param readableSpans   readable, non-void spans across the person
     * @param unreadableSpans spans skipped across the person because a date could not be read
     */
    record Selected(MemberRecord record, CoverageDecision coverage, int readableSpans, int unreadableSpans) implements SelectionResult {
    }

    /**
     * @param reason  {@link MemberSelector#MULTIPLE_PERSONS} (no date of birth sent), {@link MemberSelector#DOB_NOT_DISCRIMINATING}
     *                (several share the one sent) or {@link MemberSelector#DOB_NOT_ON_RECORDS} (no record carries one to check)
     * @param persons how many persons matched, for the log; the persons themselves are never returned
     */
    record Ambiguous(String reason, int persons) implements SelectionResult {
    }
}
