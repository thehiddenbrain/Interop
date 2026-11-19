package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The response as it arrived, for the wire view, with one exception that it states itself: a place
 * where the response repeats a secret the request sent is shown as that secret's mask, written
 * {@code [secret name]} as on the request side. A service can send a credential back, most often
 * in a Subscription that returns the header it was created with, and the wire view is what a
 * tester screenshots.
 *
 * @param status       the HTTP status, or null when no response arrived
 * @param headers      the response headers, masked in the same way as the body
 * @param body         the body as received, apart from any masked place, or null when there was none
 * @param byteLength   the number of bytes received, counted before decoding and before any masking,
 *                     or null when no response arrived
 * @param parsed       true when the body was read as JSON
 * @param parseError   why the body could not be read as JSON, or null
 * @param maskedPlaces how many places in the headers and the body together are shown as a mask.
 *                     Zero means the headers and the body are shown as they were received
 * @param maskNote     what a tester should know about the masking, in plain words: how many places
 *                     were masked and why, that a secret too short to search for was not looked
 *                     for, and that a secret whose value could not be worked out was not looked for
 *                     either. Null when nothing was masked and nothing was left unsearched
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireResponse(Integer status, List<WireHeader> headers, String body, Long byteLength, boolean parsed,
                           String parseError, int maskedPlaces, String maskNote) {

    public WireResponse {
        headers = headers == null ? List.of() : List.copyOf(headers);
        if (maskedPlaces < 0) {
            throw new IllegalArgumentException("The number of masked places cannot be negative.");
        }
        if (maskedPlaces > 0 && Texts.blank(maskNote)) {
            // "Exactly as received" must never be claimed in silence for a body that was changed for display.
            throw new IllegalArgumentException("A response with masked places must say so.");
        }
    }

    /** A response shown exactly as it was received. */
    public WireResponse(Integer status, List<WireHeader> headers, String body, Long byteLength, boolean parsed, String parseError) {
        this(status, headers, body, byteLength, parsed, parseError, 0, null);
    }

    /** Redacted, because the body carries member data and must not reach a log through toString. */
    @Override
    public String toString() {
        return "WireResponse[status=" + status + ", body redacted]";
    }
}
