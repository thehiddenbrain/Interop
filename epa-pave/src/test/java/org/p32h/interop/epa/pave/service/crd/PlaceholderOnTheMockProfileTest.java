package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The application as a tester starts it on a fresh checkout: the mock profile and the placeholder
 * code list. Here the comparison with the code list runs, because both sides are synthetic and
 * showing what a disagreement looks like is the point. What it must not do is let the placeholder
 * read as the P32H list, so every finding that rests on it says which list it was.
 */
@SpringBootTest
class PlaceholderOnTheMockProfileTest {

    private static final String PLACEHOLDER_NOTE = "The code list here is the synthetic placeholder that ships with the tool and not a P32H list.";

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired EpaProperties epa;
    @Autowired ProcedureCodeDirectory codeList;

    @Test
    void aRunOnMockIsComparedWithThePlaceholderAndEachFindingThatRestsOnItSaysSo() throws Exception {
        assertThat(epa.mock()).isTrue();
        assertThat(codeList.selfCheck().placeholder()).isTrue();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        // The scenario answers no-auth. The placeholder says 95810 needs authorization for Medicare and has no row for 99999.
        JsonNode result = JsonSafe.parse(mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content("{\"member\":{"
                + "\"memberId\":\"P32H-NO-AUTH\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"},{\"code\":\"99999\"}],\"dateOfService\":\"2026-03-20\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).node();

        assertThat(result.get("profile").stringValue()).isEqualTo("mock");
        List<JsonNode> aboutTheCodeList = new ArrayList<>();
        result.get("findings").forEach(finding -> {
            if (finding.get("kind").stringValue().startsWith("CODE_LIST_")) {
                aboutTheCodeList.add(finding);
            }
        });
        assertThat(aboutTheCodeList).hasSize(2);
        JsonNode disagreement = aboutTheCodeList.get(0);
        assertThat(disagreement.get("kind").stringValue()).isEqualTo("CODE_LIST_DISAGREES_ON_AUTH");
        assertThat(disagreement.get("subject").stringValue()).isEqualTo("95810");
        assertThat(disagreement.get("detail").stringValue()).isEqualTo("One of them is wrong, and this tool cannot tell which. " + PLACEHOLDER_NOTE);
        assertThat(disagreement.get("codeListSays").stringValue()).isEqualTo("The code list says prior authorization is required (rows for Medicare).");
        JsonNode missing = aboutTheCodeList.get(1);
        assertThat(missing.get("kind").stringValue()).isEqualTo("CODE_LIST_NOT_COMPARED");
        assertThat(missing.get("subject").stringValue()).isEqualTo("99999");
        assertThat(missing.get("detail").stringValue()).isEqualTo("The code list has no row for 99999 on 2026-03-20. " + PLACEHOLDER_NOTE);
    }
}
