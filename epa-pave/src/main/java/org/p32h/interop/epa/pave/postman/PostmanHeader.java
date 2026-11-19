package org.p32h.interop.epa.pave.postman;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One header of a collection request or of a saved example response.
 *
 * @param name     the header name as written
 * @param value    the value as written, placeholders intact. Never serialised: a collection can
 *                 carry a credential typed literally, and whatever shows a header masks it first
 * @param disabled true when the collection marks the header disabled. Postman does not send a
 *                 disabled header and neither does this tool
 */
public record PostmanHeader(String name, @JsonIgnore String value, boolean disabled) {

    /** Prints the name only. */
    @Override
    public String toString() {
        return "PostmanHeader[" + name + (disabled ? ", disabled" : "") + "]";
    }
}
