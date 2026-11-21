package org.p32h.interop.epa.pave.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.apache.catalina.connector.ClientAbortException;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The outermost filter of this tool: the headers every response carries, and the last word on
 * anything that fails beneath it.
 *
 * <ul>
 *   <li>Nothing this tool serves is stored by the browser. An answer can hold a member's name and
 *       date of birth, and "no member data is persisted" has to include the browser's disk cache.
 *       This is said of every response and not of "the API responses", because telling the two
 *       apart by address is where such a rule goes wrong: the framework also finds an API
 *       controller for spellings such as {@code /%61pi/...}, which a test on the address as
 *       written does not take for one. The pages and scripts are small and come from this
 *       machine, so fetching them again on every load costs nothing a tester would notice, and an
 *       edit to one shows on refresh.</li>
 *   <li>A page of this tool loads scripts, styles and data from this tool only, and cannot be
 *       framed by another page. A vendor response is untrusted text; if a piece of it ever
 *       reached the page as markup, this is what stops it running or calling out.</li>
 *   <li>The referrer policy is {@code same-origin}: an address of this tool, which can hold a
 *       member ID, is never sent to another site, and a form on this tool's own page still states
 *       its own origin. Under {@code no-referrer} a browser sends {@code Origin: null} for a plain
 *       form post, which no check can tell from a sandboxed frame.</li>
 * </ul>
 *
 * <p>The headers are set again on the error dispatch, so an error the server hands to the error
 * page carries them too. Answers the server gives before any filter runs get them from
 * {@link JsonErrorReportValve}, through {@link #apply}.
 *
 * <p>Anything thrown beneath this filter is answered here, in the shared error shape with a
 * reference, and logged as exception types and frames only. Left to the server, such an exception
 * is logged by loggers this tool does not own, with its message, and an exception message can
 * quote the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ResponseHeadersFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ResponseHeadersFilter.class);

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** The five headers every response of this tool carries. */
    static void apply(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Cache-Control", "no-store");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        apply(response);
        chain.doFilter(request, response);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        apply(response);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            if (clientWentAway(e)) {
                // The browser closed the connection. There is nobody to answer and nothing wrong with this tool.
                return;
            }
            String reference = UUID.randomUUID().toString();
            // Exception types and frames under a reference, never a message beyond what Faults.cause
            // allows: a message can quote the request. The browser gets the first line, without frames.
            log.error("Unhandled exception at the edge, reference {}: {}", reference, Faults.describe(e));
            EdgeAnswers.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ErrorKind.INTERNAL,
                    "This tool hit an internal error and could not complete the request.", Faults.REPORT_ACTION, reference,
                    Faults.cause(e));
        }
    }

    static boolean clientWentAway(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }
}
