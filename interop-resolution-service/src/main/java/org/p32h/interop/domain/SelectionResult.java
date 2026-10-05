package org.p32h.interop.domain;

import java.util.List;

/** Outcome of {@link MemberSelector}: one record, or a list of candidates a human (or a resend with DOB) must pick from. */
public sealed interface SelectionResult {

    /**
     * @param record          the record whose stored id is returned
     * @param coverage        evaluated over every readable span of the person (all records of a converted pair)
     * @param readableSpans   readable, non-void spans across the person
     * @param unreadableSpans spans skipped across the person because a date could not be read
     */
    record Selected(MemberRecord record, CoverageDecision coverage, int readableSpans, int unreadableSpans) implements SelectionResult {
    }

    record Ambiguous(String reason, List<Candidate> candidates) implements SelectionResult {
    }

    record Candidate(String storedMemberId, String lineOfBusiness, boolean coverageActive) {
    }
}
