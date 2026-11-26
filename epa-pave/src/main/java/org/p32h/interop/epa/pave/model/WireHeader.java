package org.p32h.interop.epa.pave.model;

/**
 * One header as shown in the wire view.
 *
 * <p>A header that is a credential by its own name is redacted here, whoever built it, so a
 * producer that forgets cannot put a credential on screen. Redacting an already redacted value
 * changes nothing.
 */
public record WireHeader(String name, String value) {

    public WireHeader {
        if (value != null && Secrets.credentialHeader(name)) {
            value = Secrets.redactCredential(name, value);
        }
    }

    /** Prints the name only. A value can hold a credential or an address with a member ID in it. */
    @Override
    public String toString() {
        return "WireHeader[" + name + "]";
    }
}
