package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The response exactly as it arrived, for the wire view.
 *
 * @param status     the HTTP status, or null when no response arrived
 * @param body       the body as received, or null when there was none
 * @param byteLength the number of bytes received, or null when no response arrived
 * @param parsed     true when the body was read as JSON
 * @param parseError why the body could not be read as JSON, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireResponse(Integer status, List<WireHeader> headers, String body, Long byteLength, boolean parsed,
                           String parseError) {

    public WireResponse {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** Redacted, because the body carries member data and must not reach a log through toString. */
    @Override
    public String toString() {
        return "WireResponse[status=" + status + ", body redacted]";
    }
}
