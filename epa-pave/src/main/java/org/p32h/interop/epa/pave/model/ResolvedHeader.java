package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One request header in the form to send and the form to show.
 *
 * @param name       the header name
 * @param display    the value with secrets masked. Null when the value could not be resolved
 * @param value      the value to send. Never serialised. Null when it could not be resolved
 * @param unresolved names of placeholders in the value that no layer could supply. A call with
 *                   such a header is refused before sending, naming them, exactly as a body is.
 *                   Sent without the header, it would fail at the far side for no visible reason,
 *                   or go out with a different credential in its place
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ResolvedHeader(String name, String display, @JsonIgnore String value, List<String> unresolved) {

    public ResolvedHeader {
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /** A header whose value resolved completely. */
    public ResolvedHeader(String name, String display, String value) {
        this(name, display, value, List.of());
    }

    /** A header whose value still holds placeholders with no value. */
    public static ResolvedHeader unresolved(String name, List<String> missing) {
        return new ResolvedHeader(name, null, null, missing);
    }

    /** True when there is a complete value to send. */
    @JsonIgnore
    public boolean resolved() {
        return unresolved.isEmpty() && value != null;
    }

    /** Prints the name only. */
    @Override
    public String toString() {
        return "ResolvedHeader[" + name + "]";
    }
}
