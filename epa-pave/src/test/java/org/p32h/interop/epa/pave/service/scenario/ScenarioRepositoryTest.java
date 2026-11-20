package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.service.error.BadInputException;
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
    void theListedSummaryNamesEverySentinelOfAScenarioAndNotOnlyTheFirst() {
        Scenario several = ScenarioRepository.parse("x.json", "{\"id\":\"x-1\",\"number\":1,\"label\":\"L\",\"sentinelMemberId\":\"P32H-A\","
                + "\"sentinelMemberIds\":[\"P32H-B\"],\"sentinelNpis\":[\"1234567893\",\"9999999995\"],\"httpStatus\":200,\"response\":{}}");
        assertThat(several.summary().sentinelMemberIds()).isEqualTo(several.sentinelMemberIds()).contains("P32H-A", "P32H-B");
        assertThat(several.summary().sentinelMemberId()).isEqualTo("P32H-A");
        assertThat(several.summary().sentinelNpis()).containsExactly("1234567893", "9999999995");
        assertThat(several.summary().crdSentinelMemberIds()).isEmpty();
        // A DTR scenario lists the CRD sentinels it also answers for, so a journey started at CRD can be traced to it.
        Scenario noProvider = bundled.list("dtr").stream().filter(s -> "P32H-DTR-NOPROV".equals(s.sentinelMemberId())).findFirst()
                .orElseThrow();
        assertThat(noProvider.summary().crdSentinelMemberIds()).containsExactly("P32H-NOPROV");
        assertThat(noProvider.summary().sentinelNpis()).containsExactly("9999999995");
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
    void namedStepsAreReadAndAnUndefinedNameIsNotAnsweredByTheMainStep() {
        Scenario scenario = ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,\"response\":{\"a\":1},"
                + "\"steps\":{\"inquire\":{\"httpStatus\":202,\"response\":{\"b\":2}},\"notify\":{\"behavior\":\"unavailable\"}}}");
        assertThat(scenario.step("inquire").httpStatus()).isEqualTo(202);
        assertThat(scenario.step("notify").behavior()).isEqualTo(ScenarioBehavior.UNAVAILABLE);
        // An inquiry against a scenario that defines no inquiry must not get the submission's answer.
        assertThat(scenario.step("unknown")).isNull();
        assertThat(scenario.step(null)).isSameAs(scenario.main());
    }

    @Test
    void aStepCanClaimItsOwnOutcomeApartFromTheScenarioAsAWhole() {
        // A scenario whose calls each have their own answer: the submission pends and the inquiry approves.
        Scenario scenario = ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,\"response\":{},"
                + "\"expect\":{\"outcome\":\"PENDED\"},"
                + "\"steps\":{\"inquire\":{\"httpStatus\":200,\"response\":{},\"expect\":{\"outcome\":\"APPROVED\",\"httpStatus\":200}},"
                + "\"plain\":{\"httpStatus\":200,\"response\":{}}}}");
        assertThat(scenario.expect().outcome()).isEqualTo("PENDED");
        assertThat(scenario.main().expect()).isNull();
        assertThat(scenario.step("inquire").expect().outcome()).isEqualTo("APPROVED");
        assertThat(scenario.step("inquire").expect().httpStatus()).isEqualTo(200);
        assertThat(scenario.step("plain").expect()).isNull();
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", "{\"id\":\"a\",\"label\":\"L\",\"number\":1,\"httpStatus\":200,"
                + "\"steps\":{\"inquire\":{\"httpStatus\":200,\"expect\":{\"codes\":{}}}}}")).hasMessageContaining("no outcome");
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
        // A run is refused with the reason, as a 422 the tester can act on, and not as an internal error.
        assertThatThrownBy(() -> external.select("crd", null)).isInstanceOf(BadInputException.class)
                .hasMessage("The mock scenarios for crd cannot be used, so nothing was answered. The scenario directory "
                        + dir.resolve("crd") + " does not exist.");
    }

    // ---- V4-02, V4-08: what a damaged set and a pasted body do ----

    private static String scenario(String id, int number, String sentinelKeys) {
        return "{\"id\":\"" + id + "\",\"number\":" + number + ",\"label\":\"L\"," + sentinelKeys
                + "\"httpStatus\":200,\"response\":{}}";
    }

    @Test
    void aSentinelMemberIdClaimedByTwoFilesIsAProblemNamingBothFiles(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.writeString(dir.resolve("crd/a.json"), scenario("a", 1, "\"sentinelMemberId\":\"P32H-TWICE\","));
        // The same sentinel in another letter case and with a space is the same sentinel to the selector.
        Files.writeString(dir.resolve("crd/b.json"), scenario("b", 2, "\"sentinelMemberId\":\"p32h-twice \","));
        ScenarioRepository.Loaded loaded = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString()))).load("crd");
        assertThat(loaded.problems()).containsExactly(
                "b.json: the sentinel member ID P32H-TWICE is already claimed by a.json. One sentinel selects one scenario.");
    }

    @Test
    void aSentinelNpiClaimedByTwoFilesIsAProblemNamingBothFiles(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.writeString(dir.resolve("crd/a.json"), scenario("a", 1, "\"sentinelNpi\":\"9999999995\","));
        Files.writeString(dir.resolve("crd/b.json"), scenario("b", 2, "\"sentinelNpi\":\" 9999999995\","));
        ScenarioRepository.Loaded loaded = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString()))).load("crd");
        assertThat(loaded.problems()).containsExactly(
                "b.json: the sentinel NPI 9999999995 is already claimed by a.json. One sentinel selects one scenario.");
    }

    @Test
    void aMisspeltTopLevelKeyIsRefusedByNameAndNotDropped() {
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberID\":\"P32H-X\",")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("it has a key this tool does not know: sentinelMemberID. Check its spelling.");
    }

    @Test
    void aRunAgainstADamagedExternalSetIsRefusedAndNotAnsweredFromWhatIsLeft(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.writeString(dir.resolve("crd/good.json"), MINIMAL);
        Files.writeString(dir.resolve("crd/broken.json"), "{\"id\": \"oops\"");
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        // Its own type, so a suite can tell the tool's files from a row that is not valid; a single run still gets HTTP 422.
        assertThatThrownBy(() -> external.select("crd", MockSelection.of(null, "P32H-PA-REQ", null)))
                .isInstanceOf(ScenarioSetUnusableException.class).isInstanceOf(BadInputException.class)
                .hasMessageStartingWith("The mock scenarios for crd cannot be used, so nothing was answered. broken.json: ")
                .satisfies(e -> {
                    ScenarioSetUnusableException unusable = (ScenarioSetUnusableException) e;
                    assertThat(unusable.protocolDirectory()).isEqualTo("crd");
                    assertThat(unusable.problems()).singleElement().asString().startsWith("broken.json: ");
                    assertThat(unusable.action()).isEqualTo("Put the files named right, then run again. The scenario list for crd shows the "
                            + "same problems.");
                });
        assertThatThrownBy(() -> external.list("crd")).isInstanceOf(ScenarioSetUnusableException.class);
        // The listing still shows what is wrong, so the tester can see which file to fix.
        assertThat(external.load("crd").problems()).singleElement().asString().startsWith("broken.json: ");
    }

    @Test
    void aSentinelInAPastedBodyIsComparedAsTheFormComparesIt() {
        String body = "{\"context\":{\"patientId\":\"%s\",\"note\":\"P32H-NOCOV is mentioned in passing\"}}";
        assertThat(bundled.select("crd", null, body.formatted("P32H-PA-SAT")).scenario().number()).isEqualTo(3);
        assertThat(bundled.select("crd", null, body.formatted("p32h-pa-sat")).scenario().number()).isEqualTo(3);
        assertThat(bundled.select("crd", null, body.formatted(" P32H-PA-SAT ")).how())
                .isEqualTo("selected by the sentinel member ID P32H-PA-SAT found in the request body");
        // A sentinel is a whole value, never a part of one.
        assertThat(bundled.select("crd", null, body.formatted("P32H-PA-SAT-2")).scenario().number()).isEqualTo(1);
        // A body that is not JSON holds no values to compare.
        assertThat(bundled.select("crd", null, "ISA*P32H-PA-SAT*\"P32H-PA-SAT\"").scenario().number()).isEqualTo(1);
    }

    // ---- the second fixture audit: one rule for every protocol, and every sentinel a file claims is checked ----

    @Test
    void aCrdSentinelClaimedByTwoDtrFilesIsAProblemNamingBothFiles(@TempDir Path dir) throws IOException {
        // F-DUP-CRDSENT: dtr-10 given P32H-NOMEM beside dtr-09 used to start, and dtr-10 never answered it.
        Files.createDirectories(dir.resolve("dtr"));
        Files.writeString(dir.resolve("dtr/a.json"), scenario("a", 1, "\"sentinelMemberId\":\"P32H-DTR-A\",\"crdSentinelMemberIds\":[\"P32H-NOMEM\"],"));
        Files.writeString(dir.resolve("dtr/b.json"), scenario("b", 2,
                "\"sentinelMemberId\":\"P32H-DTR-B\",\"crdSentinelMemberIds\":[\"P32H-NOPROV\",\"p32h-nomem\"],"));
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        assertThat(external.load("dtr").problems()).containsExactly("b.json: the CRD sentinel member ID P32H-NOMEM in its "
                + "crdSentinelMemberIds is already claimed by a.json. One sentinel selects one scenario.");
        // Within one list, and against the file's own sentinel.
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("x", 1, "\"crdSentinelMemberIds\":[\"P32H-NOMEM\",\" p32h-nomem\"],")))
                .hasMessage("it names one CRD sentinel member ID twice.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("x", 1,
                "\"sentinelMemberId\":\"P32H-NOMEM\",\"crdSentinelMemberIds\":[\"P32H-NOMEM\"],")))
                .hasMessage("it names one sentinel member ID both as its own and among its crdSentinelMemberIds.");
        // A CRD sentinel is held to the same rule as any sentinel: only this tool's own identifiers.
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("x", 1, "\"crdSentinelMemberIds\":[\"S123456789\"],")))
                .hasMessageStartingWith("one of its sentinel member IDs is not a P32H- sentinel.").hasMessageNotContaining("S123456789");
    }

    @Test
    void aPasFileThatClaimsACrdSentinelAnotherPasFileAnswersIsAProblemNamingBothFiles(@TempDir Path dir) throws IOException {
        // F-PAS-SHADOW: pas-02 given P32H-NOMEM used to start, and pas-15 took the sentinel through a map in Java.
        Files.createDirectories(dir.resolve("pas"));
        Files.writeString(dir.resolve("pas/pas-02.json"), scenario("pas-02", 2, "\"sentinelMemberId\":\"P32H-NOMEM\","));
        Files.writeString(dir.resolve("pas/pas-15.json"), scenario("pas-15", 15,
                "\"sentinelMemberId\":\"P32H-PAS-NOMEM\",\"crdSentinelMemberIds\":[\"P32H-NOMEM\"],"));
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        assertThat(external.load("pas").problems()).containsExactly("pas-15.json: the CRD sentinel member ID P32H-NOMEM in its "
                + "crdSentinelMemberIds is already claimed by pas-02.json. One sentinel selects one scenario.");
    }

    @Test
    void thePasFilesSayWhichCrdSentinelsSelectThemAndTheListShowsIt() {
        // F-PAS-CRDMAP-HIDDEN: the mapping is scenario data, read by the list as the mock reads it.
        Map<String, String> sameCase = Map.of("P32H-NOMEM", "pas-15-member-not-found", "P32H-NOPROV", "pas-21-provider-not-found",
                "P32H-ERR500", "pas-17-server-error-500", "P32H-TIMEOUT", "pas-16-timeout", "P32H-BAD400", "pas-09-invalid-bundle");
        Map<String, String> listed = new LinkedHashMap<>();
        for (Scenario scenario : bundled.list("pas")) {
            scenario.summary().crdSentinelMemberIds().forEach(crd -> listed.put(crd, scenario.id()));
            for (String crd : scenario.crdSentinelMemberIds()) {
                assertThat(scenario.description()).as(scenario.id()).contains(crd);
            }
        }
        assertThat(listed).isEqualTo(sameCase);
    }

    @Test
    void aForeignSentinelInAPastedBodySelectsAsTheSameIdTypedOnTheFormDoes() {
        // F-RAW: one rule, applied to a typed member ID and to one found in a pasted body alike.
        String body = "{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"value\":\"%s\"}]}}]}";
        for (String protocol : List.of("crd", "dtr", "pas")) {
            for (String member : List.of("P32H-NOMEM", "P32H-TIMEOUT", "P32H-DTR-ADAPT", "P32H-PAS-DENIED", "P32H-DOC-DTR", "S123456789")) {
                ScenarioSelection typed = bundled.select(protocol, MockSelection.of(null, member, null));
                ScenarioSelection pasted = bundled.select(protocol, null, body.formatted(member.toLowerCase(java.util.Locale.ROOT)));
                assertThat(pasted.scenario().id()).as(protocol + " " + member).isEqualTo(typed.scenario().id());
                assertThat(pasted.by()).as(protocol + " " + member).isEqualTo(typed.by());
                if (typed.by() != ScenarioSelection.By.DEFAULT) {
                    assertThat(pasted.inBody()).isTrue();
                    assertThat(pasted.how()).as(protocol + " " + member).contains("found in the request body").doesNotContain("default");
                }
            }
        }
        ScenarioSelection pas = bundled.select("pas", null, body.formatted("P32H-NOMEM"));
        assertThat(pas.scenario().id()).isEqualTo("pas-15-member-not-found");
        assertThat(pas.how()).isEqualTo("selected by the CRD sentinel member ID P32H-NOMEM found in the request body, which this scenario "
                + "answers for the same case");
        ScenarioSelection crd = bundled.select("crd", null, body.formatted("P32H-DTR-ADAPT"));
        assertThat(crd.scenario().id()).isEqualTo("crd-04-doc-dtr");
        assertThat(crd.via().id()).isEqualTo("dtr-02-adaptive-branching");
        assertThat(crd.how()).isEqualTo("selected because P32H-DTR-ADAPT, found in the request body, is the sentinel member ID of DTR "
                + "scenario dtr-02-adaptive-branching, and CRD answers documentation required for every DTR sentinel so that the journey "
                + "reaches DTR");
        // A CRD sentinel keeps its own CRD scenario, in a body as on the form.
        assertThat(bundled.select("crd", null, body.formatted("P32H-NOMEM")).scenario().id()).isEqualTo("crd-07-member-not-found");
    }

    @Test
    void onlyOneCrdFileAnswersDtrSentinelsAndOnlyACrdFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("crd"));
        Files.createDirectories(dir.resolve("pas"));
        Files.writeString(dir.resolve("crd/a.json"), scenario("a", 1, "\"answersDtrSentinels\":true,"));
        Files.writeString(dir.resolve("crd/b.json"), scenario("b", 2, "\"answersDtrSentinels\":true,\"crdSentinelMemberIds\":[\"P32H-X\"],"));
        Files.writeString(dir.resolve("pas/c.json"), scenario("c", 1, "\"answersDtrSentinels\":true,"));
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        assertThat(external.load("crd").problems()).containsExactly(
                "b.json: a CRD scenario cannot list crdSentinelMemberIds. Give a CRD sentinel as the file's own sentinelMemberId.",
                "b.json: answersDtrSentinels is already true in a.json. One CRD scenario answers every DTR sentinel.");
        assertThat(external.load("pas").problems()).containsExactly(
                "c.json: only a CRD scenario can answer DTR sentinels, so answersDtrSentinels belongs in a CRD file.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("x", 1, "\"answersDtrSentinels\":\"yes\",")))
                .hasMessage("its answersDtrSentinels is not true or false.");
    }

    // ---- the check at start-up ----

    /** A scenario directory with one good file per protocol, which the start-up check accepts. */
    private static void writeGoodSet(Path dir) throws IOException {
        int number = 1;
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            Files.createDirectories(dir.resolve(protocol));
            Files.writeString(dir.resolve(protocol + "/" + protocol + "-01.json"),
                    scenario(protocol + "-01", number++, "\"sentinelMemberId\":\"P32H-" + protocol.toUpperCase(java.util.Locale.ROOT) + "\","));
        }
    }

    @Test
    void theBundledScenariosPassTheCheckAtStartUp() {
        bundled.checkEveryProtocol();
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            assertThat(bundled.load(protocol).problems()).as(protocol).isEmpty();
        }
    }

    @Test
    void theProtocolsCheckedAreTheDirectoriesTheMockClientsRead() {
        assertThat(ScenarioRepository.PROTOCOLS).containsExactly(org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient.SCENARIOS,
                org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient.SCENARIOS, org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient.SCENARIOS,
                org.p32h.interop.epa.pave.client.raw.MockRawRequestClient.SCENARIOS);
    }

    @Test
    void aGoodExternalSetPassesTheCheckAtStartUp(@TempDir Path dir) throws IOException {
        writeGoodSet(dir);
        new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString()))).checkEveryProtocol();
    }

    @Test
    void aDamagedSetStopsTheStartNamingEachFileAndItsReason(@TempDir Path dir) throws IOException {
        writeGoodSet(dir);
        Files.writeString(dir.resolve("crd/crd-02-truncated.json"), "{\"id\": \"crd-02\", \"number\"");
        Files.writeString(dir.resolve("crd/crd-03-duplicate-id.json"), scenario("crd-01", 3, ""));
        Files.writeString(dir.resolve("crd/crd-04-shadow.json"), scenario("crd-04", 0, "\"sentinelMemberId\":\"P32H-CRD\","));
        Files.writeString(dir.resolve("crd/crd-05-misspelt.json"), scenario("crd-05", 5, "\"sentinelMemberID\":\"P32H-INFO\","));
        Files.delete(dir.resolve("dtr/dtr-01.json"));
        Files.delete(dir.resolve("raw/raw-01.json"));
        Files.delete(dir.resolve("raw"));
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));

        assertThatThrownBy(external::checkEveryProtocol).isInstanceOf(IllegalStateException.class).hasMessage(
                "The mock scenarios in " + dir + " (app.scenario-dir) cannot be used, so the tool does not start."
                        + " Mock scenarios for crd: crd-02-truncated.json: The file is not valid JSON (line 1, column 26)."
                        + " crd-05-misspelt.json: it has a key this tool does not know: sentinelMemberID. Check its spelling."
                        + " crd-03-duplicate-id.json: the id \"crd-01\" is already used by crd-01.json."
                        + " crd-01.json: the sentinel member ID P32H-CRD is already claimed by crd-04-shadow.json. One sentinel selects one scenario."
                        + " Mock scenarios for dtr: The scenario directory " + dir.resolve("dtr") + " holds no scenario files."
                        + " Mock scenarios for raw: The scenario directory " + dir.resolve("raw") + " does not exist.");
    }

    @Test
    void aSetDamagedWhileTheToolRunsIsRefusedOnTheNextRunAndUsedAgainOnceItIsPutRight(@TempDir Path dir) throws IOException {
        writeGoodSet(dir);
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        external.checkEveryProtocol();
        Files.writeString(dir.resolve("crd/crd-02.json"), scenario("crd-02", 2, "\"sentinelMemberId\":\"P32H-CRD\","));
        assertThatThrownBy(() -> external.select("crd", MockSelection.of(null, "P32H-CRD", null))).isInstanceOf(BadInputException.class)
                .hasMessageContaining("crd-02.json: the sentinel member ID P32H-CRD is already claimed by crd-01.json.");
        // Another protocol is not held back by it.
        assertThat(external.select("dtr", MockSelection.of(null, "P32H-DTR", null)).scenario().id()).isEqualTo("dtr-01");
        Files.delete(dir.resolve("crd/crd-02.json"));
        assertThat(external.select("crd", MockSelection.of(null, "P32H-CRD", null)).scenario().id()).isEqualTo("crd-01");
    }

    // ---- several sentinels in one file ----

    @Test
    void aFileCanNameSeveralSentinelsAndTheSingleKeysStillWork() {
        Scenario scenario = ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":\"P32H-PAS-ONE\","
                + "\"sentinelMemberIds\":[\"P32H-PA-REQ\",\" P32H-DELEG \"],\"sentinelNpis\":[\"9999999995\",\"9999999987\"],"));
        assertThat(scenario.sentinelMemberIds()).containsExactly("P32H-PAS-ONE", "P32H-PA-REQ", "P32H-DELEG");
        assertThat(scenario.sentinelMemberId()).isEqualTo("P32H-PAS-ONE");
        assertThat(scenario.sentinelNpis()).containsExactly("9999999995", "9999999987");
        assertThat(scenario.sentinelNpi()).isEqualTo("9999999995");

        Scenario listOnly = ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberIds\":[\"P32H-B\",\"P32H-C\"],"));
        assertThat(listOnly.sentinelMemberId()).isEqualTo("P32H-B");
        assertThat(listOnly.sentinelNpis()).isEmpty();
        assertThat(listOnly.sentinelNpi()).isNull();

        Scenario single = ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":\"P32H-B\",\"sentinelNpi\":\"9999999995\","));
        assertThat(single.sentinelMemberIds()).containsExactly("P32H-B");
        assertThat(single.sentinelNpis()).containsExactly("9999999995");
        // A file written before the lists existed has none, and selects by nothing.
        assertThat(ScenarioRepository.parse("x.json", MINIMAL).sentinelMemberIds()).isEmpty();
    }

    @Test
    void everySentinelOfAFileSelectsItAndTheResultNamesTheOneThatMatched(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("pas"));
        Files.writeString(dir.resolve("pas/a.json"), scenario("a", 1, "\"sentinelMemberId\":\"P32H-PAS-A\","));
        Files.writeString(dir.resolve("pas/b.json"), scenario("b", 2, "\"sentinelMemberIds\":[\"P32H-PAS-B\",\"P32H-PA-REQ\"],"
                + "\"sentinelNpis\":[\"9999999995\"],"));
        ScenarioRepository external = new ScenarioRepository(AppProperties.of(Map.of("scenario-dir", dir.toString())));
        ScenarioSelection byList = external.select("pas", MockSelection.of(null, "p32h-pa-req", null));
        assertThat(byList.scenario().id()).isEqualTo("b");
        assertThat(byList.how()).isEqualTo("selected by the sentinel member ID P32H-PA-REQ");
        assertThat(external.select("pas", MockSelection.of(null, "S123456789", "9999999995")).how())
                .isEqualTo("selected by the sentinel NPI 9999999995");
        assertThat(external.select("pas", null, "{\"identifier\":[{\"value\":\"P32H-PA-REQ\"}]}").scenario().id()).isEqualTo("b");
        // A sentinel in a list is claimed as surely as a single one.
        Files.writeString(dir.resolve("pas/c.json"), scenario("c", 3, "\"sentinelMemberId\":\"P32H-PA-REQ\","));
        assertThat(external.load("pas").problems()).containsExactly(
                "c.json: the sentinel member ID P32H-PA-REQ is already claimed by b.json. One sentinel selects one scenario.");
    }

    @Test
    void aSentinelThatIsNotThisToolsOwnIsRefusedWithoutQuotingIt() {
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":\"W123456789\",")))
                .hasMessageStartingWith("one of its sentinel member IDs is not a P32H- sentinel.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("W123456789"));
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberIds\":[\"P32H-A\",\"W123456789\"],")))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("W123456789"));
        // Said in terms a tester editing the file can act on, not as a call into this tool's code.
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelNpi\":\"9999999999\",")))
                .hasMessage("one of its sentinel NPIs does not pass the NPI check, so no request could ever carry it. Use ten digits whose "
                        + "last digit is the NPI check digit of the first nine, such as 9999999995.");
        assertThat(Npi.isValid("9999999995")).isTrue();
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberIds\":\"P32H-A\",")))
                .hasMessage("its sentinelMemberIds is not a list.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelNpis\":[9999999995],")))
                .hasMessage("its sentinelNpis holds something that is not a sentinel NPI.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":7,")))
                .hasMessage("its sentinelMemberId is not text.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":\"P32H-A\",\"sentinelMemberIds\":[\"p32h-a\"],")))
                .hasMessage("it names one sentinel member ID twice.");
    }

    @Test
    void aRepeatedKeyAndSeveralUnknownKeysAreRefused() {
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"sentinelMemberId\":\"P32H-A\",\"sentinelMemberId\":\"P32H-B\",")))
                .hasMessage("it repeats a key, and only the last value of a repeated key would count. Keep one.");
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"respnse\":{},\"sentinel\":\"P32H-A\",")))
                .hasMessage("it has keys this tool does not know: respnse, sentinel. Check their spelling.");
        // The sections a protocol reads for itself are known keys.
        ScenarioRepository.parse("x.json", scenario("a", 1, "\"nextQuestion\":{},\"paths\":[],\"responses\":[],\"steps\":{},"));
    }

    @Test
    void anUnknownKeyIsQuotedOnlyWhenItLooksLikeAKeyName() {
        // An ID pasted where a key belongs is data. The refusal is logged at start-up and shown on screen, so it is counted, not quoted.
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"S123456789\":\"x\",")))
                .hasMessage("it has a key that is not a scenario key and does not look like one, so it is not quoted here. "
                        + "Check the file's top-level keys.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("S123456789"));
        assertThatThrownBy(() -> ScenarioRepository.parse("x.json", scenario("a", 1, "\"respnse\":{},\"P32H-X 01\":1,\"S1234567801\":2,")))
                .hasMessage("it has a key this tool does not know: respnse, and 2 keys that are not a scenario key and do not look like one, "
                        + "so they are not quoted here. Check the file's top-level keys.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("S1234567801").doesNotContain("P32H-X"));
        // Every key the loader knows has the shape that is quoted, so a misspelling of one is always named.
        assertThat(ScenarioRepository.KNOWN_KEYS).allSatisfy(key -> assertThat(ScenarioRepository.unknownKeys(List.of(key))).contains(key));
    }
}
