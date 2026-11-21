package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.apache.catalina.connector.ClientAbortException;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * A client that closed the connection before this tool had written its answer: a page reloaded
 * while a call waited, a script that gave up on a slow answer. Nothing is wrong with this tool, and
 * nobody will see a screen to match a reference with, so it is one line at INFO and not an entry
 * under a reference, which the operator is told to report.
 *
 * <p>It is known only by the types the server and the framework raise for it: the embedded
 * server's {@link ClientAbortException}, which it throws for every write to a connection that is
 * gone, and the framework's {@link AsyncRequestNotUsableException}, which it throws when the
 * response can no longer be used because such a write failed. No message is read and no other
 * type is taken for it: a defect filed as a client that left would never be looked at.
 */
final class ClientGone {

    private static final Logger log = LoggerFactory.getLogger(ClientGone.class);

    /** Set on a request once its line is written, so a second failure of the same answer adds none. */
    private static final String LOGGED = ClientGone.class.getName() + ".LOGGED";

    private static final Set<String> KNOWN_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");

    private ClientGone() {
    }

    /** True when the server or the framework says, by its own type, that the connection is gone. */
    static boolean is(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 32; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }

    /** Writes the one line, once for a request. */
    static void log(HttpServletRequest request, Throwable error) {
        try {
            if (request != null) {
                if (request.getAttribute(LOGGED) != null) {
                    return;
                }
                request.setAttribute(LOGGED, Boolean.TRUE);
            }
            // The method, the route the request matched and the exception types. Never the address as
            // sent, which can hold a member ID, never a query string, a body or a message.
            log.info("The client closed the connection before the answer was written: {} {}, {}", method(request), route(request),
                    Faults.cause(error));
        } catch (RuntimeException e) {
            // Nothing to tell and nobody to tell it to: the line is not worth a failure of its own.
        }
    }

    /** A method HTTP defines, as sent; any other word a request line held is not repeated. */
    private static String method(HttpServletRequest request) {
        String method = request == null ? null : request.getMethod();
        return method != null && KNOWN_METHODS.contains(method) ? method : "another method";
    }

    /**
     * The route the request matched, such as {@code /api/members/{memberId}}: it names the endpoint
     * and holds no identifier, where the address as sent would hold the member ID itself.
     */
    private static String route(HttpServletRequest request) {
        Object pattern = request == null ? null : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route && !route.isBlank() ? route : "(a request that matched no route of this tool)";
    }
}
