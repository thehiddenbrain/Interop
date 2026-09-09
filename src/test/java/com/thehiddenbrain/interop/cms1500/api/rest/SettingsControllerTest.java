package com.thehiddenbrain.interop.cms1500.api.rest;

import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettingsControllerTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-settings");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
        registry.add("cms1500.settings-file", () -> DIRS.attachments.getParent().resolve("cfg/settings.json").toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    @Order(1)
    void showsCurrentSettingsAndFolderStatus() throws Exception {
        mvc.perform(get("/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("classpath:forms/cms1500-02-12.pdf"))
                .andExpect(jsonPath("$.attachments.root").value(DIRS.attachments.toString()))
                .andExpect(jsonPath("$.attachments.rootExists").value(true))
                .andExpect(jsonPath("$.attachments.allowedExtensions", hasSize(8)))
                .andExpect(jsonPath("$.attachments.unsupported").value("FAIL"))
                .andExpect(jsonPath("$.attachments.whenNone").value("WARN"))
                .andExpect(jsonPath("$.output.root").value(DIRS.output.toString()))
                .andExpect(jsonPath("$.output.writable").value(true))
                .andExpect(jsonPath("$.output.overwrite").value(true))
                .andExpect(jsonPath("$.form.uppercase").value(true))
                .andExpect(jsonPath("$.overridesFile").value(containsString("settings.json")))
                .andExpect(jsonPath("$.overridden").value(false));
    }

    @Test
    @Order(2)
    void partialUpdateIsAppliedAndStored() throws Exception {
        Path newOutput = DIRS.output.resolveSibling("bundles-2");
        String body = "{\"outputRoot\":\"" + newOutput.toString().replace("\\", "\\\\") + "\",\"whenNone\":\"FAIL\",\"overwrite\":false}";
        mvc.perform(put("/api/v1/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.root").value(newOutput.toString()))
                .andExpect(jsonPath("$.output.rootExists").value(false))
                .andExpect(jsonPath("$.output.overwrite").value(false))
                .andExpect(jsonPath("$.attachments.whenNone").value("FAIL"))
                .andExpect(jsonPath("$.attachments.root").value(DIRS.attachments.toString()))
                .andExpect(jsonPath("$.overridden").value(true));
        assertThat(Files.readString(DIRS.attachments.getParent().resolve("cfg/settings.json"))).contains("bundles-2");
    }

    @Test
    @Order(3)
    void invalidUpdateIs400WithDetails() throws Exception {
        mvc.perform(put("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentsRoot\":\" \",\"allowedExtensions\":[\"pdf\",\"bad ext\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("attachmentsRoot", "allowedExtensions[1]")));
        mvc.perform(put("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"/nowhere/cms1500.pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("template"))
                .andExpect(jsonPath("$.details[0].message").value(containsString("nowhere")));
        mvc.perform(put("/api/v1/settings").contentType(MediaType.APPLICATION_JSON).content("{\"unsupported\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("unsupported"));
        mvc.perform(get("/api/v1/settings"))
                .andExpect(jsonPath("$.attachments.whenNone").value("FAIL"))
                .andExpect(jsonPath("$.template").value("classpath:forms/cms1500-02-12.pdf"));
    }

    @Test
    @Order(4)
    void resetReturnsToYamlValues() throws Exception {
        mvc.perform(delete("/api/v1/settings/overrides"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.root").value(DIRS.output.toString()))
                .andExpect(jsonPath("$.output.overwrite").value(true))
                .andExpect(jsonPath("$.attachments.whenNone").value("WARN"))
                .andExpect(jsonPath("$.overridden").value(false));
        assertThat(DIRS.attachments.getParent().resolve("cfg/settings.json")).doesNotExist();
    }
}
