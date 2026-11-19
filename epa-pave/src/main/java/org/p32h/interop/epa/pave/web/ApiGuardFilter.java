package org.p32h.interop.epa.pave.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Texts;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps other web pages away from this tool's API.
 *
 * <p>The tool has no sign-in. It runs on a tester's machine and, on the live profile, holds the
 * credentials that look up real UAT members. The tester's browser has other pages open. Without
 * this filter, any of them could send requests to this tool, and with a host name pointed at this
 * machine could read the answers as well. Two checks stop that:
 * <ul>
 *   <li><b>The host a request names.</b> An API request must be addressed to localhost, a
 *       loopback address, the address the server is bound to, or a name listed in
 *       {@code app.allowed-hosts}. A page served from another name that resolves to this machine
 *       is refused, because the browser sends that other name.</li>
 *   <li><b>Where a change comes from.</b> A request that changes something must come from this
 *       tool's own pages. A browser states the origin of such a request, and one that states a
 *       different origin is refused. A client that states none, such as curl or a vendor's
 *       server, is not a web page and is let through.</li>
 * </ul>
 *
 * <p>A delivery to the notification receiver is exempt from the first check: a vendor reaches it
 * under whatever name the network gives this machine, which the tool cannot know. It records what
 * arrives and gives nothing back, so the name it is reached under exposes nothing. Reading what
 * was received is an ordinary API read and is not exempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiGuardFilter extends OncePerRequestFilter {

    static final String RECEIVER_PATH = "/api/pas/notifications";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedHosts = new LinkedHashSet<>();
    private final boolean anyHost;
    private final JsonMapper mapper;

    public ApiGuardFilter(AppProperties app, Environment environment, JsonMapper mapper) {
        this.mapper = mapper;
        allowedHosts.add("localhost");
        allowedHosts.add("127.0.0.1");
        allowedHosts.add("::1");
        String bound = Texts.trimToNull(environment.getProperty("server.address"));
        // A wildcard bind address is not a name anyone can type, so it adds nothing.
        if (bound != null && !bound.equals("0.0.0.0") && !bound.equals("::")) {
            allowedHosts.add(bare(bound));
        }
        boolean any = false;
        for (String host : app.allowedHosts()) {
            if (host.equals("*")) {
                any = true;
            } else {
                allowedHosts.add(bare(host));
            }
        }
        this.anyHost = any;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only the delivery itself. Reading what was received is an ordinary API read.
        boolean receiver = "POST".equals(request.getMethod()) && RECEIVER_PATH.equals(request.getRequestURI());
        String host = hostOf(request.getHeader("Host"));
        if (!anyHost && !receiver && (host == null || !allowedHosts.contains(host))) {
            refuse(response, "This request was addressed to a host name this tool does not answer to.",
                    "Open the tool as http://localhost and its port. To use another name for this machine, list the name under "
                            + "app.allowed-hosts and start the tool again.");
            return;
        }
        if (!READS.contains(request.getMethod()) && fromAnotherSite(request)) {
            refuse(response, "This request came from a page that is not part of this tool, so it was not carried out.",
                    "Make the change from this tool's own screen.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** True when a browser says the request was made by a page of another origin. */
    private static boolean fromAnotherSite(HttpServletRequest request) {
        String site = Texts.trimToNull(request.getHeader("Sec-Fetch-Site"));
        // "none" is a request the person made themselves, such as from the address bar.
        if (site != null && !site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none")) {
            return true;
        }
        String origin = Texts.trimToNull(request.getHeader("Origin"));
        if (origin == null) {
            return false;
        }
        String host = request.getHeader("Host");
        try {
            URI from = URI.create(origin);
            if (from.getHost() == null || host == null) {
                // "null" is what a sandboxed frame or a local file sends. Neither is this tool's page.
                return true;
            }
            int port = from.getPort() >= 0 ? from.getPort() : "https".equalsIgnoreCase(from.getScheme()) ? 443 : 80;
            return !bare(from.getHost()).equals(hostOf(host)) || port != portOf(host, request);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** The host of a Host header, lower case, without its port and without the brackets of an IPv6 literal. */
    static String hostOf(String header) {
        String value = Texts.trimToNull(header);
        if (value == null) {
            return null;
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? null : value.substring(1, end).toLowerCase(Locale.ROOT);
        }
        int colon = value.lastIndexOf(':');
        return bare(colon < 0 ? value : value.substring(0, colon));
    }

    private static int portOf(String header, HttpServletRequest request) {
        String value = header.strip();
        int from = value.startsWith("[") ? value.indexOf(']') : 0;
        int colon = value.indexOf(':', Math.max(from, 0));
        if (colon >= 0) {
            try {
                return Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return request.isSecure() ? 443 : 80;
    }

    private static String bare(String host) {
        String value = host.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
    }

    private void refuse(HttpServletResponse response, String title, String action) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(ErrorView.of(ErrorKind.VALIDATION, title, action)));
    }
}
