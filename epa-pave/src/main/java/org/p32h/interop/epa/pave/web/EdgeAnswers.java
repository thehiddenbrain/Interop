package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.springframework.http.MediaType;

/**
 * The shared error shape, written where the framework's converters are not in reach: by the
 * outermost filter, by the filter that refuses a badly formed request, and by the valve that
 * answers the embedded server's own errors.
 *
 * <p>The body is written by hand so that the valve, which the server builds by name, needs no
 * bean. It has the components of {@link org.p32h.interop.epa.pave.model.ErrorView} in the order
 * the mapper writes them, and no {@code httpStatus}: in that shape the field is the status a
 * vendor answered with, and an error of this tool's own has no vendor. Its status is the status
 * of the response. Every text written here is a fixed sentence of this tool or a reference it
 * made, never text of the request.
 */
final class EdgeAnswers {

    private EdgeAnswers() {
    }

    /** The shared shape as JSON. {@code reference} may be null. */
    static String json(ErrorKind kind, String title, String action, String reference) {
        StringBuilder out = new StringBuilder("{\"kind\":\"").append(kind.name()).append("\",\"title\":");
        quote(out, title).append(",\"action\":");
        quote(out, action).append(",\"fieldErrors\":[],\"issues\":[]");
        if (reference != null) {
            quote(out.append(",\"reference\":"), reference);
        }
        return out.append('}').toString();
    }

    /**
     * Answers with the given status in the shared shape and the headers every response carries,
     * replacing anything not yet sent. Does nothing once the response has started to go out.
     */
    static void write(HttpServletResponse response, int status, ErrorKind kind, String title, String action, String reference)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        ResponseHeadersFilter.apply(response);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json(kind, title, action, reference));
        response.flushBuffer();
    }

    private static StringBuilder quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"');
    }
}
