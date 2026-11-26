package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.ConfigStoreException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A store write that failed for a reason of this computer's files (R-32) is headed as such, with
 * the store's own sentence as its title and no fixed action, since that sentence already says what
 * to do; it is not a failure of this tool, so it has no reference. A store failure PAVE could not
 * tell keeps the answer it had.
 */
class StoreProblemAnswerTest {

    private static final String READ_ONLY = "The file /store/store.json is marked read-only. Clear Read-only in its Properties, then "
            + "try again. Nothing was changed.";

    @RestController
    static class StoreController {

        @GetMapping("/store/{what}")
        public String store(@PathVariable("what") String what) {
            if (what.equals("this-computer")) {
                throw ConfigStoreException.ofThisComputer(READ_ONLY, new IOException("S123456789"));
            }
            throw new ConfigStoreException("The configuration store could not write /store/store.json. Nothing was changed.",
                    new IOException("S123456789"));
        }
    }

    private static MockHttpServletResponse store(String what) throws Exception {
        return TestMvc.standalone(new StoreController()).perform(get("/store/" + what)).andReturn().getResponse();
    }

    @Test
    void aProblemWithThisComputersFilesIsHeadedAsSuchWithItsOwnSentence() throws Exception {
        MockHttpServletResponse response = store("this-computer");
        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = JsonSafe.parse(response.getContentAsString()).node();
        assertThat(JsonSafe.text(body, "heading")).isEqualTo("A problem with this computer's files");
        assertThat(JsonSafe.text(body, "kind")).isEqualTo("CONFIGURATION");
        assertThat(JsonSafe.text(body, "title")).isEqualTo(READ_ONLY);
        assertThat(body.has("action")).as("no fixed action: the title says what to do").isFalse();
        assertThat(body.has("reference")).isFalse();
        assertThat(body.has("cause")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain("S123456789");
    }

    @Test
    void aStoreFailurePaveCouldNotTellKeepsTheAnswerItHad() throws Exception {
        MockHttpServletResponse response = store("unknown");
        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = JsonSafe.parse(response.getContentAsString()).node();
        assertThat(JsonSafe.text(body, "kind")).isEqualTo("INTERNAL");
        assertThat(JsonSafe.text(body, "heading")).isEqualTo("A failure of this tool, not of the service");
        assertThat(JsonSafe.text(body, "title")).isEqualTo("The configuration store could not write /store/store.json. Nothing was changed.");
        assertThat(JsonSafe.text(body, "action")).isEqualTo("Check the configuration store directory and its free space, then try again.");
        assertThat(response.getContentAsString()).doesNotContain("S123456789");
    }
}
