package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One placeholder in a template and how it resolved.
 *
 * @param name    the placeholder name without braces
 * @param source  the layer that supplied the value, or UNRESOLVED
 * @param secret  true when the value is a secret and must never be displayed
 * @param display the value to show: the real value, a mask for a secret, or null when unresolved
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariableUse(String name, VariableSource source, boolean secret, String display) {

    public VariableUse {
        // A secret is shown as its mask whatever the producer passed, so a value can never be
        // displayed just because a producer forgot to mask it.
        if (secret && display != null) {
            display = Secrets.mask(name);
        }
    }

    /** The source in words, served beside the code so that no screen keeps its own copy. Never read from a request. */
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceLabel", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String sourceLabel() {
        return source == null ? null : source.label();
    }

    /** Prints no value. A value typed for one run can be a member ID. */
    @Override
    public String toString() {
        return "VariableUse[" + name + ", " + source + (secret ? ", secret" : "") + "]";
    }
}
