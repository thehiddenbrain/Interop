package com.thehiddenbrain.interop.epa.pave.model;

/**
 * Every operation the tool can send, each knowing whether it changes anything on the far side.
 *
 * <p>The write flag is what the production guardrail keys on. A write against a production
 * environment creates or changes a real prior authorization or a real subscription, so it is
 * blocked unless explicitly unlocked. Reads are always allowed.
 */
public enum EpaOperation {

    CRD_ORDER_SIGN(Protocol.CRD, "CRD order-sign", "POST", false),
    CRD_ORDER_SELECT(Protocol.CRD, "CRD order-select", "POST", false),
    CRD_DISCOVERY(Protocol.CRD, "CDS Hooks discovery", "GET", false),
    DTR_QUESTIONNAIRE_PACKAGE(Protocol.DTR, "DTR questionnaire package", "POST", false),
    DTR_NEXT_QUESTION(Protocol.DTR, "DTR next question", "POST", false),
    PAS_SUBMIT(Protocol.PAS, "PAS submit", "POST", true),
    PAS_INQUIRE(Protocol.PAS, "PAS inquire", "POST", false),
    SUBSCRIPTION_CREATE(Protocol.SUBSCRIPTION, "Subscription create", "POST", true),
    SUBSCRIPTION_GET(Protocol.SUBSCRIPTION, "Subscription get", "GET", false),
    SUBSCRIPTION_UPDATE(Protocol.SUBSCRIPTION, "Subscription update", "PUT", true),
    SUBSCRIPTION_DELETE(Protocol.SUBSCRIPTION, "Subscription delete", "DELETE", true),
    /**
     * A notification as the far side would post it to this tool's receiver. It is inbound, so it is
     * not a write against the vendor, and the tool only ever delivers one to its own receiver.
     */
    SUBSCRIPTION_NOTIFICATION(Protocol.SUBSCRIPTION, "Subscription notification", "POST", false),
    MEMBER_LOOKUP(Protocol.LOOKUP, "Member lookup", "GET", false),
    PROVIDER_LOOKUP(Protocol.LOOKUP, "Provider lookup", "GET", false),
    /**
     * A collection request the tool could not map, sent as written. Whether it changes anything is
     * unknown, so the guardrail decides from its HTTP method: see {@link #writesWith(String)}.
     */
    UNMAPPED(Protocol.RAW, "Unmapped request", "POST", false);

    private final Protocol protocol;
    private final String label;
    private final String defaultMethod;
    private final boolean write;

    EpaOperation(Protocol protocol, String label, String defaultMethod, boolean write) {
        this.protocol = protocol;
        this.label = label;
        this.defaultMethod = defaultMethod;
        this.write = write;
    }

    public Protocol protocol() {
        return protocol;
    }

    public String label() {
        return label;
    }

    public String defaultMethod() {
        return defaultMethod;
    }

    /** True when the operation always creates or changes something on the far side. */
    public boolean write() {
        return write;
    }

    /**
     * Whether sending this operation with the given HTTP method must be treated as a write.
     *
     * <p>A mapped operation answers from its own flag. An unmapped request is treated as a write
     * unless its method is one that cannot change state, because guessing "read" for an unknown
     * POST against production is the unsafe direction to be wrong in.
     */
    public boolean writesWith(String method) {
        if (this != UNMAPPED) {
            return write;
        }
        String normalised = method == null ? "" : method.strip().toUpperCase(java.util.Locale.ROOT);
        return !(normalised.equals("GET") || normalised.equals("HEAD") || normalised.equals("OPTIONS"));
    }
}
