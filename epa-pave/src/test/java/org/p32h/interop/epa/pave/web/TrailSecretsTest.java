package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * No secret reaches a trail file, member data does, and nothing of an entry reaches the log (R-21).
 *
 * <p>The application runs on the live profile against a stand-in of its own that copies every
 * secret the request carried into its answer. The runs post a secret as a value typed for the run
 * and inside a body of the tester's own as well, so each way a secret can arrive is tried. Then
 * every file under the trail's folder is searched for each secret, and the log of the whole test
 * for the member ID and the folder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
@ExtendWith(OutputCaptureExtension.class)
class TrailSecretsTest {

    private static final String BEARER = "ZZTRAIL-BEARER-TOKEN-0001";
    private static final String API_KEY = "ZZTRAIL-API-KEY-0002";
    private static final String VARIABLE = "ZZTRAIL-TENANT-SECRET-0003";
    private static final String CLIENT_SECRET = "ZZTRAIL-CLIENT-SECRET-0004";
    private static final String TYPED = "ZZTRAIL-TYPED-FOR-THE-RUN-0005";
    private static final List<String> SECRETS = List.of(BEARER, API_KEY, VARIABLE, CLIENT_SECRET, TYPED);
    private static final String MEMBER_ID = "S123456789";

    static final HttpServer VENDOR = vendor();

    private static HttpServer vendor() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getRawPath();
                ObjectNode body = JsonSafe.mapper().createObjectNode();
                if (path.endsWith("/token")) {
                    body.put("access_token", BEARER).put("token_type", "Bearer").put("expires_in", 3600);
                } else {
                    if (path.contains("/cds-services/")) {
                        body.putArray("cards");
                    } else if (path.endsWith("$submit")) {
                        body.put("resourceType", "Bundle").put("type", "collection").putArray("entry");
                    }
                    ObjectNode echoed = body.putObject("vendorExtra");
                    echoed.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                    echoed.put("key", "key " + exchange.getRequestHeaders().getFirst("x-api-key"));
                    echoed.put("tenant", "tenant " + exchange.getRequestHeaders().getFirst("X-Tenant"));
                    echoed.put("clientSecret", "as configured " + CLIENT_SECRET);
                    echoed.put("bodyAsReceived", sent);
                }
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
        registry.add("onyx.auth.enabled", () -> "true");
        registry.add("onyx.auth.token-url", () -> url("/token"));
        registry.add("onyx.auth.client-id", () -> "placeholder-client");
        registry.add("onyx.auth.client-secret", () -> CLIENT_SECRET);
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + VENDOR.getAddress().getPort() + path;
    }

    @AfterAll
    static void stopTheVendor() {
        VENDOR.stop(0);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, String fileName, String file, String... fields) throws Exception {
        String boundary = "pave-test-boundary";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(fields[i]).append("\"\r\n\r\n")
                    .append(fields[i + 1]).append("\r\n");
        }
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(fileName)
                .append("\"\r\nContent-Type: application/json\r\n\r\n").append(file).append("\r\n--").append(boundary).append("--\r\n");
        return send("POST", path, "multipart/form-data; boundary=" + boundary, body.toString());
    }

    private static String item(String name, String url) {
        return "{\"name\": \"" + name + "\", \"request\": {\"method\": \"POST\", \"header\": ["
                + "{\"key\": \"Content-Type\", \"value\": \"application/json\"}, {\"key\": \"x-api-key\", \"value\": \"{{apiKey}}\"}, "
                + "{\"key\": \"X-Tenant\", \"value\": \"{{tenantSecret}}\"}], \"url\": {\"raw\": \"" + url + "\"}}}";
    }

    private static final String COLLECTION = "{\"info\": {\"name\": \"Trail\", \"schema\": "
            + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": ["
            + item("order-sign Happy Path", "{{baseUrl}}/cds-services/order-sign-crd") + ", "
            + item("submit Happy Path", "{{baseUrl}}/Claim/$submit") + "]}";

    private static final String CASE = "{\"member\":{\"memberId\":\"" + MEMBER_ID + "\",\"firstName\":\"Given\","
            + "\"lastName\":\"Placeholder-Surname\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
            + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\",\"chainId\":\"new\"%s}";

    @Test
    void noTrailFileHoldsASecretTheMemberIdIsKeptAndTheLogHoldsNeither(CapturedOutput output) throws Exception {
        String environment = "{\"name\": \"trail-sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + url("/fhir") + "\"}, "
                + "{\"key\": \"apiKey\", \"value\": \"" + API_KEY + "\", \"type\": \"secret\"}, "
                + "{\"key\": \"tenantSecret\", \"value\": \"" + VARIABLE + "\", \"type\": \"secret\"}]}";
        assertThat(upload("/api/config/environments", "trail.json", environment, "kind", "sandbox").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/config/environments/trail-sandbox/activate", "application/json", null).statusCode()).isEqualTo(200);
        HttpResponse<String> uploaded = upload("/api/config/collections", "trail.postman_collection.json", COLLECTION);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        JsonNode collection = JsonSafe.parse(uploaded.body()).node().get("collection");
        String collectionId = collection.get("id").asString();
        Map<String, String> operations = Map.of("order-sign Happy Path", "CRD_ORDER_SIGN", "submit Happy Path", "PAS_SUBMIT");
        for (JsonNode request : JsonSafe.elements(collection, "requests")) {
            HttpResponse<String> mapped = send("PUT", "/api/config/collections/" + collectionId + "/mapping", "application/json",
                    "{\"requestId\": \"" + request.get("id").asString() + "\", \"operation\": \""
                            + operations.get(request.get("name").asString()) + "\"}");
            assertThat(mapped.statusCode()).isEqualTo(200);
        }

        Map<String, HttpResponse<String>> answers = new LinkedHashMap<>();
        answers.put("CRD run", send("POST", "/api/crd", "application/json", CASE.formatted("")));
        // A secret variable typed again for the run, as an override, never goes into the case kept.
        answers.put("CRD run with a typed value", send("POST", "/api/crd", "application/json",
                CASE.formatted(",\"variables\":{\"tenantSecret\":\"" + TYPED + "\"}")));
        answers.put("PAS submission", send("POST", "/api/pas/submit", "application/json", CASE.formatted("")));

        List<String> runIds = new ArrayList<>();
        for (Map.Entry<String, HttpResponse<String>> answer : answers.entrySet()) {
            assertThat(answer.getValue().statusCode()).as(answer.getKey() + ": " + answer.getValue().body()).isEqualTo(200);
            runIds.add(JsonSafe.parse(answer.getValue().body()).node().get("runId").asString());
        }

        Path trail = directory.resolve("run-history").resolve("trail");
        List<Path> files;
        try (Stream<Path> listed = Files.list(trail)) {
            files = listed.sorted().toList();
        }
        assertThat(files).hasSize(answers.size());
        for (Path file : files) {
            String text = Files.readString(file);
            for (String secret : SECRETS) {
                assertThat(text).as(file.getFileName() + " holds " + secret).doesNotContain(secret);
            }
            // Member data stays inside: it is what the trail is for.
            assertThat(text).as(file.getFileName().toString()).contains(MEMBER_ID).contains("Placeholder-Surname");
            // The echo came back masked, so the test proves something: the secrets went out and returned.
            assertThat(text).contains("[secret x-api-key header]");
        }

        for (String runId : runIds) {
            HttpResponse<String> kept = send("GET", "/api/trail/" + runId, null, null);
            assertThat(kept.statusCode()).isEqualTo(200);
            JsonNode body = JsonSafe.parse(kept.body()).node();
            assertThat(body.get("kept").asBoolean()).as(runId).isTrue();
            assertThat(body.get("entry").get("case").get("member").get("memberId").asString()).isEqualTo(MEMBER_ID);
            assertThat(body.get("entry").get("case").has("variables")).isFalse();
        }

        String log = output.getAll();
        assertThat(log).doesNotContain(MEMBER_ID).doesNotContain("Placeholder-Surname").doesNotContain(trail.toString());
        for (String secret : SECRETS) {
            assertThat(log).as("the log holds " + secret).doesNotContain(secret);
        }
    }
}
