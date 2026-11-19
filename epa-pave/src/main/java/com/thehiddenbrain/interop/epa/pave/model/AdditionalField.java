package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field that a response carried and the designed view did not use.
 *
 * <p>These are shown on screen as "returned but not interpreted". They exist so that a field the
 * vendor adds, or one this tool did not anticipate, appears in the interface and not only in the
 * wire view.
 *
 * @param path     where the field sits, for example {@code cards[0].source.label}
 * @param presence NULL, EMPTY or VALUE. Never ABSENT, since an absent field was not returned
 * @param value    the JSON text of the value, or null when there is none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdditionalField(String path, Presence presence, String value) {

    /** Prints the path and presence, never the value, which can be member data. */
    @Override
    public String toString() {
        return "AdditionalField[" + path + ", " + presence + "]";
    }
}
