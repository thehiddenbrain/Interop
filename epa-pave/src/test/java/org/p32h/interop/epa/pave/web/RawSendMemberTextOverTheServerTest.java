package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A request sent as written carries no member on its form, so its note, tester reference and
 * expectation are checked against the member identifiers the body about to be sent states: the
 * saved body, a body supplied for the run, and a body whose identifier only a run value fills in.
 * Each refusal is a 422 on the field, before anything is sent or recorded. A body in which no
 * identifier can be read is not refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RawSendMemberTextOverTheServerTest {

    private static final String SAVED_BODY = "{\"resourceType\": \"Bundle\", \"entry\": ["
            + "{\"resource\": {\"resourceType\": \"Patient\", \"identifier\": [{\"value\": \"S123456789\"}]}},"
            + "{\"resource\": {\"resourceType\": \"Coverage\", \"subscriberId\": \"S987654321\"}}]}";

    private static final String COLLECTION = """
            {"info": {"name": "Sent as written", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "item": [
              {"name": "post a bundle", "request": {"method": "POST", "url": {"raw": "https://example.org/p32h-placeholder/Thing"},
                "body": {"mode": "raw", "raw": %s}}}
             ]}
            """.formatted(JsonSafe.mapper().writeValueAsString(SAVED_BODY));

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

    private String upload() throws Exception {
        String boundary = "pave-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"as-written.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + COLLECTION + "\r\n--" + boundary + "--\r\n";
        HttpResponse<String> uploaded = send("POST", "/api/config/collections", "multipart/form-data; boundary=" + boundary, body);
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
        JsonNode collections = JsonSafe.parse(send("GET", "/api/config/collections", null, null).body()).node();
        for (JsonNode each : JsonSafe.elements(collections, "collections")) {
            for (JsonNode summary : JsonSafe.elements(each, "requests")) {
                if ("post a bundle".equals(JsonSafe.presentText(summary, "name"))) {
                    return "{\"collectionId\": \"" + JsonSafe.presentText(each, "id") + "\", \"requestId\": \""
                            + JsonSafe.presentText(summary, "id") + "\"}";
                }
            }
        }
        throw new AssertionError("The uploaded request is not listed");
    }

    /** Every ledger line written so far, as text. */
    private List<String> ledger() throws Exception {
        List<String> lines = new ArrayList<>();
        Path dir = home.resolve("run-history");
        if (!Files.isDirectory(dir)) {
            return lines;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                lines.addAll(Files.readAllLines(file));
            }
        }
        return lines;
    }

    private static String field(HttpResponse<String> refused, int index) {
        return JsonSafe.parse(refused.body()).node().at("/fieldErrors/" + index + "/field").asString();
    }

    private static String message(HttpResponse<String> refused, int index) {
        return JsonSafe.parse(refused.body()).node().at("/fieldErrors/" + index + "/message").asString();
    }

    @Test
    void aNoteHoldingAnIdentifierOfTheBodyIsRefusedOnTheFieldAndNothingIsRecorded() throws Exception {
        String ref = upload();
        int before = ledger().size();

        // The saved body states the member ID; the note holds it with a space in it.
        HttpResponse<String> refused = send("POST", "/api/raw", "application/json",
                "{\"request\": " + ref + ", \"note\": \"member S 123456789 again\"}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(field(refused, 0)).isEqualTo("note");
        assertThat(message(refused, 0)).isEqualTo("The note holds a member ID the request body carries. The ledger keeps it as written "
                + "and may not hold member data, so leave it out. Nothing was sent.");
        assertThat(refused.body()).doesNotContain("123456789");

        // The subscriber ID of the Coverage, in the tester reference.
        refused = send("POST", "/api/raw", "application/json", "{\"request\": " + ref + ", \"testerRef\": \"TKT-S987654321\"}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(field(refused, 0)).isEqualTo("testerRef");
        assertThat(message(refused, 0)).contains("a subscriber ID the request body carries");

        // The preview refuses it the same way.
        refused = send("POST", "/api/raw/preview", "application/json", "{\"request\": " + ref + ", \"note\": \"S123456789\"}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(field(refused, 0)).isEqualTo("note");

        // A body supplied for the run, whose identifier only a run value fills in, is read once resolved.
        String supplied = "{\"resourceType\": \"Patient\", \"identifier\": [{\"value\": \"{{who}}\"}]}";
        refused = send("POST", "/api/raw", "application/json", "{\"request\": " + ref + ", \"rawBody\": "
                + JsonSafe.mapper().writeValueAsString(supplied) + ", \"variables\": {\"who\": \"S555666777\"}, \"note\": \"s555666777\"}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(field(refused, 0)).isEqualTo("note");

        assertThat(ledger()).as("a refused run is not recorded").hasSize(before);
    }

    @Test
    void aBodyThatStatesNoIdentifierIsNotRefusedAndANoteWithoutOneIsSent() throws Exception {
        String ref = upload();
        int before = ledger().size();

        HttpResponse<String> sent = send("POST", "/api/raw", "application/json",
                "{\"request\": " + ref + ", \"note\": \"plain remark\", \"testerRef\": \"TKT-1\"}");
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);

        // Not JSON: no identifier can be read, so the same note is not refused.
        sent = send("POST", "/api/raw", "application/json", "{\"request\": " + ref + ", \"rawBody\": \"member S123456789\", "
                + "\"note\": \"S123456789\"}");
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
        assertThat(ledger()).hasSize(before + 2);
    }
}
