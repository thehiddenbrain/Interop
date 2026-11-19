package org.p32h.interop.epa.pave.model;

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

    /**
     * A body that could not be parsed at all, shown as received. Without this the raw text would sit
     * only in the wire view, which is collapsed by default.
     */
    public static AdditionalField unparsedBody(String raw) {
        return new AdditionalField("(body, not JSON)", Presence.VALUE, raw);
    }

    /** Something a tester should know about the body as a whole, such as a key that appears twice. */
    public static AdditionalField bodyNote(String message) {
        return new AdditionalField("(body)", Presence.VALUE, message);
    }

    /** Prints the path and presence, never the value, which can be member data. */
    @Override
    public String toString() {
        return "AdditionalField[" + path + ", " + presence + "]";
    }
}
