package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A value typed in an environment's settings (R-42) is a layer between the values typed for one run
 * and the export: it wins over the export's value of the same name, loses to the run's, and reaches
 * every reader of a variable with no change of its own. A name that reads as a secret is refused at
 * its field, and the typed callback url stands in for application.yml's.
 */
class ValuesLayerTest {

    @TempDir
    Path directory;

    private static MockHttpServletResponse save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse();
    }

    private static ObjectNode values(String id, String... namesAndValues) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("id", id);
        ObjectNode values = body.putObject("values");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return body;
    }

    @Test
    void aTypedValueWinsOverTheExportAndLosesToTheRun() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(fixture.gateway.resolve("{{crdOrderSignServiceId}}", Map.of()).value()).isEqualTo("order-sign-crd");

        assertThat(save(mvc, values(sandbox.id(), "crdOrderSignServiceId", "order-sign-typed", "payerName", "Placeholder Payer"))
                .getStatus()).isEqualTo(200);
        assertThat(fixture.gateway.resolve("{{crdOrderSignServiceId}}", Map.of()).value()).isEqualTo("order-sign-typed");
        // A value the export does not hold is there too, for every reader of a variable.
        assertThat(fixture.gateway.resolve("{{payerName}}", Map.of()).value()).isEqualTo("Placeholder Payer");
        assertThat(fixture.gateway.resolve("{{crdOrderSignServiceId}}", Map.of("crdOrderSignServiceId", "order-sign-run")).value())
                .isEqualTo("order-sign-run");
        // A url built from it is built with it, and a body resolved for the call reads it.
        fixture.collectionOf("Values", ConfigFixture.items(ConfigFixture.item("order-sign", "POST",
                "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}")));
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(call.url().value()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/order-sign-typed");
        assertThat(call.resolve("{\"payer\": \"{{payerName}}\"}").value()).isEqualTo("{\"payer\": \"Placeholder Payer\"}");

        // Cleared, the export's value applies again.
        assertThat(save(mvc, values(sandbox.id(), "crdOrderSignServiceId", "")).getStatus()).isEqualTo(200);
        assertThat(fixture.gateway.resolve("{{crdOrderSignServiceId}}", Map.of()).value()).isEqualTo("order-sign-crd");
    }

    @Test
    void theActiveSignInIsResolvedWithTheTypedValuesForTheStateAndTheTest() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        // An environment that states no method leaves onyx.auth to decide.
        assertThat(fixture.gateway.activeSignIn()).isNull();

        ObjectNode body = values(sandbox.id(), "signInHost", "https://auth.example.invalid");
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "{{signInHost}}/oauth/token");
        signIn.put("clientId", "placeholder-client");
        signIn.put("clientSecret", "{{undefinedSecret}}");
        assertThat(save(mvc, body).getStatus()).isEqualTo(200);

        SignInSettings active = fixture.gateway.activeSignIn();
        assertThat(active.method()).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        assertThat(active.tokenUrl()).isEqualTo("https://auth.example.invalid/oauth/token");
        assertThat(active.clientId()).isEqualTo("placeholder-client");
        // Kept as written, as for a call, so the token service names what is missing instead of sending an empty secret.
        assertThat(active.clientSecret()).isEqualTo("{{undefinedSecret}}");
    }

    @Test
    void aValueOfAnEnvironmentThatIsNotActiveReachesNoCall() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment uat = fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(save(mvc, values(uat.id(), "payerName", "Placeholder Payer")).getStatus()).isEqualTo(200);
        assertThat(fixture.gateway.resolve("{{payerName}}", Map.of()).resolved()).isFalse();
    }

    @Test
    void aNameThatReadsAsASecretIsRefusedAtItsField() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        MockHttpServletResponse refused = save(mvc, values(sandbox.id(), "apiKey", "P32H-placeholder-key"));
        assertThat(refused.getStatus()).isEqualTo(422);
        JsonNode error = JsonSafe.parse(refused.getContentAsString()).node().get("fieldErrors").get(0);
        assertThat(error.get("field").stringValue()).isEqualTo("values.apiKey");
        assertThat(error.get("message").stringValue()).isEqualTo("This looks like a secret. Put it in Sign-in, or keep it in the Postman "
                + "environment, where it is masked.");
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").settings().values()).isEmpty();
    }

    @Test
    void theTypedCallbackUrlStandsInForApplicationYmlsAndACredentialInItIsRefused() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("subscription.callback-url", "https://yml.example.invalid/api/pas/notifications")
                .build();
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE)).callbackUrl()).isNull();

        String typed = "https://tester.example.invalid/api/pas/notifications";
        assertThat(save(mvc, values(sandbox.id(), "callbackUrl", typed)).getStatus()).isEqualTo(200);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE));
        assertThat(call.callbackUrl()).isEqualTo(typed);
        assertThat(call.resolve(call.callbackUrl()).value()).isEqualTo(typed);
        // Another environment made active types none, and application.yml's applies again.
        fixture.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE)).callbackUrl()).isNull();

        // A credential typed into the callback url is refused at its field: a value is never a secret.
        MockHttpServletResponse refused = save(mvc, values(sandbox.id(), "callbackUrl", typed + "?token=P32H-placeholder-callback-token"));
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(JsonSafe.parse(refused.getContentAsString()).node().get("fieldErrors").get(0).get("field").stringValue())
                .isEqualTo("values.callbackUrl");
    }
}
