package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * The second guardrail audit repeated through the running application on the live profile, with
 * stand-ins of this test's own playing a sandbox and two production services, each recording what
 * arrives. The only proof that a write was blocked is that nothing arrived.
 *
 * <p>T1: a Lock from one browser session ends the unlock of every other, for the next write and
 * for a write that session prepared before the lock and had not yet sent. The access token
 * endpoint holds its answer until the lock is in place, so the write is caught between being
 * prepared and being sent, with no timing in the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
class SecondGuardrailAuditOverTheServerTest {

    /** A stand-in on the loopback interface that records the method, raw path and raw query of every request. */
    static final class StandIn {

        final HttpServer server;
        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch release;
        final CountDownLatch arrived = new CountDownLatch(1);

        StandIn(CountDownLatch release) {
            this.release = release;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                URI uri = exchange.getRequestURI();
                received.add(exchange.getRequestMethod() + " " + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                arrived.countDown();
                if (release != null) {
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = uri.getPath().endsWith("/token")
                        ? "{\"access_token\":\"placeholder-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)
                        : "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String url(String path) {
            return "http://127.0.0.1:" + port() + path;
        }

        /** The same address with its port written with leading zeros, as the audit wrote it. */
        String zeros(String path) {
            return "http://127.0.0.1:00" + port() + path;
        }
    }

    static final CountDownLatch TOKEN_RELEASE = new CountDownLatch(1);
    static final StandIn TOKEN = new StandIn(TOKEN_RELEASE);
    static final StandIn SANDBOX = new StandIn(null);
    static final StandIn PROD_A = new StandIn(null);
    static final StandIn PROD_B = new StandIn(null);

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
        registry.add("config-store.allow-prod-writes", () -> "true");
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
        registry.add("onyx.auth.enabled", () -> "true");
        registry.add("onyx.auth.token-url", () -> TOKEN.url("/token"));
        registry.add("onyx.auth.client-id", () -> "placeholder-client");
        registry.add("onyx.auth.client-secret", () -> "placeholder-secret");
        registry.add("onyx.variables.ymlZero", () -> PROD_B.zeros("/pb/fhir"));
        // G2-1 of the last check: production addresses that application.yml supplies.
        registry.add("onyx.variables.ymlProd", () -> PROD_B.url("/yp/fhir"));
        registry.add("onyx.variables.ymlProdOdd", () -> "http://127.0.0.1:+" + PROD_B.port() + "/yq/fhir");
    }

    @AfterAll
    static void stopTheStandIns() {
        TOKEN_RELEASE.countDown();
        for (StandIn standIn : List.of(TOKEN, SANDBOX, PROD_A, PROD_B)) {
            standIn.server.stop(0);
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> send(String method, String path, String contentType, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(90))
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

    private HttpResponse<String> upload(String path, String fileName, byte[] file, String... fields) throws Exception {
        String boundary = "pave-test-boundary";
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(fields[i]).append("\"\r\n\r\n")
                    .append(fields[i + 1]).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(fileName)
                .append("\"\r\nContent-Type: application/json\r\n\r\n");
        String body = head + new String(file, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n";
        return send("POST", path, "multipart/form-data; boundary=" + boundary, body, null);
    }

    private static byte[] environment(String name, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": \"").append(keysAndValues[i]).append("\", \"value\": \"")
                    .append(keysAndValues[i + 1]).append("\", \"enabled\": true}");
        }
        return ("{\"name\": \"" + name + "\", \"values\": [" + values + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String submit(String variablesJson) {
        return "{\"claimId\": \"CLM-A1\", \"rawBody\": \"{}\"" + (variablesJson == null ? "" : ", \"variables\": " + variablesJson) + "}";
    }

    private String unlock(String environment) throws Exception {
        HttpResponse<String> unlocked = json("POST", "/api/config/writes", "{\"action\": \"unlock\", \"environment\": \"" + environment + "\"}", null);
        assertThat(unlocked.statusCode()).as(unlocked.body()).isEqualTo(200);
        return unlocked.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private void activate(String environment) throws Exception {
        assertThat(json("POST", "/api/config/environments/" + URLEncoder.encode(environment, StandardCharsets.UTF_8).replace("+", "%20")
                + "/activate", null, null).statusCode()).isEqualTo(200);
    }

    private String raw(String collection, String request, String cookie) throws Exception {
        JsonNode collections = JsonSafe.parse(json("GET", "/api/config/collections", null, null).body()).node();
        for (JsonNode each : JsonSafe.elements(collections, "collections")) {
            if (collection.equals(JsonSafe.presentText(each, "name"))) {
                for (JsonNode summary : JsonSafe.elements(each, "requests")) {
                    if (request.equals(JsonSafe.presentText(summary, "name"))) {
                        return "{\"request\": {\"collectionId\": \"" + JsonSafe.presentText(each, "id") + "\", \"requestId\": \""
                                + JsonSafe.presentText(summary, "id") + "\"}}";
                    }
                }
            }
        }
        throw new AssertionError("No request " + request + " in " + collection);
    }

    @Test
    void theSecondAuditsRequestsReachNoProductionAndTheOrdinaryFlowsStillWork() throws Exception {
        String prodA = PROD_A.url("/pa/fhir");
        assertThat(upload("/api/config/environments", "a.json", environment("Audit Live A", "baseUrl", prodA, "crdUrl", prodA,
                "crdOrderSignServiceId", "order-sign-crd"), "kind", "prod").statusCode()).isEqualTo(200);
        assertThat(upload("/api/config/environments", "b.json", environment("Audit Live B", "baseUrl", PROD_B.url("/pb/fhir")), "kind", "prod")
                .statusCode()).isEqualTo(200);
        assertThat(upload("/api/config/environments", "s.json", environment("Audit Sandbox", "baseUrl", SANDBOX.url("/sbx")), "kind",
                "sandbox").statusCode()).isEqualTo(200);
        assertThat(upload("/api/config/collections", "vendor.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION)).statusCode())
                .isEqualTo(200);

        // T1. Session A unlocks Audit Live A and sends a submit; the token endpoint holds its answer, so the write is prepared
        // and not yet sent. Session B, which never unlocked, presses Lock.
        activate("Audit Live A");
        String sessionA = unlock("Audit Live A");
        CompletableFuture<HttpResponse<String>> inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return json("POST", "/api/pas/submit", submit(null), sessionA);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(TOKEN.arrived.await(60, TimeUnit.SECONDS)).as("the write is waiting for its token").isTrue();
        HttpResponse<String> lockedByB = json("POST", "/api/config/writes", "{\"action\": \"lock\"}", null);
        assertThat(lockedByB.statusCode()).isEqualTo(200);
        TOKEN_RELEASE.countDown();
        HttpResponse<String> prepared = inFlight.get(90, TimeUnit.SECONDS);
        // Refused at the send, in the refusal's own words: it is the lock that moved, not the environment.
        assertThat(prepared.statusCode()).isEqualTo(409);
        assertThat(prepared.body()).contains("\"kind\":\"CONFIGURATION\"")
                .contains("\"title\":\"Production writes were locked again before this write was sent, so it was not sent.\"")
                .contains("\"action\":\"Unlock them on the Config tab by typing the environment name, then send it again.\"")
                .doesNotContain("The configuration changed while this request was being prepared");
        assertThat(PROD_A.received).as("a write prepared before the lock").isEmpty();
        // A's next write is refused as locked.
        HttpResponse<String> next = json("POST", "/api/pas/submit", submit(null), sessionA);
        assertThat(next.statusCode()).isEqualTo(403);
        assertThat(PROD_A.received).as("A's next write").isEmpty();

        // B1. A sandbox at production's address with its port written with leading zeros: refused at upload, naming it.
        for (String spelling : List.of(PROD_A.zeros("/pa/fhir"), "http://127.0.0.1:0" + PROD_A.port() + "/pa/fhir")) {
            HttpResponse<String> zeros = upload("/api/config/environments", "z.json", environment("Spelling 0", "baseUrl", spelling), "kind",
                    "sandbox");
            assertThat(zeros.statusCode()).as(spelling).isEqualTo(422);
            assertThat(zeros.body()).contains("is an address of the production environment \\\"Audit Live A\\\"");
        }
        // B1 through application.yml: a sandbox whose address is {{ymlZero}}, production B with leading zeros. Its
        // address is read through application.yml as it would be sent, so it is refused at upload.
        HttpResponse<String> ymlZero = upload("/api/config/environments", "y.json", environment("Yml Zero", "baseUrl", "{{ymlZero}}"),
                "kind", "sandbox");
        assertThat(ymlZero.statusCode()).isEqualTo(422);
        assertThat(ymlZero.body()).contains("is an address of the production environment \\\"Audit Live B\\\"");
        // G2-1. A production environment whose address application.yml supplies is remembered at that address, and
        // a sandbox or a uat written at that very address is refused. One whose address cannot be known before
        // sending, read from the operating system or unreadable through application.yml, is not stored.
        assertThat(upload("/api/config/environments", "yp.json", environment("P YP", "baseUrl", "{{ymlProd}}"), "kind", "prod")
                .statusCode()).isEqualTo(200);
        for (String kind : List.of("sandbox", "uat")) {
            HttpResponse<String> near = upload("/api/config/environments", "n.json", environment("Near YP", "baseUrl", PROD_B.url("/yp/fhir")),
                    "kind", kind, "acknowledgeNotProd", "true");
            assertThat(near.statusCode()).as(kind).isEqualTo(422);
            assertThat(near.body()).as(kind).contains("is an address of the production environment \\\"P YP\\\"");
        }
        for (String reference : List.of("{{ymlProdOdd}}", "{{env:PAVE_P32H_TEST_PRODURL}}")) {
            HttpResponse<String> unknown = upload("/api/config/environments", "u.json", environment("P Unknown", "baseUrl", reference),
                    "kind", "prod");
            assertThat(unknown.statusCode()).as(reference).isEqualTo(422);
            assertThat(unknown.body()).as(reference).contains("holds \\\"" + reference + "\\\", which");
        }
        // B1 through a collection literal, from a sandbox and while production A is unlocked.
        String literals = "{\"info\": {\"name\": \"Literal zeros\", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": " + ConfigFixture.items(
                ConfigFixture.item("transaction zero", "POST", PROD_B.zeros("/pb/fhir")),
                ConfigFixture.item("submit by yml", "POST", "{{ymlZero}}/Claim/$submit"),
                ConfigFixture.item("submit at yp", "POST", PROD_B.url("/yp/fhir/Claim/$submit")),
                ConfigFixture.item("transaction unreadable", "POST", "http://127.0.0.1:" + (PROD_B.port() + 65536) + "/elsewhere/fhir"),
                ConfigFixture.item("search encoded", "GET", "{{baseUrl}}/Claim?patient=Patient%2FS123456789")) + "}";
        assertThat(upload("/api/config/collections", "literals.json", literals.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(200);
        activate("Audit Sandbox");
        assertThat(json("POST", "/api/raw", raw("Literal zeros", "transaction zero", null), null).statusCode()).isEqualTo(403);
        // A write whose address application.yml gives, from a sandbox: refused, naming production B.
        HttpResponse<String> yml = json("POST", "/api/raw", raw("Literal zeros", "submit by yml", null), null);
        assertThat(yml.statusCode()).isEqualTo(403);
        assertThat(yml.body()).contains("belongs to the production environment \\\"Audit Live B\\\"");
        HttpResponse<String> atYp = json("POST", "/api/raw", raw("Literal zeros", "submit at yp", null), null);
        assertThat(atYp.statusCode()).isEqualTo(403);
        assertThat(atYp.body()).contains("belongs to the production environment \\\"P YP\\\"");
        HttpResponse<String> unreadable = json("POST", "/api/raw", raw("Literal zeros", "transaction unreadable", null), null);
        assertThat(unreadable.statusCode()).isEqualTo(403);
        assertThat(unreadable.body()).contains("could not be read the way the HTTP client reads them");
        activate("Audit Live A");
        String unlockedA = unlock("Audit Live A");
        assertThat(json("POST", "/api/raw", raw("Literal zeros", "transaction zero", null), unlockedA).statusCode()).isEqualTo(403);
        assertThat(json("POST", "/api/raw", raw("Literal zeros", "submit by yml", null), unlockedA).statusCode()).isEqualTo(403);
        json("POST", "/api/config/writes", "{\"action\": \"lock\"}", unlockedA);

        // M-ENC. Production A active and locked: an encoded separator typed for one run hides a write in a read.
        HttpResponse<String> hook = json("POST", "/api/crd", "{\"hook\": \"order-sign\", \"rawBody\": \"{}\", \"variables\": "
                + "{\"crdOrderSignServiceId\": \"..%2F..%2FClaim%2F%24submit\"}}", null);
        assertThat(hook.statusCode()).isEqualTo(422);
        assertThat(hook.body()).contains("crdOrderSignServiceId").doesNotContain("..%2F");
        HttpResponse<String> inquiry = json("POST", "/api/pas/inquire", "{\"rawBody\": \"{}\", \"variables\": {\"baseUrl\": \""
                + prodA + "/Claim/$inquire/..%2F..%2FClaim?x=\"}}", null);
        assertThat(inquiry.statusCode()).isEqualTo(422);
        HttpResponse<String> semicolon = json("POST", "/api/pas/inquire", "{\"rawBody\": \"{}\", \"variables\": {\"baseUrl\": \""
                + prodA + "/Claim/$inquire/..%3B/..%3B/Claim?x=\"}}", null);
        assertThat(semicolon.statusCode()).isEqualTo(422);

        // Nothing reached either production service.
        assertThat(PROD_A.received).isEmpty();
        assertThat(PROD_B.received).isEmpty();

        // The ordinary flows. A sandbox whose address has an explicit port sends its write.
        activate("Audit Sandbox");
        assertThat(json("POST", "/api/pas/submit", submit(null), null).statusCode()).isEqualTo(200);
        assertThat(SANDBOX.received).contains("POST /sbx/Claim/$submit");
        // A FHIR search with an encoded character in its query is sent as written.
        assertThat(json("POST", "/api/raw", raw("Literal zeros", "search encoded", null), null).statusCode()).isEqualTo(200);
        assertThat(SANDBOX.received).contains("GET /sbx/Claim?patient=Patient%2FS123456789");
        // Production A, unlocked from the Config tab, receives its own write.
        activate("Audit Live A");
        String own = unlock("Audit Live A");
        assertThat(json("POST", "/api/pas/submit", submit(null), own).statusCode()).isEqualTo(200);
        assertThat(PROD_A.received).containsExactly("POST /pa/fhir/Claim/$submit");
        assertThat(PROD_B.received).isEmpty();
    }
}
