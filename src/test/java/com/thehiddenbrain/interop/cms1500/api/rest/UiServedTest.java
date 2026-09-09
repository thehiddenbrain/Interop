package com.thehiddenbrain.interop.cms1500.api.rest;

import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UiServedTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-ui");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void uiPagesScriptsAndSampleAreServed() throws Exception {
        // MockMvc does not execute forwards; the real-HTTP test checks the page content behind /ui/
        mvc.perform(get("/ui/")).andExpect(status().isOk()).andExpect(forwardedUrl("/ui/index.html"));
        mvc.perform(get("/ui/index.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("CMS-1500 Test Console")))
                .andExpect(content().string(containsString("id=\"btn-generate\"")));
        mvc.perform(get("/ui/app.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("window.cms1500Console")));
        mvc.perform(get("/ui/app.css")).andExpect(status().isOk());
        mvc.perform(get("/ui/samples/claim-full.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimNumber").value("CLM-2026-000123"));
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/ui/"));
        mvc.perform(get("/ui")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/ui/"));
        mvc.perform(get("/ui/missing.js")).andExpect(status().isNotFound());
    }
}
