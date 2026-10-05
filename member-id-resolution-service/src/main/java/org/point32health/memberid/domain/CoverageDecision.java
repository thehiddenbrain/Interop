package org.point32health.memberid.domain;

/**
 * @param active the flag Onyx acts on
 * @param reason why (said in the response's message and in the log, not as a field)
 * @param span   the continuous coverage period covering the first date of service, when there is one (active, or
 *               inactive because it ends before the last date asked about)
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span) {
}
