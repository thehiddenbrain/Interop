package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * A diagnosis code picked from the saved list carries its system into every step, beside a code
 * typed with none, and a facility picked as the servicing provider goes as the order's performer
 * with its NPI (D-322). The collection's requests state a reasonCode of another system, so a
 * picked code that went out with that system would show here. Mock profile, through the services;
 * placeholders only.
 */
class PickedDiagnosisAndFacilityStepsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";
    /** The system of the saved entry: not the sample's, and not the one the format rule gives. */
    private static final String SAVED_SYSTEM = "http://snomed.info/sct";
    private static final String SAMPLE_SYSTEM = "http://example.org/sample-diagnosis-system";

    static final String HOOK = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": "
            + "[{\"resource\": {\"resourceType\": \"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"system\": \"" + SAMPLE_SYSTEM
            + "\", \"code\": \"SAMPLE-DX\"}]}], \"performer\": [{\"reference\": \"Organization/sample-lab\"}]}}]}}, \"prefetch\": "
            + "{\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}, \"providers\": {\"resourceType\": \"Bundle\", \"type\": "
            + "\"searchset\", \"entry\": [{\"resource\": {\"resourceType\": \"Practitioner\", \"id\": \"prac-001\"}}, {\"resource\": "
            + "{\"resourceType\": \"Organization\", \"id\": \"sample-lab\"}}]}}}";
    static final String PACKAGE = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"order\", \"resource\": {\"resourceType\": "
            + "\"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"system\": \"" + SAMPLE_SYSTEM + "\", \"code\": \"SAMPLE-DX\"}]}], "
            + "\"performer\": [{\"reference\": \"Organization/sample-lab\"}]}}]}";
    static final String SUBMIT = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": "
            + "\"Claim\", \"id\": \"claim-1\", \"diagnosis\": [{\"sequence\": 1}]}}]}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ConfigFixture fixture() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid", "payerName",
                "Placeholder Payer");
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("CRD - Coverage check", "POST", "https://vendor.example.invalid/epa/cds-services/order-sign").body(HOOK),
                ConfigFixture.item("DTR - Fetch Questionnaire", "POST",
                        "https://vendor.example.invalid/r4/Questionnaire/$questionnaire-package").body(PACKAGE),
                ConfigFixture.item("PAS - Claim Submit", "POST", "https://vendor.example.invalid/r4/Claim/$submit").body(SUBMIT)), "[]");
        return fixture;
    }

    private RunSupport support() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    private static final MemberDetail MEMBER = MemberDetail.builder().memberId("S123456789").firstName("Given")
            .lastName("Placeholder-Surname").dateOfBirth("1970-01-31").gender("female").coverageStatus("active").build();
    private static final ProviderDetail PROVIDER = ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    /** A facility as a pick from the saved providers fills it: the organization and the NPI, no person's name. */
    private static final ProviderDetail FACILITY = ProviderDetail.builder().npi("9999999995").organizationName("Placeholder Sleep Laboratory")
            .build();
    private static final List<CodeInput> CODES = List.of(new CodeInput("95810", null, "Sleep study, attended"));
    /** A code typed with no system, then a code picked from the saved list with its system and description. */
    private static final List<CodeInput> DIAGNOSES = List.of(CodeInput.of("G47.33"),
            new CodeInput("78275009", SAVED_SYSTEM, "Obstructive sleep apnea syndrome"));

    private static JsonNode json(String body) {
        return JsonSafe.parse(body).node();
    }

    private static List<JsonNode> orders(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        root.at("/context/draftOrders/entry").forEach(entry -> out.add(entry.get("resource")));
        for (String list : List.of("parameter", "entry")) {
            for (JsonNode item : JsonSafe.elements(root, list)) {
                if ("ServiceRequest".equals(JsonSafe.presentText(item.get("resource"), "resourceType"))) {
                    out.add(item.get("resource"));
                }
            }
        }
        return out;
    }

    /** The typed code takes the system of the collection's reasonCode; the picked one keeps the system it was saved with. */
    private static void pickedKeepsItsSystem(JsonNode reasonCode) {
        assertThat(reasonCode.at("/0/coding/0/code").asString()).isEqualTo("G47.33");
        assertThat(reasonCode.at("/0/coding/0/system").asString()).isEqualTo(SAMPLE_SYSTEM);
        assertThat(reasonCode.at("/1/coding/0/code").asString()).isEqualTo("78275009");
        assertThat(reasonCode.at("/1/coding/0/system").asString()).isEqualTo(SAVED_SYSTEM);
        assertThat(reasonCode.at("/1/coding/0/display").asString()).isEqualTo("Obstructive sleep apnea syndrome");
    }

    @Test
    void theCoverageCheckSendsThePickedSystemAndTheFacilityAsThePerformer() {
        RunSupport support = support();
        CrdService service = new CrdService(support.epa(), fixture().gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)),
                new FakeCodeList(), support);
        RunEnvelope<?> run = service.run(CrdRunRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                .dateOfService("2026-11-30").diagnosisCodes(DIAGNOSES).servicingProvider(FACILITY).build(), RunOrigin.manual(), null);
        JsonNode sent = json(run.wire().request().body());
        List<JsonNode> orders = orders(sent);
        assertThat(orders).isNotEmpty();
        for (JsonNode order : orders) {
            pickedKeepsItsSystem(order.get("reasonCode"));
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("Organization/organization-servicing-1");
        }
        // The facility travels where the sample holds its Organization, with its NPI and its name.
        assertThat(sent.at("/prefetch/providers").toString()).contains("organization-servicing-1", "9999999995",
                "Placeholder Sleep Laboratory");
    }

    @Test
    void theQuestionnaireRequestSendsThePickedSystemAndTheFacilityAsThePerformer() {
        RunSupport support = support();
        DtrService service = new DtrService(support.epa(), fixture().gateway, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)),
                new FakeCodeList(), support);
        RunEnvelope<?> run = service.runPackage(DtrPackageRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                .dateOfService("2026-11-30").diagnosisCodes(DIAGNOSES).servicingProvider(FACILITY).build(), RunOrigin.manual(), null);
        JsonNode sent = json(run.wire().request().body());
        List<JsonNode> orders = orders(sent);
        assertThat(orders).isNotEmpty();
        for (JsonNode order : orders) {
            pickedKeepsItsSystem(order.get("reasonCode"));
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("Organization/organization-servicing-1");
        }
        assertThat(sent.toString()).contains("\"organization-servicing-1\"", "9999999995");
    }

    @Test
    void submitSendsThePickedSystemOnTheClaim() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        PasService service = new PasService(OnyxProperties.defaults(), fixture().gateway, new MockOnyxPasClient(scenarios,
                new MockExchanges(APP)), new FakeCodeList(), support(), beans.getBeanProvider(RunLedger.class));
        RunEnvelope<?> run = service.runSubmit(PasSubmitRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                .dateOfService("2026-11-30").diagnosisCodes(DIAGNOSES).servicingProvider(FACILITY).build(), RunOrigin.manual(), null);
        JsonNode claim = json(run.wire().request().body()).at("/entry/0/resource");
        // Submit follows no sample's system (D-321): the typed code takes the format's, the picked one its own.
        assertThat(claim.at("/diagnosis/0/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo(ICD);
        assertThat(claim.at("/diagnosis/1/diagnosisCodeableConcept/coding/0/code").asString()).isEqualTo("78275009");
        assertThat(claim.at("/diagnosis/1/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo(SAVED_SYSTEM);
        assertThat(claim.at("/careTeam/0/provider/reference").asString()).isNotBlank();
    }
}
