package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.suite.CsvRows;
import org.p32h.interop.epa.pave.web.ProcedureCodeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The application as a tester starts it on a fresh checkout: no profile, no arguments, no edited
 * file. The controller tests build the controller by hand, so this is what shows that the code
 * list parts are really there when the application starts, and that the two endpoints answer
 * through the application's own JSON mapper and error handling.
 */
@SpringBootTest
class CodeListWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    WebApplicationContext web;

    private JsonNode json(String url) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
        return JsonSafe.parse(mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString()).node();
    }

    @Test
    void theCodeListIsTheOneDirectoryInTheContextAndHoldsThePlaceholder() {
        Map<String, ProcedureCodeDirectory> directories = context.getBeansOfType(ProcedureCodeDirectory.class);
        // Exactly one: whoever asks for the directory gets the list the picker shows.
        assertThat(directories).hasSize(1);
        ProcedureCodeDirectory directory = directories.values().iterator().next();
        assertThat(directory).isInstanceOf(ProcedureCodeService.class);
        assertThat(directory.selfCheck().placeholder()).isTrue();
        assertThat(directory.selfCheck().rowCount()).isEqualTo(43);
        assertThat(context.getBeansOfType(ProcedureCodeController.class)).hasSize(1);
    }

    @Test
    void theSuiteReaderHasTheRealCsvReaderToReadThrough() {
        Map<String, CsvRows> readers = context.getBeansOfType(CsvRows.class);
        assertThat(readers).hasSize(1);
        assertThat(readers.values().iterator().next()).isInstanceOf(CsvRowsAdapter.class);
    }

    @Test
    void thePickerAnswersInTheStartedApplication() throws Exception {
        JsonNode answer = json("/api/procedure-codes?q=95810&lob=Medicare");
        assertThat(answer.get("query").stringValue()).isEqualTo("95810");
        assertThat(answer.get("lineOfBusiness").stringValue()).isEqualTo("Medicare");
        assertThat(answer.get("narrowed").booleanValue()).isTrue();
        // app.autocomplete-limit as application.yml sets it.
        assertThat(answer.get("limit").intValue()).isEqualTo(25);
        assertThat(answer.get("matched").intValue()).isEqualTo(1);
        assertThat(answer.get("codes").size()).isEqualTo(1);
        JsonNode row = answer.get("codes").get(0);
        assertThat(row.get("code").stringValue()).isEqualTo("95810");
        assertThat(row.get("authRequired").stringValue()).isEqualTo("REQUIRED");
        assertThat(row.get("delegatedVendor").stringValue()).isEqualTo("eviCore");
        assertThat(row.get("notes").stringValue()).startsWith("Placeholder row.");
        // The application's mapper leaves a null out, as every other answer of this tool does.
        assertThat(row.has("endDate")).isFalse();
        assertThat(answer.has("notice")).isFalse();
        // The same code has rows for Medicaid and Commercial. This member's own row was offered, so nothing is said of them.
        assertThat(answer.has("otherLines")).isFalse();

        // A row the list says nothing about still carries the flag, as UNKNOWN.
        JsonNode silent = json("/api/procedure-codes?q=A7030").get("codes").get(0);
        assertThat(silent.get("authRequired").stringValue()).isEqualTo("UNKNOWN");
        assertThat(silent.has("authRequiredSource")).isFalse();
    }

    @Test
    void theSelfCheckAnswersInTheStartedApplication() throws Exception {
        JsonNode check = json("/api/procedure-codes/self-check");
        assertThat(check.get("placeholder").booleanValue()).isTrue();
        assertThat(check.get("source").stringValue()).isEqualTo("classpath:codelist/placeholder-codes.csv");
        assertThat(check.get("format").stringValue()).isEqualTo("csv");
        assertThat(check.get("rowCount").intValue()).isEqualTo(43);
        assertThat(check.get("unrecognisedColumns").isEmpty()).isTrue();
        // The application's mapper keeps the order the maps were built in: lines as first named, columns as looked for.
        assertThat(JsonSafe.compact(check.get("linesOfBusiness"))).isEqualTo("{\"Medicare\":21,\"Medicaid\":7,\"Commercial\":10}");
        assertThat(check.get("recognisedColumns").propertyNames()).containsExactly("code", "description", "codeType", "authRequired",
                "lineOfBusiness", "delegatedVendor", "effectiveDate", "endDate", "notes");
        assertThat(check.get("warnings").size()).isEqualTo(1);
    }
}
