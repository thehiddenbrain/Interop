package com.thehiddenbrain.interop.patientaccess.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One outbound HTTP exchange as kept in the history (headers and bodies already redacted / truncated). */
public record RequestRecord(
        String id,
        Instant at,
        String environmentId,
        String environmentName,
        String purpose,
        String correlationId,
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestBody,
        Integer status,
        Map<String, List<String>> responseHeaders,
        String responseBody,
        boolean responseBodyTruncated,
        long durationMs,
        String error,
        String summary) {

    /** Compact form for lists. */
    public record Summary(String id, Instant at, String environmentId, String environmentName, String purpose, String correlationId,
                          String method, String url, Integer status, long durationMs, String error, String summary) {
    }

    public Summary summaryView() {
        return new Summary(id, at, environmentId, environmentName, purpose, correlationId, method, url, status, durationMs, error, summary);
    }
}
