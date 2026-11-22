package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.lists.CollectionIngest;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/lists/found}: what an uploaded collection states, read with the environment
 * export chosen with it, the stored environment being edited, or the active one; any file answers
 * with a line about it, never an internal error; nothing is stored by reading.
 */
class FoundListsControllerTest {

    static final String FOUND = "/api/lists/found";

    @TempDir
    Path directory;

    static final String ORDER_SIGN = """
            {"hook": "order-sign", "hookInstance": "{{$guid}}", "context": {"userId": "Practitioner/{{practitionerId}}",
             "patientId": "{{patientId}}", "draftOrders": {"resourceType": "Bundle", "type": "collection", "entry": [
              {"resource": {"resourceType": "ServiceRequest", "code": {"coding": [{"system": "http://www.ama-assn.org/go/cpt",
               "code": "97110", "display": "Therapeutic exercise"}]}}}]}}}""";

    static String collection(String schema) {
        return "{\"info\": {\"name\": \"Vendor sample\", \"schema\": \"" + schema + "\"}, \"item\": [{\"name\": \"Order sign\", "
                + "\"request\": {\"method\": \"POST\", \"url\": \"{{crdBase}}/cds-services/order-sign\", \"body\": {\"mode\": \"raw\", "
                + "\"raw\": " + JsonSafe.mapper().writeValueAsString(ORDER_SIGN) + "}}}]}";
    }

    static final String COLLECTION = collection("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");

    static String environment(String name, String patientId) {
        return "{\"name\": \"" + name + "\", \"values\": [{\"key\": \"crdBase\", \"value\": \"https://sandbox.example.invalid\", "
                + "\"enabled\": true}, {\"key\": \"patientId\", \"value\": \"" + patientId + "\", \"enabled\": true}, "
                + "{\"key\": \"practitionerId\", \"value\": \"prac-001\", \"enabled\": true}, "
                + "{\"key\": \"client_secret\", \"value\": \"placeholder-client-secret\", \"type\": \"secret\", \"enabled\": true}]}";
    }

    private MockMvc mvc(ConfigFixture fixture, boolean enabled) {
        TestListStore store = new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.resolve("lists").toString())),
                TestMvc.mapper());
        TestListService lists = new TestListService(TestListProperties.of(Map.of("enabled", String.valueOf(enabled))), store,
                ConfigFixture.CLOCK);
        CollectionIngest ingest = new CollectionIngest(fixture.service::environments, lists,
                new VariableResolver(name -> null, ConfigFixture.CLOCK, () -> "guid"));
        return TestMvc.standalone(new FoundListsController(ingest));
    }

    static String body(String collection, String environment, String forId, Boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (collection != null) {
            body.put("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        }
        if (environment != null) {
            body.put("environmentExport", Map.of("fileName", "vendor.postman_environment.json", "text", environment));
        }
        if (forId != null) {
            body.put("forId", forId);
        }
        if (active != null) {
            body.put("fromActiveEnvironment", active);
        }
        return JsonSafe.mapper().writeValueAsString(body);
    }

    private static ResultActions found(MockMvc mvc, String body) throws Exception {
        return mvc.perform(post(FOUND).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void theEnvironmentExportChosenWithTheCollectionFillsItsPlaceholdersAndNothingIsStored() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        MockMvc mvc = mvc(fixture, true);
        found(mvc, body(COLLECTION, environment("Vendor UAT", "pat-001"), null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.line").doesNotExist())
                .andExpect(jsonPath("$.members", hasSize(1))).andExpect(jsonPath("$.members[0].vendorPatientId").value("pat-001"))
                .andExpect(jsonPath("$.members[0].from").value("Order sign"))
                .andExpect(jsonPath("$.providers[0].vendorPractitionerId").value("prac-001"))
                .andExpect(jsonPath("$.codes[0].code").value("97110")).andExpect(jsonPath("$.codes[0].systemShort").value("CPT"))
                .andExpect(jsonPath("$.notUsed", hasSize(0)));
        assertThat(Files.exists(directory.resolve("lists"))).isFalse();
    }

    @Test
    void withoutAnEnvironmentThePlaceholdersAreSaidAndTheStoredOrTheActiveEnvironmentFillsThem() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        MockMvc mvc = mvc(fixture, true);
        found(mvc, body(COLLECTION, null, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.line").value("Nothing to add to the saved lists was found in the collection."))
                .andExpect(jsonPath("$.notUsed[0]").value("\"Order sign\": {{practitionerId}} and {{patientId}} have no value in the "
                        + "environment or the collection, so nothing was read from its body."));

        String id = fixture.service.uploadEnvironment("edited.json", environment("Edited", "pat-edited").getBytes(StandardCharsets.UTF_8),
                EnvironmentKind.SANDBOX, true).environment().id();
        fixture.service.uploadEnvironment("active.json", environment("Active", "pat-active").getBytes(StandardCharsets.UTF_8),
                EnvironmentKind.SANDBOX, true);
        fixture.service.activate("Active", null);
        found(mvc, body(COLLECTION, null, id, null)).andExpect(jsonPath("$.members[0].vendorPatientId").value("pat-edited"));
        found(mvc, body(COLLECTION, null, null, true)).andExpect(jsonPath("$.members[0].vendorPatientId").value("pat-active"));
        found(mvc, body(COLLECTION, null, "no-such-environment", null)).andExpect(status().isNotFound());
    }

    @Test
    void anyFileAnswersWithALineAboutItAndAMissingCollectionIsRefusedAtItsField() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        MockMvc mvc = mvc(fixture, true);
        found(mvc, body(collection("https://schema.getpostman.com/json/collection/v2.0.0/collection.json"), null, null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.line").value(startsWith("The collection was not read: This is a Postman "
                        + "collection in format v2.0.0. This tool reads format v2.1 only.")))
                .andExpect(jsonPath("$.members").isEmpty());
        for (String broken : List.of("not json at all", "{\"info\": 1}", environment("Wrong slot", "pat-001"), "[" .repeat(5000))) {
            found(mvc, body(broken, null, null, null)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.line").value(startsWith("The collection was not read: ")));
        }
        // An environment export that cannot be read leaves the collection read, its placeholders said.
        found(mvc, body(COLLECTION, "{\"values\": 3}", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notUsed[0]").value("The environment export was not read, so the collection's placeholders were "
                        + "filled from the collection alone."));
        found(mvc, body(null, environment("Only", "pat-001"), null, null)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("collectionExport"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Choose the collection export to read members, providers and codes from."));
        found(mvc, "{\"collectionExport\": {\"fileName\": \"a\", \"text\": \"{}\"}, \"forid\": \"x\"}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void switchedOffNothingIsReadAndTheSwitchIsSaid() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        found(mvc(fixture, false), body(COLLECTION, environment("Vendor UAT", "pat-001"), null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.line").value("Saved test lists are switched off on this installation (test-lists.enabled)."))
                .andExpect(jsonPath("$.members").isEmpty());
        try (Stream<Path> walk = Files.walk(directory)) {
            assertThat(walk.anyMatch(path -> path.toString().contains("test-lists"))).isFalse();
        }
    }
}
