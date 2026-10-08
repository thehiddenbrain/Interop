package org.p32h.interop.memberinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

/** The WebClient member information client against a local HTTP server: the body sent, every kind of answer, refused and slow connections. */
class WebClientMemberInfoClientTest {

    private static final String MEMBER = "TESTMEMBER   01";
    /** A single-digit month and day: the service reads MM/dd/yyyy, so the body must say 01/05/2026. */
    private static final LocalDate DOS = LocalDate.of(2026, 1, 5);

    /**
     * The service's contract example for the test member, with the coverage list asked for: the memberPlan (not read) and
     * two coverage records with the same fields, the contract example's plan and a later one.
     */
    private static final String ANSWER = """
            { "requestId": null,
              "members": [ { "requestId": null, "memberId": "TESTMEMBER   01",
                "memberPlan": { "productCode": "DMA", "sourceSystemId": "2064", "subsidiary": "THPPMA", "voidFlag": "N" },
                "coverageRecords": [
                  { "benefitId": "Q330", "businessLineKey": "0214", "businessTypeIndicator": "PP",
                    "carrier": "FULLY INSURED", "carrierCode": "THPP", "gridId": null, "groupId": "TESTGROUP01", "ratingState": "MA",
                    "homeGroupId": null, "network": "Select", "networkId": null, "planCode": "TESTPLAN01",
                    "planEndDate": "2024-04-30T05:00:00.000+00:00", "planName": "Tufts Health Direct ConnectorCare III",
                    "planStartDate": "2024-02-01T05:00:00.000+00:00", "planType": "TESTPLAN01 - ", "productCode": "SB",
                    "sourceSystemId": "2026", "subsidiary": "THPPMA", "voidFlag": "N", "ipa": "FM", "hierarchyLineOfBusiness": "MASB",
                    "hierarchyCompany": "MACOMM", "hierarchyCompanyDesc": "MA COMMERCIAL", "lineOfBusinessDesc": "DIRECT SUBSIDIZED",
                    "aFieldAddedLater": "ignored" },
                  { "planStartDate": "2024-05-01T04:00:00.000+00:00", "planEndDate": null, "productCode": "GT",
                    "sourceSystemId": "2026", "subsidiary": "THPPMA", "voidFlag": "N" } ] } ] }
            """;

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedCorrelation = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static MemberInfoProperties props(String baseUrl) {
        return new MemberInfoProperties(baseUrl, "/members", Duration.ofSeconds(2), Duration.ofSeconds(5), true,
                new MemberInfoProperties.Stub(false, ""));
    }

    private static WebClient.Builder builder(Duration connect, Duration read) {
        return WebClient.builder().clientConnector(ClientHttpConnectorBuilder.detect().build(
                HttpClientSettings.defaults().withConnectTimeout(connect).withReadTimeout(read)));
    }

    /** A server on /members answering {@code status} with {@code body} (none when null), after {@code delayMs}. */
    private WebClientMemberInfoClient answering(int status, String body, long delayMs, Duration readTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/members", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedCorrelation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return new WebClientMemberInfoClient(builder(Duration.ofSeconds(1), readTimeout),
                props("http://127.0.0.1:" + server.getAddress().getPort()), JSON);
    }

    private WebClientMemberInfoClient answering(int status, String body) throws IOException {
        return answering(status, body, 0, Duration.ofSeconds(2));
    }

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(WebClientMemberInfoClient.class)).addAppender(logs);
        return logs;
    }

    private static List<String> release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(WebClientMemberInfoClient.class)).detachAppender(logs);
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void sendsTheDocumentedBodyAndParsesTheAnswer() throws Exception {
        WebClientMemberInfoClient client = answering(200, ANSWER);
        ListAppender<ILoggingEvent> logs = capture();
        MemberInfoResponse response;
        try {
            response = client.lookup(MEMBER, DOS, "ONYX-1").block();
        } finally {
            assertThat(release(logs))
                    .anySatisfy(m -> assertThat(m).startsWith("member-info request POST ").contains("\"dos\":\"01/05/2026\"")
                            .contains("\"returnCoverageList\":true"))
                    .anySatisfy(m -> assertThat(m).startsWith("member-info response status=200").contains("hierarchyLineOfBusiness"));
        }
        tools.jackson.databind.JsonNode sent = JSON.readTree(receivedBody.get());
        assertThat(sent.get("memberIds").size()).isEqualTo(1);
        assertThat(sent.get("memberIds").get(0).asText()).isEqualTo(MEMBER);
        assertThat(sent.get("dos").asText()).isEqualTo("01/05/2026");
        assertThat(sent.get("returnCoverageList").asBoolean()).isTrue();
        assertThat(receivedCorrelation.get()).isEqualTo("ONYX-1");
        assertThat(receivedContentType.get()).startsWith("application/json");
        assertThat(response.coverageRecordsFor(MEMBER)).as("the coverage records, not the memberPlan").hasSize(2).first().satisfies(p -> {
            assertThat(p.hierarchyLineOfBusiness()).isEqualTo("MASB");
            assertThat(p.productCode()).isEqualTo("SB");
            assertThat(p.sourceSystemId()).isEqualTo("2026");
            assertThat(p.subsidiary()).isEqualTo("THPPMA");
            assertThat(p.planStartDate()).isEqualTo("2024-02-01T05:00:00.000+00:00");
        });
        assertThat(response.coverageRecordsFor(MEMBER).get(1).productCode()).isEqualTo("GT");
    }

    @Test
    void notFoundIsAnAnswerWithNoRecordsAndWarns() throws Exception {
        WebClientMemberInfoClient client = answering(404, "<html>Not Found</html>");
        ListAppender<ILoggingEvent> logs = capture();
        MemberInfoResponse response;
        try {
            response = client.lookup(MEMBER, DOS, "c").block();
        } finally {
            assertThat(release(logs)).anySatisfy(m -> assertThat(m).contains("marker=MEMBER_PLAN_404").contains("member-info.base-url"));
        }
        assertThat(response.coverageRecordsFor(MEMBER)).isEmpty();
    }

    @Test
    void serverErrorsThrottlingAndTimeoutsAreUnavailable503() throws Exception {
        for (int status : new int[] {500, 502, 503, 429, 408}) {
            WebClientMemberInfoClient client = answering(status, "{}");
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c").block()).as("HTTP " + status)
                    .isInstanceOfSatisfying(MemberInfoException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo(MemberInfoException.UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("HTTP_" + status);
                    });
            stop();
        }
    }

    @Test
    void anyOtherStatusIsRejected502WithoutNamingTheService() throws Exception {
        for (int status : new int[] {400, 401, 403}) {
            WebClientMemberInfoClient client = answering(status, "<html>no</html>");
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c").block()).as("HTTP " + status)
                    .isInstanceOfSatisfying(MemberInfoException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.code()).isEqualTo(MemberInfoException.REJECTED);
                        assertThat(e.getMessage()).isEqualTo("The member plan lookup rejected the request: HTTP " + status)
                                .doesNotContainIgnoringCase("member-info").doesNotContain("memberinfo");
                    });
            stop();
        }
    }

    @Test
    void anUnreadableOrEmptyBodyIsInvalidResponse502() throws Exception {
        for (String body : new String[] {"<html>maintenance</html>", "null", null}) {
            WebClientMemberInfoClient client = answering(200, body);
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c").block()).isInstanceOfSatisfying(MemberInfoException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.code()).isEqualTo(MemberInfoException.INVALID_RESPONSE);
                assertThat(e.detail()).isEqualTo(body == null ? "EMPTY_BODY" : "UNPARSEABLE_BODY");
            });
            stop();
        }
    }

    @Test
    void connectionRefusedIsUnavailable503() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        WebClientMemberInfoClient client = new WebClientMemberInfoClient(builder(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                props("http://127.0.0.1:" + port), JSON);
        assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c").block()).isInstanceOfSatisfying(MemberInfoException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("CONNECT_FAILED");
        });
    }

    @Test
    void readTimeoutIsUnavailable503() throws Exception {
        WebClientMemberInfoClient client = answering(200, ANSWER, 1500, Duration.ofMillis(400));
        assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c").block()).isInstanceOfSatisfying(MemberInfoException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("READ_TIMEOUT");
        });
    }

    @Test
    void refusesToStartWithoutABaseUrl() {
        assertThatThrownBy(() -> new WebClientMemberInfoClient(WebClient.builder(), props(""), JSON))
                .hasMessageContaining("member-info.base-url");
    }
}
