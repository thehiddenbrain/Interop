package com.thehiddenbrain.interop.patientaccess.history;

import java.util.List;
import java.util.Map;

/** Renders a history entry as a cURL command; masked headers become shell variables the tester fills in. */
public final class CurlRenderer {

    private CurlRenderer() {
    }

    public static String render(RequestRecord r) {
        StringBuilder sb = new StringBuilder("curl -sS -X ").append(r.method()).append(" \\\n  ").append(quote(r.url()));
        for (Map.Entry<String, String> h : r.requestHeaders().entrySet()) {
            String value = h.getValue();
            if (value != null && value.contains(Redaction.MASK)) {
                String var = h.getKey().equalsIgnoreCase("authorization") ? "$TOKEN" : "$" + h.getKey().toUpperCase().replaceAll("[^A-Z0-9]", "_");
                value = value.replace(Redaction.MASK, var);
                sb.append(" \\\n  -H \"").append(h.getKey()).append(": ").append(value).append('"');
            } else {
                sb.append(" \\\n  -H ").append(quote(h.getKey() + ": " + value));
            }
        }
        if (r.requestBody() != null && !r.requestBody().isEmpty()) {
            sb.append(" \\\n  --data-binary ").append(quote(r.requestBody()));
        }
        return sb.toString();
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }
}
