package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * The diagnosis on the order (D-310, D-321). The vendor's working sample carries the diagnosis on its
 * ServiceRequest as reasonCode; PAVE's order carried none, though the Service card has Diagnosis
 * codes. D-310 sent them only beside a sample whose order carries one; since D-321 a diagnosis the
 * case states goes on every order whatever the arrangement, and the sample decides only the coding
 * system where it states one. Every sample here is written for the test: its codes are placeholders
 * that must never reach what PAVE sends, and the only member ID is S123456789.
 */
class DiagnosisInOrderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final String FHIR = "https://fhir.vendor.example.invalid";
    private static final Function<String, ResolvedText> VARIABLES = template -> new FakeGateway().resolve(template,
            Map.of("fhirServer", FHIR));
    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final RequestShapes FROM_SAMPLE = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH,
            RequestShapes.Choice.ofSample(RequestShape.AS_SAMPLE, "Coverage / CRD - Prior Auth Check"));

    /** An order with a diagnosis and a performer in draftOrders, and the same order again in the prefetch. */
    static final String WITH_REASON = """
            {"hook": "order-sign", "context": {"patientId": "sample-pat-1", "draftOrders": {"resourceType": "Bundle", "type": "collection",
               "entry": [{"resource": {"resourceType": "ServiceRequest", "id": "sample-sr-1", "status": "draft",
                 "reasonCode": [{"coding": [{"system": "http://hl7.org/fhir/sid/icd-10-cm", "code": "SAMPLE-DX-1"}]}],
                 "performer": [{"reference": "Practitioner/sample-prac"}]}}]}},
             "prefetch": {"patient": {"resourceType": "Patient", "id": "sample-pat-1"},
               "orders": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "ServiceRequest"}}]}}}
            """;

    /** The same sample with no diagnosis on its order. */
    static final String WITHOUT_REASON = WITH_REASON.replace(
            "\"reasonCode\": [{\"coding\": [{\"system\": \"http://hl7.org/fhir/sid/icd-10-cm\", \"code\": \"SAMPLE-DX-1\"}]}],", "");

    private static CrdRunRequest request(List<CodeInput> diagnoses) {
        return CrdRunRequest.builder().hook("order-sign")
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").build())
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"), CodeInput.of("95810")))
                .diagnosisCodes(diagnoses)
                .dateOfService("2026-03-20").build();
    }

    private static CrdRequestBuilder.Built built(RequestShapes shapes, String sample, List<CodeInput> diagnoses) {
        return new CrdRequestBuilder(null, CLOCK).build(request(diagnoses), "hook-instance-1", VARIABLES, shapes, MemberIdRule.PACKED,
                PrefetchLayout.read(sample));
    }

    private static JsonNode root(CrdRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    @Test
    void eachDiagnosisCodeGoesOnEveryOrderWithTheSamplesSystemAndTheCodeAsTyped() {
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, WITH_REASON, List.of(CodeInput.of(" G47.33 "), CodeInput.of("E66.01")));
        JsonNode root = root(built);

        for (JsonNode entry : root.at("/context/draftOrders/entry")) {
            JsonNode reasonCode = entry.at("/resource/reasonCode");
            assertThat(reasonCode.size()).isEqualTo(2);
            assertThat(reasonCode.at("/0/coding").size()).isEqualTo(1);
            assertThat(reasonCode.at("/0/coding/0/system").asString()).isEqualTo(ICD);
            assertThat(reasonCode.at("/0/coding/0/code").asString()).isEqualTo("G47.33");
            assertThat(reasonCode.at("/1/coding/0/code").asString()).isEqualTo("E66.01");
            // Placed where FHIR's order of elements puts it, before insurance, which is still sent.
            List<String> names = JsonSafe.properties(entry.get("resource")).stream().map(Map.Entry::getKey).toList();
            assertThat(names.indexOf("reasonCode")).isEqualTo(names.size() - 2);
            assertThat(names.get(names.size() - 1)).isEqualTo("insurance");
        }
        // The copy of the order in the prefetch carries it too.
        for (JsonNode entry : root.at("/prefetch/orders/entry")) {
            assertThat(entry.at("/resource/reasonCode/0/coding/0/code").asString()).isEqualTo("G47.33");
        }
        assertThat(root.at("/prefetch/orders/entry").size()).isEqualTo(2);
        // The sample's order names a performer, and this case names no servicing provider: none goes, and the note says so.
        assertThat(built.json()).doesNotContain("SAMPLE-DX-1").doesNotContain("sample-prac").doesNotContain("performer");
        assertThat(built.notes()).contains("Each order carries the case's 2 diagnosis codes as reasonCode, with the system " + ICD
                + ", which the reasonCode of the collection's request uses.", CrdRequestBuilder.NO_PERFORMER);
        assertThat(String.join(" ", built.notes())).doesNotContain("G47.33").doesNotContain("E66.01");
    }

    @Test
    void aSystemTypedWithTheCodeWinsOverTheSamples() {
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, WITH_REASON, List.of(new CodeInput("G47.33", "urn:placeholder:dx", null)));

        assertThat(root(built).at("/context/draftOrders/entry/0/resource/reasonCode/0/coding/0/system").asString())
                .isEqualTo("urn:placeholder:dx");
        assertThat(built.notes()).contains("Each order carries the case's diagnosis code as reasonCode, with the coding system typed "
                + "with it.");
    }

    /**
     * Changed by D-321: a sample whose reasonCode states no system sent the code without one (D-310).
     * The one rule of the three steps now goes on to the format of the code, as Submit always did.
     */
    @Test
    void aSampleReasonCodeWithNoSystemLeavesTheSystemToTheFormatOfTheCode() {
        String sample = WITH_REASON.replace("\"system\": \"http://hl7.org/fhir/sid/icd-10-cm\", ", "");
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, sample, List.of(CodeInput.of("G47.33")));

        JsonNode coding = root(built).at("/context/draftOrders/entry/0/resource/reasonCode/0/coding/0");
        assertThat(coding.get("system").asString()).isEqualTo(ICD);
        assertThat(coding.get("code").asString()).isEqualTo("G47.33");
        assertThat(built.notes()).contains("Each order carries the case's diagnosis code as reasonCode, with the system ICD-10-CM (" + ICD
                + "), taken from the format of the code.");
    }

    @Test
    void aCaseWithNoDiagnosisIsToldToAddOneAndNothingIsFilledIn() {
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, WITH_REASON, List.of());

        for (JsonNode entry : root(built).at("/context/draftOrders/entry")) {
            assertThat(entry.get("resource").has("reasonCode")).isFalse();
        }
        assertThat(built.notes()).contains("The sample's order carries a diagnosis (reasonCode). This case states none: add Diagnosis "
                + "codes on the Service card.");
        assertThat(built.json()).doesNotContain("SAMPLE-DX-1");
    }

    /**
     * Changed by D-321: D-310 sent nothing here, and so a diagnosis the tester typed was left out of
     * the check without a word. Now it goes on every order under the named Bundles, with no sample
     * and with a sample whose order has none, coded by the one rule.
     */
    @Test
    void theDiagnosisGoesUnderTheNamedBundlesWithNoSampleAndWithASampleWhoseOrderHasNone() {
        List<CodeInput> diagnoses = List.of(CodeInput.of("G47.33"));
        CrdRequestBuilder.Built named = built(RequestShapes.DEFAULTS, WITH_REASON, diagnoses);
        CrdRequestBuilder.Built noReason = built(FROM_SAMPLE, WITHOUT_REASON, diagnoses);
        CrdRequestBuilder.Built noSample = new CrdRequestBuilder(null, CLOCK).build(request(diagnoses), "hook-instance-1", VARIABLES,
                FROM_SAMPLE, MemberIdRule.PACKED, null);

        for (CrdRequestBuilder.Built built : List.of(named, noReason, noSample)) {
            JsonNode root = root(built);
            assertThat(root.at("/context/draftOrders/entry").size()).isEqualTo(2);
            for (JsonNode entry : root.at("/context/draftOrders/entry")) {
                assertThat(entry.at("/resource/reasonCode/0/coding/0/code").asString()).isEqualTo("G47.33");
                assertThat(entry.at("/resource/reasonCode/0/coding/0/system").asString()).isEqualTo(ICD);
            }
            assertThat(String.join(" ", built.notes())).doesNotContain("G47.33");
        }
        // Under the named Bundles the copies of the orders in serviceRequestBundle carry it too.
        for (JsonNode entry : root(named).at("/prefetch/serviceRequestBundle/entry")) {
            assertThat(entry.at("/resource/reasonCode/0/coding/0/code").asString()).isEqualTo("G47.33");
        }
        // With no diagnosis the request is the one D-310 sent, apart from the note under the sample that carries one.
        CrdRequestBuilder.Built without = built(RequestShapes.DEFAULTS, WITHOUT_REASON, List.of());
        assertThat(without.json()).doesNotContain("reasonCode");
        assertThat(without.notes()).noneMatch(note -> note.contains("reasonCode"));
    }

    @Test
    void theSampleReadsTheDiagnosisFromDraftOrdersFirstThenFromThePrefetch() {
        assertThat(PrefetchLayout.read(WITH_REASON).reason()).isEqualTo(new PrefetchLayout.Reason(ICD));
        assertThat(PrefetchLayout.read(WITHOUT_REASON).reason()).isNull();
        String inPrefetchOnly = "{\"context\": {}, \"prefetch\": {\"order\": {\"resourceType\": \"ServiceRequest\", \"reasonCode\": "
                + "[{\"coding\": [{\"system\": \"{{dxSystem}}\", \"code\": \"SAMPLE-DX-2\"}]}]}}}";
        // A placeholder system is the vendor's variable: the diagnosis is there, its system is not stated.
        assertThat(PrefetchLayout.read(inPrefetchOnly).reason()).isEqualTo(new PrefetchLayout.Reason(null));
        assertThat(PrefetchLayout.read(inPrefetchOnly.replace("[{\"coding\": [{\"system\": \"{{dxSystem}}\", \"code\": \"SAMPLE-DX-2\"}]}]",
                "[]")).reason()).isNull();
    }
}
