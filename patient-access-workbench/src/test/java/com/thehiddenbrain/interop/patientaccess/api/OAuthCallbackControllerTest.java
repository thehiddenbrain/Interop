package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.auth.SmartAuthCodeFlow;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.support.Forms;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthCallbackControllerTest extends ApiTestSupport {

    @Autowired
    EnvironmentService environments;

    @Autowired
    SmartAuthCodeFlow flow;

    @Test
    void unknownStateRendersTheFailurePage() throws Exception {
        mvc.perform(get("/oauth/callback").param("code", "abc").param("state", "not-a-known-state"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>Login failed</title>")))
                .andExpect(content().string(containsString("<h1>Login failed</h1>")))
                .andExpect(content().string(containsString("unknown or expired state; start the login again")))
                .andExpect(content().string(containsString("<a href=\"/ui/\">Back to the workbench</a>")));
    }

    @Test
    void authorizationServerErrorsAreEscaped() throws Exception {
        String id = environments.create(new EnvironmentInput("callback <env>", null, EnvironmentTier.SANDBOX, "https://fhir.example.org/r4",
                new EnvironmentInput.AuthInput(AuthMode.SMART_AUTHORIZATION_CODE, false, "https://as.example.org/authorize", "https://as.example.org/token",
                        "app", null, null, null, null, null, null, null, null, true, Map.of(), Map.of()),
                List.of(), List.of(), null, null, null, null, true, null)).id();
        Environment env = environments.require(id);
        String state = Forms.query(flow.start(env, "http://localhost:8090", null)).get("state");

        mvc.perform(get("/oauth/callback").param("state", state).param("error", "access_denied")
                        .param("error_description", "<script>alert('x')</script>"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<h1>Login failed for callback &lt;env&gt;</h1>")))
                .andExpect(content().string(containsString("authorization server returned access_denied: &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("<a href=\"/ui/#env=" + id + "\">")));
    }
}
