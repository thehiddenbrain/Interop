package org.p32h.interop.epa.pave.client.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.pas.PasJson;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;

/**
 * A PAS scenario body that repeats a key is played exactly as written, as the CRD mock plays one:
 * writing it out again from its tree would keep only the last value of each repeated key, and the
 * wire view would not be the scenario's text. The scenario note says what the mock then does not do.
 */
class PasMockRepeatedKeyTest {

    private static final String REPEATED = "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"type\":\"collection\",\"entry\":[{\"resource\":"
            + "{\"resourceType\":\"ClaimResponse\",\"id\":\"claimresponse-{{claimId}}\",\"outcome\":\"error\",\"outcome\":\"complete\"}}]}";

    @Test
    void aBodyThatRepeatsAKeyIsPlayedAsWrittenAndOneThatDoesNotIsStillAnAnswerToTheClaimSent(@TempDir Path dir) throws IOException {
        Path bundled = Path.of("src/main/resources/scenarios");
        try (Stream<Path> files = Files.walk(bundled)) {
            for (Path file : files.toList()) {
                Path target = dir.resolve(bundled.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        Files.writeString(dir.resolve("pas/pas-90-repeated-key.json"), scenario("pas-90-repeated-key", 90, REPEATED));
        Files.writeString(dir.resolve("pas/pas-91-plain.json"), scenario("pas-91-plain", 91, REPEATED.replace("\"type\":\"batch\",", "")
                .replace("\"outcome\":\"error\",", "")));
        AppProperties app = AppProperties.of(Map.of("mock-latency-enabled", "false", "scenario-dir", dir.toString()));
        MockOnyxPasClient pas = new MockOnyxPasClient(new ScenarioRepository(app), new MockExchanges(app));

        VendorExchange repeated = submit(pas, "pas-90-repeated-key");
        assertThat(repeated.responseBody()).isEqualTo(REPEATED);
        assertThat(JsonSafe.parse(repeated.responseBody()).repeated()).extracting(JsonSafe.RepeatedKey::path)
                .containsExactlyInAnyOrder("type", "entry[0].resource.outcome");
        assertThat(repeated.scenarioNote()).contains("The scenario's body repeats a key, so it is played exactly as written: it does not "
                + "name the claim that was sent");

        VendorExchange plain = submit(pas, "pas-91-plain");
        assertThat(plain.responseBody()).contains("claimresponse-claim-zz-1").doesNotContain("{{claimId}}");
        assertThat(plain.scenarioNote()).doesNotContain("repeats a key");
    }

    private static VendorExchange submit(MockOnyxPasClient pas, String scenarioId) {
        return pas.submit(TestPreparedCalls.unconfigured(EpaOperation.PAS_SUBMIT), ResolvedText.plain(PasJson.request("claim-zz-1", "95810")),
                MockSelection.of(scenarioId, "S123456789", "1234567893"));
    }

    private static String scenario(String id, int number, String body) throws IOException {
        return "{\"id\":\"" + id + "\",\"number\":" + number + ",\"label\":\"Repeated key\",\"description\":\"A key sent twice.\","
                + "\"behavior\":\"respond\",\"httpStatus\":200,\"delayMillis\":0,\"basis\":\"Synthetic.\",\"responseText\":"
                + JsonSafe.mapper().writeValueAsString(body) + "}";
    }
}
