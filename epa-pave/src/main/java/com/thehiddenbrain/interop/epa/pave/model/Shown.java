package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * One value as it will be shown on screen, together with whether it was returned at all.
 *
 * <p>View types carry this in place of a bare String so that "not returned", "null" and "empty"
 * stay distinguishable in the browser. A bare String would force all three into one blank cell.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Shown(Presence presence, String value) {

    private static final Shown ABSENT = new Shown(Presence.ABSENT, null);
    private static final Shown NULL = new Shown(Presence.NULL, null);
    private static final Shown EMPTY = new Shown(Presence.EMPTY, null);

    public Shown {
        if (presence == null) {
            throw new IllegalArgumentException("presence is required");
        }
        if (presence != Presence.VALUE) {
            value = null;
        } else if (value == null) {
            throw new IllegalArgumentException("a VALUE needs a value");
        }
    }

    public static Shown absent() {
        return ABSENT;
    }

    public static Shown explicitNull() {
        return NULL;
    }

    public static Shown empty() {
        return EMPTY;
    }

    /** A Java value that did not come from JSON: null maps to ABSENT and an empty string to EMPTY. */
    public static Shown of(String value) {
        if (value == null) {
            return ABSENT;
        }
        return value.isEmpty() ? EMPTY : new Shown(Presence.VALUE, value);
    }

    /** A JSON node as read from a response. A container is shown as its compact JSON text. */
    public static Shown of(JsonNode node) {
        return switch (JsonSafe.presence(node)) {
            case ABSENT -> ABSENT;
            case NULL -> NULL;
            case EMPTY -> EMPTY;
            case VALUE -> new Shown(Presence.VALUE, node.isContainer() ? node.toString() : JsonSafe.text(node));
        };
    }

    @JsonIgnore
    public boolean hasValue() {
        return presence == Presence.VALUE;
    }

    /** The value, or null when nothing was returned. For logic only; the screen uses the presence. */
    public String orNull() {
        return value;
    }
}
