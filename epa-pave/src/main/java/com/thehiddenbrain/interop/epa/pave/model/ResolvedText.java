package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * A template with its placeholders resolved, in two forms: one to send and one to show.
 *
 * <p>The two are kept apart so a secret cannot reach the screen, the ledger or a log by accident.
 * {@link #display()} masks every secret by variable name and is the only form that is serialised
 * or printed. {@link #value()} is the real text and is read only at the point of sending.
 *
 * @param template   the text as written, placeholders intact
 * @param display    the resolved text with secrets masked. Null when anything is unresolved
 * @param value      the resolved text to send. Null when anything is unresolved. Never serialised
 * @param variables  every placeholder and how it resolved
 * @param unresolved names of placeholders no layer could supply
 */
public record ResolvedText(String template, String display, @JsonIgnore String value, List<VariableUse> variables,
                           List<String> unresolved) {

    public ResolvedText {
        variables = variables == null ? List.of() : List.copyOf(variables);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /** Text with no placeholders and no secrets, such as a request body the tool built itself. */
    public static ResolvedText plain(String text) {
        return new ResolvedText(text, text, text, List.of(), List.of());
    }

    @JsonIgnore
    public boolean resolved() {
        return unresolved.isEmpty() && value != null;
    }

    /**
     * Prints nothing of the content. Even the display form can hold member data, since a request
     * body is one of these.
     */
    @Override
    public String toString() {
        return "ResolvedText[" + (resolved() ? "resolved" : "unresolved " + unresolved) + "]";
    }
}
