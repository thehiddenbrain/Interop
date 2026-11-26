package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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
 * The request shape Prefetch in the environment's form (D-309): filled from the collection's
 * coverage-check sample when the environment states none, stated by the environment once chosen,
 * shown after Fill with the request it was read from and what following it leaves out. The lines
 * name keys and resource types only: no value of the sample is shown.
 */
class PrefetchShapeFormTest {

    private static final String V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    private static final String SAMPLE = """
            {"hook": "order-sign", "hookInstance": "{{$guid}}", "fhirServer": "https://ehr-sandbox.example.org/fhir",
             "context": {"userId": "Practitioner/sample-prac", "patientId": "sample-pat-1"},
             "prefetch": {"patient": {"resourceType": "Patient", "id": "sample-pat-1", "name": [{"family": "SampleFamily"}]},
                          "coverage": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Coverage"}}]},
                          "encounter": {"resourceType": "Encounter", "id": "sample-enc"}}}
            """;
    private static final String ENCOUNTER_LINE = "The sample's prefetch also carries Encounter under \"encounter\". PAVE does not build it, "
            + "so it is not sent.";

    @TempDir
    Path directory;

    @Test
    void afterFillThePrefetchShapeShowsTheSamplesKeysTheRequestAndWhatIsLeftOut() throws Exception {
        MockMvc mvc = TestMvc.standalone(new DraftController(new EnvironmentDrafts(ConfigFixture.mock(directory).service)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(EnvironmentDrafts.ENVIRONMENT_SLOT, Map.of("fileName", "uat.postman_environment.json", "text", environment()));
        body.put(EnvironmentDrafts.COLLECTION_SLOT, Map.of("fileName", "vendor.postman_collection.json", "text", collection(SAMPLE)));
        JsonNode draft = json(mvc.perform(post("/api/config/environments/draft").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(body))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        JsonNode prefetch = shape(draft.get("shapes"), "CRD_PREFETCH");
        assertThat(prefetch.get("value").asString()).isEqualTo("AS_SAMPLE");
        assertThat(prefetch.get("label").asString()).isEqualTo("Prefetch");
        assertThat(prefetch.get("words").asString()).isEqualTo("As the collection's sample (patient, coverage, encounter)");
        assertThat(prefetch.get("requestName").asString()).isEqualTo("CRD - Prior Auth Check");
        assertThat(strings(prefetch.get("lines"))).containsExactly(ENCOUNTER_LINE);
        assertThat(draft.get("shapes").toString()).doesNotContain("SampleFamily").doesNotContain("sample-pat-1").doesNotContain("sample-enc");
    }

    @Test
    void onEditTheShapeIsFromTheSampleUntilTheEnvironmentStatesTheNamedBundles() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String id = save(mvc, null, collection(SAMPLE), null).at("/settings/id").asString();

        JsonNode fromSample = shape(settings(mvc, id).get("shapes"), "CRD_PREFETCH");
        assertThat(fromSample.get("value").asString()).isEqualTo("AS_SAMPLE");
        assertThat(fromSample.get("from").asString()).isEqualTo("the collection's sample");
        assertThat(fromSample.get("requestName").asString()).isEqualTo("CRD - Prior Auth Check");
        assertThat(options(fromSample)).containsExactly("PAVE's named Bundles (patientBundle, coverageBundle, ...)",
                "As the collection's sample (patient, coverage, encounter)");
        assertThat(strings(fromSample.get("lines"))).containsExactly(ENCOUNTER_LINE);

        save(mvc, id, null, Map.of("CRD_PREFETCH", "NAMED_BUNDLES"));
        JsonNode stated = shape(settings(mvc, id).get("shapes"), "CRD_PREFETCH");
        assertThat(stated.get("value").asString()).isEqualTo("NAMED_BUNDLES");
        assertThat(stated.get("from").asString()).isEqualTo("this environment");
    }

    @Test
    void withNoSampleTheShapeIsTheNamedBundlesByDefault() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String id = save(mvc, null, collection(SAMPLE.replace("\"prefetch\"", "\"notPrefetch\"")), null).at("/settings/id").asString();

        JsonNode none = shape(settings(mvc, id).get("shapes"), "CRD_PREFETCH");
        assertThat(none.get("value").asString()).isEqualTo("NAMED_BUNDLES");
        assertThat(none.get("from").asString()).isEqualTo("default");
        assertThat(options(none)).contains("As the collection's sample");
        assertThat(none.has("lines")).isFalse();
    }

    // ------------------------------------------------------------------------------------------

    private static JsonNode save(MockMvc mvc, String id, String collection, Map<String, String> shapes) throws Exception {
        Map<String, Object> save = new LinkedHashMap<>();
        if (id == null) {
            save.put("kind", "sandbox");
            save.put("environmentExport", Map.of("fileName", "uat.postman_environment.json", "text", environment()));
        } else {
            save.put("id", id);
        }
        if (collection != null) {
            save.put("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        }
        if (shapes != null) {
            save.put("shapes", shapes);
        }
        return json(mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(save))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode settings(MockMvc mvc, String id) throws Exception {
        return json(mvc.perform(get("/api/config/environments/" + id + "/settings")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode shape(JsonNode shapes, String key) {
        List<JsonNode> rows = new ArrayList<>();
        shapes.forEach(rows::add);
        return rows.stream().filter(each -> each.get("key").asString().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> options(JsonNode shape) {
        List<String> out = new ArrayList<>();
        shape.get("options").forEach(option -> out.add(option.get("words").asString()));
        return out;
    }

    private static String environment() {
        return JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true))));
    }

    private static String collection(String hookBody) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", V21));
        Map<String, Object> hook = Map.of("name", "CRD - Prior Auth Check", "request", Map.of("method", "POST",
                "url", Map.of("raw", "{{base}}/cds-services/epa-crd-service"), "header", List.of(),
                "body", Map.of("mode", "raw", "raw", hookBody)));
        root.put("item", List.of(Map.of("name", "Coverage", "item", List.of(hook))));
        return JsonSafe.mapper().writeValueAsString(root);
    }

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(each -> out.add(each.asString()));
        }
        return out;
    }
}
