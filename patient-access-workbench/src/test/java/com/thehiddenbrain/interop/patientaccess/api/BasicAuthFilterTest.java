package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Own context: basic auth switched on (the other API tests share the open context). */
@TestPropertySource(properties = {"paw.security.basic.enabled=true", "paw.security.basic.username=tester", "paw.security.basic.password=pw-secret"})
class BasicAuthFilterTest extends ApiTestSupport {

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requestsWithoutOrWithWrongCredentialsAreChallenged() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic realm=\"patient-access-workbench\"")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("authentication required"));
        mvc.perform(get("/api/v1/info").header("Authorization", basic("tester", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get("/api/v1/info").header("Authorization", "Bearer sometoken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/info").header("Authorization", "Basic not-base64!"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ui/"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctCredentialsPassAndHealthStaysOpen() throws Exception {
        mvc.perform(get("/api/v1/info").header("Authorization", basic("tester", "pw-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basicAuth").value(true));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }
}
