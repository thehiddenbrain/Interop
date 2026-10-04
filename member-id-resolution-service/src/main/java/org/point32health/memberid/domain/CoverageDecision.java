package org.point32health.memberid.domain;

/**
 * @param active the flag Onyx acts on
 * @param reason why (said in the response's message and in the log, not as a field)
 * @param span   the covering span when active
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span) {
}
