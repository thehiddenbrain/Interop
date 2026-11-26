package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.service.config.LookupSettingsStore;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/config/lookups} (R-11, plan 5.3 and 5.5): each field error at its name, a blank
 * secret keeps the stored one, the remove box and the removal remove, a misspelt property is a 422
 * that names it, and no answer carries a secret.
 */
class LookupSettingsControllerTest {

    private static final String KEY = "member-key-placeholder-2727";

    @TempDir
    Path directory;

    private MockMvc mvc() {
        LookupSettingsStore store = new LookupSettingsStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())),
                TestMvc.mapper());
        return TestMvc.standalone(new LookupSettingsController(new LookupSettingsService(store, LookupProperties.defaults(),
                (org.p32h.interop.epa.pave.service.config.OperationGateway) null)));
    }

    private static final String SAVED = """
            {"enabled": true, "method": "POST", "address": "https://members.example.invalid/member/search",
             "bodyTemplate": "{\\"memberIds\\": [\\"{memberId}\\"], \\"from\\": \\"{dateOfService}\\"}", "dateFormat": "yyyyMMdd",
             "idForm": "SPACED",
             "signIn": {"method": "API_KEY", "headerName": "x-api-key", "apiKey": "%s"},
             "headers": [{"name": "x-trace", "value": "trace-placeholder-2828"}],
             "listPath": "members", "fieldPaths": {"dateOfBirth": "dob"}, "connectMs": 3000, "readMs": 5000}
            """;

    private static String withKey(String key) {
        return SAVED.formatted(key);
    }

    @Test
    void getAnswersBothLookupsNotStatedAtFirst() throws Exception {
        mvc().perform(get("/api/config/lookups")).andExpect(status().isOk())
                .andExpect(jsonPath("$.member.kind").value("member"))
                .andExpect(jsonPath("$.member.stated").value(false))
                .andExpect(jsonPath("$.member.inForce.from").value("not set"))
                .andExpect(jsonPath("$.member.fields[0].name").value("firstName"))
                .andExpect(jsonPath("$.member.defaults.connectMs").value(3000))
                .andExpect(jsonPath("$.provider.kind").value("provider"))
                .andExpect(jsonPath("$.provider.fields[5].name").value("fax"));
    }

    @Test
    void aSaveAnswersTheViewWithoutTheSecretAndABlankSecretKeepsIt() throws Exception {
        MockMvc mvc = mvc();
        String saved = mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(withKey(KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookup.stated").value(true))
                .andExpect(jsonPath("$.lookup.method").value("POST"))
                .andExpect(jsonPath("$.lookup.idForm").value("SPACED"))
                .andExpect(jsonPath("$.lookup.signIn.apiKey.set").value(true))
                .andExpect(jsonPath("$.lookup.headers[0].name").value("x-trace"))
                .andExpect(jsonPath("$.lookup.headers[0].value.set").value(true))
                .andExpect(jsonPath("$.lookup.inForce.words").value("POST, signs in with an API key header"))
                .andExpect(jsonPath("$.warnings.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain(KEY).doesNotContain("trace-placeholder-2828");

        // Blank keeps: the key and the header value stay stored.
        String again = withKey("").replace("trace-placeholder-2828", "");
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lookup.signIn.apiKey.set").value(true))
                .andExpect(jsonPath("$.lookup.headers[0].value.set").value(true))
                .andExpect(jsonPath("$.warnings.length()").value(0));
        String all = mvc.perform(get("/api/config/lookups")).andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain(KEY);
    }

    @Test
    void theRemoveBoxRemovesTheSecretAndARemovedHeaderIsGone() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(withKey(KEY))).andExpect(status().isOk());
        String noKey = withKey("").replace("\"apiKey\": \"\"", "\"apiKey\": \"\", \"removeApiKey\": true");
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(noKey))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("signIn.apiKey"));
        String noHeader = withKey("").replace("\"value\": \"trace-placeholder-2828\"", "\"value\": \"\", \"remove\": true");
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(noHeader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lookup.headers.length()").value(0));
    }

    @Test
    void removeBringsApplicationYmlBack() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(withKey(KEY))).andExpect(status().isOk());
        mvc.perform(delete("/api/config/lookups/member")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lookup.stated").value(false));
        mvc.perform(get("/api/config/lookups")).andExpect(jsonPath("$.member.stated").value(false));
    }

    @Test
    void eachFieldErrorIsNamed() throws Exception {
        MockMvc mvc = mvc();
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("method", "{\"method\": \"PATCH\", \"address\": \"https://x.example.invalid/{id}\"}");
        cases.put("address", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/members\"}");
        cases.put("bodyTemplate", "{\"method\": \"POST\", \"address\": \"https://x.example.invalid/s\", \"bodyTemplate\": \"{\\\"id\\\": 1}\"}");
        cases.put("dateFormat", "{\"method\": \"POST\", \"address\": \"https://x.example.invalid/s\", "
                + "\"bodyTemplate\": \"{\\\"id\\\": \\\"{memberId}\\\", \\\"d\\\": \\\"{dateOfService}\\\"}\"}");
        cases.put("idForm", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", \"idForm\": \"WIDE\"}");
        cases.put("signIn.method", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", \"signIn\": {\"method\": \"MAGIC\"}}");
        cases.put("signIn.headerName", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", "
                + "\"signIn\": {\"method\": \"API_KEY\", \"headerName\": \"bad name\", \"apiKey\": \"k-placeholder\"}}");
        cases.put("signIn.tokenUrl", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", "
                + "\"signIn\": {\"method\": \"CLIENT_CREDENTIALS\", \"tokenUrl\": \"not a url\", \"clientId\": \"c\", \"clientSecret\": \"s\"}}");
        cases.put("signIn.clientId", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", "
                + "\"signIn\": {\"method\": \"CLIENT_CREDENTIALS\", \"tokenUrl\": \"https://t.example.invalid/token\", \"clientSecret\": \"s\"}}");
        cases.put("listPath", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", \"listPath\": \"data..members\"}");
        cases.put("fieldPaths.dateOfBirth", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", "
                + "\"fieldPaths\": {\"dateOfBirth\": \"a.b.c.d.e.f.g.h.i.j.k.l.m\"}}");
        cases.put("fieldPaths.shoeSize", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", "
                + "\"fieldPaths\": {\"shoeSize\": \"size\"}}");
        cases.put("connectMs", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", \"connectMs\": 50}");
        cases.put("readMs", "{\"method\": \"GET\", \"address\": \"https://x.example.invalid/{id}\", \"readMs\": 60001}");
        for (Map.Entry<String, String> each : cases.entrySet()) {
            mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content(each.getValue()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem(each.getKey())));
        }
        // A POST never carries the identifier in its address.
        mvc.perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"POST\", "
                        + "\"address\": \"https://x.example.invalid/s/{id}\", \"bodyTemplate\": \"{\\\"id\\\": \\\"{memberId}\\\"}\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("address"));
        // A provider has no member ID form, and its body carries {npi}.
        mvc.perform(put("/api/config/lookups/provider").contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"POST\", "
                        + "\"address\": \"https://x.example.invalid/s\", \"bodyTemplate\": \"{\\\"id\\\": \\\"{memberId}\\\"}\", \"idForm\": \"PACKED\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("bodyTemplate")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("idForm")));
    }

    @Test
    void aMisspeltPropertyIsRefusedByName() throws Exception {
        String body = mvc().perform(put("/api/config/lookups/member").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\": \"GET\", \"adress\": \"https://x.example.invalid/{id}\"}"))
                .andExpect(status().isUnprocessableContent()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("adress");
    }

    @Test
    void anyOtherKindIsNotFound() throws Exception {
        mvc().perform(put("/api/config/lookups/patient").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc().perform(delete("/api/config/lookups/patient")).andExpect(status().isNotFound());
    }
}
