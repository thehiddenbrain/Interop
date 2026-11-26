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
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The diagnosis and the servicing provider go out with every step, each in its own place (D-321):
 * the order's reasonCode and performer in the coverage check and the questionnaire request, the
 * Claim's diagnosis and care team in Submit. Run on the mock profile through the services, against
 * a stored collection whose requests carry both, written for the test with placeholders only. A
 * diagnosis code is the member's own data: the marked codes reach the wire and nothing else.
 */
@ExtendWith(OutputCaptureExtension.class)
class DiagnosisAndServicingStepsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    /** Codes no fixture, scenario or sentence of this tool holds, so any place they turn up was put there by a run. */
    private static final String MARK_ICD = "Q87.89";
    private static final String MARK_OTHER = "DXMARK7";

    static final String HOOK = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": "
            + "[{\"resource\": {\"resourceType\": \"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"system\": "
            + "\"http://hl7.org/fhir/sid/icd-10-cm\", \"code\": \"SAMPLE-DX\"}]}], \"performer\": [{\"reference\": "
            + "\"PractitionerRole/sample-role\"}]}}]}}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}, "
            + "\"providers\": {\"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [{\"resource\": {\"resourceType\": "
            + "\"Practitioner\", \"id\": \"prac-001\"}}, {\"resource\": {\"resourceType\": \"PractitionerRole\", \"id\": \"sample-role\"}}]}}}";
    static final String PACKAGE = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"order\", \"resource\": {\"resourceType\": "
            + "\"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"code\": \"SAMPLE-DX\"}]}], \"performer\": [{\"reference\": "
            + "\"Practitioner/prac-001\"}]}}]}";
    static final String SUBMIT = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": "
            + "\"Claim\", \"id\": \"claim-1\", \"diagnosis\": [{\"sequence\": 1}], \"careTeam\": [{\"provider\": {\"reference\": "
            + "\"PractitionerRole/sample-role\"}}]}}]}";

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

    private static final MemberDetail MEMBER = MemberDetail.builder().memberId("S123456789").firstName("Given")
            .lastName("Placeholder-Surname").dateOfBirth("1970-01-31").gender("female").coverageStatus("active").build();
    private static final ProviderDetail PROVIDER = ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    private static final ProviderDetail SERVICING = ProviderDetail.builder().npi("9999999995").firstName("Sam").lastName("Servicing").build();
    private static final List<CodeInput> CODES = List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601"));
    private static final List<CodeInput> DIAGNOSES = List.of(CodeInput.of(MARK_ICD), CodeInput.of(MARK_OTHER));

    private static JsonNode json(String body) {
        return JsonSafe.parse(body).node();
    }

    /**
     * Every ServiceRequest of a body: in context.draftOrders, among the parameters, or among the entries. Each without
     * its authoredOn: that is the second the body was built in, which a run and its preview do not share, and a
     * comparison that kept it failed whenever a second began between the two.
     */
    private static List<JsonNode> orders(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        root.at("/context/draftOrders/entry").forEach(entry -> out.add(unstamped(entry.get("resource"))));
        for (String list : List.of("parameter", "entry")) {
            for (JsonNode item : JsonSafe.elements(root, list)) {
                if ("ServiceRequest".equals(JsonSafe.presentText(item.get("resource"), "resourceType"))) {
                    out.add(unstamped(item.get("resource")));
                }
            }
        }
        return out;
    }

    private static JsonNode unstamped(JsonNode order) {
        ObjectNode copy = ((ObjectNode) order).deepCopy();
        copy.remove("authoredOn");
        return copy;
    }

    /** What the run says in words, everything but the wire view, which shows what was sent. */
    private static String words(RunEnvelope<?> run) {
        ObjectNode tree = (ObjectNode) JsonSafe.mapper().valueToTree(run);
        tree.remove("wire");
        return tree.toString();
    }

    @Test
    void theCoverageCheckCarriesBothOnEveryOrderAndThePreviewTheSame(CapturedOutput output) {
        CrdService service = crd(fixture());
        CrdRunRequest request = CrdRunRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES).dateOfService("2026-03-20")
                .diagnosisCodes(DIAGNOSES).servicingProvider(SERVICING).build();

        RunEnvelope<?> run = service.run(request, RunOrigin.manual(), null);
        JsonNode sent = json(run.wire().request().body());
        List<JsonNode> orders = orders(sent);
        assertThat(orders).hasSize(2);
        for (JsonNode order : orders) {
            assertThat(order.at("/reasonCode/0/coding/0/code").asString()).isEqualTo(MARK_ICD);
            assertThat(order.at("/reasonCode/0/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
            assertThat(order.at("/reasonCode/1/coding/0/code").asString()).isEqualTo(MARK_OTHER);
            // The sample names its performer as a role, so the servicing person goes as its role.
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-servicing-1");
        }
        // The servicing practitioner travels in the prefetch, in the Bundle where the sample holds practitioners.
        assertThat(sent.at("/prefetch/providers").toString()).contains("practitioner-servicing-1", "9999999995");

        RequestPreview preview = service.preview(request, null);
        assertThat(orders(json(preview.body()))).isEqualTo(orders);
        assertThat(sent.at("/prefetch/providers")).isEqualTo(json(preview.body()).at("/prefetch/providers"));

        assertNoCode(words(run) + " " + String.join(" ", preview.notes()), output);
    }

    @Test
    void theQuestionnaireRequestCarriesBothOnItsOrderAndThePreviewTheSame(CapturedOutput output) {
        DtrService service = dtr(fixture());
        DtrPackageRequest request = DtrPackageRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                .dateOfService("2026-03-20").diagnosisCodes(DIAGNOSES).servicingProvider(SERVICING).build();

        RunEnvelope<?> run = service.runPackage(request, RunOrigin.manual(), null);
        JsonNode sent = json(run.wire().request().body());
        List<JsonNode> orders = orders(sent);
        assertThat(orders).hasSize(2);
        for (JsonNode order : orders) {
            assertThat(order.at("/reasonCode/0/coding/0/code").asString()).isEqualTo(MARK_ICD);
            assertThat(order.at("/reasonCode/1/coding/0/code").asString()).isEqualTo(MARK_OTHER);
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("Practitioner/practitioner-servicing-1");
        }
        List<String> referenced = new ArrayList<>();
        for (JsonNode parameter : JsonSafe.elements(sent, "parameter")) {
            if ("referenced".equals(JsonSafe.presentText(parameter, "name")) || parameter.has("resource")) {
                referenced.add(JsonSafe.presentText(parameter.get("resource"), "resourceType") + "/"
                        + JsonSafe.presentText(parameter.get("resource"), "id"));
            }
        }
        assertThat(referenced).contains("Practitioner/practitioner-1", "Practitioner/practitioner-servicing-1");

        RequestPreview preview = service.previewPackage(request, null);
        assertThat(orders(json(preview.body()))).isEqualTo(orders);
        assertThat(preview.notes()).contains("The servicing provider goes as the order's performer, Practitioner/practitioner-servicing-1.",
                "The servicing provider's Practitioner travels among the referenced resources, as the requesting provider's do.");

        assertNoCode(words(run) + " " + String.join(" ", preview.notes()), output);
    }

    @Test
    void submitSendsTheDiagnosisOnTheClaimAndTheServicingProviderOnTheCareTeamAndNothingOnItsOrder(CapturedOutput output) {
        PasService service = pas(fixture());
        PasSubmitRequest request = PasSubmitRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                .dateOfService("2026-03-20").diagnosisCodes(DIAGNOSES).servicingProvider(SERVICING).build();

        RunEnvelope<?> run = service.runSubmit(request, RunOrigin.manual(), null);
        JsonNode sent = json(run.wire().request().body());
        JsonNode claim = sent.at("/entry/0/resource");
        assertThat(claim.at("/diagnosis/0/diagnosisCodeableConcept/coding/0/code").asString()).isEqualTo(MARK_ICD);
        assertThat(claim.at("/diagnosis/1/diagnosisCodeableConcept/coding/0/code").asString()).isEqualTo(MARK_OTHER);
        // Submit names each entry by its full url, so the care team's reference is the full url of the servicing provider's role.
        String careTeam = claim.at("/careTeam/0/provider/reference").asString();
        String named = null;
        for (JsonNode entry : JsonSafe.elements(sent, "entry")) {
            if (careTeam.equals(JsonSafe.presentText(entry, "fullUrl"))) {
                named = entry.at("/resource/resourceType").asString() + "/" + entry.at("/resource/id").asString();
            }
        }
        assertThat(named).isEqualTo("PractitionerRole/practitioner-role-servicing-1");
        for (JsonNode order : orders(sent)) {
            assertThat(order.has("reasonCode")).isFalse();
            assertThat(order.has("performer")).isFalse();
        }
        assertNoCode(words(run), output);
    }

    @Test
    void eachStepRefusesABadServicingNpiAndABlankDiagnosisAtItsField() {
        ConfigFixture fixture = fixture();
        ProviderDetail bad = ProviderDetail.builder().npi("1234567890").lastName("Servicing").build();
        List<CodeInput> blank = List.of(CodeInput.of(" "));
        List<Runnable> sends = List.of(
                () -> crd(fixture).run(CrdRunRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                        .servicingProvider(bad).diagnosisCodes(blank).build(), RunOrigin.manual(), null),
                () -> dtr(fixture).runPackage(DtrPackageRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                        .servicingProvider(bad).diagnosisCodes(blank).build(), RunOrigin.manual(), null),
                () -> pas(fixture).runSubmit(PasSubmitRequest.builder().member(MEMBER).provider(PROVIDER).procedureCodes(CODES)
                        .servicingProvider(bad).diagnosisCodes(blank).build(), RunOrigin.manual(), null));
        for (Runnable send : sends) {
            try {
                send.run();
                throw new AssertionError("A bad servicing NPI and a blank diagnosis were not refused");
            } catch (ValidationException refused) {
                assertThat(refused.fieldErrors()).extracting(error -> error.field())
                        .contains("servicingProvider.npi", "diagnosisCodes[0].code");
            }
        }
        assertThat(ledger.records()).isEmpty();
    }

    /** No marked code in what the run says, in the ledger, or in the log. */
    private void assertNoCode(String words, CapturedOutput output) {
        String ledgerLines = JsonSafe.mapper().writeValueAsString(ledger.records());
        for (String code : List.of(MARK_ICD, MARK_OTHER)) {
            assertThat(words).as("the run's words").doesNotContain(code);
            assertThat(ledgerLines).as("the ledger").doesNotContain(code);
            assertThat(output.getAll()).as("the log").doesNotContain(code);
        }
        assertThat(ledger.records()).isNotEmpty();
    }
}
