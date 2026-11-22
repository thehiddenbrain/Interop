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
 * @param sentWithNoValue true when the element was sent with no value[x] in it at all, which the
 *                   presence shows as empty, as it does a value of ""; null otherwise, and then
 *                   left off the wire
 * @param tone       what the code means, for the colour of its chip, from {@link CodeTones}: always
 *                   derived from {@code system} and {@code code}, whatever is passed, so a row can
 *                   never carry a tone its code does not have. Null, and left off the wire, for a
 *                   code with no tone, an unknown code and a row with no code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeterminationRow(String subject, String label, String code, String display, String system, boolean recognised,
                               Presence presence, Boolean sentWithNoValue, Tone tone) {

    public DeterminationRow {
        sentWithNoValue = Boolean.TRUE.equals(sentWithNoValue) ? Boolean.TRUE : null;
        tone = CodeTones.of(system, code);
    }

    /** A row as every caller builds one: its tone is derived from its code. */
    public DeterminationRow(String subject, String label, String code, String display, String system, boolean recognised,
                            Presence presence, Boolean sentWithNoValue) {
        this(subject, label, code, display, system, recognised, presence, sentWithNoValue, null);
    }

    /** A row for an element that was not sent with no value, which is every row but a CRD sub-extension's. */
    public DeterminationRow(String subject, String label, String code, String display, String system, boolean recognised,
                            Presence presence) {
        this(subject, label, code, display, system, recognised, presence, null, null);
    }

    /** A row for an element the response did not carry. */
    public static DeterminationRow notReturned(String subject, String label, String system) {
        return new DeterminationRow(subject, label, null, null, system, true, Presence.ABSENT);
    }

    /** Prints the label and presence, never the value: a row can hold an authorization number. */
    @Override
    public String toString() {
        return "DeterminationRow[" + label + ", " + presence + "]";
    }
}
