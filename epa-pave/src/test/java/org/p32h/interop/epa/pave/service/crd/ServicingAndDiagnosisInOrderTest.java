package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.p32h.interop.epa.pave.service.run.Diagnoses;
import org.p32h.interop.epa.pave.service.run.SampleOrder;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * The diagnosis and the servicing provider on every order of the coverage check (D-321). A business
 * analyst read the requests PAVE sent to the vendor's UAT and said: "You are missing a Diag code and
 * Servicing Provider". Every value here is a placeholder: member S123456789, NPI 1234567893 and
 * 9999999995, ids such as prac-001, diagnosis codes such as G47.33.
 */
class ServicingAndDiagnosisInOrderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final String FHIR = "https://fhir.vendor.example.invalid";
    private static final Function<String, ResolvedText> VARIABLES = template -> new FakeGateway().resolve(template,
            Map.of("fhirServer", FHIR));
    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final RequestShapes FROM_SAMPLE = RequestShapes.DEFAULTS.with(RequestShape.CRD_PREFETCH,
            RequestShapes.Choice.ofSample(RequestShape.AS_SAMPLE, "Coverage / CRD - Prior Auth Check"));

    private static final ProviderDetail PERSON = ProviderDetail.builder().npi("9999999995").firstName("Sam").lastName("Servicing").build();
    private static final ProviderDetail ORGANIZATION = ProviderDetail.builder().npi("9999999995")
            .organizationName("Placeholder Sleep Laboratory").build();

    /** A sample whose order names its performer as a PractitionerRole, with practitioners and roles in searchset Bundles. */
    static final String ROLE_SAMPLE = """
            {"hook": "order-sign", "context": {"draftOrders": {"resourceType": "Bundle", "type": "collection",
               "entry": [{"resource": {"resourceType": "ServiceRequest", "id": "sample-sr-1",
                 "reasonCode": [{"coding": [{"system": "urn:placeholder:sample-dx", "code": "SAMPLE-DX-1"}]}],
                 "performer": [{"reference": "PractitionerRole/sample-role"}]}}]}},
             "prefetch": {"patient": {"resourceType": "Patient", "id": "sample-pat-1"},
               "practitioners": {"resourceType": "Bundle", "type": "searchset", "total": 1,
                 "entry": [{"resource": {"resourceType": "Practitioner", "id": "sample-prac"}, "search": {"mode": "include"}}]},
               "roles": {"resourceType": "Bundle", "type": "searchset",
                 "entry": [{"resource": {"resourceType": "PractitionerRole", "id": "sample-role"}, "search": {"mode": "match"}}]}}}
            """;

    /** A sample that holds its practitioner as a single resource: no Bundle to add a second one to. */
    static final String SINGLE_PRACTITIONER = """
            {"hook": "order-sign", "context": {"draftOrders": {"resourceType": "Bundle", "type": "collection",
               "entry": [{"resource": {"resourceType": "ServiceRequest", "performer": [{"reference": "Practitioner/sample-prac"}]}}]}},
             "prefetch": {"patient": {"resourceType": "Patient", "id": "sample-pat-1"},
               "practitioner": {"resourceType": "Practitioner", "id": "sample-prac"}}}
            """;

    private static CrdRunRequest.Builder request() {
        return CrdRunRequest.builder().hook("order-sign")
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").build())
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("E0601"), CodeInput.of("95810")))
                .dateOfService("2026-03-20");
    }

    private static CrdRequestBuilder.Built built(CrdRunRequest request, RequestShapes shapes, String sample) {
        return new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", VARIABLES, shapes, MemberIdRule.PACKED,
                PrefetchLayout.read(sample), SampleOrder.read(sample));
    }

    private static JsonNode root(CrdRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    private static List<JsonNode> orders(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        root.at("/context/draftOrders/entry").forEach(entry -> out.add(entry.get("resource")));
        return out;
    }

    private static List<String> ids(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        bundle.path("entry").forEach(entry -> out.add(entry.at("/resource/resourceType").asString() + "/" + entry.at("/resource/id").asString()));
        return out;
    }

    @Test
    void eachRuleOfTheCodingSystemOnEveryOrderAndTheNoteNamesNoCode() {
        List<CodeInput> codes = List.of(new CodeInput("G47.33", "urn:placeholder:typed", null), CodeInput.of("E66.01"),
                CodeInput.of("PLACEHOLDER-DX"));
        CrdRequestBuilder.Built withSample = built(request().diagnosisCodes(codes).build(), RequestShapes.DEFAULTS, ROLE_SAMPLE);
        CrdRequestBuilder.Built noSample = built(request().diagnosisCodes(codes).build(), RequestShapes.DEFAULTS, null);

        for (JsonNode order : orders(root(withSample))) {
            // Typed wins; else the sample's system; and under any arrangement of the prefetch.
            assertThat(order.at("/reasonCode/0/coding/0/system").asString()).isEqualTo("urn:placeholder:typed");
            assertThat(order.at("/reasonCode/1/coding/0/system").asString()).isEqualTo("urn:placeholder:sample-dx");
            assertThat(order.at("/reasonCode/2/coding/0/system").asString()).isEqualTo("urn:placeholder:sample-dx");
            assertThat(order.at("/reasonCode/2/coding/0/code").asString()).isEqualTo("PLACEHOLDER-DX");
        }
        for (JsonNode order : orders(root(noSample))) {
            assertThat(order.at("/reasonCode/0/coding/0/system").asString()).isEqualTo("urn:placeholder:typed");
            // With no sample: ICD-10-CM by the format, else no system at all.
            assertThat(order.at("/reasonCode/1/coding/0/system").asString()).isEqualTo(ICD);
            assertThat(order.at("/reasonCode/2/coding/0").has("system")).isFalse();
            assertThat(order.at("/reasonCode").size()).isEqualTo(3);
        }
        assertThat(noSample.notes()).contains("Each order carries the case's 3 diagnosis codes as reasonCode. Diagnosis 1 with the coding "
                + "system typed with it; diagnosis 2 with the system ICD-10-CM (" + ICD + "), taken from the format of the code; diagnosis 3 "
                + "with no coding system: none was typed with it, and the format is not that of ICD-10-CM. Type the system with the code to "
                + "send one.");
        for (CrdRequestBuilder.Built built : List.of(withSample, noSample)) {
            String notes = String.join(" ", built.notes());
            assertThat(notes).doesNotContain("G47.33").doesNotContain("E66.01").doesNotContain("PLACEHOLDER-DX");
            assertThat(built.json()).doesNotContain("SAMPLE-DX-1").doesNotContain("sample-role").doesNotContain("sample-prac");
        }
    }

    @Test
    void theCodingRuleIsOneForTheThreeSteps() {
        assertThat(Diagnoses.choose(CodeInput.of("g47.33"), null)).isEqualTo(new Diagnoses.Choice(ICD, Diagnoses.Source.FORMAT));
        assertThat(Diagnoses.choose(CodeInput.of("327.23"), null)).isEqualTo(new Diagnoses.Choice(null, Diagnoses.Source.NONE));
        assertThat(Diagnoses.choose(CodeInput.of("327.23"), "urn:x")).isEqualTo(new Diagnoses.Choice("urn:x", Diagnoses.Source.SAMPLE));
        assertThat(Diagnoses.choose(new CodeInput("G47.33", " urn:t ", null), "urn:x"))
                .isEqualTo(new Diagnoses.Choice("urn:t", Diagnoses.Source.TYPED));
        // A long text is not looked at for the format at all.
        assertThat(Diagnoses.icd10Format("G47.33" + "9".repeat(10_000))).isFalse();
        assertThat(Diagnoses.orderNote(List.of(), null)).isNull();
    }

    @Test
    void aServicingPersonGoesAsThePerformerOfEveryOrderAndTravelsBesideTheRequester() {
        CrdRequestBuilder.Built built = built(request().servicingProvider(PERSON).build(), RequestShapes.DEFAULTS, null);
        JsonNode root = root(built);

        for (JsonNode order : orders(root)) {
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("Practitioner/practitioner-servicing-1");
            assertThat(order.at("/requester/reference").asString()).isEqualTo("Practitioner/practitioner-1");
            // After requester and before insurance, as FHIR orders the elements.
            List<String> names = JsonSafe.properties(order).stream().map(Map.Entry::getKey).toList();
            assertThat(names.indexOf("performer")).isEqualTo(names.indexOf("requester") + 1);
            assertThat(names.get(names.size() - 1)).isEqualTo("insurance");
        }
        for (JsonNode entry : root.at("/prefetch/serviceRequestBundle/entry")) {
            assertThat(entry.at("/resource/performer/0/reference").asString()).isEqualTo("Practitioner/practitioner-servicing-1");
        }
        assertThat(ids(root.at("/prefetch/practitionerBundle"))).containsExactly("Practitioner/practitioner-1",
                "Practitioner/practitioner-servicing-1");
        assertThat(root.at("/prefetch/practitionerBundle/entry/1/resource/identifier/0/value").asString()).isEqualTo("9999999995");
        assertThat(built.notes()).contains("The servicing provider goes as the order's performer, Practitioner/practitioner-servicing-1.",
                "The servicing provider's resources travel beside the requesting provider's: its Practitioner in practitionerBundle.");
    }

    @Test
    void aServicingOrganizationGoesAsAnOrganizationAndAVendorIdAsThePractitionersId() {
        CrdRequestBuilder.Built organization = built(request().servicingProvider(ORGANIZATION).build(), RequestShapes.DEFAULTS, null);
        JsonNode root = root(organization);
        assertThat(root.at("/context/draftOrders/entry/0/resource/performer/0/reference").asString())
                .isEqualTo("Organization/organization-servicing-1");
        assertThat(ids(root.at("/prefetch/practitionerBundle"))).containsExactly("Practitioner/practitioner-1",
                "Organization/organization-servicing-1");

        ProviderDetail vendor = ProviderDetail.builder().vendorPractitionerId("prac-002").lastName("Servicing").build();
        JsonNode byId = root(built(request().servicingProvider(vendor).build(), RequestShapes.DEFAULTS, null));
        assertThat(byId.at("/context/draftOrders/entry/1/resource/performer/0/reference").asString()).isEqualTo("Practitioner/prac-002");
    }

    @Test
    void aServicingProviderWithTheRequestersVendorIdNamesTheOnePractitioner() {
        CrdRunRequest request = request().provider(ProviderDetail.builder().vendorPractitionerId("prac-001").lastName("Provider").build())
                .servicingProvider(ProviderDetail.builder().vendorPractitionerId("prac-001").lastName("Provider").build()).build();
        CrdRequestBuilder.Built built = built(request, RequestShapes.DEFAULTS, null);
        JsonNode root = root(built);

        assertThat(root.at("/context/draftOrders/entry/0/resource/performer/0/reference").asString()).isEqualTo("Practitioner/prac-001");
        assertThat(ids(root.at("/prefetch/practitionerBundle"))).containsExactly("Practitioner/prac-001");
        assertThat(built.notes()).contains("The servicing provider goes as the order's performer, Practitioner/prac-001. It carries the "
                + "requesting provider's vendor practitioner id, so it names the same Practitioner and no second Practitioner is sent.");
    }

    @Test
    void noServicingProviderSendsNoPerformerAndTheRequesterIsNeverPutThere() {
        for (ProviderDetail none : java.util.Arrays.asList(null, ProviderDetail.builder().build())) {
            CrdRequestBuilder.Built built = built(request().servicingProvider(none).build(), FROM_SAMPLE, ROLE_SAMPLE);
            assertThat(built.json()).doesNotContain("performer").doesNotContain("servicing");
            // The sample's order names a performer and the case none: said, and nothing filled in.
            assertThat(built.notes()).contains(CrdRequestBuilder.NO_PERFORMER);
        }
        CrdRequestBuilder.Built noSample = built(request().build(), RequestShapes.DEFAULTS, null);
        assertThat(noSample.json()).doesNotContain("performer");
        assertThat(noSample.notes()).doesNotContain(CrdRequestBuilder.NO_PERFORMER);
    }

    @Test
    void underTheSamplesArrangementARoleGoesWhereTheSamplesPerformerIsARoleAndEachResourceJoinsTheBundleOfItsType() {
        CrdRequestBuilder.Built built = built(request().servicingProvider(PERSON).diagnosisCodes(List.of(CodeInput.of("G47.33"))).build(),
                FROM_SAMPLE, ROLE_SAMPLE);
        JsonNode root = root(built);

        for (JsonNode order : orders(root)) {
            assertThat(order.at("/performer/0/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-servicing-1");
        }
        JsonNode practitioners = root.at("/prefetch/practitioners");
        assertThat(ids(practitioners)).containsExactly("Practitioner/practitioner-1", "Practitioner/practitioner-servicing-1");
        assertThat(practitioners.at("/entry/1/search/mode").asString()).isEqualTo("include");
        assertThat(practitioners.get("total").asInt()).isEqualTo(2);
        JsonNode roles = root.at("/prefetch/roles");
        assertThat(ids(roles)).containsExactly("PractitionerRole/practitioner-role-1", "PractitionerRole/practitioner-role-servicing-1");
        assertThat(roles.at("/entry/1/resource/practitioner/reference").asString()).isEqualTo("Practitioner/practitioner-servicing-1");
        assertThat(roles.at("/entry/1/search/mode").asString()).isEqualTo("match");
        assertThat(built.notes()).contains("The servicing provider goes as the order's performer, "
                + "PractitionerRole/practitioner-role-servicing-1.", "The servicing provider's resources travel where the sample holds "
                + "resources of their type: its Practitioner in the Bundle under \"practitioners\" and its PractitionerRole in the Bundle "
                + "under \"roles\".");
        // The performer is sent, so it is not named among what the sample carries and PAVE does not.
        assertThat(built.notes()).noneMatch(note -> note.contains("also carries performer"));
    }

    @Test
    void aSampleWithNoPlaceForTheTypeSendsTheReferenceAloneAndSaysSo() {
        CrdRequestBuilder.Built built = built(request().servicingProvider(PERSON).build(), FROM_SAMPLE, SINGLE_PRACTITIONER);
        JsonNode root = root(built);

        assertThat(root.at("/context/draftOrders/entry/0/resource/performer/0/reference").asString())
                .isEqualTo("Practitioner/practitioner-servicing-1");
        assertThat(root.at("/prefetch/practitioner/id").asString()).isEqualTo("practitioner-1");
        assertThat(built.json()).doesNotContain("\"id\" : \"practitioner-servicing-1\"");
        assertThat(built.notes()).contains("The sample's prefetch has no Bundle that holds a Practitioner, so the servicing provider's "
                + "Practitioner was not sent. The order still names it as its performer.");
    }

    @Test
    void withNoPrefetchTheReferenceAloneGoes() {
        CrdRequestBuilder.Built built = built(request().servicingProvider(PERSON).includePrefetch(false).build(), RequestShapes.DEFAULTS,
                null);
        assertThat(root(built).has("prefetch")).isFalse();
        assertThat(root(built).at("/context/draftOrders/entry/0/resource/performer/0/reference").asString())
                .isEqualTo("Practitioner/practitioner-servicing-1");
        assertThat(built.notes()).contains("No prefetch was sent, so the servicing provider's resources were not sent either; the order "
                + "still names it.");
    }

    @Test
    void anOrganizationRequesterCarriesTheServicingPractitionerInItsOwnBundle() {
        CrdRunRequest request = request().provider(ProviderDetail.builder().npi("1234567893").organizationName("Placeholder Clinic").build())
                .servicingProvider(PERSON).build();
        JsonNode root = root(built(request, RequestShapes.DEFAULTS, null));
        assertThat(ids(root.at("/prefetch/organizationBundle"))).containsExactly("Organization/organization-1",
                "Practitioner/practitioner-servicing-1");
        // The requester is the organization's role; the performer is the servicing person, never that role.
        assertThat(root.at("/context/draftOrders/entry/0/resource/requester/reference").asString())
                .isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(root.at("/context/draftOrders/entry/0/resource/performer/0/reference").asString())
                .isEqualTo("Practitioner/practitioner-servicing-1");
    }

    @Test
    void theSampleIsReadByPresenceAndTypeOnly() {
        SampleOrder role = SampleOrder.read(ROLE_SAMPLE);
        assertThat(role).isEqualTo(new SampleOrder(true, "urn:placeholder:sample-dx", true, "PractitionerRole", false, false));
        assertThat(role.toString()).doesNotContain("sample");
        assertThat(SampleOrder.read(null)).isEqualTo(SampleOrder.NONE);
        assertThat(SampleOrder.read("not json {")).isEqualTo(SampleOrder.NONE);
        assertThat(SampleOrder.read("[]")).isEqualTo(SampleOrder.NONE);
        assertThat(SampleOrder.ofCall("CRD - error: no patient", ROLE_SAMPLE)).isEqualTo(SampleOrder.NONE);
        // A placeholder system or reference is the vendor's variable: present, with no system and no type.
        String placeholders = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"order\", \"resource\": {\"resourceType\": "
                + "\"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"system\": \"{{dxSystem}}\"}]}], \"performer\": [{{performer}}]}}]}";
        assertThat(SampleOrder.read(placeholders)).isEqualTo(new SampleOrder(true, null, true, null, false, false));
        String claim = "{\"resourceType\": \"Bundle\", \"entry\": [{\"resource\": {\"resourceType\": \"Claim\", \"diagnosis\": [{}], "
                + "\"careTeam\": [{\"provider\": {\"reference\": \"PractitionerRole/x\"}}]}}]}";
        SampleOrder submit = SampleOrder.read(claim);
        assertThat(submit.carriesDiagnosis()).isTrue();
        assertThat(submit.carriesPerformer()).isTrue();
        assertThat(SampleOrder.read("{\"resourceType\": \"Bundle\", \"entry\": [{\"resource\": {\"resourceType\": \"Claim\", "
                + "\"diagnosis\": [], \"careTeam\": [{\"sequence\": 1}]}}]}").carriesPerformer()).isFalse();
    }

    @Test
    void theFormAndTheLayoutKnowThePerformerType() {
        PrefetchLayout layout = PrefetchLayout.read(ROLE_SAMPLE);
        assertThat(layout.performerType()).isEqualTo("PractitionerRole");
        assertThat(PrefetchLayout.BUILDABLE.get("ServiceRequest")).contains("performer", "reasonCode");
        assertThat(layout.buildableLines()).contains("The sample's order names a performer: the Servicing provider of the Provider card "
                + "goes there.");
    }
}
