package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * A call takes the payer and the FHIR server from the collection request it is sent from, at the
 * time of the call, when nothing else gives them (D-320). The case of the operator's photo: an
 * environment stored with no payer value and a collection whose coverage-check request states one;
 * the coverage check went with a Coverage that named no payer, and the vendor's service refused it.
 */
class PayerFromRequestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    /** A coverage-check request whose prefetch carries the Coverage alone under "coverage", with a payor of its own. */
    static final String HOOK = "{\"hook\": \"order-sign\", \"hookInstance\": \"{{$guid}}\", \"fhirServer\": \"https://ehr.example.org/fhir\", "
            + "\"context\": {\"userId\": \"Practitioner/pr-1\", \"patientId\": \"pat-001\", \"draftOrders\": {\"resourceType\": \"Bundle\", "
            + "\"entry\": []}}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}, \"coverage\": "
            + "{\"resourceType\": \"Coverage\", \"id\": \"cov-001\", \"payor\": [{\"identifier\": {\"system\": \"urn:example:payer-ids\", "
            + "\"value\": \"P32H-PAYER-1\"}, \"display\": \"Placeholder Payer\"}]}}}";
    static final String PACKAGE = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"coverage\", \"resource\": {\"resourceType\": "
            + "\"Coverage\", \"id\": \"cov-001\", \"payor\": [{\"reference\": \"Organization/org-payer-001\"}]}}]}";
    static final String SUBMIT = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": "
            + "\"Claim\", \"id\": \"claim-1\", \"insurer\": {\"reference\": \"Organization/org-payer-001\"}}}, {\"resource\": {\"resourceType\": "
            + "\"Organization\", \"id\": \"org-payer-001\", \"name\": \"Placeholder Payer\", \"identifier\": [{\"system\": "
            + "\"urn:example:payer-ids\", \"value\": \"P32H-PAYER-1\"}]}}]}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private static ConfigFixture withCollection(ConfigFixture fixture, String variablesJson) {
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("CRD - Coverage check", "POST", "https://vendor.example.invalid/epa/cds-services/order-sign").body(HOOK),
                ConfigFixture.item("DTR - Fetch Questionnaire", "POST",
                        "https://vendor.example.invalid/r4/Questionnaire/$questionnaire-package").body(PACKAGE),
                ConfigFixture.item("PAS - Claim Submit", "POST", "https://vendor.example.invalid/r4/Claim/$submit").body(SUBMIT)),
                variablesJson);
        return fixture;
    }

    /** An environment stored with no payer value and no FHIR server, as the operator's was. */
    private ConfigFixture photo() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        return withCollection(fixture, "[]");
    }

    private RunSupport support() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    private CrdService crd(ConfigFixture fixture) {
        RunSupport support = support();
        return new CrdService(support.epa(), fixture.gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support);
    }

    private DtrService dtr(ConfigFixture fixture) {
        RunSupport support = support();
        return new DtrService(support.epa(), fixture.gateway, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support);
    }

    private PasService pas(ConfigFixture fixture) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new PasService(OnyxProperties.defaults(), fixture.gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)),
                new FakeCodeList(), support(), beans.getBeanProvider(RunLedger.class));
    }

    private static MemberDetail member() {
        return MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                .gender("female").coverageStatus("active").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static final List<CodeInput> CODES = List.of(new CodeInput("95810", null, "Sleep study, attended"));

    private static CrdRunRequest check() {
        return CrdRunRequest.builder().member(member()).provider(provider()).procedureCodes(CODES).dateOfService("2026-03-20").build();
    }

    private static JsonNode json(String body) {
        return JsonSafe.parse(body).node();
    }

    private static JsonNode coverageOf(String body) {
        JsonNode root = json(body);
        return "Parameters".equals(JsonSafe.presentText(root, "resourceType")) ? RequestValues.parametersCoverage(root)
                : RequestValues.prefetchCoverage(root);
    }

    private static final String TAKEN_NOTE = "FHIR server, Payer name, Payer identifier and Payer identifier system come from the "
            + "collection's request \"CRD - Coverage check\", which this call is sent from: nothing else sets them.";

    @Test
    void theCoverageCheckOfThePhotoCarriesThePayerItsRequestStatesAndThePreviewShowsTheSameBody() {
        ConfigFixture fixture = photo();
        CrdService service = crd(fixture);

        RunEnvelope<CrdDetail> run = service.run(check(), RunOrigin.manual(), null);
        String sent = run.wire().request().body();
        JsonNode payor = coverageOf(sent).get("payor");
        assertThat(payor).hasSize(1);
        assertThat(payor.get(0).toString()).isEqualTo("{\"identifier\":{\"system\":\"urn:example:payer-ids\",\"value\":\"P32H-PAYER-1\"},"
                + "\"display\":\"Placeholder Payer\"}");
        assertThat(json(sent).get("fhirServer").asString()).isEqualTo("https://ehr.example.org/fhir");
        assertThat(run.detail().requestNotes()).contains(TAKEN_NOTE)
                .noneMatch(note -> note.contains("profile-coverage requires a payer"))
                .noneMatch(note -> note.contains("also carries payor"));

        RequestPreview preview = service.preview(check(), null);
        assertThat(coverageOf(preview.body())).isEqualTo(coverageOf(sent));
        assertThat(json(preview.body()).get("fhirServer").asString()).isEqualTo("https://ehr.example.org/fhir");
        assertThat(preview.notes()).contains(TAKEN_NOTE);

        // Nothing goes to the ledger that did not go there before: no payer, no body.
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.records().get(0))).doesNotContain("Placeholder Payer")
                .doesNotContain("P32H-PAYER-1");
    }

    @Test
    void theQuestionnaireRequestCarriesThePayerReferenceItsRequestStates() {
        ConfigFixture fixture = photo();
        DtrService service = dtr(fixture);
        DtrPackageRequest request = DtrPackageRequest.builder().member(member()).provider(provider()).procedureCodes(CODES)
                .dateOfService("2026-03-20").build();

        RunEnvelope<DtrDetail> run = service.runPackage(request, RunOrigin.manual(), null);
        JsonNode coverage = coverageOf(run.wire().request().body());
        assertThat(coverage.get("payor").toString()).isEqualTo("[{\"reference\":\"Organization/org-payer-001\"}]");
        assertThat(run.detail().requestNotes()).contains("Payer reference comes from the collection's request \"DTR - Fetch Questionnaire\", "
                + "which this call is sent from: nothing else sets it.");

        RequestPreview preview = service.previewPackage(request, null);
        assertThat(coverageOf(preview.body()).get("payor").toString()).isEqualTo("[{\"reference\":\"Organization/org-payer-001\"}]");
    }

    @Test
    void theInsurerOfASubmitIsTheOneItsRequestStatesAndPayerReferenceIsNotUsed() {
        ConfigFixture fixture = photo();
        PasService service = pas(fixture);
        PasSubmitRequest request = PasSubmitRequest.builder().member(member()).provider(provider()).procedureCodes(CODES)
                .dateOfService("2026-03-20").build();

        var run = service.runSubmit(request, RunOrigin.manual(), null);
        String sent = run.wire().request().body();
        JsonNode insurer = null;
        for (JsonNode entry : JsonSafe.elements(json(sent), "entry")) {
            JsonNode resource = entry.get("resource");
            if ("Organization".equals(JsonSafe.presentText(resource, "resourceType")) && CaseResources.INSURER_ID.equals(JsonSafe.presentText(resource, "id"))) {
                insurer = resource;
            }
        }
        assertThat(insurer).isNotNull();
        assertThat(insurer.get("name").asString()).isEqualTo("Placeholder Payer");
        assertThat(insurer.get("identifier").get(0).get("value").asString()).isEqualTo("P32H-PAYER-1");
        assertThat(insurer.get("identifier").get(0).get("system").asString()).isEqualTo("urn:example:payer-ids");
        assertThat(sent).doesNotContain("Organization/org-payer-001");
        assertThat(run.detail().requestNotes()).contains("Payer name, Payer identifier and Payer identifier system come from the "
                + "collection's request \"PAS - Claim Submit\", which this call is sent from: nothing else sets them.");

        RequestPreview preview = service.previewSubmit(request, null);
        assertThat(preview.body()).contains("Placeholder Payer", "P32H-PAYER-1");
    }

    @Test
    void compareWithARequestThatWorksBuildsThisToolsSideAsASendDoes() {
        ConfigFixture fixture = photo();
        CrdRunRequest pasted = CrdRunRequest.builder().member(member()).provider(provider()).procedureCodes(CODES).dateOfService("2026-03-20")
                .rawBody(HOOK).build();
        var comparison = crd(fixture).compare(pasted);
        assertThat(comparison.toolBody()).isNotNull();
        assertThat(coverageOf(comparison.toolBody()).get("payor").get(0).get("display").asString()).isEqualTo("Placeholder Payer");
    }

    // ---- the order of sources ----

    private static String payerNameOf(ConfigFixture fixture, Map<String, String> runValues) {
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN).withRunValues(runValues));
        return CaseResources.value(CaseResources.reading(call), "payerName");
    }

    @Test
    void everyOtherSourceWinsOverTheRequestAndWithNoneOfThemTheRequestGivesIt() {
        assertThat(payerNameOf(photo(), Map.of())).isEqualTo("Placeholder Payer");
        assertThat(payerNameOf(photo(), Map.of("payerName", "Typed For The Run"))).isEqualTo("Typed For The Run");
    }

    @Test
    void aValueStoredOnTheEnvironmentWins() {
        ConfigFixture fixture = photo();
        StoredEnvironment environment = fixture.store.snapshot().active();
        fixture.store.saveSettings(new ConfigStore.SettingsSave(environment.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, null, null, null, Map.of("payerName", "Stored Payer"))), (stored, after) -> null);
        assertThat(payerNameOf(fixture, Map.of())).isEqualTo("Stored Payer");
    }

    @Test
    void theExportsVariableWins() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "payerName", "Exported Payer");
        withCollection(fixture, "[]");
        assertThat(payerNameOf(fixture, Map.of())).isEqualTo("Exported Payer");
    }

    @Test
    void theCollectionsVariableWins() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        withCollection(fixture, ConfigFixture.variables("payerName", "Collection Payer"));
        assertThat(payerNameOf(fixture, Map.of())).isEqualTo("Collection Payer");
    }

    @Test
    void applicationYmlWins() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("variables.payerName", "Configured Payer").build();
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        withCollection(fixture, "[]");
        assertThat(payerNameOf(fixture, Map.of())).isEqualTo("Configured Payer");
    }

    // ---- what has nothing to ask ----

    @Test
    void aTypedAddressFollowsItsRequestAndApplicationYmlAloneHasNothingToAsk() {
        ConfigFixture fixture = photo();
        StoredEnvironment environment = fixture.store.snapshot().active();
        fixture.store.saveSettings(new ConfigStore.SettingsSave(environment.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/epa/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        // The typed address goes to the request that goes there, which is the one its values are read from (D-312).
        assertThat(payerNameOf(fixture, Map.of())).isEqualTo("Placeholder Payer");

        ConfigFixture bare = ConfigFixture.on(directory.resolve("bare")).onyx("crd.url", "https://vendor.example.invalid/epa").build();
        bare.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        PreparedCall call = bare.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(call.requestValues()).isEqualTo(RequestValues.ForCall.NONE);
        assertThat(CaseResources.value(CaseResources.reading(call), "payerName")).isNull();
    }

    @Test
    void aRequestWithNoBodyOrABodyThatIsNotJsonGivesNothingAndTheCallStillPrepares() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("CRD - Coverage check", "POST", "https://vendor.example.invalid/epa/cds-services/order-sign"),
                ConfigFixture.item("PAS - Claim Submit", "POST", "https://vendor.example.invalid/r4/Claim/$submit").body("not { JSON")));
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).requestValues()).isEqualTo(RequestValues.ForCall.NONE);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).requestValues()).isEqualTo(RequestValues.ForCall.NONE);
        // Asked with no call in hand, the shapes still answer (D-311).
        assertThat(fixture.gateway.activeShapes()).isNotNull();
    }

    @Test
    void withNoEnvironmentAndNoCollectionAPreviewStillAnswers() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        RequestPreview preview = crd(fixture).preview(check(), null);
        assertThat(preview.notes()).anyMatch(note -> note.contains("profile-coverage requires a payer"))
                .noneMatch(note -> note.contains("which this call is sent from"));
    }

    @Test
    void aPayerTheRequestStatesThroughAnUndefinedPlaceholderIsSaidAndNotSent() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("CRD - Coverage check", "POST",
                "https://vendor.example.invalid/epa/cds-services/order-sign").body(HOOK.replace("\"Placeholder Payer\"", "\"{{vendorPayer}}\"")
                .replace("\"P32H-PAYER-1\"", "\"{{vendorPayerId}}\""))));
        RequestPreview preview = crd(fixture).preview(check(), null);
        assertThat(coverageOf(preview.body()).has("payor")).isFalse();
        assertThat(preview.notes()).contains("The collection's request \"CRD - Coverage check\" writes Payer name with {{vendorPayer}}, which "
                + "nothing defines, so it is not taken.");
    }
}
