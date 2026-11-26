package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** The part of the prior authorization stack a run belongs to. */
public enum Protocol {
    CRD("Coverage Requirements Discovery"),
    DTR("Documentation Templates and Rules"),
    PAS("Prior Authorization Support"),
    SUBSCRIPTION("PAS subscriptions and notifications"),
    LOOKUP("Member and provider lookup"),
    /** A collection request sent as written, with no protocol handling. */
    RAW("Raw request");

    private final String label;

    Protocol(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Reads a protocol from text, in any case. Declared as the JSON reader too, so a number in a
     * file is never taken as a position in this list.
     */
    @JsonCreator
    public static Protocol parse(String text) {
        if (Texts.present(text)) {
            for (Protocol protocol : values()) {
                if (protocol.name().equalsIgnoreCase(text.strip())) {
                    return protocol;
                }
            }
        }
        throw new IllegalArgumentException("Protocol must be one of crd, dtr, pas, subscription, lookup or raw.");
    }
}
