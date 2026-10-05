package org.point32health.interop.mmi;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.point32health.interop.support.Masking;
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
 * <p>MMI's contract has four statuses, mapped as follows. 200: the answer is parsed. 404: no member for this id, a normal
 * result answered 200 NOT_FOUND (a 404 whose body is not MMI's envelope is still NOT_FOUND, with a warning in the log,
 * because a wrong URL would also look like that). 400: MMI could not process the request, forwarded as 400
 * {@code MEMBER_LOOKUP_REJECTED} with MMI's text. 500: MMI internal error, 503 {@code MEMBER_LOOKUP_UNAVAILABLE} so Onyx may retry later.
 * Any other status is not MMI speaking (a gateway, proxy or container): 5xx, 429 and 408 are 503, the rest 502, each
 * saying so.
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
                                throw MmiException.unavailable(requestId, detail, "The member lookup connection failed while reading the answer: " + detail, e);
                            }
                            throw e;
                        }
                        if (properties.logPayloads()) {
                            log.info("mmi response requestId={} status={} contentType={} ms={}\n{}", requestId, status.value(),
                                    res.getHeaders().getContentType(), elapsedMs(start), text.isEmpty() ? "<empty body>" : text);
                        }
                        if (status.is2xxSuccessful()) {
                            if (text.isBlank()) {
                                throw MmiException.invalidResponse(requestId, "EMPTY_BODY", "The member lookup answered " + status.value() + " with no body", null);
                            }
                            try {
                                return new MmiResult(requestId, status.value(), objectMapper.readValue(text, MmiResponse.class));
                            } catch (JacksonException e) {
                                throw MmiException.invalidResponse(requestId, "UNPARSEABLE_BODY",
                                        "The member lookup answered " + status.value() + " with an unreadable body", e);
                            }
                        }
                        int code = status.value();
                        MmiResponse envelope = asMmiEnvelope(text, requestId);
                        String mmiText = firstMessageText(envelope);
                        if (code == 404) {
                            // MMI's contract: 404 means "no member for this id". A normal answer, never a failure.
                            if (envelope == null) {
                                log.warn("marker=MMI_404_WITHOUT_ENVELOPE requestId={} url={} contentType={}: the 404 body is not MMI's "
                                        + "envelope; if every id comes back NOT_FOUND, check mmi.base-url and mmi.path", requestId, url,
                                        res.getHeaders().getContentType());
                                envelope = new MmiResponse(null, null, requestId, null, null);
                            }
                            return new MmiResult(requestId, 404, envelope);
                        }
                        if (code == 400) {
                            // MMI's contract: 400 means MMI could not process the request as sent. Forwarded with MMI's text.
                            throw MmiException.badRequest(requestId, "HTTP_400",
                                    "The member lookup rejected the request" + (mmiText == null ? "" : ": " + mmiText));
                        }
                        if (code == 500) {
                            // MMI's contract: 500 means an internal error in MMI. Onyx may retry later.
                            throw MmiException.unavailable(requestId, "HTTP_500",
                                    "The member lookup reported an internal error" + (mmiText == null ? "" : ": " + mmiText), null);
                        }
                        // Anything else is outside MMI's contract (200, 400, 404, 500): a gateway, proxy or container answered.
                        // The URL stays in the log (below); nothing a caller receives names MMI or its address.
                        String outside = "HTTP " + code + " from the member lookup, outside its contract (200, 400, 404, 500): "
                                + "a gateway or proxy answered";
                        if (status.is5xxServerError() || code == 429 || code == 408) {
                            throw MmiException.unavailable(requestId, "HTTP_" + code, outside, null);
                        }
                        throw MmiException.rejected(requestId, "HTTP_" + code, outside);
                    });
            log.info("mmi call ok requestId={} memberId={} status={} records={} ms={}", requestId, Masking.memberId(memberId),
                    result.httpStatus(), result.response().membersOrEmpty().size(), elapsedMs(start));
            return result;
        } catch (MmiException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} url={} ms={} cause={}", requestId, Masking.memberId(memberId),
                    e.code(), e.detail(), url, elapsedMs(start), e.getCause() == null ? "-" : rootMessage(e.getCause()));
            throw e;
        } catch (ResourceAccessException e) {
            String detail = classify(e);
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} url={} ms={} cause={}", requestId,
                    Masking.memberId(memberId), MmiException.UNAVAILABLE, detail, url, elapsedMs(start), rootMessage(e));
            throw MmiException.unavailable(requestId, detail, "The member lookup could not be reached: " + detail, e);
        } catch (RestClientException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} ms={} cause={}", requestId, Masking.memberId(memberId),
                    MmiException.INVALID_RESPONSE, elapsedMs(start), rootMessage(e));
            throw MmiException.invalidResponse(requestId, "CLIENT_ERROR", "The member lookup call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** The first message MMI put in its envelope, as "CODE text", or null when there is none. */
    private static String firstMessageText(MmiResponse envelope) {
        if (envelope == null) {
            return null;
        }
        return envelope.messagesOrEmpty().stream()
                .map(m -> ((m.messageCode() == null ? "" : m.messageCode() + " ") + (m.message() == null ? "" : m.message())).strip())
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
    }

    /**
     * MMI's envelope: a JSON object with any of members / messages / clientId / requestId, or an empty body. Anything else
     * (the container's default error JSON, a proxy's HTML page) is not MMI speaking and yields null.
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
