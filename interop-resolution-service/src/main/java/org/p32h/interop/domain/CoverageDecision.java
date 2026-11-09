package org.p32h.interop.domain;

/**
 * @param active     the flag Onyx acts on
 * @param reason     why (said in the response's message and in the log, not as a field)
 * @param span       the continuous coverage period behind the decision (records that overlap or touch joined). Active:
 *                   the period covering the date of service; its end tells how far the coverage runs, which matters for
 *                   a period of service. Inactive: the most recent period. Null only when there is no readable span
 * @param recordSpan the member record's own coverage span reported to Onyx (its dates and coverageId). Active: the span
 *                   covering the date of service; inactive: the most recent span. Null only when there is no readable span
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span, CoverageSpan recordSpan) {

    /** The same decision reporting another record span, for example the selected record's own. */
    public CoverageDecision withRecordSpan(CoverageSpan other) {
        return other == null ? this : new CoverageDecision(active, reason, span, other);
    }
}
