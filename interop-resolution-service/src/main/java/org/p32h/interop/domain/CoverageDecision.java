package org.p32h.interop.domain;

/**
 * @param active the flag Onyx acts on
 * @param reason why (said in the response's message and in the log, not as a field)
 * @param span   active: the continuous coverage period covering the date of service; its end tells how far the
 *               coverage runs, which matters for a period of service. Inactive: the most recent coverage period (the
 *               one that ends last, an open end counting as the latest). Null only when there is no readable span
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span) {
}
