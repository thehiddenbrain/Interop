package com.thehiddenbrain.interop.cms1500.api.rest;

import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With cms1500.ui.enabled=false (the prod profile) neither the UI nor the settings API exist. */
@SpringBootTest(properties = "cms1500.ui.enabled=false")
@AutoConfigureMockMvc
class UiDisabledTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-ui-off");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void settingsApiAndUiAreAbsent() throws Exception {
        mvc.perform(get("/api/v1/settings")).andExpect(status().isNotFound());
        mvc.perform(get("/ui/")).andExpect(status().isNotFound());
        mvc.perform(get("/ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/claims")).andExpect(status().isOk());
    }
}
