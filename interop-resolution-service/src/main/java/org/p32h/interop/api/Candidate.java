package org.p32h.interop.api;

/** One of the persons MMI returned for an AMBIGUOUS answer: the member id as on record, the line of business, the flag. */
public record Candidate(String memberId, String lineOfBusiness, boolean coverageActive) {
}
