package org.p32h.interop.domain;

/**
 * @param active the flag Onyx acts on
 * @param reason why (said in the response's message and in the log, not as a field)
 * @param span   the continuous coverage period covering the date of service, when there is one (active); its end
 *               tells how far the coverage runs, which matters for a period of service
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span) {
}
