package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.postman.PostmanFiles;

/**
 * The shape of a request at run time (R-37): an environment that states no value takes the
 * evidence of the vendor's own sample, the saved body of the collection request its call comes
 * from; a stated value wins; a call with no sample, such as one to a typed address, takes the
 * default FHIR requires. The settings view names the same evidence.
 */
class ShapeEvidenceTest {

    private static final String WITHOUT_TYPE = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", "
            + "\"entry\": []}}}";
    private static final String WITH_TYPE = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", "
            + "\"type\": \"collection\", \"entry\": []}}}";

    @TempDir
    Path directory;

    private static RequestShapes.Choice shapeOf(PreparedCall call) {
        return call.shapes().choice(RequestShape.CRD_DRAFT_ORDERS_TYPE);
    }

    private static ConfigFixture withSample(Path directory, String body) {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Vendor sample", ConfigFixture.items(ConfigFixture.item("order-sign Happy Path", "POST",
                "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}").body(body)));
        return fixture;
    }

    @Test
    void aSampleWithoutTypeGivesLeftOutFromTheCollectionsSample() {
        ConfigFixture fixture = withSample(directory, WITHOUT_TYPE);
        RequestShapes.Choice choice = shapeOf(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)));
        assertThat(choice.value()).isEqualTo(RequestShape.LEFT_OUT);
        assertThat(choice.from()).isEqualTo(RequestShapes.FROM_SAMPLE);
        assertThat(choice.requestName()).isEqualTo("order-sign Happy Path");

        // The settings view names the same evidence, for the environment's card and its form.
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        var shown = fixture.service.settings(sandbox.id()).shapes().get(0);
        assertThat(shown.value()).isEqualTo(RequestShape.LEFT_OUT);
        assertThat(shown.from()).isEqualTo(RequestShapes.FROM_SAMPLE);
    }

    @Test
    void aSampleWithTypeGivesSentFromTheCollectionsSample() {
        ConfigFixture fixture = withSample(directory, WITH_TYPE);
        RequestShapes.Choice choice = shapeOf(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)));
        assertThat(choice.value()).isEqualTo(RequestShape.SENT);
        assertThat(choice.from()).isEqualTo(RequestShapes.FROM_SAMPLE);
    }

    @Test
    void aStatedValueWinsOverTheSample() {
        ConfigFixture fixture = withSample(directory, WITHOUT_TYPE);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, null, null, Map.of(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShape.SENT), null)),
                (stored, after) -> null);
        RequestShapes.Choice choice = shapeOf(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)));
        assertThat(choice.value()).isEqualTo(RequestShape.SENT);
        assertThat(choice.from()).isEqualTo(RequestShapes.FROM_ENVIRONMENT);
    }

    @Test
    void aTypedAddressHasNoSampleAndTakesTheDefault() {
        ConfigFixture fixture = withSample(directory, WITHOUT_TYPE);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        RequestShapes.Choice choice = shapeOf(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)));
        assertThat(choice.value()).isEqualTo(RequestShape.SENT);
        assertThat(choice.from()).isEqualTo(RequestShapes.FROM_DEFAULT);
    }

    @Test
    void theMemberIdRuleAndTheTimeoutsTheEnvironmentStatesReachTheCall() {
        ConfigFixture fixture = withSample(directory, WITH_TYPE);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).memberIdRule())
                .isEqualTo(org.p32h.interop.epa.pave.model.MemberIdRule.PACKED);
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, new Timeouts(null, 60000, null, Timeouts.FROM_ENVIRONMENT), null,
                        org.p32h.interop.epa.pave.model.MemberIdRule.SPACED, null, null)), (stored, after) -> null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(call.memberIdRule()).isEqualTo(org.p32h.interop.epa.pave.model.MemberIdRule.SPACED);
        assertThat(fixture.gateway.memberIdRule()).isEqualTo(org.p32h.interop.epa.pave.model.MemberIdRule.SPACED);
        assertThat(call.timeouts().readMs()).isEqualTo(60000);
        assertThat(call.timeouts().readFrom()).isEqualTo(Timeouts.FROM_ENVIRONMENT);
        assertThat(call.timeouts().connectFrom()).isEqualTo(Timeouts.FROM_APPLICATION);
        assertThat(fixture.gateway.vendorTimeouts().readMs()).isEqualTo(60000);
        // The longest a call may now wait is the vendor's, as the environment states it, the lookups' being shorter.
        assertThat(fixture.gateway.activeTimeouts().readMs()).isEqualTo(60000);
    }

    @Test
    void theSignInTheEnvironmentStatesReachesTheCallResolvedAndNotByAValueTypedForTheRun() {
        ConfigFixture fixture = withSample(directory, WITH_TYPE);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).signIn()).isNull();
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(SignInSettings.clientCredentials("{{tokenUrl}}", "{{clientId}}", "{{clientSecret}}",
                        "{{notDefinedAnywhere}}", SignInSettings.ClientAuthentication.BODY), null, null, null, null, null)),
                (stored, after) -> null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                .withRunValues(Map.of("tokenUrl", "https://elsewhere.example.invalid/token", "clientSecret", "typed-for-the-run")));
        SignInSettings signIn = call.signIn();
        assertThat(signIn.method()).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        assertThat(signIn.tokenUrl()).isEqualTo("https://auth.sandbox.onyx.example.invalid/oauth2/token");
        assertThat(signIn.clientId()).isEqualTo("pave-sandbox-client");
        assertThat(signIn.clientSecret()).isEqualTo("placeholder-sandbox-secret");
        assertThat(signIn.clientAuthentication()).isEqualTo(SignInSettings.ClientAuthentication.BODY);
        // A value with nothing to resolve it is kept as written, so the sign-in names what is missing.
        assertThat(signIn.scope()).isEqualTo("{{notDefinedAnywhere}}");
        assertThat(fixture.gateway.activeSignInMethod()).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        // The resolved secret is held, so a service that repeats it is masked.
        assertThat(call.heldSecrets().mask("echo placeholder-sandbox-secret").text()).doesNotContain("placeholder-sandbox-secret");
    }
}
