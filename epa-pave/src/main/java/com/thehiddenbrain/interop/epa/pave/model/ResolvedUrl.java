package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Where a request will go: the url template beside what it resolved to.
 *
 * <p>Shown on the tab that will use it, before sending. As with {@link ResolvedText}, the form to
 * send and the form to show are separate and only the display form is ever serialised or printed.
 *
 * @param template   the url as configured, placeholders intact. Empty when nothing is configured
 * @param display    the resolved url with secrets masked. Null when unresolved or not configured
 * @param value      the resolved url to send to. Null when unresolved or not configured. Never serialised
 * @param variables  every placeholder and how it resolved
 * @param unresolved names of placeholders no layer could supply
 * @param source     where the template came from, in words, for example the name of a collection
 *                   request or the application.yml property
 */
public record ResolvedUrl(String template, String display, @JsonIgnore String value, List<VariableUse> variables,
                          List<String> unresolved, String source) {

    public ResolvedUrl {
        template = template == null ? "" : template;
        variables = variables == null ? List.of() : List.copyOf(variables);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /** A target that is not configured at all. */
    public static ResolvedUrl notConfigured(String source) {
        return new ResolvedUrl("", null, null, List.of(), List.of(), source);
    }

    /** True when a url template exists, whether or not it resolved. */
    @JsonIgnore
    public boolean configured() {
        return Texts.present(template);
    }

    /** True when there is a complete url to send to. */
    @JsonIgnore
    public boolean resolved() {
        return configured() && unresolved.isEmpty() && Texts.present(value);
    }

    /**
     * Prints the template only. The display form masks secrets but still holds whatever identifier
     * the url carries, and for a lookup that is the member ID.
     */
    @Override
    public String toString() {
        return "ResolvedUrl[" + template + "]";
    }
}
