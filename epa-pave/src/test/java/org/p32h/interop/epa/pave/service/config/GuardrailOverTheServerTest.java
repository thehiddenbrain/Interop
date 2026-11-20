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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The guardrail through the running application on the live profile, with stand-in servers of
 * this test's own that record every request that reaches them. The only proof that a write was
 * blocked is that nothing arrived, so each request of the guardrail audit is sent the way a tester
 * or another page would send it, and the stand-in for the second production environment is asked
 * at the end what it received: nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
class GuardrailOverTheServerTest {

    /** A vendor stand-in on the loopback interface. It answers every call with an empty JSON object and a token request with a token. */
    static final class StandIn {

        final HttpServer server;
        final List<String> received = new CopyOnWriteArrayList<>();

        StandIn() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                        + (authorization == null ? "" : " with " + authorization));
                byte[] body = exchange.getRequestURI().getPath().endsWith("/token")
                        ? "{\"access_token\":\"placeholder-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)
                        : "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }
    }

    static final StandIn SANDBOX = new StandIn();
    static final StandIn LIVE = new StandIn();
    static final StandIn LIVE_TWO = new StandIn();
    static final StandIn ELSEWHERE = new StandIn();

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
        registry.add("config-store.allow-prod-writes", () -> "true");
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
        registry.add("onyx.auth.enabled", () -> "true");
        registry.add("onyx.auth.token-url", () -> SANDBOX.url("/token"));
        registry.add("onyx.auth.client-id", () -> "placeholder-client");
        registry.add("onyx.auth.client-secret", () -> "placeholder-secret");
    }

    @AfterAll
    static void stopTheStandIns() {
        for (StandIn standIn : List.of(SANDBOX, LIVE, LIVE_TWO, ELSEWHERE)) {
            standIn.server.stop(0);
        }
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

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\", \"enabled\": true}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String submit(String baseUrl) {
        return "{\"claimId\": \"CLM-A1\", \"rawBody\": \"{}\"" + (baseUrl == null ? "" : ", \"variables\": {\"baseUrl\": \"" + baseUrl + "\"}")
                + "}";
    }

    private static String var(String baseUrl) {
        return "?var.baseUrl=" + URLEncoder.encode(baseUrl, StandardCharsets.UTF_8);
    }

    @Test
    void noWriteReachesAProductionAddressItWasNotUnlockedForAndTheTokenGoesOnlyToTheServiceItWasIssuedFor() throws Exception {
        String sandbox = SANDBOX.url("/sbx");
        String live = LIVE.url("/live");
        String liveTwo = LIVE_TWO.url("/live2");
        assertThat(upload("/api/config/environments", "live.json", environment("vendor-live", live), "kind", "prod").statusCode()).isEqualTo(200);
        assertThat(upload("/api/config/environments", "live2.json", environment("vendor-live-two", liveTwo), "kind", "prod").statusCode())
                .isEqualTo(200);
        assertThat(upload("/api/config/environments", "sbx.json", environment("pave-sandbox", sandbox), "kind", "sandbox").statusCode())
                .isEqualTo(200);
        assertThat(upload("/api/config/collections", "vendor.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION)).statusCode())
                .isEqualTo(200);

        // G2: a sandbox is active, and a value typed for the run points a write at the second production environment.
        assertThat(json("POST", "/api/config/environments/pave-sandbox/activate", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> moved = json("POST", "/api/pas/submit", submit(liveTwo), null);
        assertThat(moved.statusCode()).isEqualTo(403);
        assertThat(moved.body()).contains("the value typed for this run for baseUrl would change where it goes");
        assertThat(json("DELETE", "/api/pas/subscriptions/SUB-9" + var(liveTwo), null, null).statusCode()).isEqualTo(403);
        // The sandbox's own write goes, with the token, which is the sandbox's.
        assertThat(json("POST", "/api/pas/submit", submit(null), null).statusCode()).isEqualTo(200);
        assertThat(SANDBOX.received).contains("POST /sbx/Claim/$submit with Bearer placeholder-access-token");

        // G3: a read pointed elsewhere for one run is sent, without the token, and says so.
        HttpResponse<String> read = json("GET", "/api/pas/subscriptions/SUB-1" + var(ELSEWHERE.url("/attacker")), null, null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(ELSEWHERE.received).containsExactly("GET /attacker/Subscription/SUB-1");
        assertThat(read.body()).contains("The Onyx access token was not attached").doesNotContain("placeholder-access-token");
        assertThat(json("GET", "/api/pas/subscriptions/SUB-1", null, null).statusCode()).isEqualTo(200);
        assertThat(SANDBOX.received).contains("GET /sbx/Subscription/SUB-1 with Bearer placeholder-access-token");

        // G1: production environment one is active and unlocked; a value typed for the run points its writes at production two.
        assertThat(json("POST", "/api/config/environments/vendor-live/activate", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> unlocked = json("POST", "/api/config/writes", "{\"action\": \"unlock\", \"environment\": \"vendor-live\"}", null);
        assertThat(unlocked.statusCode()).isEqualTo(200);
        String cookie = unlocked.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        assertThat(json("POST", "/api/pas/submit", submit(liveTwo), cookie).statusCode()).isEqualTo(403);
        assertThat(json("DELETE", "/api/pas/subscriptions/SUB-9" + var(liveTwo), null, cookie).statusCode()).isEqualTo(403);
        // Its own address, unlocked, is written to; another session is not unlocked.
        assertThat(json("POST", "/api/pas/submit", submit(null), cookie).statusCode()).isEqualTo(200);
        assertThat(LIVE.received).contains("POST /live/Claim/$submit with Bearer placeholder-access-token");
        assertThat(json("POST", "/api/pas/submit", submit(null), null).statusCode()).isEqualTo(403);

        // G9: locking again moves no epoch.
        long before = epoch();
        assertThat(json("POST", "/api/config/writes", "{\"action\": \"lock\"}", cookie).statusCode()).isEqualTo(200);
        assertThat(epoch()).isEqualTo(before);
        assertThat(json("POST", "/api/pas/submit", submit(null), cookie).statusCode()).isEqualTo(403);

        // G6: production two is deleted and comes back as a sandbox. It is refused, and its address is still shown as production.
        assertThat(json("DELETE", "/api/config/environments/vendor-live-two", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> relabelled = upload("/api/config/environments", "live2.json", environment("vendor-live-two", liveTwo),
                "kind", "sandbox", "acknowledgeNotProd", "true");
        assertThat(relabelled.statusCode()).isEqualTo(422);
        assertThat(relabelled.body()).contains("belongs to the production environment \\\"vendor-live-two\\\"");
        assertThat(json("GET", "/api/config/environments", null, null).body()).contains("\"productionDestinations\"")
                .contains("{\"environment\":\"vendor-live-two\",\"destination\":\"" + liveTwo + "\",\"stored\":false}");

        // Nothing at all reached the production environment that was never unlocked.
        assertThat(LIVE_TWO.received).isEmpty();
        assertThat(LIVE.received).containsExactly("POST /live/Claim/$submit with Bearer placeholder-access-token");
    }

    private long epoch() throws Exception {
        String body = json("GET", "/api/config/effective", null, null).body();
        int at = body.indexOf("\"epoch\":") + "\"epoch\":".length();
        int end = at;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(at, end));
    }
}
