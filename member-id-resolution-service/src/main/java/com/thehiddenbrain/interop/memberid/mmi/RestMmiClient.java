package com.thehiddenbrain.interop.memberid.mmi;

import com.thehiddenbrain.interop.memberid.support.Masking;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The HTTP client for MMI: one POST per call, plain HTTP and no authentication exactly as the MMI contract
 * states for internal consumers. Connect and read timeouts ({@code mmi.connect-timeout} / {@code mmi.read-timeout}) are
 * applied to the builder by {@code MmiClientConfig}.
 * No retry and no circuit breaker: MMI is a core service that is restored quickly when it fails, and a
 * clear 503 lets Onyx retry later.
 */
public class RestMmiClient implements MmiClient {

    private static final Logger log = LoggerFactory.getLogger(RestMmiClient.class);

    private final RestClient restClient;
    private final MmiProperties properties;

    public RestMmiClient(RestClient.Builder builder, MmiProperties properties) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("mmi.base-url is required (set MMI_BASE_URL or activate a profile that defines it)");
        }
        this.properties = properties;
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
        long start = System.nanoTime();
        try {
            MmiResponse response = restClient.post()
                    .uri(properties.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .body(body)
                    .exchange((request, res) -> {
                        HttpStatusCode status = res.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            MmiResponse parsed;
                            try {
                                parsed = res.bodyTo(MmiResponse.class);
                            } catch (RuntimeException e) {
                                if (hasIoCause(e)) {
                                    // the connection broke or stalled while the body was being read: MMI is unavailable, not malformed
                                    String detail = classify(e);
                                    throw MmiException.unavailable(requestId, detail, "MMI connection failed while reading the body: " + detail, e);
                                }
                                throw MmiException.invalidResponse(requestId, "UNPARSEABLE_BODY",
                                        "MMI answered " + status.value() + " with a body that is not the expected JSON", e);
                            }
                            if (parsed == null) {
                                throw MmiException.invalidResponse(requestId, "EMPTY_BODY", "MMI answered " + status.value() + " with no body", null);
                            }
                            return parsed;
                        }
                        int code = status.value();
                        if (status.is5xxServerError() || code == 429 || code == 408) {
                            throw MmiException.unavailable(requestId, "HTTP_" + code, "MMI answered HTTP " + code, null);
                        }
                        throw MmiException.rejected(requestId, "HTTP_" + code, "MMI rejected the request with HTTP " + code);
                    });
            log.info("mmi call ok requestId={} memberId={} records={} ms={}", requestId, Masking.memberId(memberId),
                    response.membersOrEmpty().size(), elapsedMs(start));
            return new MmiResult(requestId, response);
        } catch (MmiException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} ms={}", requestId, Masking.memberId(memberId),
                    e.code(), e.detail(), elapsedMs(start));
            throw e;
        } catch (ResourceAccessException e) {
            String detail = classify(e);
            log.warn("mmi call failed requestId={} memberId={} code={} detail={} ms={} cause={}", requestId,
                    Masking.memberId(memberId), MmiException.UNAVAILABLE, detail, elapsedMs(start), rootMessage(e));
            throw MmiException.unavailable(requestId, detail, "MMI could not be reached: " + detail, e);
        } catch (RestClientException e) {
            log.warn("mmi call failed requestId={} memberId={} code={} ms={} cause={}", requestId, Masking.memberId(memberId),
                    MmiException.INVALID_RESPONSE, elapsedMs(start), rootMessage(e));
            throw MmiException.invalidResponse(requestId, "CLIENT_ERROR", "MMI call failed: " + e.getClass().getSimpleName(), e);
        }
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
