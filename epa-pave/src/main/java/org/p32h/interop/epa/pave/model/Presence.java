package org.p32h.interop.epa.pave.model;

/**
 * Whether a field was in a response at all, and if so what kind of nothing or something it held.
 *
 * <p>Absent, null and empty are three different findings when chasing a contract mismatch, so the
 * distinction is carried all the way to the screen and never collapsed into a blank cell.
 */
public enum Presence {
    /** The field was not in the response. */
    ABSENT,
    /** The field was present and explicitly null. */
    NULL,
    /** The field was present and empty: an empty string, array or object. */
    EMPTY,
    /** The field was present with a value. */
    VALUE
}
