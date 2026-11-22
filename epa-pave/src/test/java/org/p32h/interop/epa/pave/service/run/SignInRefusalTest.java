package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A token endpoint that refuses, under an environment's own sign-in, through the started
 * application on the live profile (D-305): the run card and "Test the sign-in" say what the
 * endpoint said, made safe, with the advice that names the setting; the log says none of it.
 */
@SpringBootTest
@ActiveProfiles("live")
@ExtendWith(OutputCaptureExtension.class)
class SignInRefusalTest {

    private static final String CLIENT_ID = "placeholder-client-id-0305";
    private static final String CLIENT_SECRET = "placeholder-client-secret-0305";
    private static final AtomicInteger STATUS = new AtomicInteger(401);
    /** The token endpoint's body; null answers with none. */
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger REACHED_SERVICE = new AtomicInteger();
    /** Each test saves an environment of its own, and a name is taken once. */
    private static final AtomicInteger SAVED = new AtomicInteger();

    @TempDir
    static Path home;

    private static HttpServer tokens;
    private static HttpServer service;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        tokens = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokens.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = BODY.get();
            if (body == null) {
                exchange.sendResponseHeaders(STATUS.get(), -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(STATUS.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
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

    /** Saves and activates a sandbox whose sign-in is client credentials sent as {@code how} says. */
    private MockMvc sandbox(String how) throws Exception {
        String name = "Refusal sandbox " + how + " " + SAVED.incrementAndGet();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("name", name);
        body.put("kind", "sandbox");
        body.put("acknowledgeNotProd", true);
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "http://127.0.0.1:" + tokens.getAddress().getPort() + "/token");
        signIn.put("clientId", CLIENT_ID);
        signIn.put("clientSecret", CLIENT_SECRET);
        signIn.put("scope", "system/*.read");
        signIn.put("clientAuthentication", how);
        int saved = mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getStatus();
        assertThat(saved).isEqualTo(200);
        mvc.perform(post("/api/config/environments/{name}/activate", name));
        return mvc;
    }

    private static JsonNode answer(MockMvc mvc, String path, String body) throws Exception {
        return JsonSafe.parse(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).node();
    }

    private static String detail(JsonNode run) {
        return run.at("/error/title").asString();
    }

    @Test
    void anInvalidClientWithADescriptionIsSaidOnTheRunCardAndInTheTestWithTheAdviceAndNotLogged(CapturedOutput output) throws Exception {
        STATUS.set(401);
        BODY.set("{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed for the placeholder tenant\"}");
        MockMvc mvc = sandbox("BASIC");
        int before = REACHED_SERVICE.get();

        JsonNode run = answer(mvc, "/api/crd", CASE);
        assertThat(run.at("/verdict/outcomeCode").asString()).isEqualTo("NOT_SIGNED_IN");
        assertThat(detail(run)).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: Client "
                + "authentication failed for the placeholder tenant. The request was not sent.");
        assertThat(run.at("/verdict/supporting/0").asString()).isEqualTo(detail(run));
        assertThat(run.at("/error/action").asString()).isEqualTo("Check the sign-in of the active environment in Configuration > "
                + "Environments: its token url, client id and client secret, or its bearer token, and the variables they name. The client "
                + "id and secret were sent in a Basic header: if the service wants them in the form body, choose In the form body under "
                + "Client authentication in Configuration > Environments > Sign-in.");
        assertThat(REACHED_SERVICE.get()).isEqualTo(before);

        JsonNode test = answer(mvc, "/api/config/sign-in/test", "{}");
        assertThat(test.at("/state").asString()).isEqualTo("FAILED");
        assertThat(test.at("/line").asString()).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It "
                + "said: Client authentication failed for the placeholder tenant.");
        assertThat(test.at("/advice").asString()).isEqualTo("The client id and secret were sent in a Basic header: if the service wants "
                + "them in the form body, choose In the form body under Client authentication in Configuration > Environments > Sign-in.");

        // The service's words reach the screen only: the log holds the outcome and nothing the endpoint said.
        assertThat(output.getAll()).doesNotContain("placeholder tenant").doesNotContain("invalid_client").doesNotContain(CLIENT_SECRET);
        assertThat(run.toString() + test).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void sentInTheBodyTheAdviceIsTheMirrorAndA401WithNoBodyIsAdvisedToo() throws Exception {
        STATUS.set(401);
        BODY.set(null);
        MockMvc mvc = sandbox("BODY");
        JsonNode run = answer(mvc, "/api/crd", CASE);
        // Without a body the sentence is as before.
        assertThat(detail(run)).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(run.at("/error/action").asString()).endsWith("The client id and secret were sent in the form body: if the service "
                + "wants them in a Basic header, choose Basic header under Client authentication in Configuration > Environments > Sign-in.");
        JsonNode test = answer(mvc, "/api/config/sign-in/test", "{}");
        assertThat(test.at("/line").asString()).isEqualTo("No access token: the token endpoint answered HTTP 401.");
        assertThat(test.at("/advice").asString()).startsWith("The client id and secret were sent in the form body:");
    }

    @Test
    void anInvalidScopePointsAtScopeAndABodyThatIsNotJsonIsAsBefore() throws Exception {
        STATUS.set(400);
        BODY.set("{\"error\":\"invalid_scope\",\"error_description\":\"The scope system/*.read is not granted to this client.\"}");
        MockMvc mvc = sandbox("BASIC");
        JsonNode run = answer(mvc, "/api/crd", CASE);
        assertThat(detail(run)).isEqualTo("No access token: the token endpoint answered HTTP 400 (invalid_scope). It said: The scope "
                + "system/*.read is not granted to this client. The request was not sent.");
        assertThat(run.at("/error/action").asString()).endsWith("The token endpoint refused the scope: check Scope under Sign-in in "
                + "Configuration > Environments, which PAVE sends as it stands there, and not at all when it is empty.");

        BODY.set("<html>Unauthorized</html>");
        JsonNode html = answer(mvc, "/api/crd", CASE);
        assertThat(detail(html)).isEqualTo("No access token: the token endpoint answered HTTP 400. The request was not sent.");
        // A 400 with no reason points at no one setting.
        assertThat(html.at("/error/action").asString()).isEqualTo("Check the sign-in of the active environment in Configuration > "
                + "Environments: its token url, client id and client secret, or its bearer token, and the variables they name.");
    }

    @Test
    void aDescriptionThatRepeatsTheSecretIsLongOrHoldsControlCharactersIsMadeSafe(CapturedOutput output) throws Exception {
        STATUS.set(401);
        BODY.set("{\"error\":\"invalid_client\",\"error_description\":\"Secret " + CLIENT_SECRET + " is not valid\"}");
        MockMvc mvc = sandbox("BASIC");
        JsonNode run = answer(mvc, "/api/crd", CASE);
        assertThat(detail(run)).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: Secret "
                + "[secret client secret] is not valid. The request was not sent.");
        assertThat(run.toString()).doesNotContain(CLIENT_SECRET);
        JsonNode test = answer(mvc, "/api/config/sign-in/test", "{}");
        assertThat(test.toString()).doesNotContain(CLIENT_SECRET).contains("[secret client secret]");

        BODY.set("{\"error\":\"invalid_client\",\"error_description\":\"" + "x".repeat(5000) + "\"}");
        String cut = detail(answer(mvc, "/api/crd", CASE));
        assertThat(cut).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client). It said: " + "x".repeat(300)
                + " ... (cut at 300 characters) The request was not sent.");

        BODY.set("{\"error\":\"invalid_client\",\"error_description\":\"line one\\nline\\u0007 two\\u202e\\u0000 three\\ttab\"}");
        assertThat(detail(answer(mvc, "/api/crd", CASE))).isEqualTo("No access token: the token endpoint answered HTTP 401 "
                + "(invalid_client). It said: line one line two three tab. The request was not sent.");
        assertThat(output.getAll()).doesNotContain(CLIENT_SECRET).doesNotContain("line one").doesNotContain("xxxxxxxxxx");
    }
}
