package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Resolved;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Right after "Fill the form from these files" the draft says what the files give each address
 * and each value the Edit form has, with where it comes from (D-307), so that nothing the files
 * give is typed again: on the first real run every address and value was typed by hand, and a
 * typed address replaced the collection's request it was meant to be. A request chosen for an
 * operation none was found for is mapped by the collection's mapping, and Edit and a run then use
 * it. Every file is written for the test with placeholder values; no value of a secret appears.
 */
class FilesGiveTest {

    private static final String V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    private static final String SECRET = "placeholder-key-value";

    @TempDir
    Path directory;

    @Test
    void aCollectionThatMapsEveryOperationGivesEachRowItsRequestAndAddressWithTheExportsValues() throws Exception {
        JsonNode answer = draft(ConfigFixture.mock(directory), environment(), collection(List.of(
                item("order-sign", "POST", "{{base}}/cds-services/sign-service"),
                item("order-select", "POST", "{{base}}/cds-services/select-service"),
                item("Discovery", "GET", "{{base}}/cds-services"),
                item("Questionnaire package", "POST", "{{base}}/Questionnaire/$questionnaire-package"),
                item("Next question", "POST", "{{base}}/Questionnaire/$next-question"),
                item("Claim submit", "POST", "{{base}}/Claim/$submit"),
                item("Claim inquiry", "POST", "{{base}}/Claim/$inquire?key={{apiKey}}"),
                item("Create subscription", "POST", "{{base}}/Subscription"))));

        List<JsonNode> rows = elements(answer.get("addresses"));
        // In the order and with the words of the Edit form's Addresses.
        assertThat(rows.stream().map(row -> row.get("label").asString()).toList()).containsExactly("CRD order-sign", "CRD order-select",
                "CDS Hooks discovery", "DTR questionnaire package", "DTR next question", "PAS submit", "PAS inquire", "Subscription");
        assertThat(rows.stream().map(row -> row.get("requestName").asString()).toList()).containsExactly("order-sign", "order-select",
                "Discovery", "Questionnaire package", "Next question", "Claim submit", "Claim inquiry", "Create subscription");
        assertThat(rows.get(0).get("display").asString()).isEqualTo("https://vendor.example.invalid/fhir/cds-services/sign-service");
        assertThat(rows.get(0).get("from").asString()).isEqualTo("from the collection's request \"order-sign\"");
        assertThat(rows.get(0).get("key").asString()).isEqualTo("CRD_ORDER_SIGN");
        assertThat(rows.get(7).get("key").asString()).isEqualTo("SUBSCRIPTION");
        // A secret variable in an address is shown masked.
        assertThat(rows.get(6).get("display").asString()).startsWith("https://vendor.example.invalid/fhir/Claim/$inquire?key=")
                .doesNotContain(SECRET);
        rows.forEach(row -> assertThat(strings(row.get("lines"))).as(row.get("label").asString()).isEmpty());

        List<JsonNode> values = elements(answer.get("values"));
        JsonNode fhirServer = values.get(0);
        assertThat(fhirServer.get("label").asString()).isEqualTo("FHIR server");
        assertThat(fhirServer.get("display").asString()).isEqualTo("https://ehr.example.invalid/fhir");
        assertThat(fhirServer.get("from").asString()).isEqualTo("from the export's variable fhirServer");
        JsonNode payerName = values.get(1);
        assertThat(payerName.get("label").asString()).isEqualTo("Payer name");
        assertThat(payerName.has("display")).isFalse();
        // What follows when nothing gives a value is said once, on screen; a row has its own line only for a blank variable.
        assertThat(payerName.has("from")).isFalse();
        assertThat(payerName.has("line")).isFalse();
        JsonNode blank = values.get(4);
        assertThat(blank.get("name").asString()).isEqualTo("memberIdSystem");
        assertThat(blank.get("line").asString()).isEqualTo("{{memberIdSystem}} has no value in these files. Postman exports a variable's "
                + "initial value only.");
        // The id the store gives the collection, which the mapping of a chosen request names.
        assertThat(answer.get("collectionId").asString()).startsWith("placeholder-vendor-collection-");
        assertThat(answer.toString()).doesNotContain(SECRET);
    }

    @Test
    void aCollectionThatMapsNothingSaysSoForEachRowAndListsEveryRequestToChooseFrom() throws Exception {
        JsonNode answer = draft(ConfigFixture.mock(directory), environment(), collection(List.of(
                item("Check coverage", "POST", "{{base}}/epa/check"),
                item("Ping", "GET", "{{base}}/ping"))));

        for (JsonNode row : elements(answer.get("addresses"))) {
            assertThat(row.has("requestId")).as(row.get("label").asString()).isFalse();
            assertThat(row.has("display")).isFalse();
            assertThat(strings(row.get("lines"))).containsExactly("No request of the collection was found for it. Choose one below.",
                    "Without one, a call for it is refused before anything is sent.");
        }
        List<JsonNode> requests = elements(answer.get("requests"));
        assertThat(requests.stream().map(each -> each.get("name").asString() + " " + each.get("operation").asString()).toList())
                .containsExactly("Check coverage UNMAPPED", "Ping UNMAPPED");
        assertThat(requests.get(0).get("display").asString()).isEqualTo("https://vendor.example.invalid/fhir/epa/check");
    }

    @Test
    void twoRequestsForOneOperationUseTheFirstInTheFileSayWhichAndOfferTheOther() throws Exception {
        JsonNode answer = draft(ConfigFixture.mock(directory), environment(), collection(List.of(
                item("Claim submit", "POST", "{{base}}/Claim/$submit"),
                item("Claim submit with attachments", "POST", "{{base}}/v2/Claim/$submit"),
                item("Claim submit missing payer (error)", "POST", "{{base}}/Claim/$submit"))));

        JsonNode submit = row(answer, "PAS_SUBMIT");
        assertThat(submit.get("requestName").asString()).isEqualTo("Claim submit");
        List<String> ids = elements(answer.get("requests")).stream().map(each -> each.get("id").asString()).toList();
        // The sample of an error is passed over, as a call passes it over.
        assertThat(strings(submit.get("candidates"))).containsExactly(ids.get(0), ids.get(1));
        assertThat(strings(submit.get("lines"))).containsExactly("2 requests fit this operation. \"Claim submit\" is used, the first in "
                + "the file: choose another below to use it instead.");
        assertThat(elements(answer.get("requests")).get(2).get("errorSample").asBoolean()).isTrue();
    }

    @Test
    void aPlaceholderNothingDefinesStaysVisibleWithOneLine() throws Exception {
        JsonNode answer = draft(ConfigFixture.mock(directory), environment(), collection(List.of(
                item("Claim submit", "POST", "{{base}}/{{tenant}}/Claim/$submit"))));

        JsonNode submit = row(answer, "PAS_SUBMIT");
        assertThat(submit.get("display").asString()).isEqualTo("https://vendor.example.invalid/fhir/{{tenant}}/Claim/$submit");
        assertThat(strings(submit.get("lines"))).containsExactly("{{tenant}} is not defined in these files, so it stays as written: a run "
                + "asks for its value before it sends.");
    }

    @Test
    void aCollectionAloneGivesItsRequestsWithTheirPlaceholdersAndNoValue() throws Exception {
        JsonNode answer = draft(ConfigFixture.mock(directory), null, collection(List.of(item("Claim submit", "POST", "{{base}}/Claim/$submit"))));

        JsonNode submit = row(answer, "PAS_SUBMIT");
        assertThat(submit.get("display").asString()).isEqualTo("{{base}}/Claim/$submit");
        assertThat(strings(submit.get("lines"))).containsExactly("{{base}} is not defined in these files, so it stays as written: a run "
                + "asks for its value before it sends.");
        assertThat(elements(answer.get("values")).get(0).has("display")).isFalse();
    }

    @Test
    void applicationYmlsAddressIsSaidForAnOperationNoRequestGives() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("pas.url", "https://pas.example.invalid/fhir").build();
        JsonNode answer = draft(fixture, environment(), collection(List.of(item("Ping", "GET", "{{base}}/ping"))));

        JsonNode submit = row(answer, "PAS_SUBMIT");
        assertThat(submit.get("display").asString()).isEqualTo("https://pas.example.invalid/fhir/Claim/$submit");
        assertThat(submit.get("from").asString()).isEqualTo("from application.yml");
        assertThat(strings(submit.get("lines"))).containsExactly("No request of the collection was found for it. Choose one below.");
    }

    @Test
    void aRequestChosenForAnOperationIsMappedAndEditAndARunUseIt() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        String collection = collection(List.of(item("Check coverage", "POST", "{{base}}/cds-services/epa-service"),
                item("Ping", "GET", "{{base}}/ping")));
        JsonNode drafted = draft(fixture, environment(), collection);
        JsonNode chosen = elements(drafted.get("requests")).get(0);
        assertThat(chosen.get("operation").asString()).isEqualTo("UNMAPPED");

        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("kind", "sandbox");
        save.put("environmentExport", Map.of("fileName", "uat.postman_environment.json", "text", environment()));
        save.put("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        JsonNode saved = json(mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(save))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id = saved.at("/settings/id").asString();
        // What the page sends once the save has stored the collection.
        int status = mvc.perform(put("/api/config/collections/" + drafted.get("collectionId").asString() + "/mapping")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + chosen.get("id").asString()
                        + "\",\"operation\":\"CRD_ORDER_SIGN\"}")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);

        JsonNode settings = json(mvc.perform(get("/api/config/environments/" + id + "/settings")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode orderSign = elements(settings.get("addresses")).stream().filter(each -> each.get("operation").asString()
                .equals("CRD_ORDER_SIGN")).findFirst().orElseThrow();
        assertThat(orderSign.at("/inForce/sourceLabel").asString()).isEqualTo("from the collection's request \"Check coverage\"");
        assertThat(orderSign.at("/inForce/display").asString()).isEqualTo("https://vendor.example.invalid/fhir/cds-services/epa-service");
        assertThat(orderSign.has("typed")).isFalse();

        fixture.service.activate(id, null);
        Resolved resolved = fixture.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of(), Map.of(), null);
        assertThat(resolved.requestName()).isEqualTo("Check coverage");
        assertThat(resolved.url().display()).isEqualTo("https://vendor.example.invalid/fhir/cds-services/epa-service");
    }

    @Test
    void aRequestWhoseBodyStatesItsHookIsTheCoverageCheckOnceStoredAsInTheDraft() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        Map<String, Object> check = Map.of("name", "Prior Auth Check", "request", Map.of("method", "POST",
                "url", Map.of("raw", "{{base}}/cds-services/epa-crd-service"), "header", List.of(),
                "body", Map.of("mode", "raw", "raw", "{\"hook\": \"order-sign\", \"context\": {}}")));
        String collection = collection(List.of(check));
        JsonNode orderSign = row(draft(fixture, environment(), collection), "CRD_ORDER_SIGN");
        assertThat(orderSign.get("requestName").asString()).isEqualTo("Prior Auth Check");

        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("kind", "sandbox");
        save.put("environmentExport", Map.of("fileName", "uat.postman_environment.json", "text", environment()));
        save.put("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        JsonNode saved = json(mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(save))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        fixture.service.activate(saved.at("/settings/id").asString(), null);
        assertThat(fixture.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of(), Map.of(), null).requestName())
                .isEqualTo("Prior Auth Check");
    }

    // ------------------------------------------------------------------------------------------

    private JsonNode draft(ConfigFixture fixture, String environment, String collection) throws Exception {
        MockMvc mvc = TestMvc.standalone(new DraftController(new EnvironmentDrafts(fixture.service)));
        Map<String, Object> body = new LinkedHashMap<>();
        if (environment != null) {
            body.put(EnvironmentDrafts.ENVIRONMENT_SLOT, Map.of("fileName", "uat.postman_environment.json", "text", environment));
        }
        if (collection != null) {
            body.put(EnvironmentDrafts.COLLECTION_SLOT, Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        }
        return json(mvc.perform(post("/api/config/environments/draft").contentType(MediaType.APPLICATION_JSON)
                .content(JsonSafe.mapper().writeValueAsString(body))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    private static String environment() {
        return JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true),
                Map.of("key", "fhirServer", "value", "https://ehr.example.invalid/fhir", "enabled", true),
                Map.of("key", "apiKey", "value", SECRET, "type", "secret", "enabled", true),
                Map.of("key", "memberIdSystem", "value", "", "enabled", true))));
    }

    private static String collection(List<Object> items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", V21));
        root.put("item", items);
        return JsonSafe.mapper().writeValueAsString(root);
    }

    private static Map<String, Object> item(String name, String method, String url) {
        return Map.of("name", name, "request", Map.of("method", method, "url", Map.of("raw", url), "header", List.of()));
    }

    private static JsonNode row(JsonNode answer, String operation) {
        return elements(answer.get("addresses")).stream().filter(each -> each.get("operation").asString().equals(operation)).findFirst()
                .orElseThrow();
    }

    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(each -> out.add(each.asString()));
        }
        return out;
    }
}
