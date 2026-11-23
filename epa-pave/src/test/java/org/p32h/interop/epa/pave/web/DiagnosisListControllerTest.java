package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The fourth saved list, diagnosis codes (D-322), over HTTP: built and tested as the procedure codes
 * are, and read from a store that an older PAVE wrote with three lists, an empty file, a damaged
 * file, and with the lists switched off.
 */
class DiagnosisListControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
    private static final String DIAGNOSES = "/api/lists/diagnoses";
    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";

    @TempDir
    Path directory;

    private MockMvc mvc(boolean enabled) {
        TestListStore store = new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), TestMvc.mapper());
        TestListService service = new TestListService(TestListProperties.of(Map.of("enabled", String.valueOf(enabled))), store, CLOCK);
        return TestMvc.standalone(new TestListController(service));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ResultActions refusedAt(ResultActions result, String field, String message) throws Exception {
        return result.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message));
    }

    private Path lists() {
        return directory.resolve("test-lists");
    }

    private void file(String name, String text) throws Exception {
        Files.createDirectories(lists());
        Files.writeString(lists().resolve(name), text, StandardCharsets.UTF_8);
    }

    @Test
    void aDiagnosisCodeIsAddedReadReplacedAndRemovedInAFileOfItsOwn() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get(DIAGNOSES)).andExpect(content().json("{\"enabled\": true, \"diagnoses\": [], \"count\": 0}"))
                .andExpect(jsonPath("$.problem").doesNotExist());
        String id = JsonSafe.mapper().readTree(mvc.perform(json(post(DIAGNOSES), "{\"label\": \"Sleep study\", \"code\": \" G47.33 \", "
                + "\"system\": \"" + ICD + "\", \"description\": \"Obstructive sleep apnea\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", matchesPattern("d-[0-9a-f]{12}"))).andExpect(jsonPath("$.code").value("G47.33"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.diagnoses[0].system").value(ICD))
                .andExpect(jsonPath("$.diagnoses[0].label").value("Sleep study"));
        // Kept apart from the procedure codes, in a file of its own.
        mvc.perform(get("/api/lists/codes")).andExpect(jsonPath("$.count").value(0));
        assertThat(lists().resolve("diagnoses.json")).exists();
        assertThat(lists().resolve("codes.json")).doesNotExist();
        mvc.perform(json(put(DIAGNOSES + "/" + id), "{\"code\": \"G47.33\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.system").doesNotExist()).andExpect(jsonPath("$.description").doesNotExist());
        mvc.perform(delete(DIAGNOSES + "/" + id)).andExpect(content().json("{\"removed\": true}"));
        mvc.perform(delete(DIAGNOSES + "/" + id)).andExpect(content().json("{\"removed\": false}"));
        mvc.perform(json(put(DIAGNOSES + "/" + id), "{\"code\": \"G47.33\"}")).andExpect(status().isNotFound());
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void eachFieldIsRefusedAtItsFieldUnderTheLimitsOfTheProcedureCodes() throws Exception {
        MockMvc mvc = mvc(true);
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"label\": \"No code\"}")), "code", "Enter the code, for example G47.33.");
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47 33\"}")), "code", "A code is at most 40 characters, with no space in it.");
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"code\": \"" + "G".repeat(41) + "\"}")), "code",
                "A code is at most 40 characters, with no space in it.");
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\", \"system\": \"" + "s".repeat(256) + "\"}")), "system",
                "A code system is at most 255 characters.");
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\", \"description\": \"" + "d".repeat(501) + "\"}")), "description",
                "A description is at most 500 characters.");
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\", \"system\": \"" + ICD + "\"}")).andExpect(status().isOk());
        refusedAt(mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\", \"system\": \"" + ICD + "\"}")), "code",
                "A saved diagnosis code has this code and code system already. Edit that entry instead.");
        // The same code with no system stated is sent differently, so it is an entry of its own.
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\"}")).andExpect(status().isOk());
        // An entry holds a code and its words only: a property that would name a member is refused at its name.
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"E11.9\", \"memberId\": \"S123456789\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("memberId"));
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"E11.9\", \"sytem\": \"x\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sytem"));
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void aFullListSaysSoAndAddsNothing() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            entries.append(i == 0 ? "" : ",").append("{\"id\": \"d-").append(String.format("%012d", i)).append("\", \"code\": \"Z").append(i)
                    .append("\"}");
        }
        file("diagnoses.json", "{\"version\": 1, \"entries\": [" + entries + "]}");
        MockMvc mvc = mvc(true);
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(200));
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("This list holds 200 entries, the most it keeps."));
    }

    @Test
    void anImportShowsEachRowOnADryRunAndTheTemplateHoldsPlaceholderCodesOnly() throws Exception {
        MockMvc mvc = mvc(true);
        String csv = "label,code,system,description\r\nSleep,G47.33," + ICD + ",\"Obstructive sleep apnea, adult\"\r\nNo code,,,\r\n"
                + "Again,G47.33," + ICD + ",Repeated\r\n";
        mvc.perform(json(post(DIAGNOSES + "/import"), "{\"csv\": " + JsonSafe.mapper().writeValueAsString(csv) + ", \"dryRun\": true}"))
                .andExpect(jsonPath("$.rows", hasSize(3))).andExpect(jsonPath("$.rows[0].status").value("ADD"))
                .andExpect(jsonPath("$.rows[1].reason").value("Enter the code, for example G47.33."))
                .andExpect(jsonPath("$.rows[2].reason").value("Line 2 is the same diagnosis code. Only the first of the two is imported."))
                .andExpect(jsonPath("$.applied").value(false));
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(json(post(DIAGNOSES + "/import"), "{\"csv\": " + JsonSafe.mapper().writeValueAsString(csv) + ", \"dryRun\": false}"))
                .andExpect(jsonPath("$.added").value(1));
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.diagnoses[0].description").value("Obstructive sleep apnea, adult"));
        mvc.perform(json(post(DIAGNOSES + "/import"), "{\"csv\": \"label\\r\\nx\\r\\n\", \"dryRun\": true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The file has no code column, so no row can be matched to a saved diagnosis code."));
        String template = mvc.perform(get(DIAGNOSES + "/template.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"test-diagnosis-codes-template.csv\"")))
                .andExpect(content().string(startsWith("label,code,system,description\r\nSleep study,G47.33," + ICD + ",")))
                .andReturn().getResponse().getContentAsString();
        assertThat(template.split("\r\n")[0]).isEqualTo("label,code,system,description");
        assertThat(template).doesNotContain("member").doesNotContain("S123456789");
    }

    @Test
    void aStoreWrittenByAnOlderPaveWithThreeListsIsReadAsItIsAndKeepsItsListsWhenTheFourthIsAdded() throws Exception {
        file("members.json", "{\"version\": 1, \"entries\": [{\"id\": \"m-000000000001\", \"memberId\": \"S123456789\"}]}");
        file("providers.json", "{\"version\": 1, \"entries\": [{\"id\": \"p-000000000001\", \"npi\": \"1234567893\"}]}");
        file("codes.json", "{\"version\": 1, \"entries\": [{\"id\": \"c-000000000001\", \"code\": \"E0601\"}]}");
        String before = Files.readString(lists().resolve("codes.json"));
        MockMvc mvc = mvc(true);
        mvc.perform(get(DIAGNOSES)).andExpect(status().isOk()).andExpect(content().json("{\"enabled\": true, \"diagnoses\": [], \"count\": 0}"))
                .andExpect(jsonPath("$.problem").doesNotExist());
        mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\", \"system\": \"" + ICD + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/lists/members")).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/lists/providers")).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/lists/codes")).andExpect(jsonPath("$.count").value(1));
        assertThat(Files.readString(lists().resolve("codes.json"))).isEqualTo(before);
        mvc.perform(get(DIAGNOSES)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void anEmptyOrDamagedFileIsSaidAndNeverWrittenOverAndTheOtherListsStillRead() throws Exception {
        file("codes.json", "{\"version\": 1, \"entries\": [{\"id\": \"c-000000000001\", \"code\": \"E0601\"}]}");
        for (String damaged : new String[] {"", "{\"version\": 1, \"entries\": [", "{\"version\": 2, \"entries\": []}", "[1, 2]"}) {
            file("diagnoses.json", damaged);
            MockMvc mvc = mvc(true);
            mvc.perform(get(DIAGNOSES)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0))
                    .andExpect(jsonPath("$.problem", startsWith("The file " + lists().resolve("diagnoses.json"))));
            mvc.perform(json(post(DIAGNOSES), "{\"code\": \"G47.33\"}")).andExpect(status().is5xxServerError());
            assertThat(Files.readString(lists().resolve("diagnoses.json"))).as(damaged).isEqualTo(damaged);
            mvc.perform(get("/api/lists/codes")).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.problem").doesNotExist());
        }
    }

    @Test
    void switchedOffTheListIsEmptyAndCannotBeChanged() throws Exception {
        file("diagnoses.json", "{\"version\": 1, \"entries\": [{\"id\": \"d-000000000001\", \"code\": \"G47.33\"}]}");
        MockMvc mvc = mvc(false);
        mvc.perform(get(DIAGNOSES)).andExpect(content().json("{\"enabled\": false, \"diagnoses\": [], \"count\": 0}"));
        for (MockHttpServletRequestBuilder change : new MockHttpServletRequestBuilder[] {json(post(DIAGNOSES), "{\"code\": \"G47.33\"}"),
                json(put(DIAGNOSES + "/d-000000000001"), "{\"code\": \"G47.33\"}"), delete(DIAGNOSES + "/d-000000000001"),
                json(post(DIAGNOSES + "/import"), "{\"csv\": \"code\\r\\nG47.33\\r\\n\", \"dryRun\": true}")}) {
            mvc.perform(change).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("Saved test lists are switched off on this installation (test-lists.enabled)."));
        }
    }
}
