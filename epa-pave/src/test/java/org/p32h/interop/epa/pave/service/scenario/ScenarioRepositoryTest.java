package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRepositoryTest {

    private final ScenarioRepository bundled = new ScenarioRepository(AppProperties.defaults());

    private static final String MINIMAL = "{\"id\":\"x-1\",\"number\":1,\"label\":\"One\",\"httpStatus\":200,\"response\":{\"cards\":[]}}";

    @Test
    void theFourteenCrdScenariosLoadWithTheSentinelsTheSpecificationLists() {
        ScenarioRepository.Loaded loaded = bundled.load("crd");
        assertThat(loaded.problems()).isEmpty();
        Map<Integer, String> sentinels = new LinkedHashMap<>();
        loaded.scenarios().forEach(s -> sentinels.put(s.number(), s.sentinelMemberId()));
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(1, "P32H-PA-REQ");
        expected.put(2, "P32H-NO-AUTH");
        expected.put(3, "P32H-PA-SAT");
        expected.put(4, "P32H-DOC-DTR");
        expected.put(5, "P32H-INFO");
        expected.put(6, "P32H-NOCOV");
        expected.put(7, "P32H-NOMEM");
        expected.put(8, "P32H-INACTIVE");
        expected.put(9, "P32H-NOPROV");
        expected.put(10, "P32H-DELEG");
        expected.put(11, "P32H-BAD400");
        expected.put(12, "P32H-ERR500");
        expected.put(13, "P32H-TIMEOUT");
        expected.put(14, "P32H-EMPTY");
        assertThat(sentinels).containsExactlyEntriesOf(expected);
    }

    @Test
    void everyBundledCrdScenarioIsCompleteAndSaysItIsSynthetic() {
        for (Scenario scenario : bundled.list("crd")) {
            assertThat(scenario.id()).as("id").startsWith("crd-");
            assertThat(scenario.label()).as(scenario.id()).isNotBlank();
            assertThat(scenario.description()).as(scenario.id()).isNotBlank();
            assertThat(scenario.main()).as(scenario.id()).isNotNull();
            assertThat(scenario.expect()).as(scenario.id() + " claims an outcome").isNotNull();
            assertThat(scenario.basis()).as(scenario.id()).startsWith("Synthetic.");
        }
    }

    @Test
    void theSentinelNpiSelectsProviderNotFoundAndPassesTheApplicationsOwnCheck() {
        List<Scenario> withNpi = bundled.list("crd").stream().filter(s -> s.sentinelNpi() != null).toList();
        assertThat(withNpi).hasSize(1);
        assertThat(withNpi.get(0).sentinelNpi()).isEqualTo("9999999995");
        assertThat(withNpi.get(0).number()).isEqualTo(9);
        assertThat(Npi.isValid(withNpi.get(0).sentinelNpi())).isTrue();
    }

    @Test
    void theScenariosThatModelUndefinedVendorBehaviourSayWhatTheyAssume() {
        for (Scenario scenario : bundled.list("crd")) {
            if (List.of(7, 8, 9, 10).contains(scenario.number())) {
                assertThat(scenario.assumption()).as(scenario.id()).isNotBlank();
            }
        }
    }

    @Test
    void selectionPrefersAnExplicitChoiceThenASentinelMemberThenASentinelNpiThenTheFirstScenario() {
        assertThat(bundled.select("crd", MockSelection.of("crd-14-empty", "P32H-PA-REQ", "9999999995")).scenario().number()).isEqualTo(14);
        ScenarioSelection byMember = bundled.select("crd", MockSelection.of(null, "P32H-NO-AUTH", "9999999995"));
        assertThat(byMember.scenario().number()).isEqualTo(2);
        assertThat(byMember.how()).isEqualTo("selected by the sentinel member ID P32H-NO-AUTH");
        ScenarioSelection byNpi = bundled.select("crd", MockSelection.of(null, "S123456789", "9999999995"));
        assertThat(byNpi.scenario().number()).isEqualTo(9);
        assertThat(byNpi.how()).isEqualTo("selected by the sentinel NPI 9999999995");
        ScenarioSelection byDefault = bundled.select("crd", MockSelection.of(" ", "S123456789", "1234567893"));
        assertThat(byDefault.scenario().number()).isEqualTo(1);
        // A default must never look like a deliberate choice.
        assertThat(byDefault.how()).contains("default").contains("no sentinel matched");
        assertThat(bundled.select("crd", null).scenario().number()).isEqualTo(1);
    }

    @Test
    void aSentinelIsMatchedWhateverItsCaseOrSpacing() {
        assertThat(bundled.select("crd", MockSelection.of(null, "p32h-empty", null)).scenario().number()).isEqualTo(14);
        assertThat(bundled.select("crd", MockSelection.of(null, " P32H-EMPTY ", null)).scenario().number()).isEqualTo(14);
        // A sentinel is matched whole. An id that merely starts like one is an ordinary member.
        assertThat(bundled.select("crd", MockSelection.of(null, "P32H-EMPTY-2", null)).scenario().number()).isEqualTo(1);
    }

    @Test
    void anUnknownExplicitScenarioIsRefusedAndNotReplacedByTheDefault() {
        assertThatThrownBy(() -> bundled.select("crd", MockSelection.of("crd-99-nope", "P32H-PA-REQ", null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aStepRespondsByDefaultAndCarriesItsBody() {
        Scenario scenario = ScenarioRepository.parse("x.json", MINIMAL);
        assertThat(scenario.main().behavior()).isEqualTo(ScenarioBehavior.RESPOND);
        assertThat(scenario.main().httpStatus()).isEqualTo(200);
        assertThat(scenario.main().delayMillis()).isZero();
        assertThat(scenario.main().body()).contains("\"cards\"");
        assertThat(scenario.expect()).isNull();
        assertThat(scenario.summary().expectedOutcome()).isNull();
    }

    @Test
    void handEditedSpellingsAreForgivenAndRealMistakesAreNamed() {
        Scenario lenient = ScenarioRepository.parse("x.json",
                "{\"id\":\"x-1\",\"number\":\"7\",\"label\":\"L\",\"behavior\":\" Timeout \",\"delayMillis\":\"250\"}");
        assertThat(lenient.number()).isEqualTo(7);
        assertThat(lenient.main().behavior()).isEqualTo(ScenarioBehavior.TIMEOUT);
        assertThat(lenient.main().httpStatus()).isNull();
        assertThat(lenient.main().delayMillis()).isEqualTo(250);
        assertThat(lenient.main().body()).isNull();

        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"number\":1,\"label\":\"L\",\"httpStatus\":200}"))
                .hasMessageContaining("no id");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"number\":1,\"httpStatus\":200}"))
                .hasMessageContaining("no label");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":\"one\",\"httpStatus\":200}"))
                .hasMessageContaining("number");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"behavior\":\"hang\"}"))
                .hasMessageContaining("hang");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"behavior\":\"respond\"}"))
                .hasMessageContaining("no httpStatus");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":\"OK\",\"response\":{}}"))
                .hasMessageContaining("httpStatus");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":99,\"response\":{}}"))
                .hasMessageContaining("not an HTTP status");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1}"))
                .hasMessageContaining("describes no call");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,\"expect\":{\"codes\":{}}}"))
                .hasMessageContaining("no outcome");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "[1,2]")).hasMessageContaining("one JSON object");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\": ")).hasMessageContaining("not valid JSON");
    }

    @Test
    void aBodyThatIsDeliberatelyNotJsonIsGivenAsText() {
        Scenario scenario = ScenarioRepository.parse("x.json",
                "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,\"responseText\":\"{\\\"cards\\\": [\"}");
        assertThat(scenario.main().body()).isEqualTo("{\"cards\": [");
    }

    @Test
    void namedStepsAreReadAndFallBackToTheMainStep() {
        Scenario scenario = ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,\"response\":{\"a\":1},"
                + "\"steps\":{\"inquire\":{\"httpStatus\":202,\"response\":{\"b\":2}},\"notify\":{\"behavior\":\"unavailable\"}}}");
        assertThat(scenario.step("inquire").httpStatus()).isEqualTo(202);
        assertThat(scenario.step("notify").behavior()).isEqualTo(ScenarioBehavior.UNAVAILABLE);
        assertThat(scenario.step("unknown")).isSameAs(scenario.main());
        assertThat(scenario.step(null)).isSameAs(scenario.main());
    }

    @Test
    void anExternalDirectoryReplacesTheBundledScenariosAndIsReadFreshEachTime(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.writeString(dir.resolve("crd/one.json"), MINIMAL);
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        assertThat(external.list("crd")).extracting(Scenario::id).containsExactly("x-1");

        // QA adds a case without a restart.
        Files.writeString(dir.resolve("crd/two.json"), MINIMAL.replace("x-1", "x-2").replace("\"number\":1", "\"number\":2"));
        assertThat(external.list("crd")).extracting(Scenario::id).containsExactly("x-1", "x-2");
    }

    @Test
    void aFileThatCannotBeUsedIsReportedByNameAndTheOthersStillLoad(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.writeString(dir.resolve("crd/good.json"), MINIMAL);
        Files.writeString(dir.resolve("crd/broken.json"), "{\"id\": \"oops\"");
        Files.writeString(dir.resolve("crd/duplicate.json"), MINIMAL);
        Files.writeString(dir.resolve("crd/notes.txt"), "not a scenario");
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));

        ScenarioRepository.Loaded loaded = external.load("crd");

        assertThat(loaded.scenarios()).extracting(Scenario::id).containsExactly("x-1");
        assertThat(loaded.problems()).hasSize(2);
        assertThat(loaded.problems()).anySatisfy(p -> assertThat(p).startsWith("broken.json:"));
        assertThat(loaded.problems()).anySatisfy(p -> assertThat(p).contains("already used by"));
    }

    @Test
    void aMissingExternalDirectoryIsAProblemAndNotASilentlyEmptyList(@TempDir Path dir) {
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        ScenarioRepository.Loaded loaded = external.load("crd");
        assertThat(loaded.scenarios()).isEmpty();
        assertThat(loaded.problems()).singleElement().asString().contains("does not exist");
        assertThatThrownBy(() -> external.select("crd", null)).isInstanceOf(IllegalStateException.class);
    }
}
