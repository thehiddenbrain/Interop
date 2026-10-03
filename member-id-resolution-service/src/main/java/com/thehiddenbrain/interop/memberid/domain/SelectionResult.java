package com.thehiddenbrain.interop.memberid.domain;

import java.util.List;

/** Outcome of {@link MemberSelector}: one record, or a list of candidates a human (or a resend with DOB) must pick from. */
public sealed interface SelectionResult {

    record Selected(MemberRecord record, CoverageDecision coverage) implements SelectionResult {
    }

    record Ambiguous(String reason, List<Candidate> candidates) implements SelectionResult {
    }

    record Candidate(String storedMemberId, String company, String lineOfBusiness, boolean coverageActive) {
    }
}
