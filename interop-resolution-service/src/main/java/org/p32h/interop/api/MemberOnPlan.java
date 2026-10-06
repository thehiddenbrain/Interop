package org.p32h.interop.api;

/** One of the members on the plan behind an AMBIGUOUS answer: the member id as on record, the line of business, the flag. */
public record MemberOnPlan(String memberId, String lineOfBusiness, boolean coverageActive) {
}
