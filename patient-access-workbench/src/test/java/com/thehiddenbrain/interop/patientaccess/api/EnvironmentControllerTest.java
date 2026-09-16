package com.thehiddenbrain.interop.patientaccess.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EnvironmentControllerTest extends ApiTestSupport {

    private JsonNode create(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content(environmentJson(name)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createReturns201WithSecretsMasked() throws Exception {
        JsonNode env = create("api-create");
        assertThat(env.path("id").asText()).hasSize(20);
        assertThat(env.path("version").asInt()).isEqualTo(1);
        assertThat(env.path("vendor").asText()).isEqualTo("Onyx SAFHIR");
        assertThat(env.path("auth").path("mode").asText()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(env.path("auth").path("clientSecret").path("set").asBoolean()).isTrue();
        assertThat(env.path("auth").path("clientSecret").path("hint").asText()).isEqualTo("***ABCD");
        assertThat(env.path("auth").path("staticToken").path("set").asBoolean()).isFalse();
        assertThat(env.path("headers").get(0).path("secret").asBoolean()).isTrue();
        assertThat(env.path("headers").get(0).path("secretValue").path("hint").asText()).isEqualTo("***1234");
        assertThat(env.path("headers").get(0).has("value")).isFalse();
        assertThat(env.toString()).doesNotContain("client-secret-value").doesNotContain("api-key-value").doesNotContain("\"enc\"");

        String id = env.path("id").asText();
        mvc.perform(get("/api/v1/environments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("api-create"))
                .andExpect(jsonPath("$.auth.clientSecret.set").value(true))
                .andExpect(jsonPath("$.auth.clientSecret.hint").value("***ABCD"))
                .andExpect(jsonPath("$.createdAt").isString());
        MvcResult list = mvc.perform(get("/api/v1/environments")).andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString()).contains("\"api-create\"").doesNotContain("client-secret-value");
    }

    @Test
    void unknownEnvironmentIs404AndInvalidBodiesAre400() throws Exception {
        mvc.perform(get("/api/v1/environments/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("environment 'does-not-exist' not found"))
                .andExpect(jsonPath("$.details").isArray());

        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"PROD\",\"fhirBaseUrl\":\"http://fhir.example.org\",\"fhir\":{\"trustAllCertificates\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("environment rejected")))
                .andExpect(jsonPath("$.details[?(@.field == 'name')].message").value("is required"))
                .andExpect(jsonPath("$.details[?(@.field == 'fhirBaseUrl')].message").value("must use https for a PROD environment"))
                .andExpect(jsonPath("$.details[?(@.field == 'fhir.trustAllCertificates')]").exists());

        create("api-dup");
        mvc.perform(post("/api/v1/environments").contentType(MediaType.APPLICATION_JSON).content(environmentJson("API-DUP")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("name"))
                .andExpect(jsonPath("$.details[0].message").value("another environment has this name"));
    }

    @Test
    void updateHonoursVersionsAndSecretMergeRules() throws Exception {
        String id = create("api-update").path("id").asText();

        mvc.perform(put("/api/v1/environments/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"stale\",\"version\":42}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("you sent 42")));

        // null keeps the secret, only the fields sent change
        mvc.perform(put("/api/v1/environments/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"edited\",\"auth\":{\"clientSecret\":null,\"scopes\":\"system/*.rs\"},\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.notes").value("edited"))
                .andExpect(jsonPath("$.name").value("api-update"))
                .andExpect(jsonPath("$.auth.scopes").value("system/*.rs"))
                .andExpect(jsonPath("$.auth.clientSecret.set").value(true))
                .andExpect(jsonPath("$.auth.clientSecret.hint").value("***ABCD"))
                .andExpect(jsonPath("$.headers", hasSize(1)));

        // a new value replaces it (and the hint changes)
        mvc.perform(put("/api/v1/environments/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auth\":{\"clientSecret\":\"rotated-secret-value-WXYZ\"},\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.auth.clientSecret.hint").value("***WXYZ"));

        // "" clears it, which the CLIENT_CREDENTIALS validation refuses
        mvc.perform(put("/api/v1/environments/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auth\":{\"clientSecret\":\"\"},\"version\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("auth.clientSecret"));
        mvc.perform(put("/api/v1/environments/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auth\":{\"mode\":\"NONE\",\"clientSecret\":\"\"},\"headers\":[],\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.mode").value("NONE"))
                .andExpect(jsonPath("$.auth.clientSecret.set").value(false))
                .andExpect(jsonPath("$.headers", hasSize(0)));

        mvc.perform(put("/api/v1/environments/nope").contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void duplicateAndDeleteRoundTrip() throws Exception {
        String id = create("api-copy-source").path("id").asText();

        mvc.perform(post("/api/v1/environments/" + id + "/duplicate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)))
                .andExpect(jsonPath("$.name").value("api-copy-source (copy)"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.auth.clientSecret.set").value(true))
                .andExpect(jsonPath("$.headers[0].secretValue.set").value(true));
        MvcResult named = mvc.perform(post("/api/v1/environments/" + id + "/duplicate").param("name", "api-copy-prod"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("api-copy-prod"))
                .andReturn();
        String copyId = mapper.readTree(named.getResponse().getContentAsString()).path("id").asText();

        mvc.perform(delete("/api/v1/environments/" + copyId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/environments/" + copyId)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/environments/" + copyId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/environments/missing/duplicate")).andExpect(status().isNotFound());
    }
}
