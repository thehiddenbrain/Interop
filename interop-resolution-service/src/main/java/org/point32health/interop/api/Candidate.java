package org.point32health.interop.api;

/** One of the persons MMI returned for an AMBIGUOUS answer. */
public record Candidate(String storedMemberId, String lineOfBusiness, boolean coverageActive) {
}
