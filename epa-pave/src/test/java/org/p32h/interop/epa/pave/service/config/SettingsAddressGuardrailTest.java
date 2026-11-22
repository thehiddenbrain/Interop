package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An address typed in an environment's settings is used at once and in the order of R-02, and it
 * is one of the environment's own addresses before the production guardrail: under a sandbox a
 * typed address that reads as production asks for the confirmation again (R-09) and a write to it
 * is refused without that confirmation; under prod it is among the production destinations, stays
 * remembered after the environment is removed, and a write to it waits for the unlock.
 */
class SettingsAddressGuardrailTest {

    private static final String TYPED_SIGN = "https://typed.vendor.example.invalid/cds-services/order-sign-typed";
    private static final String PROD_CLAIMS = "https://claims.prodvendor.example.invalid/fhir";

    @TempDir
    Path directory;

    private static MockHttpServletResponse save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse();
    }

    private static ObjectNode addresses(String id, String key, String address) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("id", id);
        body.putObject("addresses").put(key, address);
        return body;
    }

    private static String field(MockHttpServletResponse response, String name) throws Exception {
        JsonNode errors = JsonSafe.parse(response.getContentAsString()).node().get("fieldErrors");
        for (JsonNode error : errors) {
            if (name.equals(error.get("field").stringValue())) {
                return error.get("message").stringValue();
            }
        }
        return null;
    }

    @Test
    void aCallGoesToTheRequestNamedThenTheTypedAddressThenTheMappedRequestThenApplicationYml() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("crd.url", "https://yml.vendor.example.invalid").build();
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);

        // application.yml first, while nothing else names the operation.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).url().template())
                .isEqualTo("https://yml.vendor.example.invalid/cds-services/{{crdOrderSignServiceId}}");

        StoredCollection collection = fixture.collectionOf("Typed order", ConfigFixture.items(
                ConfigFixture.item("order-sign mapped", "POST", "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}")
                        .header("X-Vendor", "kept-with-the-request").body("{\"hook\": \"order-sign\"}")));
        PreparedCall mapped = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(mapped.requestName()).isEqualTo("order-sign mapped");

        assertThat(save(mvc, addresses(sandbox.id(), "CRD_ORDER_SIGN", TYPED_SIGN)).getStatus()).isEqualTo(200);
        PreparedCall typed = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        // The typed address replaces the mapped request wholly: its url, its headers and its body.
        assertThat(typed.url().value()).isEqualTo(TYPED_SIGN);
        assertThat(typed.requestName()).isNull();
        assertThat(typed.headers()).isEmpty();
        assertThat(typed.savedBody()).isNull();

        // A request named for this run still goes where it says.
        PreparedCall named = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                .withRequest(ConfigFixture.ref(collection, "order-sign mapped")));
        assertThat(named.url().value()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/order-sign-crd");

        // Clearing the typed address brings the mapped request back.
        assertThat(save(mvc, addresses(sandbox.id(), "CRD_ORDER_SIGN", "")).getStatus()).isEqualTo(200);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).requestName()).isEqualTo("order-sign mapped");
    }

    @Test
    void theSubscriptionAddressIsABaseEachOperationAppendsItsPathTo() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(save(mvc, addresses(sandbox.id(), "SUBSCRIPTION", "https://subs.vendor.example.invalid/fhir/")).getStatus()).isEqualTo(200);

        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE)).url().value())
                .isEqualTo("https://subs.vendor.example.invalid/fhir/Subscription");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-placeholder-1"))
                .url().value()).isEqualTo("https://subs.vendor.example.invalid/fhir/Subscription/sub-placeholder-1");
        // A get with no id lists, as it does from application.yml.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)).url().value())
                .isEqualTo("https://subs.vendor.example.invalid/fhir/Subscription");
    }

    @Test
    void aTypedAddressThatReadsAsProductionUnderASandboxAsksAgainAndAWriteToItIsRefusedWithoutTheConfirmation() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment sandbox = fixture.activeEnvironmentOf("Plain Sandbox", EnvironmentKind.SANDBOX,
                "baseUrl", "https://sandbox.vendor.example.invalid/fhir");
        assertThat(sandbox.acknowledgedNotProd()).isTrue();
        String prodLooking = "https://claims.prod-like.vendor.example.invalid/fhir/Claim/$submit";

        MockHttpServletResponse unconfirmed = save(mvc, addresses(sandbox.id(), "PAS_SUBMIT", prodLooking));
        assertThat(unconfirmed.getStatus()).isEqualTo(422);
        assertThat(field(unconfirmed, "acknowledgeNotProd")).startsWith("It looks like production because the address typed for PAS submit "
                + "contains \"prod\".");
        assertThat(fixture.store.snapshot().byName("Plain Sandbox").settings().addresses()).isEmpty();

        ObjectNode confirmed = addresses(sandbox.id(), "PAS_SUBMIT", prodLooking);
        confirmed.put("acknowledgeNotProd", true);
        assertThat(save(mvc, confirmed).getStatus()).isEqualTo(200);
        // Confirmed, the typed address is the environment's own and a write may go to it.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value()).isEqualTo(prodLooking);

        // An environment whose confirmation does not cover the typed address is refused the write.
        StoredEnvironment held = fixture.store.snapshot().byName("Plain Sandbox");
        StoredEnvironment withoutConfirmation = new StoredEnvironment(held.id(), held.name(), held.kind(), held.content(), held.file(),
                held.originalFileName(), held.uploadedAt(), false, held.productionSigns(), held.settings(), null, null);
        ConfigSnapshot snapshot = new ConfigSnapshot(fixture.store.epoch(), java.util.List.of(withoutConfirmation), held.name(),
                java.util.List.of());
        CallPlan plan = fixture.gateway.describe(snapshot, CallIntent.of(EpaOperation.PAS_SUBMIT));
        assertThat(plan.refusal()).isNotNull();
        assertThat(plan.refusal().reason()).contains("the url it would go to contains \"prod\"");
    }

    @Test
    void aTypedAddressOfAProductionEnvironmentIsAProductionDestinationRememberedAfterRemovalAndWaitsForTheUnlock() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).allowProdWrites().build();
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        StoredEnvironment production = fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        assertThat(save(mvc, addresses(production.id(), "PAS_SUBMIT", PROD_CLAIMS + "/Claim/$submit")).getStatus()).isEqualTo(200);

        assertThat(fixture.store.snapshot().productionOwners(org.p32h.interop.epa.pave.postman.UrlNormal.destination(PROD_CLAIMS
                + "/Claim/$submit"))).containsExactly("Onyx Production");

        // Locked until the unlock, as every write to production is.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)))
                .isInstanceOf(ProdWriteBlockedException.class);
        ProdUnlock unlock = fixture.gateway.unlock("Onyx Production");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock)).url().value())
                .isEqualTo(PROD_CLAIMS + "/Claim/$submit");

        // Removed, its typed address stays production, in this store and in one started afresh on the same folder.
        fixture.store.removeEnvironment("Onyx Production");
        ConfigFixture restarted = ConfigFixture.mock(directory);
        for (ConfigFixture each : java.util.List.of(fixture, restarted)) {
            assertThat(each.store.snapshot().productionOwners(org.p32h.interop.epa.pave.postman.UrlNormal.destination(PROD_CLAIMS
                    + "/Claim/$submit"))).containsExactly("Onyx Production");
            assertThatThrownBy(() -> each.environmentOf("Claims Sandbox", EnvironmentKind.SANDBOX, "baseUrl", PROD_CLAIMS))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("is an address of the production environment \"Onyx Production\"");
        }
    }

    @Test
    void aSandboxCannotTypeAnAddressOfAProductionEnvironment() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        fixture.environment(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        StoredEnvironment sandbox = fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);

        MockHttpServletResponse refused = save(mvc, addresses(sandbox.id(), "PAS_SUBMIT", "https://api.onyx.example.invalid/fhir/Claim/$submit"));
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(JsonSafe.parse(refused.getContentAsString()).node().get("title").stringValue())
                .contains("is an address of the production environment \"Onyx Production\"");
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").settings().addresses()).isEmpty();
    }
}
