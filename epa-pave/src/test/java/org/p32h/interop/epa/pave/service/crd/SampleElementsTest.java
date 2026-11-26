package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * What a sample's resource carries that PAVE's does not, said by name (D-310). The vendor's working
 * sample carries more on its Patient, Coverage and order than PAVE's case holds; the tester could
 * see that only by pasting the two bodies into a diff tool. Each sample here is written for the
 * test: its values are placeholders, and no note may carry one of them.
 */
class SampleElementsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final Function<String, ResolvedText> VARIABLES = template -> new FakeGateway().resolve(template,
            Map.of("fhirServer", "https://fhir.vendor.example.invalid", "payerIdentifier", "PAYER-PLACEHOLDER"));
    private static final RequestShapes FROM_SAMPLE = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH,
            RequestShapes.Choice.ofSample(RequestShape.AS_SAMPLE, "Coverage / CRD - Prior Auth Check"));

    /** The values every sample below holds, none of which may appear in a note or in what is sent. */
    private static final List<String> SAMPLE_VALUES = List.of("SAMPLE-DX-1", "sample-prac", "Sample Street", "555-0100", "en-US",
            "sample-ext", "sample-profile", "GROUP-PLACEHOLDER", "PLAN-PLACEHOLDER", "self-sample");

    static final String SAMPLE = """
            {"hook": "order-sign", "context": {"patientId": "sample-pat-1", "draftOrders": {"resourceType": "Bundle", "type": "collection",
               "entry": [{"resource": {"resourceType": "ServiceRequest", "id": "sample-sr-1", "status": "draft", "intent": "order",
                 "code": {"coding": [{"code": "E0601"}]}, "subject": {"reference": "Patient/sample-pat-1"},
                 "reasonCode": [{"coding": [{"system": "http://hl7.org/fhir/sid/icd-10-cm", "code": "SAMPLE-DX-1"}]}],
                 "performer": [{"reference": "Practitioner/sample-prac"}]}}]}},
             "prefetch": {
               "patient": {"resourceType": "Patient", "id": "sample-pat-1", "meta": {"profile": ["sample-profile"]},
                 "extension": [{"url": "sample-ext"}], "active": true, "name": [{"family": "SampleFamily"}],
                 "telecom": [{"value": "555-0100"}], "gender": "female", "address": [{"line": ["Sample Street"]}],
                 "communication": [{"language": {"text": "en-US"}}]},
               "coverage": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Coverage",
                 "id": "sample-cov", "status": "active", "relationship": {"coding": [{"code": "self-sample"}]},
                 "class": [{"type": {"coding": [{"code": "plan"}]}, "value": "PLAN-PLACEHOLDER"},
                           {"type": {"coding": [{"code": "group"}]}, "value": "GROUP-PLACEHOLDER"}]}}]}}}
            """;

    private static MemberDetail.Builder member() {
        return MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                .dateOfBirth("1970-01-31").gender("female").coverageStatus("active");
    }

    private static CrdRequestBuilder.Built built(MemberDetail member, List<CodeInput> diagnoses, String sample) {
        CrdRunRequest request = CrdRunRequest.builder().hook("order-sign").member(member)
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"))).diagnosisCodes(diagnoses).dateOfService("2026-03-20").build();
        return new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", VARIABLES, FROM_SAMPLE, MemberIdRule.PACKED,
                PrefetchLayout.read(sample));
    }

    private static void noSampleValue(List<String> lines) {
        String all = String.join(" ", lines);
        for (String value : SAMPLE_VALUES) {
            assertThat(all).as("no value of the sample in what is said").doesNotContain(value);
        }
    }

    @Test
    void eachResourceTypeSentSaysWhatTheSamplesCarriesByNameInOneNote() {
        CrdRequestBuilder.Built built = built(member().build(), List.of(CodeInput.of("G47.33")), SAMPLE);

        // Changed by D-321: a performer the case names no servicing provider for is said once, by the order's own note.
        assertThat(built.notes()).contains(
                CrdRequestBuilder.NO_PERFORMER,
                "The sample's Patient also carries meta, extension, active, telecom, address and communication. PAVE has no value for "
                        + "them in this case, so they are not sent.",
                "The sample's Coverage also carries relationship, class of type plan and class of type group. PAVE has no value for "
                        + "them in this case, so they are not sent.");
        assertThat(built.notes().stream().filter(note -> note.startsWith("The sample's Patient also carries"))).hasSize(1);
        noSampleValue(built.notes());
        // Nothing of what is named is filled in: performer is not the requester, and no class is made up.
        JsonNode root = JsonSafe.parse(built.json()).node();
        assertThat(root.at("/context/draftOrders/entry/0/resource").has("performer")).isFalse();
        assertThat(root.at("/prefetch/coverage/entry/0/resource").has("class")).isFalse();
        assertThat(root.at("/prefetch/patient").has("address")).isFalse();
        for (String value : SAMPLE_VALUES) {
            assertThat(built.json()).doesNotContain(value);
        }
    }

    @Test
    void aClassPaveSendsIsNotNamedAndOnlyTheClassTypeItLacksIs() {
        CrdRequestBuilder.Built built = built(member().planName("Plan placeholder").build(), List.of(CodeInput.of("G47.33")), SAMPLE);

        assertThat(built.notes()).contains("The sample's Coverage also carries relationship and class of type group. PAVE has no value "
                + "for them in this case, so they are not sent.");
    }

    @Test
    void aMemberTheCaseLacksTodayIsNamedAndOneTheCaseGivesIsNot() {
        // The sample's Patient has gender; a case with no gender sends none, and so it is named for this case.
        CrdRequestBuilder.Built built = built(member().gender(null).build(), List.of(CodeInput.of("G47.33")), SAMPLE);

        assertThat(built.notes()).contains("The sample's Patient also carries meta, extension, active, telecom, gender, address and "
                + "communication. PAVE has no value for them in this case, so they are not sent.");
    }

    @Test
    void anAddressTheCaseHoldsIsNotCalledAbsentAndIsStillNotSent() {
        MemberDetail withAddress = member().address(new org.p32h.interop.epa.pave.model.PostalAddress("1 Placeholder Way", null,
                "Placeholder City", "ZZ", "00000")).build();
        CrdRequestBuilder.Built built = built(withAddress, List.of(CodeInput.of("G47.33")), SAMPLE);

        assertThat(built.notes()).contains("The sample's Patient also carries meta, extension, active, telecom, address and "
                + "communication. PAVE has no value for meta, extension, active, telecom and communication in this case, and the case "
                + "holds address but PAVE's coverage check does not build it. None of them is sent.");
        assertThat(built.json()).doesNotContain("Placeholder Way");
        assertThat(String.join(" ", built.notes())).doesNotContain("Placeholder Way").doesNotContain("Placeholder City");
        String onlyAddress = "{\"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"address\": []}}}";
        assertThat(built(withAddress, List.of(), onlyAddress).notes()).contains("The sample's Patient also carries address. The case "
                + "holds a value for it, but PAVE's coverage check does not build it, so it is not sent.");
    }

    @Test
    void aReasonCodeTheCaseGivesNoDiagnosisForIsSaidOnceByTheDiagnosisNote() {
        CrdRequestBuilder.Built built = built(member().build(), List.of(), SAMPLE);

        assertThat(built.notes()).contains("The sample's order carries a diagnosis (reasonCode). This case states none: add Diagnosis "
                + "codes on the Service card.", CrdRequestBuilder.NO_PERFORMER);
        // Changed by D-321: the performer is said by the order's note and is not named a second time among the elements.
        assertThat(built.notes()).noneMatch(note -> note.startsWith("The sample's ServiceRequest also carries"));
    }

    @Test
    void theFormNamesWhatPaveNeverWritesWhateverTheCase() {
        List<String> lines = PrefetchLayout.read(SAMPLE).formLines();

        // Changed by D-321: PAVE now writes a performer, so it is no longer named as one it has no field for; its own line says where it goes.
        assertThat(lines).containsExactly(
                "The sample's Patient also carries meta, extension, active, telecom, address and communication. PAVE has no field for "
                        + "them, so they are not sent.",
                "The sample's Coverage also carries relationship and class of type group. PAVE has no field for them, so they are not "
                        + "sent.",
                "The sample's order carries a diagnosis (reasonCode): the Diagnosis codes of the Service card go there, with the coding "
                        + "system the sample uses.",
                "The sample's order names a performer: the Servicing provider of the Provider card goes there.");
        noSampleValue(lines);
    }

    @Test
    void underTheNamedBundlesNothingIsNamed() {
        CrdRunRequest request = CrdRunRequest.builder().hook("order-sign").member(member().build())
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"))).dateOfService("2026-03-20").build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", VARIABLES,
                RequestShapes.DEFAULTS, MemberIdRule.PACKED, PrefetchLayout.read(SAMPLE));

        assertThat(built.notes()).noneMatch(note -> note.contains("also carries"));
    }

    @Test
    void whatTheFormSaysPaveCanWriteHoldsEveryMemberABuiltRequestCarries() {
        // Every value a case can hold, and a sample asking for every type PAVE builds, so every member the builders write is written.
        MemberDetail full = member().subscriberId("SUB-PLACEHOLDER-1").planName("Plan placeholder").coverageStart("2026-01-01")
                .coverageEnd("2026-12-31").vendorPatientId("PATIENT-PLACEHOLDER-1").build();
        String sample = """
                {"context": {"draftOrders": {"resourceType": "Bundle", "entry": [{"resource": {"resourceType": "ServiceRequest",
                   "reasonCode": [{"coding": [{"system": "urn:placeholder:dx", "code": "SAMPLE-DX-1"}]}]}}]}},
                 "prefetch": {"all": {"resourceType": "Bundle", "type": "collection", "entry": [
                   {"resource": {"resourceType": "Patient", "identifier": [{"value": "x"}]}},
                   {"resource": {"resourceType": "Coverage", "identifier": [{"value": "x"}], "subscriberId": "x"}},
                   {"resource": {"resourceType": "Practitioner"}}, {"resource": {"resourceType": "PractitionerRole"}},
                   {"resource": {"resourceType": "ServiceRequest"}}]}}}
                """;
        Map<String, Set<String>> written = new HashMap<>();
        for (MemberDetail member : List.of(full)) {
            CrdRequestBuilder.Built built = built(member, List.of(CodeInput.of("G47.33")), sample);
            for (JsonNode resource : PrefetchLayout.resources(JsonSafe.parse(built.json()).node())) {
                written.computeIfAbsent(resource.get("resourceType").asString(), any -> new HashSet<>()).addAll(PrefetchLayout.names(resource));
            }
        }
        // An organization known by name only is the one case that builds an Organization.
        CrdRunRequest organization = CrdRunRequest.builder().hook("order-sign").member(full)
                .provider(ProviderDetail.builder().organizationName("Placeholder Clinic").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"))).build();
        String orgSample = "{\"prefetch\": {\"org\": {\"resourceType\": \"Organization\"}, \"role\": {\"resourceType\": \"PractitionerRole\"}}}";
        CrdRequestBuilder.Built orgBuilt = new CrdRequestBuilder(null, CLOCK).build(organization, "hook-instance-1", VARIABLES, FROM_SAMPLE,
                MemberIdRule.PACKED, PrefetchLayout.read(orgSample));
        for (JsonNode resource : PrefetchLayout.resources(JsonSafe.parse(orgBuilt.json()).node())) {
            written.computeIfAbsent(resource.get("resourceType").asString(), any -> new HashSet<>()).addAll(PrefetchLayout.names(resource));
        }

        assertThat(written.keySet()).containsExactlyInAnyOrderElementsOf(PrefetchLayout.BUILDABLE.keySet());
        written.forEach((type, names) -> assertThat(PrefetchLayout.BUILDABLE.get(type)).as(type).containsAll(names));
        assertThat(written.get("ServiceRequest")).contains("reasonCode");
        assertThat(written.get("Coverage")).contains("class", "period", "subscriberId", "payor", "identifier");
        assertThat(written.get("Patient")).contains("identifier", "name", "birthDate", "gender");
    }

    @Test
    void theNamesAreReadOneLevelDeepAndAPlaceholderIsNoName() {
        PrefetchLayout layout = PrefetchLayout.read("{\"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"{{extra}}\": 1, "
                + "\"contact\": [{\"telecom\": [{\"value\": \"555-0100\"}]}], \"photo\": []}}}");

        assertThat(layout.elements()).containsExactly(Map.entry("Patient", List.of("contact", "photo")));
        assertThat(PrefetchLayout.classTypes(JsonSafe.parse("{\"class\": [{\"type\": {\"coding\": [{\"code\": \"{{classType}}\"}]}}, "
                + "{\"type\": {\"coding\": [{\"code\": \"group\"}]}}, {\"type\": {\"coding\": [{\"code\": \"group\"}]}}]}").node()))
                .containsExactly("group");
    }
}
