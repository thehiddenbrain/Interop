package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorControllerTest extends ApiTestSupport {

    @Test
    void listsPresetsFromTheBundledFileAndServesOneByKey() throws Exception {
        mvc.perform(get("/api/v1/vendors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("bundled vendors.yaml"))
                .andExpect(jsonPath("$.vendors[?(@.key == 'onyx-safhir')].name").value("Onyx SAFHIR"));

        mvc.perform(get("/api/v1/vendors/onyx-safhir"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.igBaseUrls.c4bb").value("{host}/v1/api/carin-bb"))
                .andExpect(jsonPath("$.auth.tokenEndpoint").value("{host}/v1/token"));

        mvc.perform(get("/api/v1/vendors/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
