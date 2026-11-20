package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A timeout a mock scenario plays, on each protocol tab, as the running server answers it on the
 * default profile.
 *
 * <p>The answer says once, in the error panel, that a scenario played the timeout, that nothing
 * was sent and that no environment failed. The verdict and the next step do not say it again, and
 * what a real timeout would mean is worded as that: beside "nothing was sent", "the payer may have
 * received the submission" said as a fact is a contradiction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayedTimeoutOverTheServerTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @LocalServerPort
    int port;

    private JsonNode post(String path, String memberId) throws Exception {
        String body = "{\"member\":{\"memberId\":\"" + memberId + "\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
                + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
                + "\"dateOfService\":\"2026-03-20\"}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return JsonSafe.parse(response.body()).node();
    }

    /** Every sentence of the verdict, the next step and the error panel, in the order a tester reads them. */
    private static List<String> sentences(JsonNode answer) {
        List<String> out = new ArrayList<>();
        out.add(answer.at("/verdict/headline").asString());
        answer.at("/verdict/supporting").forEach(line -> out.add(line.asString()));
        out.add(answer.at("/nextStep/headline").asString());
        out.add(answer.at("/nextStep/reason").asString());
        out.add(answer.at("/error/title").asString());
        out.add(answer.at("/error/action").asString());
        return out;
    }

    private static long saying(List<String> sentences, String words) {
        return sentences.stream().filter(line -> line.toLowerCase(java.util.Locale.ROOT).contains(words)).count();
    }

    @Test
    void eachProtocolTabSaysOnceThatTheTimeoutWasPlayedAndWordsTheRestAsWhatARealOneWouldMean() throws Exception {
        for (String[] tab : List.of(new String[] {"/api/crd", "P32H-TIMEOUT", "the member or the code"},
                new String[] {"/api/dtr/package", "P32H-DTR-TIMEOUT", "the member or the questionnaire"})) {
            JsonNode answer = post(tab[0], tab[1]);
            List<String> said = sentences(answer);
            assertThat(answer.at("/error/kind").asString()).as(tab[0]).isEqualTo("TIMEOUT");
            // The heading is the server's, and says what the timeout was rather than blaming an environment.
            assertThat(answer.at("/error/heading").asString()).as(tab[0])
                    .isEqualTo("A timeout played by a mock scenario, not a fault in the environment");
            assertThat(answer.at("/error/title").asString()).as(tab[0]).startsWith("A mock scenario played a timeout. Nothing was sent and no "
                    + "environment failed: the response time shown, ");
            assertThat(answer.at("/error/action").asString()).as(tab[0]).startsWith("Had this been a real service, this tool would have "
                    + "waited up to ");
            assertThat(saying(said, "played a timeout")).as(tab[0] + " " + said).isEqualTo(1);
            assertThat(saying(said, "nothing was sent")).as(tab[0] + " " + said).isEqualTo(1);
            assertThat(saying(said, "inside the read timeout")).as(tab[0] + " " + said).isZero();
            assertThat(answer.at("/verdict/supporting")).as(tab[0]).hasSize(1);
            assertThat(answer.at("/nextStep/action").asString()).as(tab[0]).isEqualTo("CHECK_ENVIRONMENT");
            assertThat(answer.at("/nextStep/headline").asString()).as(tab[0])
                    .isEqualTo("Next, had this been a real service: check the environment, then send again.");
            assertThat(answer.at("/nextStep/reason").asString()).as(tab[0]).isEqualTo("Had this been a real service, a timeout would be a "
                    + "finding about the environment, not about " + tab[2] + ".");
        }

        JsonNode submission = post("/api/pas/submit", "P32H-PAS-TIMEOUT");
        List<String> said = sentences(submission);
        assertThat(saying(said, "played a timeout")).as(said.toString()).isEqualTo(1);
        assertThat(saying(said, "nothing was sent")).as(said.toString()).isEqualTo(1);
        assertThat(saying(said, "inside the read timeout")).as(said.toString()).isZero();
        assertThat(submission.at("/error/heading").asString()).isEqualTo("A timeout played by a mock scenario, not a fault in the environment");
        // Never said as what happened beside "nothing was sent".
        assertThat(saying(said, "may have received")).as(said.toString()).isZero();
        assertThat(submission.at("/verdict/supporting")).hasSize(1);
        assertThat(submission.at("/nextStep/action").asString()).isEqualTo("CHECK_ENVIRONMENT");
        assertThat(submission.at("/nextStep/headline").asString()).isEqualTo("Next, had this been a real service: check the environment, "
                + "then ask for the status before sending again.");
        assertThat(submission.at("/nextStep/reason").asString()).isEqualTo("Had this been a real service, the payer might have received the "
                + "submission: ask for its status before sending it again. Sending it again without checking could create a second request.");
        // The claim id to ask about is still handed on, as after a real timeout.
        assertThat(submission.at("/nextStep/carry/claimId")).hasSize(1);
    }

    @Test
    void everyOtherErrorIsHeadedByTheServerAsItsKindIs() throws Exception {
        JsonNode failed = post("/api/crd", "P32H-ERR500");
        assertThat(failed.at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(failed.at("/error/heading").asString()).isEqualTo("A fault in the environment: the service failed");

        // An error answered in place of a result is headed too.
        HttpResponse<String> refused = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/crd"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"hook\": \"order-sign\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        JsonNode body = JsonSafe.parse(refused.body()).node();
        assertThat(body.at("/kind").asString()).isEqualTo("VALIDATION");
        assertThat(body.at("/heading").asString()).isEqualTo("A problem with what was submitted");
    }

    @Test
    void theChainViewAndTheSuiteVocabularySayWhatIsTrueOfAPlayedTimeout() throws Exception {
        String body = "{\"member\":{\"memberId\":\"P32H-TIMEOUT\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
                + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
                + "\"dateOfService\":\"2026-03-20\",\"chainId\":\"new\"}";
        HttpResponse<String> run = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/crd"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
        String chainId = JsonSafe.parse(run.body()).node().at("/chainId").asString();
        assertThat(chainId).startsWith("chain-");

        HttpResponse<String> chain = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/chains/" + chainId)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(chain.statusCode()).as(chain.body()).isEqualTo(200);
        JsonNode view = JsonSafe.parse(chain.body()).node();
        assertThat(view.at("/nextAction").asString()).isEqualTo("CHECK_ENVIRONMENT");
        String label = "Check the environment. No usable answer came back: on the live profile the service failed or could not be "
                + "reached, and on the mock profile a scenario played that, with nothing sent.";
        assertThat(view.at("/nextHeadline").asString()).isEqualTo(label);

        // A suite row names its next action by the words the scenarios endpoints serve.
        HttpResponse<String> scenarios = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/crd/scenarios")).GET().build(), HttpResponse.BodyHandlers.ofString());
        List<String> labels = new ArrayList<>();
        JsonSafe.parse(scenarios.body()).node().get("nextActions").forEach(option -> {
            if ("CHECK_ENVIRONMENT".equals(option.at("/code").asString())) {
                labels.add(option.at("/label").asString());
            }
        });
        assertThat(labels).containsExactly(label);
    }
}
