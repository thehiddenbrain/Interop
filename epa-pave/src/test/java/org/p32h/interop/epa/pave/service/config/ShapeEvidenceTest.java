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
 * from, and for a call to a typed address the collection's own request for the operation; a stated
 * value wins; a call with no sample takes the default FHIR requires. The settings view names the same
 * evidence.
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

    /**
     * A typed address replaces where the call goes, and carries no header and no body of a request.
     * How the service wants the request arranged is still read from the collection's own request
     * for the operation: a tester who typed an address to correct it was otherwise sent back to
     * this tool's own arrangement, which the service refused (D-311).
     */
    @Test
    void aTypedAddressStillFollowsTheSampleOfTheCollectionsRequest() {
        ConfigFixture fixture = withSample(directory, WITHOUT_TYPE);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        RequestShapes.Choice choice = shapeOf(call);
        assertThat(choice.value()).isEqualTo(RequestShape.LEFT_OUT);
        assertThat(choice.from()).isEqualTo(RequestShapes.FROM_SAMPLE);
        assertThat(choice.requestName()).isEqualTo("order-sign Happy Path");
        assertThat(call.sampleName()).isEqualTo("order-sign Happy Path");
        assertThat(call.sampleBody()).isEqualTo(WITHOUT_TYPE);
        // The call itself is the typed one: it goes to the typed address and takes nothing else of the request.
        assertThat(call.url().toString()).contains("typed.vendor.example.invalid");
        assertThat(call.requestName()).isNull();
        assertThat(call.savedBody()).isNull();
        assertThat(call.headers()).isEmpty();
    }

    /**
     * The case met on the first run against the vendor's service: the address of the coverage
     * check was typed, the collection's sample carries the patient under "patient", and the
     * request still went in this tool's own named Bundles, where the service found no patient.
     */
    @Test
    void aCoverageCheckToATypedAddressTakesThePrefetchOfTheCollectionsSample() {
        String sample = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", "
                + "\"entry\": []}}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}, \"coverage\": "
                + "{\"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [{\"resource\": {\"resourceType\": \"Coverage\", "
                + "\"id\": \"cov-001\"}}]}}}";
        ConfigFixture fixture = withSample(directory, sample);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        RequestShapes.Choice prefetch = call.shapes().choice(RequestShape.CRD_PREFETCH);
        assertThat(prefetch.value()).isEqualTo(RequestShape.AS_SAMPLE);
        assertThat(prefetch.from()).isEqualTo(RequestShapes.FROM_SAMPLE);
        assertThat(prefetch.requestName()).isEqualTo("order-sign Happy Path");
        // What the coverage check reads its arrangement from is the sample, though the call has no body of its own.
        assertThat(org.p32h.interop.epa.pave.service.crd.PrefetchLayout.ofCall(call.sampleName(), call.sampleBody())).isNotNull();
        assertThat(call.savedBody()).isNull();
    }

    /**
     * A vendor's collection holds several coverage checks, each for a service of its own. The first
     * in the file carried the patient in a Bundle of its own name; the tester's address belonged to
     * the second, which carries it under "patient". The first was followed and the service found no
     * patient (D-312).
     */
    @Test
    void aTypedAddressFollowsTheRequestThatGoesToThatAddressNotTheFirstInTheFile() {
        String named = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": []}}, "
                + "\"prefetch\": {\"patientBundle\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": "
                + "[{\"resource\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}}]}}}";
        String bare = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", "
                + "\"entry\": []}}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}}}";
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Vendor sample", ConfigFixture.items(
                ConfigFixture.item("order-sign First service", "POST", "{{crdUrl}}/first/cds-services/order-sign").body(named),
                ConfigFixture.item("order-sign Second service", "POST", "{{crdUrl}}/second/cds-services/order-sign").body(bare)));
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");

        // The address of the second, typed with its placeholder and a trailing slash: the same address all the same.
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "{{crdUrl}}/second/cds-services/order-sign/"), null, null, null)), (stored, after) -> null);
        PreparedCall second = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(second.sampleName()).isEqualTo("order-sign Second service");
        assertThat(second.sampleBody()).isEqualTo(bare);
        assertThat(second.shapes().choice(RequestShape.CRD_DRAFT_ORDERS_TYPE).value()).isEqualTo(RequestShape.SENT);
        assertThat(second.shapes().choice(RequestShape.CRD_PREFETCH).requestName()).isEqualTo("order-sign Second service");

        // An address no request of the collection goes to: the first mapped, as before.
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        PreparedCall other = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(other.sampleName()).isEqualTo("order-sign First service");
        assertThat(other.shapes().choice(RequestShape.CRD_DRAFT_ORDERS_TYPE).value()).isEqualTo(RequestShape.LEFT_OUT);
    }

    /**
     * The address is often typed because the collection's own does not fill: its variable has no
     * value in the export. The request is then told by how its address ends, and the longer end
     * wins over a shorter one that also fits.
     */
    @Test
    void aRequestWhoseAddressDoesNotFillIsToldByHowItsAddressEnds() {
        String named = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": []}}, "
                + "\"prefetch\": {\"patientBundle\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": []}}}";
        String bare = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", "
                + "\"entry\": []}}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}}}";
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Vendor sample", ConfigFixture.items(
                ConfigFixture.item("order-sign First service", "POST", "{{noSuchBase}}/cds-services/order-sign").body(named),
                ConfigFixture.item("order-sign Second service", "POST", "{{noSuchBase}}/epa/cds-services/order-sign").body(bare)));
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/v1/API/epa/cds-services/order-sign"), null, null, null)), (stored, after) -> null);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        assertThat(call.sampleName()).isEqualTo("order-sign Second service");
        assertThat(call.shapes().choice(RequestShape.CRD_PREFETCH).requestName()).isEqualTo("order-sign Second service");
        // An end of one part, or none, tells nothing and is never used.
        assertThat(ConfigStoreGateway.writtenEnd("{{base}}/order-sign")).isNull();
        assertThat(ConfigStoreGateway.writtenEnd("{{base}}")).isNull();
        assertThat(ConfigStoreGateway.writtenEnd("https://vendor.example.invalid/EPA/cds-services/order-sign/?x=1"))
                .isEqualTo("/epa/cds-services/order-sign");
    }

    @Test
    void aTypedAddressWithNoRequestOfTheCollectionTakesTheDefault() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(shapeOf(call).value()).isEqualTo(RequestShape.SENT);
        assertThat(shapeOf(call).from()).isEqualTo(RequestShapes.FROM_DEFAULT);
        assertThat(call.sampleName()).isNull();
        assertThat(call.sampleBody()).isNull();
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
