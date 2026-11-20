package org.p32h.interop.epa.pave.service.config;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

/**
 * M7 end to end, on the live profile against a stand-in server of this test's own: a credential
 * typed into a collection reaches the server as written, and appears in no view of the
 * configuration, no wire view, no log line and no ledger line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
@ExtendWith(OutputCaptureExtension.class)
class LiteralCredentialsOverTheServerTest {

    private static final String CANARY = "ZZLIT";

    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer VENDOR = vendor();

    private static HttpServer vendor() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery()
                        + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"resourceType\": \"Parameters\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
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

    @Test
    void theValueReachesTheServerAndNoViewNoWireViewNoLogAndNoLedgerLineHoldsIt(CapturedOutput output) throws Exception {
        String base = "http://127.0.0.1:" + VENDOR.getAddress().getPort() + "/fhir";
        assertThat(upload("/api/config/environments", "sbx.json", "{\"name\": \"pave-sandbox\", \"values\": [{\"key\": \"baseUrl\", "
                + "\"value\": \"" + base + "\", \"enabled\": true}]}", "kind", "sandbox").statusCode()).isEqualTo(200);
        HttpResponse<String> uploaded = upload("/api/config/collections", "literal.postman_collection.json",
                LiteralCredentialsInACollectionTest.COLLECTION);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        assertThat(uploaded.body()).doesNotContain(CANARY).contains("holds a credential typed into the collection")
                .contains("the collection is kept exactly as it was uploaded, value included");
        assertThat(send("POST", "/api/config/environments/pave-sandbox/activate", null, null).statusCode()).isEqualTo(200);

        JsonNode collection = JsonSafe.parse(uploaded.body()).node().get("collection");
        String collectionId = collection.get("id").asString();
        String requestId = collection.get("requests").get(0).get("id").asString();
        String detail = send("GET", "/api/config/collections/" + collectionId + "/requests/" + requestId, null, null).body();
        String ref = "{\"collectionId\": \"" + collectionId + "\", \"requestId\": \"" + requestId + "\"}";
        List<String> views = List.of(detail, send("GET", "/api/config/collections", null, null).body(),
                send("GET", "/api/config/effective", null, null).body(),
                send("POST", "/api/config/resolve", "application/json", "{\"operation\": \"UNMAPPED\", \"request\": " + ref + "}").body(),
                send("POST", "/api/raw/preview", "application/json", "{\"request\": " + ref + "}").body());
        for (String view : views) {
            assertThat(view).doesNotContain(CANARY);
        }

        // The tester loads the body the request view handed out, and sends it from the collection as it is.
        String body = JsonSafe.parse(detail).node().get("body").asString();
        assertThat(body).contains("{{password as typed}}");
        HttpResponse<String> sent = send("POST", "/api/raw", "application/json", JsonSafe.mapper().writeValueAsString(
                java.util.Map.of("request", JsonSafe.parse(ref).node(), "rawBody", body)));
        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(RECEIVED).containsExactly("POST /fhir/oauth2/token?client_secret=ZZLIT-URLSECRET-15&api_key=ZZLIT-URLKEY-16"
                + "&access_token=ZZLIT-URLTOKEN-18&scope=read {\"resourceType\": \"Parameters\", \"password\": \"ZZLIT-JSONPASSWORD-24\"}");
        // The wire view of what was sent shows the masks.
        assertThat(sent.body()).doesNotContain(CANARY).contains("[secret client_secret as typed]").contains("[secret password as typed]");

        // No log line, and no ledger line.
        assertThat(output.getAll()).doesNotContain(CANARY);
        try (Stream<Path> files = Files.walk(directory.resolve("run-history"))) {
            List<Path> lines = files.filter(Files::isRegularFile).toList();
            assertThat(lines).isNotEmpty();
            for (Path file : lines) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain(CANARY);
            }
        }
    }
}
