package org.point32health.memberid.mmi;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.point32health.memberid.support.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP client for MMI: one POST per call, plain HTTP and no authentication exactly as the MMI contract
 * states for internal consumers. Connect and read timeouts ({@code mmi.connect-timeout} / {@code mmi.read-timeout})
 * are applied to the builder by {@code MmiClientConfig}. No retry: a clear 503 lets Onyx retry later.
 *
 * <p>Status mapping: 2xx is parsed; 404 is MMI's "no member for this id" and is returned as a normal result when the
 * body is an MMI envelope or empty (a 404 with a non-MMI body, such as the servlet container's default error page or a
 * proxy's HTML, means the URL is wrong and is a 502 {@code HTTP_404}); 5xx, 429 and 408 are 503 (Onyx may retry);
 * any other status is 502.
 *
 * <p>With {@code mmi.log-payloads=true} the exact request body sent and the exact response body received
 * (status, headers' content type, raw text) are written to the log, so an integration problem can be read
 * straight from the console. Those bodies contain member PHI; keep the flag off in prod.
 */
public class RestMmiClient implements MmiClient {

    private static final Logger log = LoggerFactory.getLogger(RestMmiClient.class);

    private final RestClient restClient;
    private final MmiProperties properties;
    private final ObjectMapper objectMapper;

    public RestMmiClient(RestClient.Builder builder, MmiProperties properties, ObjectMapper objectMapper) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("mmi.base-url is required (set MMI_BASE_URL or activate a profile that defines it)");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public String kind() {
        return "REST";
    }

    @Override
    public MmiResult search(String memberId, String correlationId) {
        String requestId = MmiRequestIds.next(properties.clientId());
        MmiRequest body = new MmiRequest(memberId, memberId, properties.voidCoverageRecord(), properties.clientId(),
                properties.clientType(), requestId);
        String requestJson = objectMapper.writeValueAsString(body);
        String url = properties.baseUrl() + properties.path();
        if (properties.logPayloads()) {
            log.info("mmi request requestId={} POST {}\n{}", requestId, url, requestJson);
        }
        long start = System.nanoTime();
        try {
            MmiResult result = restClient.post()
                    .uri(properties.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .body(requestJson)
                    .exchange((request, res) -> {
                        HttpStatusCode status = res.getStatusCode();
                        String text;
                        try {
                            text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        } catch (RuntimeException e) {
                            if (hasIoCause(e)) {
                                String detail = classify(e);
                                throw MmiException.unavailable(requestId, detail, "MMI connection failed while reading the body: " + detail, e);
                            }
                            throw e;
                        }
                        if (properties.logPayloads()) {
                            log.info("mmi response requestId={} status={} contentType={} ms={}\n{}", requestId, status.value(),
                                    res.getHeaders().getContentType(), elapsedMs(start), text.isEmpty() ? "<empty body>" : text);
                        }
                        if (status.is2xxSuccessful()) {
                            if (text.isBlank()) {
                                throw MmiException.invalidResponse(requestId, "EMPTY_BODY", "MMI answered " + status.value() + " with no body", null);
                            }
                            try {
                                return new MmiResult(requestId, status.value(), objectMapper.readValue(text, MmiResponse.class));
                            } catch (JacksonException e) {
                                throw MmiException.invalidResponse(requestId, "UNPARSEABLE_BODY",
                                        "MMI answered " + status.value() + " with a body that is not the expected JSON", e);
                            }
                        }
                        int code = status.value();
                        if (code == 404) {
                            MmiResponse notFound = asMmiEnvelope(text, requestId);
                            if (notFound != null) {
                                return new MmiResult(requestId, 404, notFound);
                            }
                            throw MmiException.rejected(requestId, "HTTP_404", "MMI answered 404 without an MMI response body: the MMI URL is "
                                    + "probably wrong (mmi.base-url + mmi.path = " + url + ")");
                        }
                        if (status.is5xxServerError() || code == 429 || code == 408) {
                            throw MmiException.unavailable(requestId, "HTTP_" + code, "MMI answered HTTP " + code, null);
                        }
                        throw MmiException.rejected(requestId, "HTTP_" + code, "MMI rejected the request with HTTP " + code);
                    });
            log.info("mmi call ok requestId={} memberId={} status={} records={} ms={}", requestId, Masking.memberId(memberId),
                    result.httpStatus(), result.response().membersOrEmpty().size(), elapsedMs(start));
            return result;
        } catch (MmiException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} ms={} cause={}", requestId, Masking.memberId(memberId),
                    e.code(), e.detail(), elapsedMs(start), e.getCause() == null ? "-" : rootMessage(e.getCause()));
            throw e;
        } catch (ResourceAccessException e) {
            String detail = classify(e);
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} url={} ms={} cause={}", requestId,
                    Masking.memberId(memberId), MmiException.UNAVAILABLE, detail, url, elapsedMs(start), rootMessage(e));
            throw MmiException.unavailable(requestId, detail, "MMI could not be reached: " + detail, e);
        } catch (RestClientException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} ms={} cause={}", requestId, Masking.memberId(memberId),
                    MmiException.INVALID_RESPONSE, elapsedMs(start), rootMessage(e));
            throw MmiException.invalidResponse(requestId, "CLIENT_ERROR", "MMI call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * MMI's "no member" answer is a 404 whose body is the usual envelope (messages, no members) or nothing at all.
     * Anything else with a 404 (the container's default error JSON, a proxy's HTML page) is not MMI speaking.
     */
    private MmiResponse asMmiEnvelope(String text, String requestId) {
        if (text == null || text.isBlank()) {
            return new MmiResponse(null, null, requestId, null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node.isObject() && (node.has("members") || node.has("messages") || node.has("clientId") || node.has("requestId"))) {
                return objectMapper.treeToValue(node, MmiResponse.class);
            }
        } catch (JacksonException e) {
            // not JSON: fall through
        }
        return null;
    }

    private static boolean hasIoCause(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.io.IOException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String classify(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException || t instanceof UnknownHostException) {
                return "CONNECT_FAILED";
            }
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return "READ_TIMEOUT";
            }
            t = t.getCause();
        }
        return "CONNECT_FAILED";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
