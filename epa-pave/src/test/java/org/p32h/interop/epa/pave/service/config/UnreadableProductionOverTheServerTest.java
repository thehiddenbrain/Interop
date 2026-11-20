package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * N1 of the check of the guardrail fixes, through the running application on the live profile,
 * with a stand-in playing a sandbox and one playing production, each recording what arrives. The
 * only proof that a write was blocked is that nothing arrived.
 *
 * <p>The store starts as an earlier version of this tool left it: a production environment whose
 * address the client cannot read ({@code :+port}), and a sandbox on the same machine. That address
 * protects its whole host until the production environment is uploaded again written plainly. Every
 * spelling the checker used is then refused at upload as production, and the plain spelling
 * uploaded as a sandbox afterwards is refused too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
class UnreadableProductionOverTheServerTest {

    static final SecondGuardrailAuditOverTheServerTest.StandIn SANDBOX = new SecondGuardrailAuditOverTheServerTest.StandIn(null);
    static final SecondGuardrailAuditOverTheServerTest.StandIn PROD = new SecondGuardrailAuditOverTheServerTest.StandIn(null);

    @TempDir
    static Path directory;

    static String plain() {
        return PROD.url("/zq/fhir");
    }

    /** The checker's spellings of the production stand-in's address, none of which the client can read. */
    static List<String> unreadable() {
        int port = PROD.port();
        return List.of("http://127.0.0.1:+" + port + "/zq/fhir", "http://127.0.0.1:" + port + "\\zq\\fhir",
                "http://127.0.0.1:" + port + ":/zq/fhir", "http://127.0.0.1:" + port + "/zq/fh\tir", "http://127.0.0.1:99999/zq/fhir",
                "http://127.0.0.1:" + port + ":80/zq/fhir", "http://127.0.0.1: " + port + "/zq/fhir");
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        Path store = directory.resolve("config-store");
        try {
            Files.createDirectories(store.resolve("environments"));
            Files.write(store.resolve("environments/legacy-prod.json"), environment("Legacy Prod", "baseUrl", unreadable().get(0)));
            Files.write(store.resolve("environments/audit-sandbox.json"), environment("Audit Sandbox", "baseUrl", SANDBOX.url("/sbx")));
            Files.writeString(store.resolve("store.json"), "{\"version\": 1, \"epoch\": 5, \"activeEnvironment\": \"Audit Sandbox\", "
                    + "\"environments\": [{\"name\": \"Legacy Prod\", \"kind\": \"prod\", \"file\": \"environments/legacy-prod.json\"}, "
                    + "{\"name\": \"Audit Sandbox\", \"kind\": \"sandbox\", \"file\": \"environments/audit-sandbox.json\"}], "
                    + "\"productionDestinations\": [], \"collections\": []}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("config-store.directory", store::toString);
        registry.add("config-store.allow-prod-writes", () -> "true");
        registry.add("tracking.directory", () -> directory.resolve("run-history").toString());
    }

    @AfterAll
    static void stopTheStandIns() {
        SANDBOX.server.stop(0);
        PROD.server.stop(0);
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

    static byte[] environment(String name, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i]))
                    .append(", \"value\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i + 1])).append(", \"enabled\": true}");
        }
        return ("{\"name\": " + JsonSafe.mapper().writeValueAsString(name) + ", \"values\": [" + values + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String title(HttpResponse<String> response) {
        return JsonSafe.presentText(JsonSafe.parse(response.body()).node(), "title");
    }

    private String unlock(String environment) throws Exception {
        HttpResponse<String> unlocked = json("POST", "/api/config/writes", "{\"action\": \"unlock\", \"environment\": \"" + environment + "\"}", null);
        assertThat(unlocked.statusCode()).as(unlocked.body()).isEqualTo(200);
        return unlocked.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> activate(String environment) throws Exception {
        return json("POST", "/api/config/environments/" + URLEncoder.encode(environment, StandardCharsets.UTF_8).replace("+", "%20")
                + "/activate", null, null);
    }

    private String raw(String collection, String request) throws Exception {
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

    private static final String SUBMIT = "{\"claimId\": \"CLM-A1\", \"rawBody\": \"{}\"}";

    @Test
    void anUnreadableProductionAddressProtectsItsHostAndIsNeverAcceptedAgain() throws Exception {
        assertThat(upload("/api/config/collections", "vendor.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION)).statusCode())
                .isEqualTo(200);
        String literals = "{\"info\": {\"name\": \"Literal\", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": " + ConfigFixture.items(
                ConfigFixture.item("transaction plain", "POST", plain())) + "}";
        assertThat(upload("/api/config/collections", "literal.json", literals.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(200);

        // The store as an earlier version left it: the unreadable production address protects every port and path of its host.
        JsonNode listed = JsonSafe.parse(json("GET", "/api/config/environments", null, null).body()).node();
        assertThat(JsonSafe.elements(listed, "productionDestinations")).anySatisfy(each -> assertThat(JsonSafe.presentText(each, "destination"))
                .isEqualTo("every port and every path of the host 127.0.0.1, because an address of this environment could not be read the "
                        + "way the HTTP client reads it"));
        HttpResponse<String> sandboxPlain = upload("/api/config/environments", "s.json", environment("UP plain", "baseUrl", plain()),
                "kind", "sandbox", "acknowledgeNotProd", "true");
        assertThat(sandboxPlain.statusCode()).isEqualTo(422);
        assertThat(title(sandboxPlain)).contains("\"Legacy Prod\"");
        assertThat(json("POST", "/api/pas/submit", SUBMIT, null).statusCode()).as("the sandbox stored before, on the same host").isEqualTo(403);
        assertThat(json("POST", "/api/raw", raw("Literal", "transaction plain"), null).statusCode()).isEqualTo(403);
        HttpResponse<String> activated = activate("Legacy Prod");
        assertThat(activated.statusCode()).isEqualTo(422);
        assertThat(title(activated)).startsWith("\"Legacy Prod\" cannot be made active: it is a production environment, and the variable "
                + "\"baseUrl\" holds the address \"http://127.0.0.1:+" + PROD.port() + "/zq/fhir\"");
        assertThat(PROD.received).isEmpty();
        assertThat(SANDBOX.received).isEmpty();

        // Every spelling of the checker is refused as production, with the address quoted as the file writes it.
        for (String spelling : unreadable()) {
            HttpResponse<String> refused = upload("/api/config/environments", "p.json", environment("Legacy Prod", "baseUrl", spelling),
                    "kind", "prod");
            assertThat(refused.statusCode()).as(spelling).isEqualTo(422);
            assertThat(title(refused)).as(spelling).isEqualTo("This environment was declared as prod, and the variable \"baseUrl\" holds the "
                    + "address \"" + spelling.replace("\t", "\\t") + "\", which this tool cannot read the way the HTTP client reads it. It "
                    + "was not stored.");
        }

        // Written plainly, it replaces the whole host: the sandbox writes again, and the plain spelling stays production.
        assertThat(upload("/api/config/environments", "p.json", environment("Legacy Prod", "baseUrl", plain()), "kind", "prod").statusCode())
                .isEqualTo(200);
        assertThat(json("POST", "/api/pas/submit", SUBMIT, null).statusCode()).isEqualTo(200);
        assertThat(SANDBOX.received).containsExactly("POST /sbx/Claim/$submit");
        HttpResponse<String> again = upload("/api/config/environments", "s.json", environment("UP plain", "baseUrl", plain()),
                "kind", "sandbox", "acknowledgeNotProd", "true");
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(title(again)).contains("is an address of the production environment \"Legacy Prod\"");
        assertThat(json("POST", "/api/raw", raw("Literal", "transaction plain"), null).statusCode()).isEqualTo(403);
        assertThat(PROD.received).isEmpty();

        // Production written plainly and unlocked for itself receives its own write.
        assertThat(activate("Legacy Prod").statusCode()).isEqualTo(200);
        String own = unlock("Legacy Prod");
        assertThat(json("POST", "/api/pas/submit", SUBMIT, own).statusCode()).isEqualTo(200);
        assertThat(PROD.received).containsExactly("POST /zq/fhir/Claim/$submit");
    }
}
