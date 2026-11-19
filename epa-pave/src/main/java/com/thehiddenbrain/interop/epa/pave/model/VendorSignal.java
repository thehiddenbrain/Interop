package com.thehiddenbrain.interop.epa.pave.model;

/**
 * Something a vendor response said that the published profile has no standard code for.
 *
 * <p>Only code in the client layer sets one of these, from a recognition rule kept in one marked
 * method per client. How the live service actually signals these cases is not confirmed, so each
 * rule is an assumption that can be corrected in one place.
 */
public enum VendorSignal {
    MEMBER_NOT_FOUND,
    PROVIDER_NOT_FOUND
}
