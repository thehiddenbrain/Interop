package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The outbound request as this tool built it, for the wire view. Two headers are not listed
 * because the HTTP client writes them itself: Host and Content-Length. A header of the collection
 * request that the client would not let through as written is left out, and the note says which.
 *
 * <p>The request also knows which secret values it carried, so that the response to it can be
 * shown without them. They travel with the request, and not beside it, because the request is the
 * one thing every description of the same call is built around: a mock client that rewrites a
 * response body keeps the request, and so keeps what must stay hidden in whatever it builds.
 *
 * @param url         the resolved url with secrets masked, or null when no url is configured
 * @param urlTemplate the url as configured, placeholders intact
 * @param body        the exact body, or null when the request has none
 * @param sent        false when nothing left this machine: the mock profile, or a blocked write
 * @param note        why nothing was sent, or anything else a tester should know about how the
 *                    request was put on the wire. Null when there is nothing to say
 * @param secrets     the secret values the request carried in its url, headers and body. Never
 *                    serialised and never printed. Never null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireRequest(String method, String url, String urlTemplate, List<WireHeader> headers, String body,
                          boolean sent, String note, @JsonIgnore SentSecrets secrets) {

    public WireRequest {
        headers = headers == null ? List.of() : List.copyOf(headers);
        secrets = secrets == null ? SentSecrets.none() : secrets;
    }

    /** A request that carried no secret, or one that never had a response to show: nothing was sent, or it was refused. */
    public WireRequest(String method, String url, String urlTemplate, List<WireHeader> headers, String body, boolean sent, String note) {
        this(method, url, urlTemplate, headers, body, sent, note, SentSecrets.none());
    }

    /** Redacted, because the body carries member data and must not reach a log through toString. */
    @Override
    public String toString() {
        return "WireRequest[" + method + ", body redacted]";
    }
}
