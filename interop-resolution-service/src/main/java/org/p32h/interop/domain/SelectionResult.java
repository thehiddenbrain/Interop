package org.p32h.interop.domain;

import java.time.LocalDate;

/**
 * Outcome of {@link MemberSelector}: one record; AMBIGUOUS when several persons match and nothing in the request tells
 * them apart; or nobody current when every matching person is neither covered on the date of service nor covered at
 * all in the recent past.
 */
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

    /**
     * Every matching person is stale: not covered on the date of service and with no coverage ending on or after
     * {@code recentSince}. Answered as NOT_FOUND.
     *
     * @param persons           how many persons matched, for the log
     * @param latestCoverageEnd the latest end date among their coverage spans, for the log; null when none has a span
     */
    record NoneRecent(int persons, LocalDate latestCoverageEnd) implements SelectionResult {
    }
}
