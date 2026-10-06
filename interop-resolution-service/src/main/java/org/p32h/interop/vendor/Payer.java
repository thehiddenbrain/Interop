package org.p32h.interop.vendor;

/** The payer identity Onyx puts on a request to a UM vendor: the id the vendor keys on and the name that goes with it. */
public record Payer(String id, String name) {
}
