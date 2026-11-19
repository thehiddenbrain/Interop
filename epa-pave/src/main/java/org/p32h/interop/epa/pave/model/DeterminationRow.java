package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of the determination grid: a standard code and what it means.
 *
 * @param subject    what the row is about when a result holds more than one answer, such as the
 *                   procedure code. Null when the result has a single answer
 * @param label      what the row is, for example "Prior authorization"
 * @param code       the code as returned, or null when none was returned
 * @param display    the meaning of the code, or "Unrecognised code: X" when the tool does not know it
 * @param system     the code system or the element the code came from
 * @param recognised false when the code is outside what the tool knows. Still shown, never hidden
 * @param presence   whether the element was returned at all
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeterminationRow(String subject, String label, String code, String display, String system, boolean recognised,
                               Presence presence) {

    /** A row for an element the response did not carry. */
    public static DeterminationRow notReturned(String subject, String label, String system) {
        return new DeterminationRow(subject, label, null, null, system, true, Presence.ABSENT);
    }
}
