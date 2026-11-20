package org.p32h.interop.epa.pave.model.compare;

/**
 * How two requests differ at one element. Each kind carries the words the screen shows for it, so
 * the browser keeps no copy of this list.
 */
public enum DifferenceKind {
    ONLY_IN_TOOL("Only in the tool's request"),
    ONLY_IN_YOURS("Only in your request"),
    DIFFERENT_VALUE("A different value"),
    /** A string against a number, an object against a list, an explicit null against a value. */
    DIFFERENT_TYPE("A different JSON type"),
    /** The same number with a different written precision, or the same reference in another form. */
    WRITTEN_DIFFERENTLY("The same value written differently"),
    DIFFERENT_ORDER("A different order"),
    /** The same resource under two keys of a map whose keys the sender or the service chooses. */
    DIFFERENT_KEY("A different key for the same content");

    private final String label;

    DifferenceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
