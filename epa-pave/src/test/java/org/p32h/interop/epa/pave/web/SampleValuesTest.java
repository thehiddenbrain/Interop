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

    // ---- the Submit sample: the payer and the member ID system ----

    private static final String SUBMIT = "PAS / Claim Submit";
    private static final String MEMBER = "S123456789";

    @Test
    void theSubmitBundleGivesThePayerItsOneIdentifierAndTheSystemOfTheMemberIdTypedMb() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\", \"identifier\": [{\"system\": "
                + "\"http://hl7.org/fhir/sid/us-npi\", \"value\": \"1234567893\"}]");
        String patient = patient("{\"system\": \"https://payer.example.org/mrn\", \"value\": \"P-0001\"}, {\"type\": {\"coding\": "
                + "[{\"code\": \"MB\"}]}, \"system\": \"https://payer.example.org/member-id\", \"value\": \"" + MEMBER + "\"}");
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, submit(organization, patient)));

        String from = "from the collection's request \"" + SUBMIT + "\"";
        assertThat(value(values, "payerName").get("display").asString()).isEqualTo("Placeholder Health Plan");
        assertThat(value(values, "payerName").get("from").asString()).isEqualTo(from);
        assertThat(value(values, "payerIdentifier").get("display").asString()).isEqualTo("1234567893");
        assertThat(value(values, "payerIdentifierSystem").get("display").asString()).isEqualTo("http://hl7.org/fhir/sid/us-npi");
        assertThat(value(values, "memberIdSystem").get("display").asString()).isEqualTo("https://payer.example.org/member-id");
        assertThat(value(values, "memberIdSystem").get("from").asString()).isEqualTo(from);
        // Member data in the sample is never shown in this part: only the system of the member ID.
        assertThat(values.toString()).doesNotContain(MEMBER).doesNotContain("P-0001");
    }

    @Test
    void severalIdentifiersGiveNoneAndALineNamesTheirSystems() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\", \"identifier\": [{\"system\": "
                + "\"http://hl7.org/fhir/sid/us-npi\", \"value\": \"1234567893\"}, {\"system\": \"https://payer.example.org/payer-id\", "
                + "\"value\": \"PAYER01\"}]");
        String patient = patient("{\"system\": \"https://payer.example.org/mrn\", \"value\": \"P-0001\"}, {\"system\": "
                + "\"https://payer.example.org/member-id\", \"value\": \"" + MEMBER + "\"}");
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, submit(organization, patient)));

        JsonNode identifier = value(values, "payerIdentifier");
        assertThat(identifier.has("display")).isFalse();
        assertThat(strings(identifier.get("notes"))).containsExactly("The collection's request \"" + SUBMIT + "\" gives the payer 2 "
                + "identifiers (http://hl7.org/fhir/sid/us-npi and https://payer.example.org/payer-id), so none is taken: choose one and "
                + "type it.");
        assertThat(value(values, "payerIdentifierSystem").has("display")).isFalse();
        assertThat(value(values, "payerName").get("display").asString()).isEqualTo("Placeholder Health Plan");
        JsonNode system = value(values, "memberIdSystem");
        assertThat(system.has("display")).isFalse();
        assertThat(strings(system.get("notes"))).containsExactly("The collection's request \"" + SUBMIT + "\" gives the patient 2 "
                + "identifiers (https://payer.example.org/mrn and https://payer.example.org/member-id) and none is typed MB, so the member "
                + "ID system is not taken.");
        assertThat(values.toString()).doesNotContain(MEMBER).doesNotContain("PAYER01").doesNotContain("P-0001");
    }

    @Test
    void theSystemOfTheSamplesIdentifierIsNotLaidBesideAnIdentifierFromElsewhere() throws Exception {
        String environment = JsonSafe.mapper().writeValueAsString(Map.of("name", "Placeholder UAT", "values", List.of(
                Map.of("key", "base", "value", "https://vendor.example.invalid/fhir", "enabled", true),
                Map.of("key", "payerIdentifier", "value", "OTHER01", "enabled", true))));
        String organization = organization("\"name\": \"{{payer_display_name}}\", \"identifier\": [{\"system\": "
                + "\"http://hl7.org/fhir/sid/us-npi\", \"value\": \"1234567893\"}]");
        String collection = collection(V21, submit(organization, patient("{\"value\": \"" + MEMBER + "\"}")));
        JsonNode values = draft(ConfigFixture.mock(directory), environment, collection);

        assertThat(value(values, "payerIdentifier").get("from").asString()).isEqualTo("from the export's variable payerIdentifier");
        JsonNode system = value(values, "payerIdentifierSystem");
        assertThat(system.has("display")).isFalse();
        assertThat(strings(system.get("notes"))).containsExactly("The collection's request \"" + SUBMIT + "\" states the system of its "
                + "own payer identifier, and the payer identifier in force comes from elsewhere, so it is not taken.");
        // A placeholder nothing defines stays as written; the only identifier, with no system, gives no member ID system.
        assertThat(value(values, "payerName").get("display").asString()).isEqualTo("{{payer_display_name}}");
        assertThat(value(values, "memberIdSystem").has("display")).isFalse();

        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String id = save(mvc, null, environment, collection, true, null).at("/settings/id").asString();
        assertThat(settingsValue(mvc, id, "payerIdentifier").at("/inForce/source").asString()).isEqualTo("EXPORT");
        assertThat(settingsValue(mvc, id, "payerIdentifierSystem").at("/inForce/source").asString()).isEqualTo("NOT_SET");
    }

    @Test
    void theSaveStoresThePayerAndTheMemberIdSystemAndSaysEach() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\", \"identifier\": [{\"system\": "
                + "\"http://hl7.org/fhir/sid/us-npi\", \"value\": \"1234567893\"}]");
        String patient = patient("{\"type\": {\"coding\": [{\"code\": \"MB\"}]}, \"system\": \"https://payer.example.org/member-id\", "
                + "\"value\": \"" + MEMBER + "\"}");
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        JsonNode saved = save(mvc, null, environment(), collection(V21, submit(organization, patient)), true, null);

        String from = "from the collection's request \"" + SUBMIT + "\"";
        assertThat(strings(saved.get("warnings"))).contains("Payer name set " + from + ".", "Payer identifier set " + from + ".",
                "Payer identifier system set " + from + ".", "Member ID system set " + from + ".");
        String id = saved.at("/settings/id").asString();
        for (String name : List.of("payerName", "payerIdentifier", "payerIdentifierSystem", "memberIdSystem")) {
            assertThat(settingsValue(mvc, id, name).at("/inForce/sourceLabel").asString()).as(name).isEqualTo(from);
        }
        assertThat(Files.readString(directory.resolve("store.json"))).doesNotContain(MEMBER);
    }

    // ---- the PAS types of the items, and the report type ----

    private static final String EXT = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-";

    private static String item(int sequence, String requestType, String certification, String serviceType) {
        return "{\"sequence\": " + sequence + ", \"extension\": [{\"url\": \"" + EXT + "itemTraceNumber\", \"valueIdentifier\": "
                + "{\"value\": \"T-" + sequence + "\"}}, {\"url\": \"" + EXT + "serviceItemRequestType\", \"valueCodeableConcept\": "
                + "{\"coding\": [{\"system\": \"https://codesystem.x12.org/005010/1525\", \"code\": \"" + requestType + "\"}]}}, "
                + "{\"url\": \"" + EXT + "certificationType\", \"valueCodeableConcept\": {\"coding\": [{\"system\": "
                + "\"https://codesystem.x12.org/005010/1322\", \"code\": \"" + certification + "\"}]}}], \"category\": {\"coding\": "
                + "[{\"system\": \"https://codesystem.x12.org/005010/1365\", \"code\": \"" + serviceType + "\"}]}}";
    }

    private static String supporting(String reportType) {
        return "{\"sequence\": 1, \"extension\": [{\"url\": \"" + EXT + "documentInformation\", \"extension\": [{\"url\": "
                + "\"reportTypeCode\", \"valueCodeableConcept\": {\"coding\": [{\"system\": \"https://codesystem.x12.org/005010/755\", "
                + "\"code\": \"" + reportType + "\"}]}}]}]}";
    }

    @Test
    void theItemsGiveTheirPasTypesWhereTheBuilderWritesThemAndTheSupportingInformationItsReportType() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\"");
        String claim = "\"item\": [" + item(1, "HS", "I", "12") + ", " + item(2, "HS", "I", "12") + "], \"supportingInfo\": ["
                + supporting("09") + "]";
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, submit(organization,
                patient("{\"value\": \"" + MEMBER + "\"}"), claim)));

        String from = "from the collection's request \"" + SUBMIT + "\"";
        assertThat(value(values, "pasRequestType").get("display").asString()).isEqualTo("HS");
        assertThat(value(values, "pasRequestType").get("from").asString()).isEqualTo(from);
        assertThat(value(values, "pasServiceType").get("display").asString()).isEqualTo("12");
        assertThat(value(values, "pasCertificationType").get("display").asString()).isEqualTo("I");
        assertThat(value(values, "pasReportType").get("display").asString()).isEqualTo("09");
        // The callback url is the address of this tool's own receiver, which no vendor's sample can state.
        assertThat(value(values, "callbackUrl").has("request")).isFalse();
    }

    @Test
    void itemsThatDisagreeGiveNoneAndALineSaysSo() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\"");
        String claim = "\"item\": [" + item(1, "HS", "I", "12") + ", " + item(2, "AR", "I", "12") + "]";
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, submit(organization,
                patient("{\"value\": \"" + MEMBER + "\"}"), claim)));

        JsonNode requestType = value(values, "pasRequestType");
        assertThat(requestType.has("display")).isFalse();
        assertThat(strings(requestType.get("notes"))).containsExactly("The items of the collection's request \"" + SUBMIT + "\" state "
                + "different PAS request types (HS and AR), so none is taken.");
        // The types the items agree on are still taken.
        assertThat(value(values, "pasCertificationType").get("display").asString()).isEqualTo("I");
        assertThat(value(values, "pasServiceType").get("display").asString()).isEqualTo("12");
    }

    @Test
    void aCodeOfAnotherSystemIsNotWhereTheBuilderWritesTheTypeAndGivesNothing() throws Exception {
        String organization = organization("\"name\": \"Placeholder Health Plan\"");
        String claim = "\"item\": [{\"sequence\": 1, \"category\": {\"coding\": [{\"system\": \"https://example.org/categories\", "
                + "\"code\": \"12\"}]}}]";
        JsonNode values = draft(ConfigFixture.mock(directory), environment(), collection(V21, submit(organization,
                patient("{\"value\": \"" + MEMBER + "\"}"), claim)));
        assertThat(value(values, "pasServiceType").has("display")).isFalse();
        assertThat(strings(value(values, "pasServiceType").get("notes"))).isEmpty();
    }

    // ---- the save ----
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


    private static String organization(String members) {
        return "{\"fullUrl\": \"urn:uuid:payer-1\", \"resource\": {\"resourceType\": \"Organization\", \"id\": \"payer-1\", " + members + "}}";
    }

    private static String patient(String identifiers) {
        return "{\"fullUrl\": \"http://vendor.example.invalid/fhir/Patient/pat-1\", \"resource\": {\"resourceType\": \"Patient\", "
                + "\"id\": \"pat-1\", \"identifier\": [" + identifiers + "]}}";
    }

    /** A Submit sample whose Claim names the payer by its fullUrl and the patient by type and id. */
    private static Map<String, Object> submit(String organization, String patient) {
        return submit(organization, patient, "\"item\": [{\"sequence\": 1}]");
    }

    /** As above, the Claim's items and supporting information written as given. */
    private static Map<String, Object> submit(String organization, String patient, String claimParts) {
        String body = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"fullUrl\": \"urn:uuid:claim-1\", "
                + "\"resource\": {\"resourceType\": \"Claim\", \"use\": \"preauthorization\", \"insurer\": {\"reference\": "
                + "\"urn:uuid:payer-1\"}, \"patient\": {\"reference\": \"Patient/pat-1\"}, " + claimParts + "}}, "
                + organization + ", " + patient + "]}";
        return Map.of("name", "Claim Submit", "request", Map.of("method", "POST", "url", Map.of("raw", "{{base}}/Claim/$submit"),
                "header", List.of(), "body", Map.of("mode", "raw", "raw", body)));
    }

    private static String collection(String schema, Map<String, Object> item) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", schema));
        String folder = String.valueOf(item.get("name")).startsWith("Claim") ? "PAS" : "Coverage";
        root.put("item", List.of(Map.of("name", folder, "item", List.of(item))));
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
