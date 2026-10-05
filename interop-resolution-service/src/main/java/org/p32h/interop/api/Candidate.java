package org.p32h.interop.api;

/** One of the persons MMI returned for an AMBIGUOUS answer. */
public record Candidate(String storedMemberId, String lineOfBusiness, boolean coverageActive) {
}
