package com.thehiddenbrain.interop.patientaccess.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps credentials out of the history, logs and cURL exports. */
public final class Redaction {

    public static final String MASK = "***";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALWAYS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "apikey", "ocp-apim-subscription-key", "x-auth-token", "client_secret");
    private static final Set<String> BODY_FIELDS = Set.of("access_token", "refresh_token", "id_token", "client_secret",
            "client_assertion", "code", "code_verifier");

    private Redaction() {
    }

    public static boolean isSensitiveHeader(String name, Set<String> extra) {
        String n = name.toLowerCase(Locale.ROOT);
        return ALWAYS.contains(n) || (extra != null && extra.contains(n));
    }

    public static Map<String, String> headers(Map<String, String> headers, Set<String> extra) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((k, v) -> out.put(k, isSensitiveHeader(k, extra) ? maskValue(k, v) : v));
        return out;
    }

    public static Map<String, List<String>> multiHeaders(Map<String, List<String>> headers, Set<String> extra) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((k, v) -> {
            if (k == null) {
                return;
            }
            out.put(k, isSensitiveHeader(k, extra) ? List.of(MASK) : v);
        });
        return out;
    }

    /** "Bearer ***" keeps the scheme visible; everything else is fully masked. */
    static String maskValue(String name, String value) {
        if (value != null && name.equalsIgnoreCase("authorization")) {
            int space = value.indexOf(' ');
            if (space > 0) {
                return value.substring(0, space) + " " + MASK;
            }
        }
        return MASK;
    }

    /** Masks token-like fields in a JSON or form-encoded body (token endpoint traffic). */
    public static String body(String body, String contentType) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node instanceof ObjectNode obj) {
                    boolean changed = false;
                    for (String f : BODY_FIELDS) {
                        if (obj.has(f)) {
                            obj.put(f, MASK);
                            changed = true;
                        }
                    }
                    return changed ? MAPPER.writeValueAsString(obj) : body;
                }
            } catch (Exception ignored) {
                // not JSON after all
            }
            return body;
        }
        if (ct.contains("x-www-form-urlencoded")) {
            StringBuilder sb = new StringBuilder();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (sb.length() > 0) {
                    sb.append('&');
                }
                if (BODY_FIELDS.contains(key.toLowerCase(Locale.ROOT))) {
                    sb.append(key).append('=').append(MASK);
                } else {
                    sb.append(pair);
                }
            }
            return sb.toString();
        }
        return body;
    }
}
