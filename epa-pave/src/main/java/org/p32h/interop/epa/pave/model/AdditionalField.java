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
 * @param value    for a field, the JSON text of the value, or null when there is none. For a raw
 *                 body, the text as received. For a note, the sentence
 * @param kind     what the entry is, so the screen does not have to tell them apart by their path
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdditionalField(String path, Presence presence, String value, Kind kind) {

    /** What an entry in the list of returned fields is. */
    public enum Kind {
        /** A field of the response the designed view did not use. */
        FIELD,
        /** The whole body as received, because it could not be parsed. */
        RAW_BODY,
        /** Something worth knowing about the body as a whole, such as a key that appears twice. */
        NOTE
    }

    public AdditionalField {
        kind = kind == null ? Kind.FIELD : kind;
    }

    /** A field of the response. */
    public AdditionalField(String path, Presence presence, String value) {
        this(path, presence, value, Kind.FIELD);
    }

    /**
     * A body that could not be parsed at all, shown as received. Without this the raw text would sit
     * only in the wire view, which is collapsed by default.
     */
    public static AdditionalField unparsedBody(String raw) {
        return new AdditionalField("(body, not JSON)", Presence.VALUE, raw, Kind.RAW_BODY);
    }

    /** Something a tester should know about the body as a whole, such as a key that appears twice. */
    public static AdditionalField bodyNote(String message) {
        return new AdditionalField("(body)", Presence.VALUE, message, Kind.NOTE);
    }

    /** Prints the path and presence, never the value, which can be member data. */
    @Override
    public String toString() {
        return "AdditionalField[" + path + ", " + presence + "]";
    }
}
