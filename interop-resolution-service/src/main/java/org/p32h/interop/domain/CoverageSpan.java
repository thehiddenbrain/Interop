package org.p32h.interop.domain;

import java.time.LocalDate;

/**
 * One readable, non-void coverage period. {@code end == null} means open-ended (MMI's null, blank or
 * 12/31/3999). Both ends are inclusive: a span ending on the date of service still covers it.
 */
public record CoverageSpan(LocalDate effective, LocalDate end) {

    public boolean covers(LocalDate date) {
        return !date.isBefore(effective) && (end == null || !date.isAfter(end));
    }

    public boolean isOpenEnded() {
        return end == null;
    }

    /** Open-ended sorts after every dated end. */
    public LocalDate endForSorting() {
        return end == null ? LocalDate.MAX : end;
    }
}
