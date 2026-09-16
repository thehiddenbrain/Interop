package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestExceptionHandlerTest extends ApiTestSupport {

    @Test
    void unknownEndpointsAreNotFoundApiErrors() throws Exception {
        mvc.perform(get("/api/v1/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("no such endpoint: api/v1/no-such-endpoint"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void malformedJsonIsReportedWithTheFieldPath() throws Exception {
        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"tier\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("request body is not valid"))
                .andExpect(jsonPath("$.details[0].field").value("tier"))
                .andExpect(jsonPath("$.details[0].message").value("value 'NOPE' is not a valid EnvironmentTier"));

        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"auth\":{\"mode\":\"BAD\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("auth.mode"));

        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"headers\":[{\"name\":\"a\",\"secret\":\"maybe\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("headers[0].secret"));

        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("body"))
                .andExpect(jsonPath("$.details[0].message").value(containsString("Unexpected end-of-input")));
    }

    @Test
    void wrongMethodsMediaTypesAndParametersAreBadRequests() throws Exception {
        mvc.perform(delete("/api/v1/catalog"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/environments").contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("unsupported content type; send application/json"));
        mvc.perform(get("/api/v1/history").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(get("/api/v1/environments/x/fhir/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("url")));
    }
}
