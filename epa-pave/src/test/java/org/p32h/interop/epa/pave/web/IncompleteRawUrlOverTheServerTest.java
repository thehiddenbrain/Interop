package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A request sent as written whose url still has a part with no value, on the mock profile, as the
 * running server answers the three questions the Config tab asks about it: what the preview of
 * its values says, what the raw preview says and what the raw send says. The send refuses it, as
 * on live, so the values preview has to say so rather than promise a scenario's answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncompleteRawUrlOverTheServerTest {

    private static final String COLLECTION = """
            {"info": {"name": "Incomplete urls", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item": [
              {"name": "thing by id", "request": {"method": "GET", "url": {"raw": "https://example.org/p32h-placeholder/Thing/{id}"}}},
              {"name": "subscription-get Happy Path", "request": {"method": "GET",
                "url": {"raw": "https://example.org/p32h-placeholder/fhir/Subscription/:id"}}}
             ]}
            """;

    private static final String REFUSED = "Sent as written from the Config tab, it is refused until every part of its url has a value, on "
            + "the mock profile as on live, and nothing is sent.";

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String file) throws Exception {
        String boundary = "pave-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"incomplete.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + file + "\r\n--" + boundary + "--\r\n";
        return send("POST", "/api/config/collections", "multipart/form-data; boundary=" + boundary, body);
    }

    /** The collection id, the request id and the operation in force for one request of the uploaded collection. */
    private String[] find(String name) throws Exception {
        JsonNode collections = JsonSafe.parse(send("GET", "/api/config/collections", null, null).body()).node();
        for (JsonNode each : JsonSafe.elements(collections, "collections")) {
            for (JsonNode summary : JsonSafe.elements(each, "requests")) {
                if (name.equals(JsonSafe.presentText(summary, "name"))) {
                    return new String[] {JsonSafe.presentText(each, "id"), JsonSafe.presentText(summary, "id"),
                            JsonSafe.presentText(summary, "effectiveOperation")};
                }
            }
        }
        throw new AssertionError("No request " + name);
    }

    @Test
    void theValuesPreviewSaysWhatTheRawPreviewAndTheRawSendDo() throws Exception {
        assertThat(upload(COLLECTION).statusCode()).isEqualTo(200);
        for (String name : new String[] {"thing by id", "subscription-get Happy Path"}) {
            String[] found = find(name);
            String ref = "{\"collectionId\": \"" + found[0] + "\", \"requestId\": \"" + found[1] + "\"}";

            // The values preview, as the Config tab asks for it: by the operation in force and the request.
            HttpResponse<String> resolved = send("POST", "/api/config/resolve", "application/json",
                    "{\"operation\": \"" + found[2] + "\", \"request\": " + ref + "}");
            assertThat(resolved.statusCode()).as(name + ": " + resolved.body()).isEqualTo(200);
            JsonNode notes = JsonSafe.parse(resolved.body()).node().get("notes");
            assertThat(notes.toString()).as(name).contains("No environment is active. Open Configuration and make one active.").contains(REFUSED);

            // The raw preview and the raw send refuse the same request, naming the part.
            for (String path : new String[] {"/api/raw/preview", "/api/raw"}) {
                HttpResponse<String> refused = send("POST", path, "application/json", "{\"request\": " + ref + "}");
                assertThat(refused.statusCode()).as(name + " " + path + ": " + refused.body()).isEqualTo(422);
                assertThat(JsonSafe.parse(refused.body()).node().at("/fieldErrors/0/field").asString()).as(name + " " + path)
                        .isEqualTo("urlValues.id");
            }
        }
        // A request that can only be sent as written is not said to be answered by a scenario.
        String[] unmapped = find("thing by id");
        assertThat(unmapped[2]).isEqualTo("UNMAPPED");
        String note = send("POST", "/api/config/resolve", "application/json", "{\"operation\": \"UNMAPPED\", \"request\": {\"collectionId\": \""
                + unmapped[0] + "\", \"requestId\": \"" + unmapped[1] + "\"}}").body();
        assertThat(note).doesNotContain("answered from a scenario");
        assertThat(note).contains(REFUSED);
    }
}
