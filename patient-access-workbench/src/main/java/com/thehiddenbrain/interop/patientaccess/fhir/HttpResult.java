package com.thehiddenbrain.interop.patientaccess.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Outcome of one outbound HTTP call: status, headers, body and the history entry it was recorded as. */
public final class HttpResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;
    private final long durationMs;
    private final String requestId;
    private JsonNode json;
    private boolean jsonParsed;

    public HttpResult(String url, int status, Map<String, List<String>> headers, String body, long durationMs, String requestId) {
        this.url = url;
        this.status = status;
        this.headers = headers;
        this.body = body == null ? "" : body;
        this.durationMs = durationMs;
        this.requestId = requestId;
    }

    public String url() {
        return url;
    }

    public int status() {
        return status;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    public String contentType() {
        return header("Content-Type");
    }

    public boolean isJson() {
        String ct = contentType();
        if (ct != null) {
            String l = ct.toLowerCase(Locale.ROOT);
            return l.contains("json");
        }
        String t = body.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    public String body() {
        return body;
    }

    public long durationMs() {
        return durationMs;
    }

    /** Id of the request-history entry (evidence for conformance results). */
    public String requestId() {
        return requestId;
    }

    /** Parsed body, or null when it is not JSON. */
    public synchronized JsonNode json() {
        if (!jsonParsed) {
            jsonParsed = true;
            try {
                json = body.isBlank() ? null : MAPPER.readTree(body);
            } catch (Exception e) {
                json = null;
            }
        }
        return json;
    }

    public String resourceType() {
        JsonNode n = json();
        return n != null && n.hasNonNull("resourceType") ? n.get("resourceType").asText() : null;
    }

    public boolean isResource(String type) {
        return type.equals(resourceType());
    }

    /** Short description of an OperationOutcome body, or the first characters of any other body. */
    public String errorSummary() {
        JsonNode n = json();
        if (n != null && "OperationOutcome".equals(resourceType()) && n.has("issue")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode issue : n.get("issue")) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(issue.path("severity").asText("?")).append('/').append(issue.path("code").asText("?"));
                String text = issue.path("diagnostics").asText(null);
                if (text == null && issue.has("details")) {
                    text = issue.get("details").path("text").asText(null);
                }
                if (text != null) {
                    sb.append(": ").append(text);
                }
            }
            return sb.toString();
        }
        String t = body.strip();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
