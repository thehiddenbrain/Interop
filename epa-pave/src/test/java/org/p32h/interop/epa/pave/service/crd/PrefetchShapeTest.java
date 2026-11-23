package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * The prefetch arranged as the collection's coverage-check sample writes it (D-309). On the first
 * run against the vendor's UAT the check was answered "No patient data provided": PAVE sent its
 * five named Bundles, and the vendor's Postman sample, which the service answers, carries the
 * patient under a key of its own. Every sample here is written for the test with placeholders; the
 * only member ID is S123456789, and the sample's own values never appear in what PAVE sends.
 */
class PrefetchShapeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final String FHIR = "https://fhir.vendor.example.invalid";
    private static final Function<String, ResolvedText> VARIABLES = template -> new FakeGateway().resolve(template,
            Map.of("fhirServer", FHIR, "memberIdSystem", "https://payer.example.invalid/member-id"));
    private static final String SAMPLE_NAME = "Coverage / CRD - Prior Auth Check";
    private static final RequestShapes FROM_SAMPLE = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH,
            RequestShapes.Choice.ofSample(RequestShape.AS_SAMPLE, SAMPLE_NAME));

    /** A bare Patient under patient and a searchset Bundle under coverage, the vendor's sample's own values in each. */
    static final String PATIENT_AND_SEARCHSET = """
            {"hook": "order-sign", "hookInstance": "{{$guid}}", "fhirServer": "{{fhirServer}}",
             "context": {"userId": "Practitioner/sample-prac", "patientId": "sample-pat-1", "draftOrders": {"resourceType": "Bundle",
                         "type": "collection", "entry": []}},
             "prefetch": {
               "patient": {"resourceType": "Patient", "id": "sample-pat-1", "name": [{"family": "SampleFamily"}],
                           "identifier": [{"system": "https://payer.example.invalid/member-id", "value": "sample-member"}]},
               "coverage": {"resourceType": "Bundle", "type": "searchset", "total": 1,
                            "entry": [{"fullUrl": "urn:uuid:sample-cov", "resource": {"resourceType": "Coverage", "id": "sample-cov-1"},
                                       "search": {"mode": "match"}}]}}}
            """;

    private static CrdRunRequest request() {
        return builder().build();
    }

    private static CrdRunRequest.Builder builder() {
        return CrdRunRequest.builder().hook("order-sign")
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").coverageStatus("active").vendorPatientId("PATIENT-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"), new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService("2026-03-20");
    }

    private static CrdRequestBuilder.Built built(RequestShapes shapes, String sampleBody) {
        return new CrdRequestBuilder(null, CLOCK).build(request(), "hook-instance-1", VARIABLES, shapes, MemberIdRule.PACKED,
                PrefetchLayout.read(sampleBody));
    }

    private static JsonNode prefetch(CrdRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node().get("prefetch");
    }

    private static List<String> keys(JsonNode object) {
        List<String> out = new ArrayList<>();
        JsonSafe.properties(object).forEach(property -> out.add(property.getKey()));
        return out;
    }

    private static List<String> types(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        bundle.get("entry").forEach(entry -> out.add(entry.at("/resource/resourceType").asString()));
        return out;
    }

    private static String sample(String prefetch) {
        return "{\"hook\": \"order-sign\", \"context\": {\"patientId\": \"sample-pat-1\"}, \"prefetch\": " + prefetch + "}";
    }

    @Test
    void aBarePatientAndASearchsetCoverageGoUnderTheSamplesKeysBuiltFromTheCase() {
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, PATIENT_AND_SEARCHSET);
        JsonNode prefetch = prefetch(built);

        assertThat(keys(prefetch)).containsExactly("patient", "coverage");
        JsonNode patient = prefetch.get("patient");
        assertThat(patient.get("resourceType").asString()).isEqualTo("Patient");
        assertThat(patient.get("id").asString()).as("the case's patient, never the sample's").isEqualTo("PATIENT-PLACEHOLDER-1");
        assertThat(patient.at("/identifier/0/value").asString()).isEqualTo("S123456789");
        assertThat(patient.at("/name/0/family").asString()).isEqualTo("Placeholder-Surname");
        JsonNode coverage = prefetch.get("coverage");
        assertThat(coverage.get("type").asString()).isEqualTo("searchset");
        assertThat(coverage.get("total").asInt()).isEqualTo(1);
        assertThat(types(coverage)).containsExactly("Coverage");
        assertThat(coverage.at("/entry/0/resource/id").asString()).isEqualTo("coverage-1");
        assertThat(coverage.at("/entry/0/fullUrl").asString()).isEqualTo(FHIR + "/Coverage/coverage-1");
        assertThat(coverage.at("/entry/0/search/mode").asString()).isEqualTo("match");
        assertThat(built.json()).doesNotContain("sample-pat-1").doesNotContain("SampleFamily").doesNotContain("sample-cov")
                .doesNotContain("sample-member");

        assertThat(built.notes()).contains("Prefetch: As the collection's sample, from the collection's sample (the request \""
                + SAMPLE_NAME + "\"). The prefetch was sent under the sample's keys, each built from the case: \"patient\" (a Patient) "
                + "and \"coverage\" (a Bundle of type searchset holding Coverage).");
        assertThat(built.notes()).contains("The sample's prefetch holds no Practitioner or PractitionerRole, so they were not sent in "
                + "the prefetch.");
        assertThat(built.notes()).noneMatch(note -> note.startsWith("The prefetch was sent as the named Bundles"));
        // Only what was sent is counted as missing: no provider resource went, so no provider requirement is counted.
        assertThat(built.incomplete()).noneMatch(required -> required.element().startsWith("Practitioner"));
    }

    @Test
    void oneBundleHoldingSeveralTypesKeepsTheSamplesOrderAndEveryOrder() {
        String body = sample("""
                {"everything": {"resourceType": "Bundle", "type": "collection", "entry": [
                   {"resource": {"resourceType": "Coverage"}}, {"resource": {"resourceType": "Patient"}},
                   {"resource": {"resourceType": "ServiceRequest"}}, {"resource": {"resourceType": "Practitioner"}},
                   {"resource": {"resourceType": "PractitionerRole"}}]}}
                """);
        JsonNode everything = prefetch(built(FROM_SAMPLE, body)).get("everything");

        assertThat(everything.get("type").asString()).isEqualTo("collection");
        assertThat(everything.has("total")).isFalse();
        assertThat(types(everything)).containsExactly("Coverage", "Patient", "ServiceRequest", "ServiceRequest", "Practitioner",
                "PractitionerRole");
        assertThat(everything.at("/entry/0/search").isMissingNode()).as("the sample states no search mode").isTrue();
    }

    @Test
    void theFiveNamedBundlesAsASampleAreByteForByteWhatPaveSendsToday() {
        String named = JsonSafe.parse(built(RequestShapes.DEFAULTS, null).json()).node().get("prefetch").toString();
        CrdRequestBuilder.Built followed = built(FROM_SAMPLE, sample(named));
        CrdRequestBuilder.Built today = built(RequestShapes.DEFAULTS, null);

        assertThat(followed.json()).isEqualTo(today.json());
    }

    @Test
    void noSampleIsTodaysRequestAndNotesUnchanged() {
        CrdRequestBuilder.Built without = new CrdRequestBuilder(null, CLOCK).build(request(), "hook-instance-1", VARIABLES,
                RequestShapes.DEFAULTS, MemberIdRule.PACKED);
        CrdRequestBuilder.Built withNull = built(RequestShapes.DEFAULTS, null);
        // A sample in hand changes nothing while the shape is the named Bundles.
        CrdRequestBuilder.Built defaultWithSample = built(RequestShapes.DEFAULTS, PATIENT_AND_SEARCHSET);

        assertThat(withNull.json()).isEqualTo(without.json());
        assertThat(withNull.notes()).isEqualTo(without.notes());
        assertThat(defaultWithSample.json()).isEqualTo(without.json());
        assertThat(defaultWithSample.notes()).isEqualTo(without.notes());
        assertThat(keys(prefetch(without))).containsExactly("patientBundle", "coverageBundle", "practitionerBundle",
                "practitionerRoleBundle", "serviceRequestBundle");
    }

    @Test
    void aTypePaveDoesNotBuildIsLeftOutAndSaidByKeyAndType() {
        String body = sample("""
                {"patient": {"resourceType": "Patient"}, "encounter": {"resourceType": "Encounter", "id": "sample-enc"},
                 "context": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Location"}},
                   {"resource": {"resourceType": "PractitionerRole"}}]},
                 "devices": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Device"}}]}}
                """);
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, body);
        JsonNode prefetch = prefetch(built);

        assertThat(keys(prefetch)).containsExactly("patient", "context");
        assertThat(types(prefetch.get("context"))).containsExactly("PractitionerRole");
        assertThat(built.notes()).contains(
                "The sample's prefetch also carries Encounter under \"encounter\". PAVE does not build it, so it is not sent.",
                "The sample's prefetch also carries Location in the Bundle under \"context\". PAVE does not build it, so it is not sent.",
                "The sample's prefetch also carries Device in the Bundle under \"devices\". PAVE does not build it, so it is not sent.",
                "The sample's Bundle under \"devices\" holds no type PAVE builds, so the key is not sent.");
        assertThat(built.json()).doesNotContain("sample-enc");
        assertThat(PrefetchLayout.read(body).leftOutLines()).hasSize(4);
    }

    @Test
    void aKeyWrittenAsNullIsSentAsNullAndAPlaceholderIsLeftOutAndSaid() {
        String body = sample("{\"patient\": {\"resourceType\": \"Patient\"}, \"encounter\": null, \"coverage\": {{coverageJson}}, "
                + "\"location\": \"{{locationJson}}\", \"note\": \"text\"}");
        CrdRequestBuilder.Built built = built(FROM_SAMPLE, body);
        JsonNode prefetch = prefetch(built);

        assertThat(keys(prefetch)).containsExactly("patient", "encounter");
        assertThat(prefetch.get("encounter").isNull()).isTrue();
        assertThat(built.notes()).contains(
                "The sample's prefetch writes \"coverage\" as a placeholder. PAVE sends nothing of a sample's values, so it is not sent.",
                "The sample's prefetch writes \"location\" as a placeholder. PAVE sends nothing of a sample's values, so it is not sent.",
                "The sample's prefetch holds something other than a resource or a Bundle under \"note\". PAVE cannot fill it from the "
                        + "case, so it is not sent.");
        assertThat(built.notes()).anyMatch(note -> note.contains("\"encounter\" as null, as the sample writes it"));
    }

    @Test
    void aPractitionerForAProviderKnownAsAnOrganizationIsLeftOutAndSaid() {
        CrdRunRequest organization = builder()
                .provider(ProviderDetail.builder().organizationName("Placeholder Clinic").npi("1234567893").build()).build();
        PrefetchLayout layout = PrefetchLayout.read(sample("{\"practitioner\": {\"resourceType\": \"Practitioner\"}, "
                + "\"organization\": {\"resourceType\": \"Organization\"}}"));
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null, CLOCK).build(organization, "h", VARIABLES, FROM_SAMPLE,
                MemberIdRule.PACKED, layout);

        assertThat(keys(prefetch(built))).containsExactly("organization");
        assertThat(built.notes()).contains("The sample's prefetch holds a Practitioner under \"practitioner\". This case gives none: "
                + "the provider is known by an organization name only. It was not sent.");
    }

    @Test
    void theShapeStatedWithNoSampleToFollowSendsTheNamedBundlesAndSaysSo() {
        RequestShapes stated = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH, RequestShapes.Choice.ofEnvironment(RequestShape.AS_SAMPLE));
        CrdRequestBuilder.Built built = built(stated, null);

        assertThat(keys(prefetch(built))).startsWith("patientBundle");
        assertThat(built.notes()).contains("Prefetch: As the collection's sample, from this environment, but the request this call comes "
                + "from writes no prefetch to follow, so the named Bundles below were sent.");
        RequestShapes named = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH, RequestShapes.Choice.ofEnvironment(RequestShape.NAMED_BUNDLES));
        CrdRequestBuilder.Built namedBuilt = built(named, PATIENT_AND_SEARCHSET);
        assertThat(keys(prefetch(namedBuilt))).startsWith("patientBundle");
        assertThat(namedBuilt.notes()).contains("Prefetch: PAVE's named Bundles, from this environment.");
    }

    @Test
    void aSampleOfAnErrorOrWithNoPrefetchIsNotFollowed() {
        assertThat(PrefetchLayout.ofCall("CRD - Prior Auth Check (error: no member)", PATIENT_AND_SEARCHSET)).isNull();
        assertThat(PrefetchLayout.ofCall(SAMPLE_NAME, PATIENT_AND_SEARCHSET)).isNotNull();
        assertThat(PrefetchLayout.read(sample("{}"))).isNull();
        assertThat(PrefetchLayout.read(sample("null"))).isNull();
        assertThat(PrefetchLayout.read("{\"hook\": \"order-sign\"}")).isNull();
        assertThat(PrefetchLayout.read("not json")).isNull();
        assertThat(VendorSamples.fromBody(RequestShape.CRD_PREFETCH, PATIENT_AND_SEARCHSET)).isEqualTo(RequestShape.AS_SAMPLE);
        assertThat(VendorSamples.fromBody(RequestShape.CRD_PREFETCH, sample("{}"))).isNull();
    }

    @Test
    void theShapeIsReadFromTheSampleOfAV21AndAV20CollectionWithItsKeysInTheOptionsWords() {
        for (String schema : List.of("https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
                "https://schema.getpostman.com/json/collection/v2.0.0/collection.json")) {
            List<ShapeEvidence> evidence = VendorSamples.read(PostmanParser.collection(collection(schema, PATIENT_AND_SEARCHSET)));
            ShapeEvidence prefetch = evidence.stream().filter(each -> each.shape() == RequestShape.CRD_PREFETCH).findFirst().orElseThrow();

            assertThat(prefetch.value()).as(schema).isEqualTo(RequestShape.AS_SAMPLE);
            assertThat(prefetch.requestName()).isEqualTo("CRD - Prior Auth Check");
            assertThat(prefetch.layout().optionWords()).isEqualTo("As the collection's sample (patient, coverage)");
            assertThat(prefetch.layout().keys()).extracting(PrefetchLayout.Key::holds)
                    .containsExactly("a Patient", "a Bundle of type searchset holding Coverage");
        }
    }

    @Test
    void theShapeAndItsOptionsReadAsTheFormShowsThem() {
        assertThat(RequestShape.CRD_PREFETCH.label()).isEqualTo("Prefetch");
        assertThat(RequestShape.CRD_PREFETCH.defaultValue()).isEqualTo("NAMED_BUNDLES");
        assertThat(RequestShape.CRD_PREFETCH.words("NAMED_BUNDLES")).isEqualTo("PAVE's named Bundles (patientBundle, coverageBundle, ...)");
        assertThat(RequestShape.CRD_PREFETCH.words("AS_SAMPLE")).isEqualTo("As the collection's sample");
        assertThat(RequestShape.parse("CRD_PREFETCH")).isEqualTo(RequestShape.CRD_PREFETCH);
    }

    private static final Function<String, ResolvedText> NO_SYSTEM = template -> new FakeGateway().resolve(template, Map.of("fhirServer", FHIR));

    private static CrdRequestBuilder.Built built(Function<String, ResolvedText> variables, String sampleBody) {
        return new CrdRequestBuilder(null, CLOCK).build(request(), "hook-instance-1", variables, FROM_SAMPLE, MemberIdRule.PACKED,
                PrefetchLayout.read(sampleBody));
    }

    /** A Patient, a Coverage and a Practitioner whose identifiers state another member ID system, another type and another NPI system. */
    private static final String OTHER_SYSTEMS = sample("""
            {"patient": {"resourceType": "Patient", "identifier": [{"type": {"coding": [{"system": "https://types.example.invalid", "code": "SB"}]},
                          "system": "https://other-payer.example.invalid/members", "value": "sample-member"}]},
             "coverage": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Coverage",
                          "identifier": [{"system": "{{memberSystem}}", "value": "sample-member"}]}}]},
             "practitioner": {"resourceType": "Practitioner", "identifier": [{"system": "https://npi.example.invalid", "value": "0000000000"}]}}
            """);

    @Test
    void theMemberIdSystemAndTypeAndTheNpiSystemFollowTheSampleWhereNothingIsSetAndTheValuesAreTheCases() {
        CrdRequestBuilder.Built built = built(NO_SYSTEM, OTHER_SYSTEMS);
        JsonNode prefetch = prefetch(built);

        JsonNode patientId = prefetch.at("/patient/identifier/0");
        assertThat(patientId.get("system").asString()).isEqualTo("https://other-payer.example.invalid/members");
        assertThat(patientId.at("/type/coding/0/code").asString()).isEqualTo("SB");
        assertThat(patientId.at("/type/coding/0/system").asString()).isEqualTo("https://types.example.invalid");
        assertThat(patientId.get("value").asString()).isEqualTo("S123456789");
        // The Coverage's own system is a placeholder, never taken: the Patient's stands for the same member ID.
        JsonNode coverageId = prefetch.at("/coverage/entry/0/resource/identifier/0");
        assertThat(coverageId.get("system").asString()).isEqualTo("https://other-payer.example.invalid/members");
        assertThat(coverageId.at("/type/coding/0/code").asString()).as("the sample's Coverage states no type").isEqualTo("MB");
        JsonNode npi = prefetch.at("/practitioner/identifier/0");
        assertThat(npi.get("system").asString()).isEqualTo("https://npi.example.invalid");
        assertThat(npi.get("value").asString()).isEqualTo("1234567893");
        assertThat(built.json()).doesNotContain("sample-member").doesNotContain("0000000000");
        assertThat(built.notes()).contains(
                "The member ID on the Patient went with the system https://other-payer.example.invalid/members, which the sample states: "
                        + "the value Member ID system (memberIdSystem) is not set, and a value set there wins.",
                "The member ID on the Patient was typed SB (https://types.example.invalid), as the sample types it, in place of MB.",
                "The practitioner's NPI went with the system https://npi.example.invalid, which the sample's Practitioner states, in "
                        + "place of http://hl7.org/fhir/sid/us-npi.");
        assertThat(built.incomplete()).noneMatch(required -> required.element().startsWith("Patient.identifier.system"));
    }

    @Test
    void theEnvironmentsMemberIdSystemWinsOverTheSample() {
        CrdRequestBuilder.Built built = built(VARIABLES, OTHER_SYSTEMS);

        assertThat(prefetch(built).at("/patient/identifier/0/system").asString()).isEqualTo("https://payer.example.invalid/member-id");
        assertThat(built.notes()).noneMatch(note -> note.startsWith("The member ID on the Patient went with the system"));
    }

    @Test
    void theMemberIdGoesWhereTheSamplesPatientAndCoverageCarryIt() {
        String body = sample("""
                {"patient": {"resourceType": "Patient", "name": [{"family": "SampleFamily"}]},
                 "coverage": {"resourceType": "Coverage", "subscriberId": "sample-subscriber"}}
                """);
        CrdRequestBuilder.Built built = built(VARIABLES, body);
        JsonNode prefetch = prefetch(built);

        assertThat(prefetch.get("patient").has("identifier")).isFalse();
        assertThat(prefetch.get("coverage").has("identifier")).isFalse();
        assertThat(prefetch.at("/coverage/subscriberId").asString()).isEqualTo("S123456789");
        assertThat(built.notes()).contains(
                "The Patient was sent without the member ID, as the sample's Patient carries no identifier. The member ID still goes on "
                        + "the Coverage.",
                "The Coverage carries the member ID as subscriberId too, as the sample's Coverage has one and no subscriber ID was given.",
                "The Coverage was sent without an identifier, as the sample's Coverage carries the member ID in subscriberId only.");
    }

    @Test
    void aMemberOfTheSamplesContextPaveHasNoValueForIsNotSentAndIsSaidByName() {
        String body = "{\"hook\": \"order-sign\", \"context\": {\"userId\": \"Practitioner/x\", \"patientId\": \"sample-pat\", "
                + "\"encounterId\": \"sample-enc-1\"}, \"prefetch\": {\"patient\": {\"resourceType\": \"Patient\"}}}";
        CrdRequestBuilder.Built built = built(VARIABLES, body);
        JsonNode context = JsonSafe.parse(built.json()).node().get("context");

        assertThat(keys(context)).containsExactly("userId", "patientId", "draftOrders");
        assertThat(built.notes()).contains("The sample's context also carries encounterId. PAVE has no value for it, so it is not sent.");
        assertThat(PrefetchLayout.read(body).formLines())
                .containsExactly("The sample's context also carries encounterId. PAVE has no value for it, so it is not sent.");
        assertThat(built.json()).doesNotContain("sample-enc-1");
    }

    static String collection(String schema, String hookBody) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", schema));
        Map<String, Object> hook = Map.of("name", "CRD - Prior Auth Check", "request", Map.of("method", "POST",
                "url", Map.of("raw", "{{base}}/cds-services/epa-crd-service"), "header", List.of(),
                "body", Map.of("mode", "raw", "raw", hookBody)));
        root.put("item", List.of(Map.of("name", "Coverage", "item", List.of(hook))));
        return JsonSafe.mapper().writeValueAsString(root);
    }
}
