package org.p32h.interop.epa.pave.model;

/** How a call to a vendor failed at the transport or HTTP level. NONE when it did not. */
public enum OnyxFailureType {
    NONE,
    /** The service answered with a status that is neither a success nor 5xx: 4xx, and also 1xx and 3xx. */
    BAD_REQUEST,
    /** The service answered with an HTTP 5xx status. */
    SERVER_ERROR,
    /** No response arrived inside the read timeout. */
    TIMEOUT,
    /** No connection could be made. */
    UNAVAILABLE,
    /** A success status arrived with a body that is empty or not JSON. */
    MALFORMED_RESPONSE,
    /**
     * Authentication is on and this tool obtained no access token because of its own sign-in
     * settings or the token endpoint's refusal of the client, so nothing was sent.
     */
    NOT_SIGNED_IN,
    /**
     * Authentication is on and the token endpoint could not be reached, timed out, failed or gave
     * no usable token, so nothing was sent.
     */
    TOKEN_ENDPOINT_FAULT
}
