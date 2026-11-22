package org.p32h.interop.epa.pave.web;

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

/** The third saved list, procedure codes, over HTTP: built and tested as the members and providers are. */
class CodeListControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
    private static final String CODES = "/api/lists/codes";
    private static final String CPT = "http://www.ama-assn.org/go/cpt";

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

    @Test
    void aCodeIsAddedReadReplacedAndRemoved() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get(CODES)).andExpect(content().json("{\"enabled\": true, \"codes\": [], \"count\": 0}"));
        String id = JsonSafe.mapper().readTree(mvc.perform(json(post(CODES), "{\"label\": \"Therapy\", \"code\": \" 97110 \", \"system\": \""
                + CPT + "\", \"description\": \"Therapeutic exercise\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", matchesPattern("c-[0-9a-f]{12}"))).andExpect(jsonPath("$.code").value("97110"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(get(CODES)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.codes[0].system").value(CPT));
        mvc.perform(json(put(CODES + "/" + id), "{\"code\": \"97110\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.system").doesNotExist()).andExpect(jsonPath("$.description").doesNotExist());
        mvc.perform(delete(CODES + "/" + id)).andExpect(content().json("{\"removed\": true}"));
        mvc.perform(get(CODES)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void eachFieldIsRefusedAtItsFieldAndOneCodeInOneSystemNamesOneEntry() throws Exception {
        MockMvc mvc = mvc(true);
        refusedAt(mvc.perform(json(post(CODES), "{\"label\": \"No code\"}")), "code", "Enter the code, for example 97110.");
        refusedAt(mvc.perform(json(post(CODES), "{\"code\": \"971 10\"}")), "code", "A code is at most 40 characters, with no space in it.");
        refusedAt(mvc.perform(json(post(CODES), "{\"code\": \"" + "9".repeat(41) + "\"}")), "code",
                "A code is at most 40 characters, with no space in it.");
        refusedAt(mvc.perform(json(post(CODES), "{\"code\": \"97110\", \"system\": \"" + "s".repeat(256) + "\"}")), "system",
                "A code system is at most 255 characters.");
        refusedAt(mvc.perform(json(post(CODES), "{\"code\": \"97110\", \"description\": \"" + "d".repeat(501) + "\"}")), "description",
                "A description is at most 500 characters.");
        mvc.perform(json(post(CODES), "{\"code\": \"97110\", \"system\": \"" + CPT + "\"}")).andExpect(status().isOk());
        refusedAt(mvc.perform(json(post(CODES), "{\"code\": \"97110\", \"system\": \"" + CPT + "\"}")), "code",
                "A saved procedure code has this code and code system already. Edit that entry instead.");
        // The same code with no system stated is sent differently, so it is an entry of its own.
        mvc.perform(json(post(CODES), "{\"code\": \"97110\"}")).andExpect(status().isOk());
        mvc.perform(json(post(CODES), "{\"code\": \"97110\", \"sytem\": \"x\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sytem"));
        mvc.perform(get(CODES)).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void anImportShowsEachRowOnADryRunAndTheTemplateHoldsPlaceholderCodes() throws Exception {
        MockMvc mvc = mvc(true);
        String csv = "label,code,system,description\r\nTherapy,97110," + CPT + ",\"Therapeutic exercise, one area\"\r\nNo code,,,\r\n"
                + "Again,97110," + CPT + ",Repeated\r\n";
        mvc.perform(json(post(CODES + "/import"), "{\"csv\": " + JsonSafe.mapper().writeValueAsString(csv) + ", \"dryRun\": true}"))
                .andExpect(jsonPath("$.rows", hasSize(3))).andExpect(jsonPath("$.rows[0].status").value("ADD"))
                .andExpect(jsonPath("$.rows[1].reason").value("Enter the code, for example 97110."))
                .andExpect(jsonPath("$.rows[2].reason").value("Line 2 is the same procedure code. Only the first of the two is imported."))
                .andExpect(jsonPath("$.applied").value(false));
        mvc.perform(json(post(CODES + "/import"), "{\"csv\": " + JsonSafe.mapper().writeValueAsString(csv) + ", \"dryRun\": false}"))
                .andExpect(jsonPath("$.added").value(1));
        mvc.perform(get(CODES)).andExpect(jsonPath("$.codes[0].description").value("Therapeutic exercise, one area"));
        mvc.perform(json(post(CODES + "/import"), "{\"csv\": \"label\\r\\nx\\r\\n\", \"dryRun\": true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The file has no code column, so no row can be matched to a saved procedure code."));
        mvc.perform(get(CODES + "/template.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"test-procedure-codes-template.csv\"")))
                .andExpect(content().string(startsWith("label,code,system,description\r\n\"Therapy, needs prior authorization\",97110,")));
    }

    @Test
    void switchedOffTheListIsEmptyAndCannotBeChanged() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(get(CODES)).andExpect(content().json("{\"enabled\": false, \"codes\": [], \"count\": 0}"));
        mvc.perform(json(post(CODES), "{\"code\": \"97110\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Saved test lists are switched off on this installation (test-lists.enabled)."));
    }
}
