package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityHeadersFilterTest extends ApiTestSupport {

    @Test
    void apiResponsesCarryDefensiveHeadersAndNoStore() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    @Test
    void uiPagesGetTheCspButSwaggerDoesNot() throws Exception {
        // index.html may not be present in this build; the headers are set before the resource is resolved
        mvc.perform(get("/ui/"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().doesNotExist("Cache-Control"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
        mvc.perform(get("/api-docs"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }
}
