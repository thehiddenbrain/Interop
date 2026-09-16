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

    /** Encodes a query value: reserved FHIR characters (| , : /) stay readable, everything else is percent-encoded. */
    public static String encode(String value) {
        if (value == null) {
            return "";
        }
        String enc = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return enc.replace("+", "%20").replace("%7C", "|").replace("%2C", ",").replace("%3A", ":").replace("%2F", "/");
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
        return env.baseUrl() + "/" + resourceType + (q.isEmpty() ? "" : "?" + q);
    }

    public static String readUrl(Environment env, String resourceType, String id) {
        return env.baseUrl() + "/" + resourceType + "/" + encodePath(id);
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
        guard(env, target);
        return target;
    }

    /** Throws unless {@code target} is under the environment's base URL (host mismatch allowed only when configured). */
    public static void guard(Environment env, String target) {
        URI base;
        URI t;
        try {
            base = new URI(env.baseUrl());
            t = new URI(target);
        } catch (URISyntaxException e) {
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "not a valid URL: " + target);
        }
        if (t.getScheme() == null || t.getHost() == null) {
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "not an absolute URL: " + target);
        }
        boolean sameHost = t.getScheme().equalsIgnoreCase(base.getScheme())
                && t.getHost().equalsIgnoreCase(base.getHost())
                && port(t) == port(base);
        if (!sameHost) {
            if (env.fhir().allowNextLinkHostMismatch()) {
                return;
            }
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "URL " + target + " is not on the environment's host "
                    + base.getHost() + " (enable fhir.allowNextLinkHostMismatch if the server pages through another host)");
        }
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        String path = t.getRawPath() == null ? "" : t.getRawPath();
        if (!(path.equals(basePath) || path.startsWith(basePath.endsWith("/") ? basePath : basePath + "/"))) {
            if (env.fhir().allowNextLinkHostMismatch()) {
                return;
            }
            throw new WorkbenchException(ErrorCode.TARGET_NOT_ALLOWED, "URL " + target + " is outside the FHIR base path " + basePath);
        }
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
