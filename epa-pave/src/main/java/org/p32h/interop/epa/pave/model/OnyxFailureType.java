package org.p32h.interop.epa.pave.model;

/** How a call to a vendor failed at the transport or HTTP level. NONE when it did not. */
public enum OnyxFailureType {
    NONE,
    /** The service answered with an HTTP 4xx status. */
    BAD_REQUEST,
    /** The service answered with an HTTP 5xx status. */
    SERVER_ERROR,
    /** No response arrived inside the read timeout. */
    TIMEOUT,
    /** No connection could be made. */
    UNAVAILABLE,
    /** A success status arrived with a body that is empty or not JSON. */
    MALFORMED_RESPONSE
}
