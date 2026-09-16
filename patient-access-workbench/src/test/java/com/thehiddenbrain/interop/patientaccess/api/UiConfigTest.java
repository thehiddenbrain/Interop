package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UiConfigTest extends ApiTestSupport {

    @Test
    void rootAndBareUiPathRedirectToTheUi() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/"));
        mvc.perform(get("/ui"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/"));
    }
}
