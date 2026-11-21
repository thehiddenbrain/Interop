package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.service.crd.CrdResponseMapper;
import org.p32h.interop.epa.pave.service.crd.CrdVerdicts;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * A fault a mock scenario played, on each protocol, as the running server answers it on the default
 * profile: the next step is a rehearsal and sends nobody to fix an environment that never failed.
 * The same fault from a service that is not a scenario keeps the words a real fault gets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayedFaultNextStepTest {

    private static final String REHEARSAL = "Next, had this been a real service: ";
    private static final String CONNECTION_LINE = "The connection was refused or could not be opened.";

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
                + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"E0470\"}],"
                + "\"dateOfService\":\"2026-03-20\"}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return JsonSafe.parse(response.body()).node();
    }

    private static List<String> supporting(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.at("/verdict/supporting").forEach(line -> out.add(line.asString()));
        return out;
    }

    @Test
    void eachProtocolWordsAPlayedFaultAsARehearsalWithTheActionARealOneGets() throws Exception {
        // path, sentinel, scenario id, the action a real fault of that kind gets
        for (String[] played : List.of(new String[] {"/api/crd", "P32H-ERR500", "crd-12-server-error-500", "CHECK_ENVIRONMENT"},
                new String[] {"/api/crd", "P32H-BAD400", "crd-11-bad-request-400", "FIX_REQUEST"},
                new String[] {"/api/pas/submit", "P32H-PAS-DOWN", "pas-13-transport-failure", "CHECK_ENVIRONMENT"},
                new String[] {"/api/pas/submit", "P32H-PAS-ERR500", "pas-17-server-error-500", "CHECK_ENVIRONMENT"},
                new String[] {"/api/dtr/package", "P32H-DTR-MALFORMED", "dtr-06-malformed-package", "REVIEW"},
                new String[] {"/api/dtr/package", "P32H-DTR-ERR500", "dtr-05-package-error-500", "CHECK_ENVIRONMENT"})) {
            JsonNode answer = post(played[0], played[1]);
            String headline = answer.at("/nextStep/headline").asString();
            String reason = answer.at("/nextStep/reason").asString();
            assertThat(headline).as(played[1]).startsWith(REHEARSAL);
            assertThat(reason).as(played[1]).contains("Pick another case to go on").doesNotContain(played[2]);
            assertThat(answer.at("/nextStep/action").asString()).as(played[1]).isEqualTo(played[3]);
            assertThat(supporting(answer)).as(played[1]).doesNotContain(CONNECTION_LINE);
            // The line that names the scenario is still the first under the verdict, which the reason points to.
            assertThat(supporting(answer).get(0)).as(played[1]).contains(played[2]);
        }
    }

    /**
     * After a played PAS fault a real service could have received, the two ways on are one line:
     * another case first, then the inquiry rehearsed. A played unreachable service received
     * nothing and offers no inquiry.
     */
    @Test
    void aPlayedPasFaultGivesBothWaysOnInOneLineInOrder() throws Exception {
        String failed = post("/api/pas/submit", "P32H-PAS-ERR500").at("/nextStep/reason").asString();
        assertThat(failed).isEqualTo("On mock a scenario played this answer, to show how this tool reads it; the line under the verdict names "
                + "it. Pick another case to go on, or rehearse the inquiry: had this been a real service, Send inquiry would check whether "
                + "the payer has the submission.");
        String timedOut = post("/api/pas/submit", "P32H-PAS-TIMEOUT").at("/nextStep/reason").asString();
        assertThat(timedOut).isEqualTo("Had this been a real service, the payer might have received the submission, and sending it again "
                + "without checking could create a second request. Pick another case to go on, or rehearse the inquiry: Send inquiry would "
                + "check whether the payer has it.");
        for (String reason : List.of(failed, timedOut)) {
            assertThat(reason.split("Pick another case", -1)).hasSize(2);
            assertThat(reason.split("Send inquiry", -1)).hasSize(2);
        }
        assertThat(post("/api/pas/submit", "P32H-PAS-DOWN").at("/nextStep/reason").asString()).doesNotContain("inquiry")
                .endsWith("Pick another case to go on.");
    }

    @Test
    void aPlayedTimeoutKeepsItsOwnRehearsedReason() throws Exception {
        JsonNode answer = post("/api/crd", "P32H-TIMEOUT");
        assertThat(answer.at("/nextStep/headline").asString()).isEqualTo(REHEARSAL + "check the environment, then send again.");
        assertThat(answer.at("/nextStep/reason").asString()).isEqualTo(PlayedFaults.aboutTheEnvironment("the member or the code"));
    }

    @Test
    void theSameFaultFromAServiceThatIsNoScenarioKeepsTheWordsOfARealFault() {
        CrdResponseMapper mapper = new CrdResponseMapper();
        VendorExchange real = TestExchanges.crd(500, "{\"resourceType\":\"OperationOutcome\"}");
        NextStepView live = CrdVerdicts.nextStep(mapper.read(real), real);
        assertThat(live.headline()).isEqualTo("Next: check the environment, then send again.");
        assertThat(live.reason()).doesNotContain("Pick another case");

        VendorExchange played = real.withScenario("crd-12-server-error-500", "chosen for this test");
        NextStepView rehearsed = CrdVerdicts.nextStep(mapper.read(played), played);
        assertThat(rehearsed.headline()).isEqualTo(REHEARSAL + "check the environment, then send again.");
        assertThat(rehearsed.action()).isEqualTo(live.action());
        assertThat(rehearsed.carry()).isEqualTo(live.carry());
    }

    @Test
    void aSubscriptionFaultAScenarioPlayedIsARehearsalToo() {
        // No scenario file plays a subscription fault today, so the exchange is marked as a scenario's here.
        VendorExchange played = TestExchanges.of(EpaOperation.SUBSCRIPTION_UPDATE, 503, "<html>Unavailable</html>", false)
                .withScenario("pas-10-subscription-lifecycle", "chosen for this test");
        NextStepView step = PlayedFaults.rehearsed(played, SubscriptionOutcome.SERVICE_ERROR,
                new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then list the subscriptions before sending "
                        + "again.", "The service may have received the request and applied it.", Map.of()), "unused");
        assertThat(step.headline()).startsWith(REHEARSAL);
        assertThat(step.reason()).isEqualTo(PlayedFaults.pickAnother());
        assertThat(step.action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        // Not a fault, so not touched.
        assertThat(PlayedFaults.played(played, SubscriptionOutcome.UPDATED)).isFalse();
    }
}
