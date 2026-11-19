package org.p32h.interop.epa.pave.model;

/**
 * Whether a request reached the service it was meant for.
 *
 * <p>This is what decides the advice after a write that got no usable answer. A prior
 * authorization request that never reached the payer can be sent again. One that may have reached
 * it must be asked about first, because the payer may already hold it, and a second copy would be
 * a second request. The failure type alone does not say which: "unavailable" covers a connection
 * that was refused and one that broke after the request had gone out.
 */
public enum Reached {
    /**
     * The connection was never opened, or nothing was sent at all, so nothing was delivered and
     * the service cannot have acted.
     */
    NO,
    /**
     * A response arrived, so the request was received at the far end: by the service, or by a
     * gateway in front of it. After an answer that could not be used, the service may have acted.
     */
    YES,
    /**
     * The request may have gone out, and no answer came back to say whether it arrived: a read
     * timeout, or a connection that failed after it was opened. The service may have acted.
     */
    CANNOT_SAY
}
