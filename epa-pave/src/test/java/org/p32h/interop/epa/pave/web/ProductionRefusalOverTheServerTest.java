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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A replay and a suite row refused on production, through the real endpoints on the live profile,
 * against a stand-in playing production that records what reaches it.
 *
 * <p>Two halves meet here: each protocol service tells the gateway where its call came from, so
 * the gateway words the refusal for a replay or a suite, and the run's next step is headed by the
 * action's own label. Each half has tests of its own; this shows that together a tester reads the
 * origin's sentence once, never the advice meant for a screen, and that nothing arrived.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
class ProductionRefusalOverTheServerTest {

    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer PRODUCTION = production();

    private static HttpServer production() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                byte[] body = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": []}".getBytes(StandardCharsets.UTF_8);
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
        registry.add("config-store.allow-prod-writes", () -> "true");
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
        // On live a replay rebuilds the request from the ledger line, so the line must keep the member ID.
        registry.add("tracking.record-member-id", () -> "true");
    }

    @AfterAll
    static void stopTheStandIn() {
        PRODUCTION.stop(0);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> send(String method, String path, String contentType, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> json(String method, String path, String body, String cookie) throws Exception {
        return send(method, path, "application/json", body, cookie);
    }

    private HttpResponse<String> upload(String path, String fileName, String file, String... fields) throws Exception {
        String boundary = "pave-test-boundary";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(fields[i]).append("\"\r\n\r\n")
                    .append(fields[i + 1]).append("\r\n");
        }
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(fileName)
                .append("\"\r\nContent-Type: text/plain\r\n\r\n").append(file).append("\r\n--").append(boundary).append("--\r\n");
        return send("POST", path, "multipart/form-data; boundary=" + boundary, body.toString(), null);
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            found++;
        }
        return found;
    }

    private static final String SUBMISSION = "{\"member\":{\"memberId\":\"S123456789\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
            + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
            + "\"dateOfService\":\"2026-03-20\"}";

    @Test
    void aRefusedReplayAndARefusedSuiteRowSayTheirOwnSentenceOnceAndNothingArrives() throws Exception {
        String base = "http://127.0.0.1:" + PRODUCTION.getAddress().getPort() + "/live";
        assertThat(upload("/api/config/environments", "live.json", "{\"name\": \"vendor-live\", \"values\": [{\"key\": \"baseUrl\", "
                + "\"value\": \"" + base + "\"}]}", "kind", "prod").statusCode()).isEqualTo(200);
        assertThat(upload("/api/config/collections", "vendor.json", PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION)).statusCode())
                .isEqualTo(200);
        assertThat(json("POST", "/api/config/environments/vendor-live/activate", null, null).statusCode()).isEqualTo(200);

        // A submission sent from the screen with writes unlocked reaches production: that is the run replayed below.
        HttpResponse<String> unlocked = json("POST", "/api/config/writes", "{\"action\": \"unlock\", \"environment\": \"vendor-live\"}", null);
        assertThat(unlocked.statusCode()).isEqualTo(200);
        String cookie = unlocked.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        HttpResponse<String> sent = json("POST", "/api/pas/submit", SUBMISSION, cookie);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
        String runId = JsonSafe.parse(sent.body()).node().get("runId").asString();
        assertThat(RECEIVED).containsExactly("POST /live/Claim/$submit");

        // The session that unlocked keeps its unlock; a replay never carries one.
        HttpResponse<String> replayed = json("POST", "/api/runs/replay", "{\"runIds\": [\"" + runId + "\"]}", cookie);
        assertThat(replayed.statusCode()).isEqualTo(200);
        JsonNode entry = JsonSafe.parse(replayed.body()).node().get("results").get(0);
        String said = entry.get("verdict").toString() + entry.get("nextStep").toString();
        assertThat(entry.at("/nextStep/action").asString()).isEqualTo("SEND_ON_ITS_OWN");
        assertThat(count(said, "never carries")).as(said).isEqualTo(1);
        assertThat(said).contains("a replay never carries the production unlock").doesNotContain("To send it from a screen")
                .doesNotContain("unlock production writes on the Config tab");

        // A suite row, with no unlock anywhere.
        assertThat(json("POST", "/api/config/writes", "{\"action\": \"lock\"}", cookie).statusCode()).isEqualTo(200);
        HttpResponse<String> started = upload("/api/suites", "prod.csv", "caseId,protocol,memberId,npi,procedureCodes,dateOfService\n"
                + "prod-1,pas,S123456789,1234567893,95810,2026-03-20\n");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String suiteRunId = JsonSafe.parse(started.body()).node().get("suiteRunId").asString();
        String suite = null;
        for (int tries = 0; tries < 100; tries++) {
            suite = json("GET", "/api/suites/" + suiteRunId, null, null).body();
            if (!suite.contains("\"state\":\"running\"") && !suite.contains("\"state\":\"RUNNING\"")) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        }
        JsonNode row = JsonSafe.parse(suite).node().get("results").get(0);
        assertThat(row.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(row.get("nextAction").asString()).isEqualTo("SEND_ON_ITS_OWN");
        String rowSays = row.get("reason").asString() + " " + row.get("nextStep").asString();
        assertThat(count(rowSays, "never carries")).as(rowSays).isEqualTo(1);
        assertThat(rowSays).contains("a suite never carries the production unlock").doesNotContain("To send it from a screen")
                .doesNotContain("unlock production writes on the Config tab");
        assertThat(RECEIVED).containsExactly("POST /live/Claim/$submit");
    }
}
