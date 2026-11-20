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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A service that sends a secret of the request back never gets it onto a screen, for every answer
 * the browser receives.
 *
 * <p>The wire view masks a secret a service repeats. What a screen draws besides it, the designed
 * view and the fields returned but not interpreted, is read from the parsed body, and a raw send
 * once copied that body as received, so the secret the wire view hid was shown in clear a few
 * lines below it. A test of one mapper cannot show the whole answer is clean, so this starts the
 * application on the live profile against a stand-in of its own that copies the bearer token, an
 * api key header and the value of a secret variable of the environment into a field nobody
 * anticipated and into a property name, and searches each whole answer the controllers return.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
class SecretsSentBackOverTheServerTest {

    private static final String BEARER = "ZZECHO-BEARER-TOKEN-0001";
    private static final String API_KEY = "ZZECHO-API-KEY-0002";
    private static final String VARIABLE = "ZZECHO-TENANT-SECRET-0003";
    private static final List<String> SECRETS = List.of(BEARER, API_KEY, VARIABLE);

    /** Every request that reached the stand-in, with the three headers that carry a secret. */
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer VENDOR = vendor();

    private static HttpServer vendor() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getRawPath();
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String key = exchange.getRequestHeaders().getFirst("x-api-key");
                String tenant = exchange.getRequestHeaders().getFirst("X-Tenant");
                RECEIVED.add(exchange.getRequestMethod() + " " + path + " auth=" + authorization + " key=" + key + " tenant=" + tenant);
                byte[] body = (path.endsWith("/token")
                        ? "{\"access_token\":\"" + BEARER + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                        : echo(path, authorization, key, tenant)).getBytes(StandardCharsets.UTF_8);
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

    /** A body of the shape each operation expects, with every secret the request carried copied where nobody looks for it. */
    private static String echo(String path, String authorization, String key, String tenant) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        if (path.endsWith("/cds-services")) {
            body.putArray("services");
        } else if (path.contains("/cds-services/")) {
            body.putArray("cards");
        } else if (path.endsWith("$questionnaire-package")) {
            body.put("resourceType", "Parameters").putArray("parameter");
        } else if (path.endsWith("$next-question")) {
            body.put("resourceType", "Questionnaire").put("status", "active");
        } else if (path.endsWith("$submit") || path.endsWith("$inquire")) {
            body.put("resourceType", "Bundle").put("type", "collection").putArray("entry");
        } else if (path.endsWith("/Subscription")) {
            body.put("resourceType", "Subscription").put("id", "sub-echo-1").put("status", "requested");
        }
        ObjectNode extra = body.putObject("vendorExtra");
        extra.put("echoedAuthorization", String.valueOf(authorization));
        extra.put("echoedKey", "key " + key);
        extra.put("echoedTenant", "tenant " + tenant + " as received");
        body.put(String.valueOf(key), "an api key used as a property name");
        body.put(String.valueOf(tenant), "a secret variable used as a property name");
        body.put(String.valueOf(authorization), "the authorization header used as a property name");
        return body.toString();
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
        registry.add("onyx.subscription.callback-url", () -> "https://pave.example.invalid/api/pas/notifications?access_token=" + CALLBACK);
    }

    private static final String CLIENT_SECRET = "ZZECHO-CLIENT-SECRET-0005";
    private static final String CALLBACK = "ZZECHO-CALLBACK-TOKEN-0004";

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

    private HttpResponse<String> json(String method, String path, String body) throws Exception {
        return send(method, path, "application/json", body);
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

    /** A collection request with the two headers that carry a secret of the environment. */
    private static String item(String name, String method, String url) {
        return "{\"name\": \"" + name + "\", \"request\": {\"method\": \"" + method + "\", \"header\": ["
                + "{\"key\": \"Content-Type\", \"value\": \"application/json\"}, {\"key\": \"x-api-key\", \"value\": \"{{apiKey}}\"}, "
                + "{\"key\": \"X-Tenant\", \"value\": \"{{tenantSecret}}\"}], \"url\": {\"raw\": \"" + url + "\"}}}";
    }

    private static final Map<String, String> MAPPED = new LinkedHashMap<>();

    static {
        MAPPED.put("order-sign Happy Path", "CRD_ORDER_SIGN");
        MAPPED.put("$questionnaire-package Happy path", "DTR_QUESTIONNAIRE_PACKAGE");
        MAPPED.put("next-question Happy Path", "DTR_NEXT_QUESTION");
        MAPPED.put("submit Happy Path", "PAS_SUBMIT");
        MAPPED.put("inquiry Happy Path", "PAS_INQUIRE");
        MAPPED.put("subscription-create Happy Path", "SUBSCRIPTION_CREATE");
        MAPPED.put("member by id", "MEMBER_LOOKUP");
        MAPPED.put("provider by npi", "PROVIDER_LOOKUP");
        MAPPED.put("echo as written", "UNMAPPED");
    }

    private static final String COLLECTION = "{\"info\": {\"name\": \"Echo\", \"schema\": "
            + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": ["
            + String.join(", ", List.of(
            item("order-sign Happy Path", "POST", "{{baseUrl}}/cds-services/order-sign-crd"),
            item("$questionnaire-package Happy path", "POST", "{{baseUrl}}/Questionnaire/$questionnaire-package"),
            item("next-question Happy Path", "POST", "{{baseUrl}}/Questionnaire/$next-question"),
            item("submit Happy Path", "POST", "{{baseUrl}}/Claim/$submit"),
            item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire"),
            item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription"),
            item("member by id", "GET", "{{baseUrl}}/Member/{id}"),
            item("provider by npi", "GET", "{{baseUrl}}/Provider/{id}"),
            item("echo as written", "GET", "{{baseUrl}}/echo")))
            + "]}";

    private static final String CASE = "{\"member\":{\"memberId\":\"S123456789\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
            + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
            + "\"dateOfService\":\"2026-03-20\"%s}";

    @Test
    void noAnswerTheBrowserReceivesHoldsASecretTheServiceSentBack() throws Exception {
        String environment = "{\"name\": \"echo-sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + url("/fhir") + "\"}, "
                + "{\"key\": \"apiKey\", \"value\": \"" + API_KEY + "\", \"type\": \"secret\"}, "
                + "{\"key\": \"tenantSecret\", \"value\": \"" + VARIABLE + "\", \"type\": \"secret\"}]}";
        assertThat(upload("/api/config/environments", "echo.json", environment, "kind", "sandbox").statusCode()).isEqualTo(200);
        assertThat(json("POST", "/api/config/environments/echo-sandbox/activate", null).statusCode()).isEqualTo(200);
        HttpResponse<String> uploaded = upload("/api/config/collections", "echo.postman_collection.json", COLLECTION);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        JsonNode collection = JsonSafe.parse(uploaded.body()).node().get("collection");
        String collectionId = collection.get("id").asString();
        Map<String, String> ids = new LinkedHashMap<>();
        for (JsonNode request : JsonSafe.elements(collection, "requests")) {
            ids.put(request.get("name").asString(), request.get("id").asString());
        }
        // Each request is mapped by hand, so the test does not depend on what detection makes of a name.
        for (Map.Entry<String, String> mapped : MAPPED.entrySet()) {
            HttpResponse<String> changed = json("PUT", "/api/config/collections/" + collectionId + "/mapping",
                    "{\"requestId\": \"" + ids.get(mapped.getKey()) + "\", \"operation\": \"" + mapped.getValue() + "\"}");
            assertThat(changed.statusCode()).as(mapped.getKey()).isEqualTo(200);
        }

        Map<String, HttpResponse<String>> answers = new LinkedHashMap<>();
        answers.put("CRD run", json("POST", "/api/crd", CASE.formatted("")));
        answers.put("CRD discovery", json("GET", "/api/crd/discover", null));
        answers.put("DTR package", json("POST", "/api/dtr/package", CASE.formatted("")));
        answers.put("DTR next question", json("POST", "/api/dtr/next-question",
                "{\"questionnaire\": \"{\\\"resourceType\\\":\\\"Questionnaire\\\"}\", \"provider\": {\"npi\": \"1234567893\"}}"));
        answers.put("PAS submission", json("POST", "/api/pas/submit", CASE.formatted("")));
        answers.put("PAS inquiry", json("POST", "/api/pas/inquire", CASE.formatted(",\"claimId\":\"CLM-ECHO-1\"")));
        answers.put("subscription", json("POST", "/api/pas/subscriptions", "{}"));
        answers.put("member lookup", json("GET", "/api/members/S123456789", null));
        answers.put("provider lookup", json("GET", "/api/providers/1234567893", null));
        answers.put("raw send", json("POST", "/api/raw", "{\"request\": {\"collectionId\": \"" + collectionId + "\", \"requestId\": \""
                + ids.get("echo as written") + "\"}}"));

        for (Map.Entry<String, HttpResponse<String>> answer : answers.entrySet()) {
            assertThat(answer.getValue().statusCode()).as(answer.getKey() + ": " + answer.getValue().body()).isEqualTo(200);
            for (String secret : SECRETS) {
                assertThat(answer.getValue().body()).as(answer.getKey() + " holds " + secret).doesNotContain(secret);
            }
        }
        // The raw send lists what came back in its own words: the echoed values are there, masked.
        assertThat(answers.get("raw send").body()).contains("vendorExtra.echoedKey").contains("[secret x-api-key header]")
                .contains("[secret tenantSecret]");

        // The test proves nothing unless every secret went out and came back: each call carried both
        // environment secrets, and the vendor calls carried the token.
        List<String> calls = RECEIVED.stream().filter(line -> !line.contains("/token ")).toList();
        assertThat(calls).hasSizeGreaterThanOrEqualTo(answers.size());
        for (String call : calls) {
            if (!call.contains("/cds-services ")) {
                // Discovery is put together from the hook's url, without that request's headers.
                assertThat(call).contains("key=" + API_KEY).contains("tenant=" + VARIABLE);
            }
        }
        assertThat(calls).anyMatch(call -> call.contains("/Claim/$submit") && call.contains("auth=Bearer " + BEARER));
        assertThat(calls).anyMatch(call -> call.contains("/cds-services/order-sign-crd") && call.contains("auth=Bearer " + BEARER));

        // A notification the service delivers repeats each of the four kinds of secret this tool gives it: a secret variable
        // of the active environment, the access token, the client secret and the credential of the address to call back.
        String notification = "{\"resourceType\":\"Bundle\",\"type\":\"history\",\"entry\":[{\"resource\":{\"resourceType\":"
                + "\"ClaimResponse\",\"id\":\"cr-echo-1\",\"disposition\":\"echo " + VARIABLE + " " + BEARER + " " + CLIENT_SECRET + " "
                + CALLBACK + "\"}}]}";
        HttpResponse<String> delivered = send("POST", "/api/pas/notifications", "application/fhir+json", notification);
        assertThat(delivered.statusCode()).as(delivered.body()).isEqualTo(200);
        HttpResponse<String> held = json("GET", "/api/pas/notifications", null);
        for (HttpResponse<String> shown : List.of(delivered, held)) {
            for (String secret : List.of(VARIABLE, BEARER, CLIENT_SECRET, CALLBACK)) {
                assertThat(shown.body()).as("the notification holds " + secret).doesNotContain(secret);
            }
        }
        assertThat(held.body()).contains("[secret tenantSecret]").contains("4 places in this payload are masked");
    }
}
