package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.lists.CollectionIngest;
import org.p32h.interop.epa.pave.service.lists.TestCodeInput;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Adding what a collection states about diagnoses and servicing providers to the saved lists
 * (D-322): the fourth list counted in the result line, a facility saved as a provider known by its
 * organization, labels that tell a performer from a requester, entries already there left as they
 * are, a full list, the lists switched off, a store written before the fourth list existed, and a
 * damaged list file that takes nothing while the others still add.
 */
class DiagnosisAndPerformerAddTest {

    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";

    @TempDir
    Path directory;

    private TestListService lists;

    private MockMvc mvc(boolean enabled) {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        TestListStore store = new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.resolve("lists").toString())),
                TestMvc.mapper());
        lists = new TestListService(TestListProperties.of(Map.of("enabled", String.valueOf(enabled))), store, ConfigFixture.CLOCK);
        CollectionIngest ingest = new CollectionIngest(fixture.service::environments, lists,
                new VariableResolver(name -> null, ConfigFixture.CLOCK, () -> "guid"));
        return TestMvc.standalone(new FoundListsController(ingest), new TestListController(lists));
    }

    /** An order-sign request with a diagnosis and a facility as the order's performer, and a Submit with the same diagnosis. */
    static String collection() {
        String orderSign = "{\"hook\": \"order-sign\", \"context\": {\"userId\": \"Practitioner/prac-001\", \"patientId\": \"pat-001\", "
                + "\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": "
                + "\"ServiceRequest\", \"id\": \"sr-1\", \"code\": {\"coding\": [{\"system\": \"http://www.ama-assn.org/go/cpt\", "
                + "\"code\": \"95810\"}]}, \"reasonCode\": [{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"G47.33\", "
                + "\"display\": \"Obstructive sleep apnea\"}]}], \"performer\": [{\"reference\": \"Organization/org-facility-001\"}]}}]}}, "
                + "\"prefetch\": {\"organizations\": {\"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [{\"resource\": "
                + "{\"resourceType\": \"Organization\", \"id\": \"org-facility-001\", \"name\": \"Placeholder Sleep Laboratory\", "
                + "\"identifier\": [{\"system\": \"" + NPI + "\", \"value\": \"9999999995\"}], \"telecom\": [{\"system\": \"phone\", "
                + "\"value\": \"617-555-0110\"}]}}]}}}";
        String submit = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": \"Claim\", "
                + "\"provider\": {\"reference\": \"Practitioner/prac-001\"}, \"careTeam\": [{\"sequence\": 1, \"provider\": {\"reference\": "
                + "\"Practitioner/prac-001\"}}], \"diagnosis\": [{\"sequence\": 1, \"diagnosisCodeableConcept\": {\"coding\": [{\"system\": \""
                + ICD + "\", \"code\": \"G47.33\"}]}}, {\"sequence\": 2, \"diagnosisCodeableConcept\": {\"coding\": [{\"system\": \"" + ICD
                + "\", \"code\": \"E11.9\"}]}}]}}]}";
        return "{\"info\": {\"name\": \"Vendor sample\", \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, "
                + "\"item\": [{\"name\": \"Order sign\", \"request\": {\"method\": \"POST\", \"url\": \"https://x.invalid/cds-services/a\", "
                + "\"body\": {\"mode\": \"raw\", \"raw\": " + JsonSafe.mapper().writeValueAsString(orderSign) + "}}}, {\"name\": \"Submit\", "
                + "\"request\": {\"method\": \"POST\", \"url\": \"https://x.invalid/Claim/$submit\", \"body\": {\"mode\": \"raw\", \"raw\": "
                + JsonSafe.mapper().writeValueAsString(submit) + "}}}]}";
    }

    private static String body() {
        return JsonSafe.mapper().writeValueAsString(Map.of("collectionExport", Map.of("fileName", "vendor.postman_collection.json", "text",
                collection())));
    }

    private static org.springframework.test.web.servlet.ResultActions add(MockMvc mvc) throws Exception {
        return mvc.perform(post("/api/lists/found/add").contentType(MediaType.APPLICATION_JSON).content(body()));
    }

    @Test
    void theFoundPartListsTheDiagnosisCodesAndThePerformersWithTheirRoles() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/lists/found").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnoses", hasSize(2))).andExpect(jsonPath("$.diagnoses[0].code").value("G47.33"))
                .andExpect(jsonPath("$.diagnoses[0].systemShort").value("ICD-10-CM")).andExpect(jsonPath("$.diagnoses[0].from").value("Order sign"))
                .andExpect(jsonPath("$.providers", hasSize(2)))
                .andExpect(jsonPath("$.providers[0].vendorPractitionerId").value("prac-001"))
                .andExpect(jsonPath("$.providers[0].requesterIn").value("Order sign"))
                .andExpect(jsonPath("$.providers[0].performerIn").value("Submit"))
                .andExpect(jsonPath("$.providers[1].organization").value("Placeholder Sleep Laboratory"))
                .andExpect(jsonPath("$.providers[1].npi").value("9999999995"))
                .andExpect(jsonPath("$.providers[1].performerIn").value("Order sign"))
                .andExpect(jsonPath("$.providers[1].requesterIn").doesNotExist())
                .andExpect(jsonPath("$.notUsed", hasSize(0)));
        assertThat(Files.exists(directory.resolve("lists"))).isFalse();
    }

    @Test
    void addingCountsTheDiagnosisCodesAndLabelsEachProviderWithWhatItIs() throws Exception {
        MockMvc mvc = mvc(true);
        add(mvc).andExpect(status().isOk())
                .andExpect(jsonPath("$.line").value("Added to the saved lists: 1 member, 2 providers, 1 procedure code, 2 diagnosis codes."))
                .andExpect(jsonPath("$.diagnoses.added").value(2));
        mvc.perform(get("/api/lists/diagnoses")).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.diagnoses[0].label").value("From Order sign")).andExpect(jsonPath("$.diagnoses[0].code").value("G47.33"))
                .andExpect(jsonPath("$.diagnoses[0].system").value(ICD))
                .andExpect(jsonPath("$.diagnoses[0].description").value("Obstructive sleep apnea"))
                .andExpect(jsonPath("$.diagnoses[1].label").value("From Submit"));
        mvc.perform(get("/api/lists/providers")).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.providers[0].label").value("Requester in Order sign, performer in Submit"))
                .andExpect(jsonPath("$.providers[1].label").value("Performer in Order sign"))
                .andExpect(jsonPath("$.providers[1].organization").value("Placeholder Sleep Laboratory"))
                .andExpect(jsonPath("$.providers[1].npi").value("9999999995")).andExpect(jsonPath("$.providers[1].phone").value("617-555-0110"))
                .andExpect(jsonPath("$.providers[1].vendorPractitionerId").doesNotExist());
        // A saved diagnosis code holds the code and its words only: nothing names the member it was found beside.
        String stored = Files.readString(directory.resolve("lists").resolve("test-lists").resolve("diagnoses.json"), StandardCharsets.UTF_8);
        assertThat(stored).doesNotContain("pat-001").doesNotContain("memberId").doesNotContain("patient");

        add(mvc).andExpect(jsonPath("$.line").value("Nothing new was added to the saved lists. Already there: 1 member, 2 providers, "
                + "1 procedure code, 2 diagnosis codes."));
    }

    @Test
    void anEntryTypedEarlierIsLeftAsItIsAndAFacilityByNameAloneIsOneEntry() throws Exception {
        MockMvc mvc = mvc(true);
        lists.addDiagnosis(new TestCodeInput("Typed by hand", "G47.33", ICD, "Typed words"));
        mvc.perform(post("/api/lists/providers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\": \"Typed facility\", \"organization\": \"Placeholder Imaging Center\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/lists/providers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"organization\": \"Placeholder Imaging Center\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("organization"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("A saved provider has this organization, with no NPI and no vendor "
                        + "practitioner id, already. Edit that entry instead."));
        add(mvc).andExpect(jsonPath("$.diagnoses.added").value(1)).andExpect(jsonPath("$.diagnoses.already").value(1));
        mvc.perform(get("/api/lists/diagnoses")).andExpect(jsonPath("$.diagnoses[0].label").value("Typed by hand"))
                .andExpect(jsonPath("$.diagnoses[0].description").value("Typed words"));
    }

    @Test
    void aFullDiagnosisListSaysHowManyDidNotFitAndSwitchedOffNothingIsAdded() throws Exception {
        MockMvc mvc = mvc(true);
        for (int i = 0; i < 200; i++) {
            lists.addDiagnosis(new TestCodeInput(null, "Z" + i, null, null));
        }
        add(mvc).andExpect(jsonPath("$.line").value("Added to the saved lists: 1 member, 2 providers, 1 procedure code. The diagnosis "
                + "codes list is full (200 entries): 2 diagnosis codes did not fit.")).andExpect(jsonPath("$.diagnoses.full").value(2));

        mvc(false).perform(post("/api/lists/found/add").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.diagnoses").doesNotExist());
        mvc(false).perform(post("/api/lists/found").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.diagnoses", hasSize(0)));
    }

    @Test
    void aStoreWrittenBeforeTheFourthListTakesItAndADamagedListTakesNothingWhileTheOthersAdd() throws Exception {
        Path folder = directory.resolve("lists").resolve("test-lists");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("members.json"), "{\"version\": 1, \"entries\": []}");
        Files.writeString(folder.resolve("providers.json"), "{\"version\": 1, \"entries\": []}");
        Files.writeString(folder.resolve("codes.json"), "{\"version\": 1, \"entries\": []}");
        MockMvc mvc = mvc(true);
        add(mvc).andExpect(jsonPath("$.diagnoses.added").value(2));

        Files.writeString(folder.resolve("diagnoses.json"), "{\"version\": 1, \"entries\": [");
        Files.delete(folder.resolve("providers.json"));
        add(mvc).andExpect(status().isOk())
                .andExpect(jsonPath("$.line").value("Added to the saved lists: 2 providers. Already there: 1 member, 1 procedure code. The "
                        + "diagnosis codes list could not be read: 2 diagnosis codes were not added."))
                .andExpect(jsonPath("$.diagnoses.unread").value(2))
                .andExpect(jsonPath("$.notUsed[0]", startsWith("The file " + folder.resolve("diagnoses.json") + " could not be read")));
        assertThat(Files.readString(folder.resolve("diagnoses.json"))).isEqualTo("{\"version\": 1, \"entries\": [");
    }
}
