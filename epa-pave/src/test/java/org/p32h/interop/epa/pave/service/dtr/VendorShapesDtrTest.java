package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.crd.CrdResponseMapper;
import org.p32h.interop.epa.pave.service.crd.CrdVerdicts;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DTR requests in the shape the vendor's sandbox accepted (R-37, history V9 and V10), held
 * against placeholder fixtures written from those requests.
 */
class VendorShapesDtrTest {

    private static final String PROFILE = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-input-parameters";
    private static final String COVERAGE_INFORMATION = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    private final DtrRequestBuilder builder = new DtrRequestBuilder(null);

    private static String fixture(String name) throws IOException {
        try (InputStream in = VendorShapesDtrTest.class.getResourceAsStream("/vendor-shapes/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    /** What CRD hands DTR after the placeholder auth-needed answer, through the next step as a run makes it. */
    private static Map<String, List<String>> carriedFromCrd() throws IOException {
        VendorExchange exchange = TestExchanges.crd(200, fixture("crd-answer-auth-needed.json"));
        NextStepView next = CrdVerdicts.nextStep(new CrdResponseMapper().read(exchange), exchange);
        return next.carry();
    }

    @Test
    void thePackageRequestDeclaresTheProfileTheVendorAccepted() {
        DtrRequestBuilder.Package built = builder.buildPackage(DtrFixtures.packageRequest("S123456789").build(),
                template -> new FakeGateway().resolve(template, Map.of()));
        JsonNode root = json(built.json());
        assertThat(root.get("resourceType").asString()).isEqualTo("Parameters");
        assertThat(root.at("/meta/profile")).hasSize(1);
        assertThat(root.at("/meta/profile/0").asString()).isEqualTo(PROFILE);
        assertThat(DtrRequestBuilder.PROFILE_PACKAGE_INPUT).isEqualTo(PROFILE);
    }

    @Test
    void crdHandsOnTheCoverageInformationAsReceivedAndTheOrderCarriesIt() throws IOException {
        Map<String, List<String>> carry = carriedFromCrd();
        List<String> handed = carry.get(NextStepView.CARRY_COVERAGE_INFORMATION);
        assertThat(handed).hasSize(1);
        JsonNode value = json(handed.get(0));
        assertThat(value.get("order").asString()).isEqualTo("ServiceRequest/service-request-1");
        JsonNode received = json(fixture("crd-answer-auth-needed.json")).at("/systemActions/0/resource/extension/0");
        assertThat(value.get("extension")).isEqualTo(received);

        // A DTR step passes it on unchanged, so a later package request still has it.
        Map<String, List<String>> passed = DtrHandoff.afterPackage("run-00000000-0000-4000-8000-000000000001", List.of("95810"),
                List.of(), carry).carry(null);
        assertThat(passed.get(NextStepView.CARRY_COVERAGE_INFORMATION)).isEqualTo(handed);

        DtrRequestBuilder.Package built = builder.buildPackage(DtrFixtures.packageRequest("S123456789").carry(passed).build(),
                template -> new FakeGateway().resolve(template, Map.of()));
        JsonNode order = null;
        for (JsonNode parameter : JsonSafe.elements(json(built.json()), "parameter")) {
            if ("order".equals(JsonSafe.text(parameter, "name"))) {
                order = parameter.get("resource");
            }
        }
        assertThat(order).isNotNull();
        assertThat(order.get("id").asString()).isEqualTo("service-request-1");
        assertThat(order.get("extension")).hasSize(1);
        assertThat(order.at("/extension/0")).isEqualTo(received);
        assertThat(order.at("/extension/0/url").asString()).isEqualTo(COVERAGE_INFORMATION);
        assertThat(built.notes()).contains("The order ServiceRequest/service-request-1 carries the coverage-information extension CRD returned "
                + "for it, exactly as received, as the request the vendor accepted did.");
    }

    @Test
    void coverageInformationForAnOrderThisRequestDoesNotSendIsNotCarriedAndSaysSo() {
        Map<String, List<String>> carry = Map.of(NextStepView.CARRY_COVERAGE_INFORMATION, List.of(
                "{\"order\":\"ServiceRequest/service-request-9\",\"extension\":{\"url\":\"" + COVERAGE_INFORMATION + "\"}}", "not json"));
        DtrRequestBuilder.Package built = builder.buildPackage(DtrFixtures.packageRequest("S123456789").carry(carry).build(),
                ResolvedText::plain);
        assertThat(built.json()).doesNotContain(COVERAGE_INFORMATION);
        assertThat(built.notes()).contains("CRD returned coverage information for ServiceRequest/service-request-9, which this request does "
                + "not send as an order, so it was not carried.", "Coverage information handed on from CRD could not be read as an order "
                + "and an extension, so it was not carried.");
    }

    @Test
    void nextQuestionChangesOnlyTheAnswersAndTheStatusOfTheVendorsResponse() throws IOException {
        String vendor = fixture("dtr-vendor-questionnaire-response.json");
        JsonNode before = json(vendor);
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(DtrAnswers.read(null, vendor, List.of(DtrAnswerInput.of("q1", "true"))),
                DtrFixtures.filler("S123456789"), DtrFixtures.CLOCK, null);
        ObjectNode sent = (ObjectNode) built.response().deepCopy();

        // The answer was added, marked as a person's, and the contained Questionnaire keeps its launchContext extension.
        assertThat(sent.at("/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(sent.at("/contained/0")).isEqualTo(before.at("/contained/0"));
        assertThat(sent.at("/contained/0/extension/1/extension/0/valueCoding/code").asString()).isEqualTo("VENDOR-CODE-PLACEHOLDER");

        // Everything else is exactly what the vendor sent.
        ObjectNode expected = (ObjectNode) before.deepCopy();
        expected.remove("item");
        expected.remove("status");
        sent.remove("item");
        sent.remove("status");
        assertThat(sent).isEqualTo(expected);
        assertThat(built.response().get("status").asString()).isEqualTo("in-progress");
    }

    @Test
    void aResponseIsAuthoredInWholeSecondsUtc() {
        DtrRequestBuilder.LoopBody first = builder.buildNextQuestion(DtrAnswers.read("{\"resourceType\":\"Questionnaire\",\"id\":\"q\","
                + "\"url\":\"https://example.org/p32h-placeholder/Questionnaire/q\",\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\"}]}", null,
                List.of()), DtrFixtures.filler("S123456789"), DtrFixtures.CLOCK, null);
        // The clock stands at 11:00 in New York; the instant goes out in UTC with no fraction, as the vendor's validator took it.
        assertThat(first.response().get("authored").asString()).isEqualTo("2026-03-10T15:00:00Z");
        assertThat(DtrRequestBuilder.authored(java.time.Clock.fixed(java.time.Instant.parse("2026-11-22T14:06:08.987654321Z"),
                java.time.ZoneId.of("Asia/Tokyo")))).isEqualTo("2026-11-22T14:06:08Z");
    }
}
