package org.p32h.interop.vendor;

/**
 * How a vendor wants a THP member id that is stored as a 9-character core (for TMP a letter and 8 digits) + spaces +
 * 2-digit suffix (TMP / SCO). Ids stored in any other shape (Public Plans 11 continuous characters, HPHC {@code HP...})
 * are always passed exactly as stored, whatever the vendor's format.
 */
public enum VendorIdFormat {
    /** Exactly as stored: core, three spaces, suffix (14 characters). */
    AS_STORED,
    /** Core + suffix, no spaces (11 characters). */
    COMPACT_11,
    /** Core + 3 spaces + suffix (14 characters). */
    SPACED_14,
    /** The 9-character core only, the number printed on the card; Optum stores this. */
    CORE_9
}
