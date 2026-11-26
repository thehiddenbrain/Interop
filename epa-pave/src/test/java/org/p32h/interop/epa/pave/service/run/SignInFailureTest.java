package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * A call this tool could not sign in for, through the started application on the live profile, the
 * real token service and the real clients: it is recorded and said as not sent, never as a service
 * that could not be reached, and its words follow whether the cause is the tester's settings or the
 * token endpoint.
 *
 * <p>The token endpoint is a socket on the loopback interface whose answer each test sets. The
 * service the calls would go to counts what reaches it, and nothing must.
 */
@SpringBootTest
@ActiveProfiles("live")
class SignInFailureTest {

    private static final String CLIENT_ID = "placeholder-client-id-0042";
    private static final String CLIENT_SECRET = "placeholder-client-secret-0042";
    /** The token endpoint closes the connection without answering. */
    private static final int DROP = -1;
    private static final AtomicInteger TOKEN_STATUS = new AtomicInteger(400);
    private static final AtomicInteger REACHED_SERVICE = new AtomicInteger();

    @TempDir
    static Path home;

    private static HttpServer tokens;
    private static HttpServer service;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        tokens = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokens.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (TOKEN_STATUS.get() == DROP) {
                exchange.close();
                return;
            }
            byte[] body = "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(TOKEN_STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        tokens.start();
        service = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        service.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            REACHED_SERVICE.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        service.start();
        String base = "http://127.0.0.1:" + service.getAddress().getPort();
        registry.add("onyx.crd.url", () -> base);
        registry.add("onyx.dtr.url", () -> base + "/fhir");
        registry.add("onyx.pas.url", () -> base + "/fhir");
        registry.add("onyx.subscription.url", () -> base + "/fhir");
        registry.add("onyx.subscription.callback-url", () -> "http://127.0.0.1:8057/api/pas/notifications");
        registry.add("onyx.variables[crdOrderSignServiceId]", () -> "placeholder-service");
        registry.add("onyx.variables[tokenUrl]", () -> "http://127.0.0.1:" + tokens.getAddress().getPort() + "/token");
        registry.add("onyx.auth.enabled", () -> "true");
        registry.add("onyx.auth.token-url", () -> "{{tokenUrl}}");
        registry.add("onyx.auth.client-id", () -> CLIENT_ID);
        registry.add("onyx.auth.client-secret", () -> CLIENT_SECRET);
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @AfterAll
    static void stop() {
        tokens.stop(0);
        service.stop(0);
    }

    @Autowired WebApplicationContext context;

    private static final String CASE = "{\"member\":{\"memberId\":\"S123456789\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":"
            + "\"1234567893\"},\"procedureCodes\":[{\"code\":\"E0470\"}],\"dateOfService\":\"2026-03-20\"}";

    /**
     * A write is sent only to an environment whose kind is known, so a sandbox is uploaded and made
     * active first. It defines the same values the settings above do.
     */
    private void sandboxActive(MockMvc mvc) throws Exception {
        String environment = "{\"name\":\"Sign-in test sandbox\",\"values\":[{\"key\":\"tokenUrl\",\"value\":\"http://127.0.0.1:"
                + tokens.getAddress().getPort() + "/token\",\"enabled\":true},{\"key\":\"crdOrderSignServiceId\",\"value\":"
                + "\"placeholder-service\",\"enabled\":true}]}";
        mvc.perform(multipart("/api/config/environments").file(new MockMultipartFile("file", "sign-in.postman_environment.json",
                "application/json", environment.getBytes(StandardCharsets.UTF_8))).param("kind", "sandbox"));
        mvc.perform(post("/api/config/environments/{name}/activate", "Sign-in test sandbox"));
    }

    private JsonNode send(String path, String body) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        sandboxActive(mvc);
        String answer = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonSafe.parse(answer).node();
    }

    private JsonNode line(String runId) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        return JsonSafe.parse(mvc.perform(get("/api/runs/" + runId)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .node();
    }

    private static List<String> supporting(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.at("/verdict/supporting").forEach(line -> out.add(line.asString()));
        return out;
    }

    /** Every call that sends, as a tester starts it. */
    private static List<String[]> calls() {
        return List.of(new String[] {"/api/crd", CASE}, new String[] {"/api/dtr/package", CASE}, new String[] {"/api/pas/submit", CASE},
                new String[] {"/api/pas/subscriptions", "{}"});
    }

    private void assertNotSent(JsonNode answer, String what, String errorType) throws Exception {
        assertThat(answer.at("/verdict/outcomeCode").asString()).as(what + " " + answer).isEqualTo("NOT_SIGNED_IN");
        assertThat(answer.at("/verdict/tone").asString()).as(what).isEqualTo("NEUTRAL");
        assertThat(answer.at("/answeredBy").asString()).as(what).isEqualTo("NOT_SENT");
        assertThat(answer.at("/wire/request/sent").asBoolean()).as(what).isFalse();
        assertThat(answer.at("/wire/response").isMissingNode() || answer.at("/wire/response").isNull()).as(what).isTrue();
        JsonNode line = line(answer.at("/runId").asString());
        assertThat(line.at("/outcome").asString()).as(what).isEqualTo("NOT_SIGNED_IN");
        assertThat(line.at("/errorType").asString()).as(what).isEqualTo(errorType);
        assertThat(line.at("/answeredBy").asString()).as(what).isEqualTo("NOT_SENT");
        assertThat(line.at("/elapsedMillis").isMissingNode() || line.at("/elapsedMillis").isNull()).as(what).isTrue();
        // Neither the client id nor the secret is anywhere in what the browser gets.
        assertThat(answer.toString()).as(what).doesNotContain(CLIENT_ID).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void aTokenEndpointThatRefusesTheClientIsTheTestersSettingsAndNothingIsSent() throws Exception {
        TOKEN_STATUS.set(400);
        int before = REACHED_SERVICE.get();
        for (String[] call : calls()) {
            JsonNode answer = send(call[0], call[1]);
            assertNotSent(answer, call[0], "NOT_SIGNED_IN");
            assertThat(answer.at("/verdict/headline").asString()).as(call[0]).isEqualTo("Nothing was sent: this tool could not sign in.");
            assertThat(supporting(answer).get(0)).as(call[0]).contains("answered HTTP 400");
            assertThat(supporting(answer)).as(call[0]).noneMatch(line -> line.contains("which secret has no value"));
            assertThat(answer.at("/error/kind").asString()).as(call[0]).isEqualTo("CONFIGURATION");
            assertThat(answer.at("/error/heading").asString()).as(call[0]).isEqualTo("Not sent: this tool could not sign in");
            assertThat(answer.at("/nextStep/action").asString()).as(call[0]).isEqualTo("OPEN_CONFIG");
            assertThat(answer.at("/nextStep/headline").asString()).as(call[0]).isEqualTo("Next: correct the sign-in settings, then send again.");
        }
        assertThat(REACHED_SERVICE.get()).isEqualTo(before);
    }

    @Test
    void aTokenEndpointThatFailsIsTheEnvironmentAtTheTokenEndpointAndNothingIsSent() throws Exception {
        TOKEN_STATUS.set(503);
        int before = REACHED_SERVICE.get();
        for (String[] call : calls()) {
            JsonNode answer = send(call[0], call[1]);
            assertNotSent(answer, call[0], "TOKEN_ENDPOINT_FAULT");
            assertThat(answer.at("/verdict/headline").asString()).as(call[0])
                    .isEqualTo("Nothing was sent: the token endpoint gave this tool no access token.");
            assertThat(supporting(answer).get(0)).as(call[0]).contains("answered HTTP 503");
            assertThat(answer.at("/error/kind").asString()).as(call[0]).isEqualTo("UNREACHABLE");
            assertThat(answer.at("/error/heading").asString()).as(call[0]).isEqualTo("Not sent: the token endpoint gave no access token");
            assertThat(answer.at("/nextStep/action").asString()).as(call[0]).isEqualTo("CHECK_ENVIRONMENT");
            assertThat(answer.at("/nextStep/headline").asString()).as(call[0]).isEqualTo("Next: check the token endpoint, then send again.");
        }
        assertThat(REACHED_SERVICE.get()).isEqualTo(before);
    }

    @Test
    void aTokenEndpointThatDropsTheConnectionIsTheEnvironmentToo() throws Exception {
        // The endpoint closes the connection without an answer: the token service cannot read one.
        TOKEN_STATUS.set(DROP);
        JsonNode answer = send("/api/crd", CASE);
        assertNotSent(answer, "dropped", "TOKEN_ENDPOINT_FAULT");
        // It was reached, so it is not said to be unreachable: a port nothing listens on reads differently.
        assertThat(supporting(answer).get(0)).isEqualTo("No access token: the token endpoint closed the connection without answering. "
                + "The request was not sent.");
        assertThat(answer.at("/nextStep/action").asString()).isEqualTo("CHECK_ENVIRONMENT");
    }
}
