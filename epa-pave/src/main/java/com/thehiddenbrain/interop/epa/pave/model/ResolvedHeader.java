package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One request header in the form to send and the form to show.
 *
 * @param name    the header name
 * @param display the value with secrets masked
 * @param value   the value to send. Never serialised
 */
public record ResolvedHeader(String name, String display, @JsonIgnore String value) {

    /** Prints the name only. */
    @Override
    public String toString() {
        return "ResolvedHeader[" + name + "]";
    }
}
