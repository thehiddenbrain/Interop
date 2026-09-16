package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InfoControllerTest extends ApiTestSupport {

    @Test
    void infoReportsVersionFlagsAndIgs() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("patient-access-workbench"))
                .andExpect(jsonPath("$.version").value(not(emptyString())))
                .andExpect(jsonPath("$.igs.c4bb.version").value("2.1.0"))
                .andExpect(jsonPath("$.igs.smart.name").value("SMART App Launch"))
                .andExpect(jsonPath("$.ui").value(true))
                .andExpect(jsonPath("$.demo").value(false))
                .andExpect(jsonPath("$.basicAuth").value(false));
    }

    @Test
    void settingsExposeTheEffectiveConfigurationWithoutSecrets() throws Exception {
        mvc.perform(get("/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataDir").value(DATA_DIR.toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.dataDirPresent").value(true))
                .andExpect(jsonPath("$.masterKeySource").value(endsWith("master.key")))
                .andExpect(jsonPath("$.publicBaseUrl").value(""))
                .andExpect(jsonPath("$.http.userAgent").value("patient-access-workbench"))
                .andExpect(jsonPath("$.http.maxRetries").value(1))
                .andExpect(jsonPath("$.history.maxEntries").value(1000))
                .andExpect(jsonPath("$.search.pageSize").value(50))
                .andExpect(jsonPath("$.conformance.concurrency").value(4))
                .andExpect(jsonPath("$.demoEnabled").value(false))
                .andExpect(jsonPath("$.basicAuthEnabled").value(false))
                .andExpect(jsonPath("$.masterKey").doesNotExist());
    }
}
