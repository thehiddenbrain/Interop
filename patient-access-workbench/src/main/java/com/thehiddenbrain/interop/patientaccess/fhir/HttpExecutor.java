package com.thehiddenbrain.interop.patientaccess.fhir;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.history.Redaction;
import com.thehiddenbrain.interop.patientaccess.history.RequestLog;
import com.thehiddenbrain.interop.patientaccess.history.RequestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes one HTTP exchange against an environment: timing, retry on 429/503, and a redacted entry
 * in the request history. Knows nothing about tokens; callers add the Authorization header.
 */
@Component
public class HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpExecutor.class);
    private static final long MAX_RETRY_WAIT_MS = 10_000;

    private final HttpClientFactory clients;
    private final RequestLog history;
    private final WorkbenchProperties properties;
    private final Clock clock;

    public HttpExecutor(HttpClientFactory clients, RequestLog history, WorkbenchProperties properties, Clock clock) {
        this.clients = clients;
        this.history = history;
        this.properties = properties;
        this.clock = clock;
    }

    /** Everything one call needs; secret header names are masked in the history. */
    public record Call(String method, String url, Map<String, String> headers, String body, String contentType,
                       String purpose, String correlationId, Set<String> secretHeaderNames, boolean redactBody) {

        public static Call get(String url, Map<String, String> headers, String purpose, String correlationId, Set<String> secretHeaderNames) {
            return new Call("GET", url, headers, null, null, purpose, correlationId, secretHeaderNames, false);
        }

        public static Call postForm(String url, Map<String, String> headers, String form, String purpose, String correlationId, Set<String> secretHeaderNames) {
            return new Call("POST", url, headers, form, "application/x-www-form-urlencoded", purpose, correlationId, secretHeaderNames, true);
        }
    }

    public HttpResult execute(Environment env, Call call) {
        HttpClient client = clients.forEnvironment(env);
        int attempts = 0;
        int maxAttempts = 1 + Math.max(0, properties.http().maxRetries());
        while (true) {
            attempts++;
            HttpResult result = executeOnce(env, client, call);
            if ((result.status() == 429 || result.status() == 503) && attempts < maxAttempts) {
                long wait = retryAfterMs(result.header("Retry-After"));
                log.info("{} {} answered {}; retrying in {} ms", call.method(), call.url(), result.status(), wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
                continue;
            }
            return result;
        }
    }

    private HttpResult executeOnce(Environment env, HttpClient client, Call call) {
        String id = Ids.next();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", properties.http().userAgent());
        if (call.headers() != null) {
            headers.putAll(call.headers());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(UrlBuilder.sanitize(call.url())))
                .timeout(clients.readTimeout(env));
        headers.forEach((k, v) -> {
            if (v != null) {
                builder.header(k, v);
            }
        });
        if (call.body() != null) {
            builder.header("Content-Type", call.contentType() == null ? "application/json" : call.contentType());
            builder.method(call.method(), HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
        } else {
            builder.method(call.method(), HttpRequest.BodyPublishers.noBody());
        }
        long start = System.nanoTime();
        String requestBodyForLog = call.redactBody() ? Redaction.body(call.body(), call.contentType()) : call.body();
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            long duration = (System.nanoTime() - start) / 1_000_000;
            String body = new String(response.body(), charsetOf(response.headers().firstValue("Content-Type").orElse(null)));
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            HttpResult result = new HttpResult(call.url(), response.statusCode(), response.headers().map(), body, duration, id);
            String loggedBody = call.redactBody() ? Redaction.body(body, contentType) : body;
            boolean truncated = false;
            int max = properties.history().maxBodyBytes();
            if (loggedBody != null && loggedBody.length() > max) {
                loggedBody = loggedBody.substring(0, max);
                truncated = true;
            }
            history.record(new RequestRecord(id, clock.instant(), env.id(), env.name(), call.purpose(), call.correlationId(),
                    call.method(), call.url(), Redaction.headers(headers, call.secretHeaderNames()), requestBodyForLog,
                    response.statusCode(), Redaction.multiHeaders(response.headers().map(), call.secretHeaderNames()),
                    loggedBody, truncated, duration, null, summarize(result)));
            log.debug("{} {} -> {} in {} ms", call.method(), call.url(), response.statusCode(), duration);
            return result;
        } catch (HttpTimeoutException e) {
            return failure(env, call, id, start, headers, requestBodyForLog, "timeout after " + clients.readTimeout(env).toMillis() + " ms", e);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
            return failure(env, call, id, start, headers, requestBodyForLog, msg, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(env, call, id, start, headers, requestBodyForLog, "interrupted", e);
        }
    }

    private HttpResult failure(Environment env, Call call, String id, long start, Map<String, String> headers, String requestBody,
                               String error, Exception cause) {
        long duration = (System.nanoTime() - start) / 1_000_000;
        history.record(new RequestRecord(id, clock.instant(), env.id(), env.name(), call.purpose(), call.correlationId(),
                call.method(), call.url(), Redaction.headers(headers, call.secretHeaderNames()), requestBody, null, Map.of(), null,
                false, duration, error, null));
        log.warn("{} {} failed: {}", call.method(), call.url(), error);
        throw new WorkbenchException(ErrorCode.UPSTREAM_UNREACHABLE, call.method() + " " + call.url() + " failed: " + error,
                List.of(), new com.thehiddenbrain.interop.patientaccess.common.ApiError.Upstream(null, call.url(), null, id), cause);
    }

    static String summarize(HttpResult r) {
        String type = r.resourceType();
        if (type == null) {
            return null;
        }
        if ("Bundle".equals(type)) {
            var n = r.json();
            int entries = n.has("entry") ? n.get("entry").size() : 0;
            String total = n.has("total") ? " of " + n.get("total").asInt() : "";
            return "Bundle " + n.path("type").asText("") + ": " + entries + " entries" + total;
        }
        if ("OperationOutcome".equals(type)) {
            return "OperationOutcome: " + r.errorSummary();
        }
        return type + "/" + r.json().path("id").asText("");
    }

    static long retryAfterMs(String header) {
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                return Math.min(MAX_RETRY_WAIT_MS, Math.max(250, seconds * 1000));
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall through to the default
            }
        }
        return 1000;
    }

    static java.nio.charset.Charset charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int i = lower.indexOf("charset=");
            if (i >= 0) {
                String cs = lower.substring(i + 8).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) {
                    cs = cs.substring(0, semi);
                }
                try {
                    return java.nio.charset.Charset.forName(cs.replace("\"", ""));
                } catch (Exception ignored) {
                    // unknown charset: default below
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
