package org.p32h.interop.memberinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestMemberInfoClientTest {

    private static final String MEMBER = "TESTMEMBER   01";
    /** A single-digit month and day: the service reads MM/dd/yyyy, so the body must say 01/05/2026. */
    private static final LocalDate DOS = LocalDate.of(2026, 1, 5);
    private static final String URL = "http://member-info.test/members";

    /** The answer in the service's contract example, for the test member. */
    private static final String ANSWER = """
            { "requestId": null,
              "members": [ { "requestId": null, "memberId": "TESTMEMBER   01",
                "memberPlan": { "benefitId": "Q330", "businessLineKey": "0214", "businessTypeIndicator": "PP",
                  "carrier": "FULLY INSURED", "carrierCode": "THPP", "gridId": null, "groupId": "TESTGROUP01", "ratingState": "MA",
                  "homeGroupId": null, "network": "Select", "networkId": null, "planCode": "TESTPLAN01",
                  "planEndDate": "2024-04-30T05:00:00.000+00:00", "planName": "Tufts Health Direct ConnectorCare III",
                  "planStartDate": "2024-02-01T05:00:00.000+00:00", "planType": "TESTPLAN01 - ", "productCode": "SB",
                  "sourceSystemId": "2026", "subsidiary": "THPPMA", "voidFlag": "N", "ipa": "FM", "hierarchyLineOfBusiness": "MASB",
                  "hierarchyCompany": "MACOMM", "hierarchyCompanyDesc": "MA COMMERCIAL", "lineOfBusinessDesc": "DIRECT SUBSIDIZED",
                  "aFieldAddedLater": "ignored" } } ] }
            """;

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static MemberInfoProperties props(String baseUrl) {
        return new MemberInfoProperties(baseUrl, "/members", Duration.ofSeconds(2), Duration.ofSeconds(5), true,
                new MemberInfoProperties.Stub(false, ""));
    }

    private static RestMemberInfoClient answering(HttpStatus status, String contentType, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(body == null ? withStatus(status)
                : withStatus(status).contentType(MediaType.parseMediaType(contentType)).body(body));
        return new RestMemberInfoClient(builder, props("http://member-info.test"), JSON);
    }

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(RestMemberInfoClient.class)).addAppender(logs);
        return logs;
    }

    private static List<String> release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(RestMemberInfoClient.class)).detachAppender(logs);
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void sendsTheDocumentedBodyAndParsesTheAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", Matchers.startsWith("application/json")))
                .andExpect(header("X-Correlation-Id", "ONYX-1"))
                .andExpect(jsonPath("$.memberIds.length()").value(1))
                .andExpect(jsonPath("$.memberIds[0]").value(MEMBER))
                .andExpect(jsonPath("$.dos").value("01/05/2026"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        RestMemberInfoClient client = new RestMemberInfoClient(builder, props("http://member-info.test"), JSON);

        ListAppender<ILoggingEvent> logs = capture();
        MemberInfoResponse response;
        try {
            response = client.lookup(MEMBER, DOS, "ONYX-1");
        } finally {
            assertThat(release(logs))
                    .anySatisfy(m -> assertThat(m).startsWith("member-info request POST " + URL).contains("\"dos\":\"01/05/2026\""))
                    .anySatisfy(m -> assertThat(m).startsWith("member-info response status=200").contains("hierarchyLineOfBusiness"));
        }
        server.verify();
        assertThat(response.plansFor(MEMBER)).singleElement().satisfies(p -> {
            assertThat(p.hierarchyLineOfBusiness()).isEqualTo("MASB");
            assertThat(p.lineOfBusinessDesc()).isEqualTo("DIRECT SUBSIDIZED");
            assertThat(p.hierarchyCompany()).isEqualTo("MACOMM");
            assertThat(p.hierarchyCompanyDesc()).isEqualTo("MA COMMERCIAL");
            assertThat(p.subsidiary()).isEqualTo("THPPMA");
            assertThat(p.carrierCode()).isEqualTo("THPP");
            assertThat(p.productCode()).isEqualTo("SB");
            assertThat(p.planStartDate()).isEqualTo("2024-02-01T05:00:00.000+00:00");
            assertThat(p.gridId()).isNull();
        });
    }

    @Test
    void notFoundIsAnAnswerWithNoPlanAndWarns() {
        ListAppender<ILoggingEvent> logs = capture();
        MemberInfoResponse response;
        try {
            response = answering(HttpStatus.NOT_FOUND, MediaType.TEXT_HTML_VALUE, "<html>Not Found</html>").lookup(MEMBER, DOS, "c");
        } finally {
            assertThat(release(logs)).anySatisfy(m -> assertThat(m).contains("marker=MEMBER_PLAN_404").contains("member-info.base-url"));
        }
        assertThat(response.plansFor(MEMBER)).isEmpty();
    }

    @Test
    void serverErrorsThrottlingAndTimeoutsAreUnavailable503() {
        for (HttpStatus status : List.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.REQUEST_TIMEOUT)) {
            RestMemberInfoClient client = answering(status, MediaType.APPLICATION_JSON_VALUE, "{}");
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c")).as("HTTP " + status.value())
                    .isInstanceOfSatisfying(MemberInfoException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo(MemberInfoException.UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("HTTP_" + status.value());
                    });
        }
    }

    @Test
    void anyOtherStatusIsRejected502WithoutNamingTheService() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            RestMemberInfoClient client = answering(status, MediaType.TEXT_HTML_VALUE, "<html>no</html>");
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c")).as("HTTP " + status.value())
                    .isInstanceOfSatisfying(MemberInfoException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.code()).isEqualTo(MemberInfoException.REJECTED);
                        assertThat(e.getMessage()).isEqualTo("The member plan lookup rejected the request: HTTP " + status.value())
                                .doesNotContainIgnoringCase("member-info").doesNotContain("memberinfo");
                    });
        }
    }

    @Test
    void anUnreadableOrEmptyBodyIsInvalidResponse502() {
        for (String body : new String[] {"<html>maintenance</html>", null}) {
            RestMemberInfoClient client = answering(HttpStatus.OK, MediaType.TEXT_HTML_VALUE, body);
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c")).isInstanceOfSatisfying(MemberInfoException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.code()).isEqualTo(MemberInfoException.INVALID_RESPONSE);
                assertThat(e.detail()).isEqualTo(body == null ? "EMPTY_BODY" : "UNPARSEABLE_BODY");
            });
        }
    }

    @Test
    void connectionRefusedIsUnavailable503() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        RestClient.Builder builder = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofSeconds(1))));
        RestMemberInfoClient client = new RestMemberInfoClient(builder, props("http://127.0.0.1:" + port), JSON);
        assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c")).isInstanceOfSatisfying(MemberInfoException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("CONNECT_FAILED");
        });
    }

    @Test
    void readTimeoutIsUnavailable503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/members", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            RestClient.Builder builder = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                    .build(HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofMillis(400))));
            RestMemberInfoClient client = new RestMemberInfoClient(builder, props("http://127.0.0.1:" + server.getAddress().getPort()), JSON);
            assertThatThrownBy(() -> client.lookup(MEMBER, DOS, "c")).isInstanceOfSatisfying(MemberInfoException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.detail()).isEqualTo("READ_TIMEOUT");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesToStartWithoutABaseUrl() {
        assertThatThrownBy(() -> new RestMemberInfoClient(RestClient.builder(), props(""), JSON)).hasMessageContaining("member-info.base-url");
    }
}
