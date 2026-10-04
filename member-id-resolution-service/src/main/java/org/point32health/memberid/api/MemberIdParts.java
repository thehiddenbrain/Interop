package org.point32health.memberid.api;

/** The two fields for vendors that take the 9 digits and the suffix separately (format SPLIT). */
public record MemberIdParts(String memberId, String suffix) {
}
