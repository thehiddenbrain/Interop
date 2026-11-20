package org.p32h.interop.epa.pave.model.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One value of the case the tool built its request for, and where that value came from.
 *
 * <p>The tool's request is built for the case on the form. Where the form is silent the value is
 * read out of the pasted request, so that the two requests describe the same case and what is
 * left is how each is built. This record is what keeps that borrowing visible.
 *
 * @param field   a stable name for the value, as the form names it, for example {@code member.memberId}
 * @param label   the same in plain words
 * @param source  the form, or the pasted request
 * @param sourceLabel the words for {@code source}, so a screen keeps no copy of them
 * @param path    for a value read from the pasted request, where it was read. Null for the form
 * @param pointer for a value read from the pasted request, its JSON Pointer there. Null for the form
 * @param value   the value that was used
 * @param note    anything a tester should know about how the value was used, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseValue(String field, String label, Source source, String sourceLabel, String path, String pointer, Shown value,
                        String note) {

    /** Where a value of the case came from. */
    public enum Source {
        FORM("The form"),
        YOUR_REQUEST("Your request");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public CaseValue {
        if (field == null || source == null || value == null) {
            throw new IllegalArgumentException("A case value needs a field, a source and a value.");
        }
        sourceLabel = source.label();
    }

    /** A case value with the words for its source taken from the source. */
    public CaseValue(String field, String label, Source source, String path, String pointer, Shown value, String note) {
        this(field, label, source, source == null ? null : source.label(), path, pointer, value, note);
    }

    /** Prints which value and where it came from, never the value: it can be a member ID or a name. */
    @Override
    public String toString() {
        return "CaseValue[" + field + ", " + source + "]";
    }
}
