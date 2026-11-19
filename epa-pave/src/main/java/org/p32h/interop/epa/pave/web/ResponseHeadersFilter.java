package org.p32h.interop.epa.pave.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Headers every response carries, set in one place.
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
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ResponseHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
