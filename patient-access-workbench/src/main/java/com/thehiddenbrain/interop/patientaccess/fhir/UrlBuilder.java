package com.thehiddenbrain.interop.patientaccess.fhir;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds FHIR URLs with FHIR-friendly encoding and refuses targets outside the environment. */
public final class UrlBuilder {

    private UrlBuilder() {
    }

    /** Encodes a query value: , : / stay readable, | becomes %7C (java.net.URI rejects a bare pipe), the rest is percent-encoded. */
    public static String encode(String value) {
        if (value == null) {
            return "";
        }
        String enc = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return enc.replace("+", "%20").replace("%2C", ",").replace("%3A", ":").replace("%2F", "/");
    }

    /** Percent-encodes the characters java.net.URI refuses in URLs a server handed us (next links, references). */
    public static String sanitize(String url) {
        if (url == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(url.length() + 8);
        for (char c : url.toCharArray()) {
            switch (c) {
                case ' ' -> sb.append("%20");
                case '|' -> sb.append("%7C");
                case '"' -> sb.append("%22");
                case '<' -> sb.append("%3C");
                case '>' -> sb.append("%3E");
                case '{' -> sb.append("%7B");
                case '}' -> sb.append("%7D");
                case '^' -> sb.append("%5E");
                case '`' -> sb.append("%60");
                case '\\' -> sb.append("%5C");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String query(MultiValueMap<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            for (String v : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(encode(e.getKey()).replace("%3A", ":")).append('=').append(encode(v));
            }
        }
        return sb.toString();
    }

    public static String searchUrl(Environment env, String resourceType, MultiValueMap<String, String> params) {
        String q = query(params);
        return env.baseUrlFor(IgRouting.igFor(resourceType, params)) + "/" + resourceType + (q.isEmpty() ? "" : "?" + q);
    }

    public static String readUrl(Environment env, String resourceType, String id) {
        return readUrl(env, resourceType, id, IgRouting.igFor(resourceType, null));
    }

    /** Read URL on the base of a specific IG (a prior-auth EOB lives on the PDex base). */
    public static String readUrl(Environment env, String resourceType, String id, String igKey) {
        return env.baseUrlFor(igKey) + "/" + resourceType + "/" + encodePath(id);
    }

    public static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Resolves a relative or absolute URL against the base URL and checks it stays inside the environment. */
    public static String resolveInside(Environment env, String url) {
        String base = env.baseUrl();
        String target;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            target = url;
        } else {
            target = base + (url.startsWith("/") ? url : "/" + url);
        }
        target = sanitize(target);
        guard(env, target);
        return target;
    }

    /** Throws unless {@code target} is under one of the environment's base URLs (host mismatch allowed only when configured). */
    public static void guard(Environment env, String target) {
        URI t;
        try {
            t = new URI(sanitize(target));
        } catch (URISyntaxException e) {
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "not a valid URL: " + target);
        }
        if (t.getScheme() == null || t.getHost() == null) {
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "not an absolute URL: " + target);
        }
        boolean anyHost = false;
        for (String baseUrl : env.allBaseUrls()) {
            URI base;
            try {
                base = new URI(sanitize(baseUrl));
            } catch (URISyntaxException e) {
                continue;
            }
            boolean sameHost = t.getScheme().equalsIgnoreCase(base.getScheme())
                    && t.getHost().equalsIgnoreCase(base.getHost())
                    && port(t) == port(base);
            if (!sameHost) {
                continue;
            }
            anyHost = true;
            String basePath = base.getRawPath() == null ? "" : base.getRawPath();
            String path = t.getRawPath() == null ? "" : t.getRawPath();
            if (path.equals(basePath) || path.startsWith(basePath.endsWith("/") ? basePath : basePath + "/")) {
                return;
            }
        }
        if (env.fhir().allowNextLinkHostMismatch()) {
            return;
        }
        if (!anyHost) {
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "URL " + target + " is not on the environment's host(s) "
                    + env.allBaseUrls() + " (enable fhir.allowNextLinkHostMismatch if the server pages through another host)");
        }
        throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "URL " + target + " is outside the FHIR base path(s) " + env.allBaseUrls());
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    public static String hostOf(String url) {
        try {
            return new URI(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
