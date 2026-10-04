package org.point32health.memberid.mmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestMmiClientTest {

    private static final String MMI_OK = """
            { "clientId": "MBRIDSVC", "clientType": "INT", "requestId": "x", "messages": null,
              "members": [ { "memberId": "123456789   01", "memberFirstName": "Taylor", "socialSecurityNumber": "***-**-1234",
                             "company": "THP", "lineOfBusiness": "MCR",
                             "coverage": [ { "effDate": "01/01/2024", "endDate": null, "groupId": "g", "voidFlag": "N" } ] } ] }
            """;

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static MmiProperties props(String baseUrl) {
        return new MmiProperties(baseUrl, "/master/member/v1", "MBRIDSVC", "INT", Duration.ofSeconds(2), Duration.ofSeconds(5), "N",
                List.of("ERROR"), true, new MmiProperties.Stub(false, ""));
    }

    @Test
    void sendsTheDocumentedBodyAndParsesTheAnswer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", Matchers.startsWith("application/json")))
                .andExpect(header("X-Correlation-Id", "ONYX-1"))
                .andExpect(jsonPath("$.memberId").value("123456789   01"))
                .andExpect(jsonPath("$.legacyMemberId").value("123456789   01"))
                .andExpect(jsonPath("$.voidCoverageRecord").value("N"))
                .andExpect(jsonPath("$.clientId").value("MBRIDSVC"))
                .andExpect(jsonPath("$.clientType").value("INT"))
                .andExpect(jsonPath("$.requestId").value(Matchers.matchesRegex("MBRIDSVC-\\d{13}-\\d{5}")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("dosStartDate"))))
                .andRespond(withSuccess(MMI_OK, MediaType.APPLICATION_JSON));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);

        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestMmiClient.class)).addAppender(logs);
        MmiResult result;
        try {
            result = client.search("123456789   01", "ONYX-1");
        } finally {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestMmiClient.class)).detachAppender(logs);
        }
        assertThat(logs.list.stream().map(e -> e.getFormattedMessage()).toList())
                .anySatisfy(m -> assertThat(m).startsWith("mmi request requestId=").contains("\"legacyMemberId\":\"123456789   01\""))
                .anySatisfy(m -> assertThat(m).startsWith("mmi response requestId=").contains("status=200").contains("\"members\"").contains("123456789   01"));

        assertThat(result.requestId()).matches("MBRIDSVC-\\d{13}-\\d{5}");
        assertThat(result.response().membersOrEmpty()).hasSize(1);
        assertThat(result.response().members().get(0).memberId()).isEqualTo("123456789   01");
        assertThat(result.response().members().get(0).toString()).doesNotContain("Taylor").doesNotContain("1234");
        server.verify();
    }

    @Test
    void serverErrorIsUnavailable503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo(MmiException.UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("HTTP_500");
        });
    }

    @Test
    void throttlingIsUnavailable503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class,
                e -> assertThat(e.detail()).isEqualTo("HTTP_429"));
    }

    @Test
    void requestTimeoutStatusIsUnavailable503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1")).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("HTTP_408");
        });
    }

    @Test
    void clientErrorIsRejected502() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.code()).isEqualTo(MmiException.REJECTED);
            assertThat(e.detail()).isEqualTo("HTTP_400");
        });
    }

    @Test
    void unparseableBodyIsInvalidResponse502() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://mmi.test/master/member/v1"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        RestMmiClient client = new RestMmiClient(builder, props("http://mmi.test"), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.code()).isEqualTo(MmiException.INVALID_RESPONSE);
        });
    }

    @Test
    void connectionRefusedIsUnavailable503() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        RestClient.Builder builder = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofSeconds(1))));
        RestMmiClient client = new RestMmiClient(builder, props("http://127.0.0.1:" + port), JSON);
        assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.detail()).isEqualTo("CONNECT_FAILED");
        });
    }

    @Test
    void readTimeoutIsUnavailable503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/master/member/v1", exchange -> {
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
            RestMmiClient client = new RestMmiClient(builder, props("http://127.0.0.1:" + server.getAddress().getPort()), JSON);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.search("123456789", "c")).isInstanceOfSatisfying(MmiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.detail()).isEqualTo("READ_TIMEOUT");
            });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesToStartWithoutABaseUrl() {
        assertThatThrownBy(() -> new RestMmiClient(RestClient.builder(), props(""), JSON)).hasMessageContaining("mmi.base-url");
    }
}
