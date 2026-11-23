package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The values a vendor's sample requests state are taken from them (D-308). On the first real run
 * the coverage check was refused for a missing fhirServer that the vendor's own coverage-check
 * sample had stated all along, and the operator had to find it in Postman and type it. Each file
 * is written for the test with placeholder values; no value of a secret appears.
 */
class SampleValuesTest {

    private static final String V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    private static final String V20 = "https://schema.getpostman.com/json/collection/v2.0.0/collection.json";
    private static final String SANDBOX = "https://ehr-sandbox.example.org/fhir";
    private static final String TOKEN = "placeholder-ehr-token";
    private static final String HOOK = "Coverage / CRD - Prior Auth Check";

    @TempDir
    Path directory;

    // ---- the draft ----

    @Test
    void theCoverageCheckSampleGivesFhirServerAndSaysWhatElseItSendsByNameOnly() throws Exception {
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, hook("\"" + SANDBOX + "\"", true)));

        JsonNode fhirServer = value(values, "fhirServer");
        assertThat(fhirServer.get("display").asString()).isEqualTo(SANDBOX);
        assertThat(fhirServer.get("from").asString()).isEqualTo("from the collection's request \"" + HOOK + "\"");
        assertThat(fhirServer.get("request").asString()).isEqualTo(HOOK);
        assertThat(strings(fhirServer.get("notes"))).containsExactly("The sample also sends fhirAuthorization. PAVE does not send it: if "
                + "the service refuses the check, that is the first thing to look at.");
        // Only the values a sample states in PAVE's places: nothing else is given.
        assertThat(value(values, "payerName").has("display")).isFalse();
        assertThat(values.toString()).doesNotContain(TOKEN).doesNotContain("access_token");
    }

    @Test
    void aVariableOfTheNameWinsOverTheSampleAndTheLineThenNamesTheSample() throws Exception {
        String environment = JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true),
                Map.of("key", "fhirServer", "value", "https://ehr.example.invalid/fhir", "enabled", true))));
        JsonNode fhirServer = value(draft(ConfigFixture.mock(directory), environment, collection(V21, hook("\"" + SANDBOX + "\"", true))),
                "fhirServer");

        assertThat(fhirServer.get("display").asString()).isEqualTo("https://ehr.example.invalid/fhir");
        assertThat(fhirServer.get("from").asString()).isEqualTo("from the export's variable fhirServer");
        assertThat(fhirServer.has("request")).isFalse();
        assertThat(strings(fhirServer.get("notes"))).containsExactly("The collection's request \"" + HOOK + "\" also sends "
                + "fhirAuthorization. PAVE does not send it: if the service refuses the check, that is the first thing to look at.");
    }

    @Test
    void aPlaceholderInTheSampleIsResolvedAsAnAddressIsAndOneNothingDefinesStaysWithItsLine() throws Exception {
        String environment = JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true),
                Map.of("key", "ehrBase", "value", "https://ehr-base.example.org", "enabled", true))));
        JsonNode defined = value(draft(ConfigFixture.mock(directory), environment, collection(V21, hook("\"{{ehrBase}}/fhir\"", false))),
                "fhirServer");
        assertThat(defined.get("display").asString()).isEqualTo("https://ehr-base.example.org/fhir");
        assertThat(strings(defined.get("notes"))).isEmpty();

        JsonNode undefined = value(draft(ConfigFixture.mock(directory), environment(), collection(V21, hook("\"{{ehrHost}}/fhir\"", false))),
                "fhirServer");
        assertThat(undefined.get("display").asString()).isEqualTo("{{ehrHost}}/fhir");
        assertThat(strings(undefined.get("notes"))).containsExactly("{{ehrHost}} is not defined in these files, so it stays as written: "
                + "a run asks for its value before it sends.");
    }

    @Test
    void aSampleThatNamesTheValueItselfOrASecretGivesNothing() throws Exception {
        JsonNode itself = value(draft(ConfigFixture.mock(directory), environment(), collection(V21, hook("\"{{fhirServer}}\"", false))),
                "fhirServer");
        assertThat(itself.has("display")).isFalse();
        assertThat(strings(itself.get("notes"))).containsExactly("The collection's request \"" + HOOK + "\" writes it as {{fhirServer}}, "
                + "which these files do not define.");

        String environment = JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true),
                Map.of("key", "ehrSecret", "value", TOKEN, "type", "secret", "enabled", true))));
        JsonNode secret = value(draft(ConfigFixture.mock(directory), environment, collection(V21, hook("\"https://{{ehrSecret}}.example.org\"",
                false))), "fhirServer");
        assertThat(secret.has("display")).isFalse();
        assertThat(strings(secret.get("notes"))).containsExactly("The collection's request \"" + HOOK + "\" states it through a secret, so "
                + "it is not taken.");
        assertThat(secret.toString()).doesNotContain(TOKEN);
    }

    @Test
    void aCollectionWithNoCoverageCheckSampleGivesNoValueAndAnOlderFormatOneDoes() throws Exception {
        Map<String, Object> ping = Map.of("name", "Ping", "request", Map.of("method", "GET", "url", Map.of("raw", "{{base}}/ping"),
                "header", List.of()));
        JsonNode none = value(draft(ConfigFixture.mock(directory), environment(), collection(V21, ping)), "fhirServer");
        assertThat(none.has("display")).isFalse();
        assertThat(strings(none.get("notes"))).isEmpty();

        JsonNode older = value(draft(ConfigFixture.mock(directory), environment(), collection(V20, hook("\"" + SANDBOX + "\"", true))),
                "fhirServer");
        assertThat(older.get("display").asString()).isEqualTo(SANDBOX);
    }

    @Test
    void withNoRequestMappedToOrderSignTheOnlyHookBodyIsTheSample() throws Exception {
        // A hook PAVE does not send is mapped to no operation, but its body is still the only one that can state fhirServer.
        Map<String, Object> other = Map.of("name", HOOK, "request", Map.of("method", "POST",
                "url", Map.of("raw", "{{base}}/cds-services/epa-crd-service"), "header", List.of(),
                "body", Map.of("mode", "raw", "raw", "{\"hook\": \"appointment-book\", \"fhirServer\": \"" + SANDBOX + "\"}")));
        assertThat(value(draft(ConfigFixture.mock(directory), environment(), collection(V21, other)), "fhirServer").get("display")
                .asString()).isEqualTo(SANDBOX);
    }

    // ---- the save ----

    @Test
    void aNewEnvironmentSavedWithTheBoxTickedHasTheSamplesValueInForceAndEditSaysWhere() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        JsonNode saved = save(mvc, null, environment(), collection(V21, hook("\"" + SANDBOX + "\"", true)), true, null);

        assertThat(strings(saved.get("warnings"))).contains("FHIR server set from the collection's request \"" + HOOK + "\".");
        JsonNode fhirServer = settingsValue(mvc, saved.at("/settings/id").asString(), "fhirServer");
        assertThat(fhirServer.has("typed")).isFalse();
        assertThat(fhirServer.at("/inForce/source").asString()).isEqualTo("SAMPLE");
        assertThat(fhirServer.at("/inForce/sourceLabel").asString()).isEqualTo("from the collection's request \"" + HOOK + "\"");
        assertThat(fhirServer.at("/inForce/display").asString()).isEqualTo(SANDBOX);

        // Kept in the store with where it came from: read again by a service that starts on the same folder.
        ConfigFixture again = ConfigFixture.mock(directory);
        assertThat(again.service.settings(saved.at("/settings/id").asString()).values().get(0).inForce().sourceLabel())
                .isEqualTo("from the collection's request \"" + HOOK + "\"");
        assertThat(Files.readString(directory.resolve("store.json"))).contains("\"valueOrigins\"").doesNotContain(TOKEN);
    }

    @Test
    void onEditAStoredEnvironmentGainsTheValueAndATypedValueThenTakesItsPlace() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String id = save(mvc, null, environment(), null, false, null).at("/settings/id").asString();
        assertThat(settingsValue(mvc, id, "fhirServer").at("/inForce/source").asString()).isEqualTo("NOT_SET");

        JsonNode edited = save(mvc, id, null, collection(V21, hook("\"" + SANDBOX + "\"", true)), true, Map.of("fhirServer", ""));
        assertThat(strings(edited.get("warnings"))).contains("FHIR server set from the collection's request \"" + HOOK + "\".");
        assertThat(settingsValue(mvc, id, "fhirServer").at("/inForce/source").asString()).isEqualTo("SAMPLE");

        save(mvc, id, null, null, false, Map.of("fhirServer", "https://typed.example.org/fhir"));
        JsonNode typed = settingsValue(mvc, id, "fhirServer");
        assertThat(typed.at("/inForce/source").asString()).isEqualTo("THIS_ENVIRONMENT");
        assertThat(typed.get("typed").asString()).isEqualTo("https://typed.example.org/fhir");
    }

    @Test
    void aTypedValueAndTheExportsValueAreNeverReplacedAndAnUntickedBoxStoresNothing() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String collection = collection(V21, hook("\"" + SANDBOX + "\"", true));

        JsonNode typed = save(mvc, null, environment("Typed UAT"), collection, true, Map.of("fhirServer", "https://typed.example.org/fhir"));
        assertThat(strings(typed.get("warnings"))).noneMatch(line -> line.startsWith("FHIR server set from"));
        assertThat(settingsValue(mvc, typed.at("/settings/id").asString(), "fhirServer").get("typed").asString())
                .isEqualTo("https://typed.example.org/fhir");

        String exported = JsonSafe.mapper().writeValueAsString(Map.of("name", "Exported UAT", "values", List.of(
                Map.of("key", "fhirServer", "value", "https://ehr.example.invalid/fhir", "enabled", true))));
        JsonNode export = save(mvc, null, exported, collection, true, null);
        JsonNode fromExport = settingsValue(mvc, export.at("/settings/id").asString(), "fhirServer");
        assertThat(fromExport.at("/inForce/source").asString()).isEqualTo("EXPORT");
        assertThat(fromExport.at("/inForce/display").asString()).isEqualTo("https://ehr.example.invalid/fhir");

        JsonNode unticked = save(mvc, null, environment("Unticked UAT"), collection, false, null);
        assertThat(strings(unticked.get("warnings"))).noneMatch(line -> line.startsWith("FHIR server set from"));
        assertThat(settingsValue(mvc, unticked.at("/settings/id").asString(), "fhirServer").at("/inForce/source").asString())
                .isEqualTo("NOT_SET");
    }

    // ------------------------------------------------------------------------------------------

    private JsonNode draft(ConfigFixture fixture, String environment, String collection) throws Exception {
        MockMvc mvc = TestMvc.standalone(new DraftController(new EnvironmentDrafts(fixture.service)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(EnvironmentDrafts.ENVIRONMENT_SLOT, Map.of("fileName", "uat.postman_environment.json", "text", environment));
        body.put(EnvironmentDrafts.COLLECTION_SLOT, Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        return json(mvc.perform(post("/api/config/environments/draft").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(body))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("values");
    }

    private static JsonNode save(MockMvc mvc, String id, String environment, String collection, boolean samples,
                                 Map<String, String> values) throws Exception {
        Map<String, Object> save = new LinkedHashMap<>();
        if (id == null) {
            save.put("kind", "sandbox");
        } else {
            save.put("id", id);
        }
        if (environment != null) {
            save.put("environmentExport", Map.of("fileName", "uat.postman_environment.json", "text", environment));
        }
        if (collection != null) {
            save.put("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text", collection));
            save.put("sampleValues", samples);
        }
        if (values != null) {
            save.put("values", values);
        }
        String answer = mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(save))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(answer).doesNotContain(TOKEN);
        return json(answer);
    }

    private static JsonNode settingsValue(MockMvc mvc, String id, String name) throws Exception {
        JsonNode settings = json(mvc.perform(get("/api/config/environments/" + id + "/settings")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        return value(settings.get("values"), name);
    }

    private static JsonNode value(JsonNode values, String name) {
        List<JsonNode> rows = new ArrayList<>();
        values.forEach(rows::add);
        return rows.stream().filter(each -> each.get("name").asString().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    private static String environment() {
        return environment("Placeholder UAT");
    }

    /** An export that defines none of the ten values under PAVE's names, as the vendor's did. */
    private static String environment(String name) {
        return JsonSafe.mapper().writeValueAsString(Map.of("name", name, "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true))));
    }

    /** The coverage-check sample, its fhirServer written as given, with or without the fhirAuthorization PAVE never builds. */
    private static Map<String, Object> hook(String fhirServer, boolean authorization) {
        String body = "{\"hook\": \"order-sign\", \"hookInstance\": \"{{$guid}}\", \"fhirServer\": " + fhirServer
                + (authorization ? ", \"fhirAuthorization\": {\"access_token\": \"" + TOKEN + "\", \"token_type\": \"Bearer\"}" : "")
                + ", \"context\": {\"userId\": \"Practitioner/prac-001\", \"patientId\": \"pat-001\"}, \"prefetch\": {}}";
        return Map.of("name", "CRD - Prior Auth Check", "request", Map.of("method", "POST",
                "url", Map.of("raw", "{{base}}/cds-services/epa-crd-service"), "header", List.of(),
                "body", Map.of("mode", "raw", "raw", body)));
    }

    private static String collection(String schema, Map<String, Object> item) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", schema));
        root.put("item", List.of(Map.of("name", "Coverage", "item", List.of(item))));
        return JsonSafe.mapper().writeValueAsString(root);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(each -> out.add(each.asString()));
        }
        return out;
    }
}
