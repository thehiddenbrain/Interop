package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field of a lookup result as the confirmation strip shows it.
 *
 * @param name        the tool's own name for the field, for example dateOfBirth
 * @param label       the name in plain words
 * @param value       the value with its presence, so "not returned" differs from "empty"
 * @param sourceField the field in the API response the value was read from, or null when the
 *                    response did not carry any spelling the mapping knows
 * @param masked      true when the value repeats a secret this tool holds, which this tool masked
 *                    before the value reached the result. Absent otherwise. The mask text is not
 *                    what the service returned and not member data, so a screen leaves such a
 *                    field out of the case: sent on with the next run it would go out as data
 * @param note        why the field is marked, in words, when it is masked. Absent otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupField(String name, String label, Shown value, String sourceField, Boolean masked, String note) {

    /** Said of a field whose value holds something this tool masked. */
    public static final String MASKED_NOTE = "This value repeats a secret this tool holds, which this tool masked, so it is not what the "
            + "service returned and is not offered for the case.";

    public LookupField {
        // Present only when true, so an unmasked field carries neither the flag nor the note.
        if (!Boolean.TRUE.equals(masked)) {
            masked = null;
            note = null;
        }
    }

    /** A field whose value holds nothing this tool masked. */
    public LookupField(String name, String label, Shown value, String sourceField) {
        this(name, label, value, sourceField, null, null);
    }

    /**
     * The same field marked as masked when its value holds a mask, and unchanged otherwise.
     * Called only on a response in which this tool masked something, so a mask found here is the
     * tool's own.
     */
    public LookupField markedIfMasked() {
        if (value == null || Secrets.maskedNames(value.value()).isEmpty()) {
            return this;
        }
        return new LookupField(name, label, value, sourceField, Boolean.TRUE, MASKED_NOTE);
    }

    /** Prints the field name and presence, never the value, which is member data. */
    @Override
    public String toString() {
        return "LookupField[" + name + ", " + (value == null ? null : value.presence()) + (masked == null ? "" : ", masked") + "]";
    }
}
