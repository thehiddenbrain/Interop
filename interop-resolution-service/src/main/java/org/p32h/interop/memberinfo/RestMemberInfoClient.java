package org.p32h.interop.memberinfo;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP client for the member information service: one POST per call,
 * {@code { memberIds: [id], dos: MM/dd/yyyy, returnCoverageList: true }},
 * plain HTTP and no authentication, like the member index. Connect and read timeouts ({@code member-info.connect-timeout} /
 * {@code member-info.read-timeout}) are applied by {@code MemberInfoClientConfig}. No retry: a clear 503 lets Onyx retry later.
 *
 * <p>200 is parsed. 404 is taken as "no records for this member" and answered with no members (with a warning,
 * because a wrong URL looks the same). 5xx, 429 and 408 are 503 {@code MEMBER_PLAN_UNAVAILABLE}; any other status is 502
 * {@code MEMBER_PLAN_ERROR}. The service's error contract is not documented yet; confirm it in PQA.
 *
 * <p>With {@code member-info.log-payloads=true} the exact request and response bodies are written to the log.
 */
public class RestMemberInfoClient implements MemberInfoClient {

    private static final Logger log = LoggerFactory.getLogger(RestMemberInfoClient.class);

    private final RestClient restClient;
    private final MemberInfoProperties properties;
    private final ObjectMapper objectMapper;

    public RestMemberInfoClient(RestClient.Builder builder, MemberInfoProperties properties, ObjectMapper objectMapper) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("member-info.base-url is required (set MEMBER_INFO_BASE_URL or activate a profile that defines it)");
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
    public MemberInfoResponse lookup(String memberId, LocalDate dateOfService, String correlationId) {
        String requestJson = objectMapper.writeValueAsString(MemberInfoRequest.of(memberId, dateOfService));
        String url = properties.baseUrl() + properties.path();
        if (properties.logPayloads()) {
            log.info("member-info request POST {}\n{}", url, requestJson);
        }
        long start = System.nanoTime();
        try {
            MemberInfoResponse response = restClient.post()
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
                        } catch (IOException e) {
                            String detail = classify(e);
                            throw MemberInfoException.unavailable(detail, "The member plan lookup connection failed while reading the answer: " + detail, e);
                        }
                        if (properties.logPayloads()) {
                            log.info("member-info response status={} contentType={} ms={}\n{}", status.value(), res.getHeaders().getContentType(),
                                    elapsedMs(start), text.isEmpty() ? "<empty body>" : text);
                        }
                        int code = status.value();
                        if (status.is2xxSuccessful()) {
                            if (text.isBlank()) {
                                throw MemberInfoException.invalidResponse("EMPTY_BODY", "The member plan lookup answered " + code + " with no body", null);
                            }
                            MemberInfoResponse parsed;
                            try {
                                parsed = objectMapper.readValue(text, MemberInfoResponse.class);
                            } catch (JacksonException e) {
                                throw MemberInfoException.invalidResponse("UNPARSEABLE_BODY",
                                        "The member plan lookup answered " + code + " with an unreadable body", e);
                            }
                            if (parsed == null) {
                                throw MemberInfoException.invalidResponse("UNPARSEABLE_BODY",
                                        "The member plan lookup answered " + code + " with an unreadable body", null);
                            }
                            return parsed;
                        }
                        if (code == 404) {
                            log.warn("marker=MEMBER_PLAN_404 memberId={} url={}: taken as no records; if every member gets it, "
                                    + "check member-info.base-url and member-info.path", memberId, url);
                            return new MemberInfoResponse(null, null);
                        }
                        if (status.is5xxServerError() || code == 429 || code == 408) {
                            throw MemberInfoException.unavailable("HTTP_" + code, "The member plan lookup answered HTTP " + code, null);
                        }
                        throw MemberInfoException.rejected("HTTP_" + code, "The member plan lookup rejected the request: HTTP " + code);
                    });
            log.info("member-info call ok memberId={} dos={} members={} ms={}", memberId, dateOfService,
                    response.membersOrEmpty().size(), elapsedMs(start));
            return response;
        } catch (MemberInfoException e) {
            log.warn("member-info call failed memberId={} code={} detail={} url={} ms={} cause={}", memberId, e.code(), e.detail(), url,
                    elapsedMs(start), e.getCause() == null ? "-" : rootMessage(e.getCause()));
            throw e;
        } catch (ResourceAccessException e) {
            String detail = classify(e);
            log.warn("member-info call failed memberId={} code={} detail={} url={} ms={} cause={}", memberId,
                    MemberInfoException.UNAVAILABLE, detail, url, elapsedMs(start), rootMessage(e));
            throw MemberInfoException.unavailable(detail, "The member plan lookup could not be reached: " + detail, e);
        } catch (RestClientException e) {
            log.warn("member-info call failed memberId={} code={} url={} ms={} cause={}", memberId, MemberInfoException.INVALID_RESPONSE,
                    url, elapsedMs(start), rootMessage(e));
            throw MemberInfoException.invalidResponse("CLIENT_ERROR", "The member plan lookup call failed: " + e.getClass().getSimpleName(), e);
        }
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
