package org.p32h.interop.epa.pave.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps other web pages away from this tool.
 *
 * <p>The tool has no sign-in. It runs on a tester's machine and, on the live profile, holds the
 * credentials that look up real UAT members. The tester's browser has other pages open. Without
 * this filter, any of them could send requests to this tool, and with a host name pointed at this
 * machine could read the answers as well. Two checks stop that:
 * <ul>
 *   <li><b>The host a request names.</b> A request must be addressed to localhost, a loopback
 *       address, the address the server is bound to, or a name listed in
 *       {@code app.allowed-hosts}. A page served from another name that resolves to this machine
 *       is refused, because the browser sends that other name.</li>
 *   <li><b>Where a request comes from.</b> A request a browser says another site's page made is
 *       refused, whatever its method. Refusing changes alone is not enough: a read acts too. On the
 *       live profile a lookup asks the payer about an identifier the other page chose, with this
 *       tool's credentials, and a link to the ledger export makes the browser save the ledger to
 *       the tester's disk. That the browser would not show the answer to the other page does not
 *       undo either.</li>
 * </ul>
 *
 * <p>Where a request comes from is read from {@code Sec-Fetch-Site} when the browser sends it,
 * since a page can neither set nor forge that header. {@code same-origin} is this tool's own page
 * and {@code none} is the tester typing an address or opening a bookmark; both are let through
 * without looking at {@code Origin}, which a browser can set to {@code null} on the tool's own
 * plain form post. Any other value is another site. Only when that header is absent is
 * {@code Origin} compared with the host the request names: scheme, host and port, and nothing
 * else in it. A client that sends neither header is not a web page, such as curl or a vendor's
 * server, and is let through.
 *
 * <p>One request from another site is let past this filter: a top-level navigation by GET, so that
 * a link to the tool on a wiki page still opens it. {@link CrossSiteNavigationInterceptor} then
 * refuses it whenever the framework hands it to a controller method, so such a link opens a page
 * of the tool and never an answer of the API. No address is sorted here into page and API.
 *
 * <p>What this leaves open: a request another page makes that carries neither
 * {@code Sec-Fetch-Site} nor {@code Origin}. Browsers send {@code Sec-Fetch-Site} only to an
 * origin they hold to be secure, which includes http://localhost and the loopback addresses but
 * not another name served over plain http, and an older browser does not send it at all. Under a
 * name listed in {@code app.allowed-hosts}, or in such a browser, another page's image or link
 * still reaches a read, as before this check; a change for which the browser states the other
 * origin is still refused. Nothing in such a request can tell it from curl.
 *
 * <p><b>Both checks apply to every request, whatever its address.</b> An earlier version applied
 * them to addresses that start with {@code /api/} and let pages and static files through. That
 * was a hole: the server, the framework and a filter each read an address in their own way, and
 * the framework also finds an API controller for {@code /%61pi/...}, which does not start with
 * {@code /api/} as written. A check that first has to decide which addresses are the API can be
 * got round by anyone who finds a spelling it decides wrongly, so this filter decides nothing of
 * the kind.
 *
 * <p>A delivery to the notification receiver is exempt from the host check: a vendor reaches it
 * under whatever name the network gives this machine, which the tool cannot know. It records what
 * arrives and gives nothing back, so the name it is reached under exposes nothing. Reading what
 * was received is an ordinary read and is not exempt. The exemption is for that one address
 * written exactly: any other spelling of it is held to the check like everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiGuardFilter.class);

    static final String RECEIVER_PATH = "/api/pas/notifications";
    static final String ANOTHER_SITE_TITLE = "This request came from a page that is not part of this tool, so it was not carried out.";
    static final String ANOTHER_SITE_ACTION = "Open this tool from its own address, http://localhost and its port, and do it from there.";

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
        for (String entry : app.allowedHosts()) {
            if (entry.equals("*")) {
                any = true;
                continue;
            }
            String host = entryHost(entry);
            if (entryNamesPort(entry)) {
                // The entry is the operator's own setting, a machine name and not member data.
                log.warn("The entry {} under app.allowed-hosts names a port. A host name is matched whatever port it is used with, "
                        + "so the port is ignored and {} is used.", entry, host);
            }
            allowedHosts.add(host);
        }
        this.anyHost = any;
    }

    /**
     * An entry of {@code app.allowed-hosts}, read as a host name: lower case, a port written with it
     * dropped, and the dot that ends a fully qualified name dropped, as on a Host header. An entry
     * that is not a host name at all, such as one written as a url, is refused with a sentence that
     * names it: read as a host it would let in a name nobody meant and still refuse the one meant.
     */
    static String entryHost(String entry) {
        String value = entry.strip();
        if (value.isEmpty() || value.chars().anyMatch(c -> c == '/' || c == '@' || c == '?' || c == '#' || Character.isWhitespace(c))) {
            throw notAHostName(entry);
        }
        String host;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || !portOrNothing(value.substring(end + 1))) {
                throw notAHostName(entry);
            }
            host = value.substring(1, end);
        } else if (value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            int colon = value.indexOf(':');
            if (colon == 0 || !portOrNothing(value.substring(colon))) {
                throw notAHostName(entry);
            }
            host = value.substring(0, colon);
        } else {
            // No colon, or several: a bare IPv6 address has several colons and no port.
            host = value;
        }
        host = host.toLowerCase(Locale.ROOT);
        host = host.endsWith(".") && host.length() > 1 ? host.substring(0, host.length() - 1) : host;
        if (host.isEmpty() || host.equals(".")) {
            throw notAHostName(entry);
        }
        return host;
    }

    /** True when an entry of {@code app.allowed-hosts} carries a port, which {@link #entryHost} drops. */
    static boolean entryNamesPort(String entry) {
        String value = entry.strip();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end >= 0 && value.length() > end + 1;
        }
        return value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':');
    }

    /** A colon followed by the digits of a port, or nothing at all. */
    private static boolean portOrNothing(String rest) {
        return rest.isEmpty() || (rest.length() > 1 && rest.length() <= 6 && rest.charAt(0) == ':'
                && rest.substring(1).chars().allMatch(c -> c >= '0' && c <= '9'));
    }

    private static IllegalStateException notAHostName(String entry) {
        // The entry is the operator's own setting, a machine name and not member data.
        return new IllegalStateException("The entry " + entry.strip() + " under app.allowed-hosts is not a host name, so the tool "
                + "did not start. Write the name alone, such as qa-laptop.example, with no scheme such as http://, no path and no "
                + "user name.");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only the delivery itself, at its address written exactly. Reading what was received is an ordinary read.
        boolean receiver = "POST".equals(request.getMethod()) && RECEIVER_PATH.equals(request.getRequestURI());
        String host = hostOf(request.getHeader("Host"));
        if (!anyHost && !receiver && (host == null || !allowedHosts.contains(host))) {
            refuse(request, response, mapper, "This request was addressed to a host name this tool does not answer to.",
                    "Open the tool as http://localhost and its port. To use another name for this machine, list the name under "
                            + "app.allowed-hosts and start the tool again.");
            return;
        }
        if (fromAnotherSite(request) && !topLevelNavigation(request)) {
            refuseFromAnotherSite(request, response, mapper);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * True when a browser says another site's page made the request. {@code Sec-Fetch-Site}
     * decides when present; {@code Origin} is consulted only when it is absent.
     */
    static boolean fromAnotherSite(HttpServletRequest request) {
        String site = Texts.trimToNull(request.getHeader("Sec-Fetch-Site"));
        if (site != null) {
            // "none" is a request the person made themselves, such as from the address bar.
            return !site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none");
        }
        String origin = Texts.trimToNull(request.getHeader("Origin"));
        return origin != null && !sameOrigin(origin, request);
    }

    /** A link followed in the top-level window by GET, the one request from another site let past this filter. */
    private static boolean topLevelNavigation(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && "navigate".equalsIgnoreCase(Texts.trimToNull(request.getHeader("Sec-Fetch-Mode")))
                && "document".equalsIgnoreCase(Texts.trimToNull(request.getHeader("Sec-Fetch-Dest")));
    }

    /**
     * True when the origin is this tool's: the scheme the request came in on, the host and the port
     * it names, and nothing more. {@code null}, which a sandboxed frame or a local file sends, and
     * an origin with user information, a path or a query are never this tool's.
     */
    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        String host = request.getHeader("Host");
        URI from;
        try {
            from = new URI(origin);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = request.isSecure() ? "https" : "http";
        if (host == null || from.getHost() == null || !scheme.equalsIgnoreCase(from.getScheme()) || from.getRawUserInfo() != null
                || Texts.present(from.getRawPath()) || from.getRawQuery() != null || from.getRawFragment() != null) {
            return false;
        }
        int port = from.getPort() >= 0 ? from.getPort() : request.isSecure() ? 443 : 80;
        return hostOf(from.getHost()).equals(hostOf(host)) && port == portOf(host, request);
    }

    /**
     * The host of a Host header, lower case, without its port, without the brackets of an IPv6
     * literal and without the dot that ends a fully qualified name, so {@code localhost.} is
     * {@code localhost}.
     */
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
        String host = bare(colon < 0 ? value : value.substring(0, colon));
        return host.endsWith(".") && host.length() > 1 ? host.substring(0, host.length() - 1) : host;
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

    static void refuseFromAnotherSite(HttpServletRequest request, HttpServletResponse response, JsonMapper mapper) throws IOException {
        refuse(request, response, mapper, ANOTHER_SITE_TITLE, ANOTHER_SITE_ACTION);
    }

    /**
     * Refuses in the shared error shape. A browser that is opening a page gets the same two
     * sentences as plain text, since nothing of this tool is there yet to show the other form.
     */
    private static void refuse(HttpServletRequest request, HttpServletResponse response, JsonMapper mapper, String title, String action)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        String accepts = request.getHeader("Accept");
        if (accepts != null && accepts.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_HTML_VALUE)) {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write(title + "\n" + action + "\n");
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(ErrorView.of(ErrorKind.VALIDATION, title, action)));
    }
}
