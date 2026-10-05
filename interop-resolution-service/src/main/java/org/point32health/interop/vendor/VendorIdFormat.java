package org.point32health.interop.vendor;

/**
 * How a vendor wants a THP member id that is stored as 9 digits + spaces + 2-digit suffix (TMP / SCO).
 * Ids stored in any other shape (Public Plans 11 continuous characters, HPHC {@code HP...}) are always
 * passed exactly as stored, whatever the vendor's format.
 */
public enum VendorIdFormat {
    /** Exactly as stored: {@code 123456789   01}. */
    AS_STORED,
    /** 9 digits + suffix, no spaces: {@code 12345678901}. */
    COMPACT_11,
    /** 9 digits + 3 spaces + suffix: {@code 123456789   01}. */
    SPACED_14,
    /** Two fields, the 9 digits and the suffix, plus their 11-character join for convenience. */
    SPLIT
}
