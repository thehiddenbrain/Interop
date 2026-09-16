package com.thehiddenbrain.interop.patientaccess.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decodes {@code application/x-www-form-urlencoded} bodies and URL query strings into a map for assertions. */
public final class Forms {

    private Forms() {
    }

    public static Map<String, String> parse(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    /** The decoded query parameters of a URL. */
    public static Map<String, String> query(String url) {
        int q = url.indexOf('?');
        return q < 0 ? new LinkedHashMap<>() : parse(url.substring(q + 1));
    }
}
