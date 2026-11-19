package org.p32h.interop.epa.pave.model;

import java.util.Locale;

/**
 * Every operation the tool can send, each knowing whether it changes anything on the far side.
 *
 * <p>The write flag is what the production guardrail keys on. A write against a production
 * environment creates or changes a real prior authorization or a real subscription, so it is
 * blocked unless explicitly unlocked. Reads are always allowed.
 *
 * <p>Each operation also carries its fallback path: what the tool appends to the base url from
 * {@code application.yml} when no collection request is mapped to the operation. The base url is
 * chosen by protocol: {@code onyx.crd.url}, {@code onyx.dtr.url}, {@code onyx.pas.url} or
 * {@code onyx.subscription.url}. A double-brace name in a path is an ordinary placeholder. The
 * CRD service ids are placeholders because they are not known. A single-brace {@code {id}} is
 * filled from the call's url values.
 */
public enum EpaOperation {

    CRD_ORDER_SIGN(Protocol.CRD, "CRD order-sign", "POST", false, "/cds-services/{{crdOrderSignServiceId}}"),
    CRD_ORDER_SELECT(Protocol.CRD, "CRD order-select", "POST", false, "/cds-services/{{crdOrderSelectServiceId}}"),
    CRD_DISCOVERY(Protocol.CRD, "CDS Hooks discovery", "GET", false, "/cds-services"),
    DTR_QUESTIONNAIRE_PACKAGE(Protocol.DTR, "DTR questionnaire package", "POST", false, "/Questionnaire/$questionnaire-package"),
    DTR_NEXT_QUESTION(Protocol.DTR, "DTR next question", "POST", false, "/Questionnaire/$next-question"),
    /**
     * Answering a questionnaire inside the tool: completing a static questionnaire, or finding that
     * a required question was left unanswered. Nothing is sent to the vendor for this step, so the
     * gateway never hands out a target for it. It exists so the step has its own line in the ledger
     * and is not recorded as a call that was never made.
     */
    DTR_RESPONSE(Protocol.DTR, "DTR questionnaire response", "POST", false, null),
    PAS_SUBMIT(Protocol.PAS, "PAS submit", "POST", true, "/Claim/$submit"),
    PAS_INQUIRE(Protocol.PAS, "PAS inquire", "POST", false, "/Claim/$inquire"),
    SUBSCRIPTION_CREATE(Protocol.SUBSCRIPTION, "Subscription create", "POST", true, "/Subscription"),
    /** With an id this reads one subscription. With none, the trailing id segment is dropped and it lists them. */
    SUBSCRIPTION_GET(Protocol.SUBSCRIPTION, "Subscription get", "GET", false, "/Subscription/{id}"),
    SUBSCRIPTION_UPDATE(Protocol.SUBSCRIPTION, "Subscription update", "PUT", true, "/Subscription/{id}"),
    SUBSCRIPTION_DELETE(Protocol.SUBSCRIPTION, "Subscription delete", "DELETE", true, "/Subscription/{id}"),
    /**
     * A notification as the far side would post it to this tool's receiver. It is inbound, so the
     * gateway never hands out a target for it: a sample notification is delivered to the tool's
     * own receiver and nowhere else.
     */
    SUBSCRIPTION_NOTIFICATION(Protocol.SUBSCRIPTION, "Subscription notification", "POST", false, null),
    /** The url is {@code lookup.member.url}, a template containing {@code {id}}. */
    MEMBER_LOOKUP(Protocol.LOOKUP, "Member lookup", "GET", false, null),
    /** The url is {@code lookup.provider.url}, a template containing {@code {id}}. */
    PROVIDER_LOOKUP(Protocol.LOOKUP, "Provider lookup", "GET", false, null),
    /**
     * A collection request the tool could not map, sent as written. Whether it changes anything is
     * unknown, so the guardrail decides from its HTTP method: see {@link #writesWith(String)}.
     */
    UNMAPPED(Protocol.RAW, "Unmapped request", "POST", false, null);

    private final Protocol protocol;
    private final String label;
    private final String defaultMethod;
    private final boolean write;
    private final String fallbackPath;

    EpaOperation(Protocol protocol, String label, String defaultMethod, boolean write, String fallbackPath) {
        this.protocol = protocol;
        this.label = label;
        this.defaultMethod = defaultMethod;
        this.write = write;
        this.fallbackPath = fallbackPath;
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

    /** The path appended to the protocol's fallback base url, or null when the operation has none. */
    public String fallbackPath() {
        return fallbackPath;
    }

    /**
     * False for the two operations that never leave the tool: an inbound notification and a
     * questionnaire response completed locally. The gateway refuses to prepare a call for either,
     * and neither can be run again by a replay or a suite.
     */
    public boolean outbound() {
        return this != SUBSCRIPTION_NOTIFICATION && this != DTR_RESPONSE;
    }

    /**
     * Whether sending this operation with the given HTTP method must be treated as a write.
     *
     * <p>The answer depends on the method that will actually go out, not only on the operation's
     * name, because a caller or a remapped collection request could pair a read operation with a
     * method that changes state:
     * <ul>
     *   <li>a write operation is a write whatever the method</li>
     *   <li>GET, HEAD and OPTIONS cannot change state, so with those a read stays a read</li>
     *   <li>a read operation sent with its own default method is a read. That covers the reads that
     *       are POSTs: a CRD hook, a DTR operation, a PAS inquiry</li>
     *   <li>a read operation sent with any other method is treated as a write: "Subscription get"
     *       sent as DELETE is a delete</li>
     *   <li>an unmapped request is a write unless its method cannot change state, and so is a call
     *       with no method at all</li>
     *   <li>an operation that is not outbound is judged like an unmapped request. The tool never
     *       sends one, so if a collection request detected as one were ever pointed at a vendor
     *       url, nothing is known about what it would do there</li>
     * </ul>
     * Wherever the direction of the error is uncertain, the answer is "write", because wrongly
     * blocking a read costs a click and wrongly allowing a write costs a real authorization.
     */
    public boolean writesWith(String method) {
        if (write) {
            return true;
        }
        boolean unknownEffect = this == UNMAPPED || !outbound();
        String normalised = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return unknownEffect;
        }
        if (normalised.equals("GET") || normalised.equals("HEAD") || normalised.equals("OPTIONS")) {
            return false;
        }
        return unknownEffect || !normalised.equals(defaultMethod);
    }
}
