package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The outbound request exactly as it was built, for the wire view.
 *
 * @param url         the resolved url with secrets masked, or null when no url is configured
 * @param urlTemplate the url as configured, placeholders intact
 * @param body        the exact body, or null when the request has none
 * @param sent        false when nothing left this machine: the mock profile, or a blocked write
 * @param note        why nothing was sent, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireRequest(String method, String url, String urlTemplate, List<WireHeader> headers, String body,
                          boolean sent, String note) {

    public WireRequest {
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** Redacted, because the body carries member data and must not reach a log through toString. */
    @Override
    public String toString() {
        return "WireRequest[" + method + ", body redacted]";
    }
}
