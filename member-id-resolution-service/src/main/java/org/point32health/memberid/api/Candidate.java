package org.point32health.memberid.api;

/** One of the persons MMI returned for an AMBIGUOUS answer. */
public record Candidate(String storedMemberId, String lineOfBusiness, boolean coverageActive) {
}
