package org.p32h.interop.memberinfo;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;
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
 * {@code MEMBER_PLAN_ERROR}; an empty, unreadable or {@code null} body is 502 {@code MEMBER_PLAN_INVALID_RESPONSE}.
 * The service's error contract is not documented yet; confirm it in PQA.
 *
 * <p>With {@code member-info.log-payloads=true} the exact request and response bodies are written to the log.
 */
public class WebClientMemberInfoClient implements MemberInfoClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientMemberInfoClient.class);

    private final WebClient webClient;
    private final MemberInfoProperties properties;
    private final ObjectMapper objectMapper;

    public WebClientMemberInfoClient(WebClient.Builder builder, MemberInfoProperties properties, ObjectMapper objectMapper) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("member-info.base-url is required (set MEMBER_INFO_BASE_URL or activate a profile that defines it)");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public String kind() {
        return "REST";
    }

    @Override
    public Mono<MemberInfoResponse> lookup(String memberId, LocalDate dateOfService, String correlationId) {
        String requestJson = objectMapper.writeValueAsString(MemberInfoRequest.of(memberId, dateOfService));
        String url = properties.baseUrl() + properties.path();
        return Mono.defer(() -> {
            if (properties.logPayloads()) {
                log.info("member-info request POST {}\n{}", url, requestJson);
            }
            long start = System.nanoTime();
            return webClient.post()
                    .uri(properties.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .bodyValue(requestJson)
                    .exchangeToMono(res -> res.bodyToMono(String.class).defaultIfEmpty("")
                            .map(text -> read(res.statusCode(), text, memberId, url, start)))
                    .doOnNext(response -> log.info("member-info call ok memberId={} dos={} members={} ms={}", memberId, dateOfService,
                            response.membersOrEmpty().size(), elapsedMs(start)))
                    .onErrorMap(e -> !(e instanceof MemberInfoException), e -> {
                        if (e instanceof WebClientException || isTimeout(e)) {
                            String detail = classify(e);
                            return MemberInfoException.unavailable(detail, "The member plan lookup could not be reached: " + detail, e);
                        }
                        return MemberInfoException.invalidResponse("CLIENT_ERROR",
                                "The member plan lookup call failed: " + e.getClass().getSimpleName(), e);
                    })
                    .doOnError(MemberInfoException.class, e -> log.warn(
                            "member-info call failed memberId={} code={} detail={} url={} ms={} cause={}", memberId, e.code(),
                            e.detail(), url, elapsedMs(start), e.getCause() == null ? "-" : rootMessage(e.getCause())));
        });
    }

    /** The answer's status and body: the parsed answer, an empty one for a 404, or the failure to report. */
    private MemberInfoResponse read(HttpStatusCode status, String text, String memberId, String url, long start) {
        int code = status.value();
        if (properties.logPayloads()) {
            log.info("member-info response status={} ms={}\n{}", code, elapsedMs(start), text.isEmpty() ? "<empty body>" : text);
        }
        if (status.is2xxSuccessful()) {
            if (text.isBlank()) {
                throw MemberInfoException.invalidResponse("EMPTY_BODY", "The member plan lookup answered " + code + " with no body", null);
            }
            MemberInfoResponse parsed;
            try {
                parsed = objectMapper.readValue(text, MemberInfoResponse.class);
            } catch (JacksonException e) {
                throw MemberInfoException.invalidResponse("UNPARSEABLE_BODY", "The member plan lookup answered " + code + " with an unreadable body", e);
            }
            if (parsed == null) {
                throw MemberInfoException.invalidResponse("UNPARSEABLE_BODY", "The member plan lookup answered " + code + " with an unreadable body", null);
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
    }

    /** CONNECT_FAILED for a refused, unresolved or timed-out connection; READ_TIMEOUT when the answer did not come in time. */
    private static String classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return "CONNECT_FAILED";
            }
        }
        return isTimeout(e) ? "READ_TIMEOUT" : "CONNECT_FAILED";
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
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
