package com.thehiddenbrain.interop.epa.pave.model;

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
}
