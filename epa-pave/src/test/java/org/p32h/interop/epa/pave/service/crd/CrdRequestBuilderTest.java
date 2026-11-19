package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CrdRequestBuilderTest {

    private static final Function<String, ResolvedText> NOTHING_SET = template -> new FakeGateway().resolve(template, Map.of());

    private static CrdRunRequest.Builder request() {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("F").lineOfBusiness("Medicare").planName("Placeholder Plan")
                        .coverageStatus("active").subscriberId("SUB-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .dateOfService("2026-03-20");
    }

    private static JsonNode build(CrdRunRequest request, Function<String, ResolvedText> variables) {
        return JsonSafe.parse(new CrdRequestBuilder(null).build(request, "hook-instance-1", variables).json()).node();
    }

    @Test
    void theRequestCarriesTheHookItsInstanceAndTheContext() {
        JsonNode json = build(request().build(), NOTHING_SET);
        assertThat(json.get("hook").asString()).isEqualTo("order-sign");
        assertThat(json.get("hookInstance").asString()).isEqualTo("hook-instance-1");
        assertThat(json.at("/context/patientId").asString()).isEqualTo("patient-1");
        assertThat(json.at("/context/userId").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(json.at("/context/draftOrders/resourceType").asString()).isEqualTo("Bundle");
        assertThat(json.at("/context/draftOrders/entry")).hasSize(2);
        // order-sign carries no selections. order-select does.
        assertThat(json.at("/context").has("selections")).isFalse();
    }

    @Test
    void eachProcedureCodeBecomesTheCodeOfItsOwnServiceRequest() {
        JsonNode json = build(request().build(), NOTHING_SET);
        JsonNode first = json.at("/context/draftOrders/entry/0/resource");
        assertThat(first.get("resourceType").asString()).isEqualTo("ServiceRequest");
        assertThat(first.get("id").asString()).isEqualTo("service-request-1");
        assertThat(first.at("/code/coding/0/code").asString()).isEqualTo("95810");
        assertThat(first.at("/code/coding/0/display").asString()).isEqualTo("Sleep study, attended");
        assertThat(first.at("/code/coding/0/system").asString()).isEqualTo("http://www.ama-assn.org/go/cpt");
        assertThat(first.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(first.at("/requester/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(first.get("occurrenceDateTime").asString()).isEqualTo("2026-03-20");
        JsonNode second = json.at("/context/draftOrders/entry/1/resource");
        assertThat(second.get("id").asString()).isEqualTo("service-request-2");
        assertThat(second.at("/code/coding/0/code").asString()).isEqualTo("E0601");
        assertThat(second.at("/code/coding/0/system").asString()).isEqualTo("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets");
        assertThat(second.at("/code/coding/0").has("display")).isFalse();
    }

    @Test
    void theMemberBecomesAPatientWithTheNoSpaceMemberId() {
        JsonNode patient = build(request().build(), NOTHING_SET).at("/prefetch/patient");
        assertThat(patient.get("resourceType").asString()).isEqualTo("Patient");
        assertThat(patient.at("/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(patient.at("/identifier/0/type/coding/0/code").asString()).isEqualTo("MB");
        assertThat(patient.at("/name/0/family").asString()).isEqualTo("Placeholder-Surname");
        assertThat(patient.at("/name/0/given/0").asString()).isEqualTo("Given");
        assertThat(patient.get("birthDate").asString()).isEqualTo("1970-01-31");
        assertThat(patient.get("gender").asString()).isEqualTo("female");
    }

    @Test
    void theNpiBecomesAPractitionerRoleAndAPractitioner() {
        JsonNode json = build(request().build(), NOTHING_SET);
        assertThat(json.at("/prefetch/practitionerRole/resourceType").asString()).isEqualTo("PractitionerRole");
        assertThat(json.at("/prefetch/practitionerRole/practitioner/reference").asString()).isEqualTo("Practitioner/practitioner-1");
        JsonNode practitioner = json.at("/prefetch/practitioner");
        assertThat(practitioner.at("/identifier/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/us-npi");
        assertThat(practitioner.at("/identifier/0/value").asString()).isEqualTo("1234567893");
        assertThat(practitioner.at("/name/0/family").asString()).isEqualTo("Provider");
    }

    @Test
    void theCoverageCarriesOnlyWhatWasSupplied() {
        JsonNode coverage = build(request().build(), NOTHING_SET).at("/prefetch/coverage");
        assertThat(coverage.get("status").asString()).isEqualTo("active");
        assertThat(coverage.get("subscriberId").asString()).isEqualTo("SUB-PLACEHOLDER-1");
        assertThat(coverage.at("/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(coverage.at("/beneficiary/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(coverage.at("/class/0/value").asString()).isEqualTo("Placeholder Plan");
        // No payer was configured, so none is named.
        assertThat(coverage.has("payor")).isFalse();
    }

    @Test
    void nothingIsInventedForAMemberAboutWhomOnlyTheIdIsKnown() {
        CrdRunRequest bare = CrdRunRequest.builder().member(MemberDetail.builder().memberId("S123456789").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).procedureCodes(List.of(CodeInput.of("95810"))).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null).build(bare, "h", NOTHING_SET);
        JsonNode json = JsonSafe.parse(built.json()).node();
        JsonNode patient = json.at("/prefetch/patient");
        assertThat(patient.has("name")).isFalse();
        assertThat(patient.has("birthDate")).isFalse();
        assertThat(patient.has("gender")).isFalse();
        // A coverage status that was not supplied is not "active". It is left out and the note says so.
        assertThat(json.at("/prefetch/coverage").has("status")).isFalse();
        assertThat(json.at("/prefetch/coverage").has("subscriberId")).isFalse();
        assertThat(json.at("/context/draftOrders/entry/0/resource").has("occurrenceDateTime")).isFalse();
        assertThat(json.has("fhirServer")).isFalse();
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).isEqualTo("No coverage status was sent, because none was supplied."));
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).isEqualTo("No date of birth was sent, because none was supplied."));
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).contains("without an identifier system").contains("memberIdSystem"));
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).contains("No fhirServer was sent"));
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).contains("No payer was named"));
    }

    @Test
    void aSourceSystemsOwnWordingIsNotTranslatedIntoAFhirCode() {
        // "Termed" might mean cancelled. Deciding that would be a guess, so no status is sent.
        CrdRunRequest request = request().member(MemberDetail.builder().memberId("S123456789").coverageStatus("Termed").gender("X").build()).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null).build(request, "h", NOTHING_SET);
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.at("/prefetch/coverage").has("status")).isFalse();
        assertThat(json.at("/prefetch/patient").has("gender")).isFalse();
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).contains("The coverage status \"Termed\" is not one this tool can map"));
        assertThat(built.notes()).anySatisfy(n -> assertThat(n).contains("The gender \"X\" is not one this tool can map"));
        assertThat(CaseResources.coverageStatus("ACTIVE")).isEqualTo("active");
        assertThat(CaseResources.coverageStatus("canceled")).isEqualTo("cancelled");
        assertThat(CaseResources.coverageStatus("inactive")).isNull();
        assertThat(CaseResources.gender("male")).isEqualTo("male");
        assertThat(CaseResources.gender(" ")).isNull();
    }

    @Test
    void configuredVariablesFillTheElementsThatNeedThem() {
        FakeGateway gateway = new FakeGateway().variable("fhirServer", "https://ehr.example.invalid/fhir")
                .variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id")
                .variable("payerName", "Placeholder payer").variable("payerIdentifier", "PAYER-PLACEHOLDER")
                .variable("payerIdentifierSystem", "https://example.org/p32h-placeholder/payer-id");
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null).build(request().build(), "h", t -> gateway.resolve(t, Map.of()));
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.get("fhirServer").asString()).isEqualTo("https://ehr.example.invalid/fhir");
        assertThat(json.at("/prefetch/patient/identifier/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/member-id");
        assertThat(json.at("/prefetch/coverage/payor/0/display").asString()).isEqualTo("Placeholder payer");
        assertThat(json.at("/prefetch/coverage/payor/0/identifier/value").asString()).isEqualTo("PAYER-PLACEHOLDER");
        assertThat(built.notes()).noneMatch(n -> n.contains("fhirServer") || n.contains("identifier system") || n.contains("No payer"));
    }

    @Test
    void theCodingSystemComesFromTheTesterThenTheCodeListThenTheFormatOfTheCode() {
        FakeCodeList list = new FakeCodeList().row(ProcedureCode.builder().code("70553").system("urn:from-the-code-list")
                .authRequired(AuthRequirement.UNKNOWN).build());
        CrdRunRequest request = request().procedureCodes(List.of(new CodeInput("70553", "urn:stated-by-the-tester", null),
                CodeInput.of("70553"), CodeInput.of("95810"), CodeInput.of("0042T"), CodeInput.of("G0299"), CodeInput.of("XYZ"))).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(list).build(request, "h", NOTHING_SET);
        JsonNode entries = JsonSafe.parse(built.json()).node().at("/context/draftOrders/entry");
        assertThat(entries.at("/0/resource/code/coding/0/system").asString()).isEqualTo("urn:stated-by-the-tester");
        assertThat(entries.at("/1/resource/code/coding/0/system").asString()).isEqualTo("urn:from-the-code-list");
        assertThat(entries.at("/2/resource/code/coding/0/system").asString()).isEqualTo(CaseResources.CPT);
        assertThat(entries.at("/3/resource/code/coding/0/system").asString()).isEqualTo(CaseResources.CPT);
        assertThat(entries.at("/4/resource/code/coding/0/system").asString()).isEqualTo(CaseResources.HCPCS);
        // A code whose format decides nothing is sent without a system, and that is stated.
        assertThat(entries.at("/5/resource/code/coding/0").has("system")).isFalse();
        assertThat(built.notes()).contains("The coding system for 95810 was taken from its format: CPT.",
                "No coding system could be worked out for XYZ, so it was sent without one.");
        assertThat(built.notes()).noneMatch(n -> n.contains("70553"));
    }

    @Test
    void aNameWhereTheCodeListShouldHoldACodingSystemIsNotSentAsOneAndTheRequestNotesSayWhatWasDone() {
        // An export that writes a name where the system belongs. A FHIR coding system is a uri, so
        // the name must not become the system of the order.
        FakeCodeList list = new FakeCodeList().row(ProcedureCode.builder().code("95810").system("CPT").build())
                .row(ProcedureCode.builder().code("E0601").system("Durable equipment").build());
        CrdRunRequest request = request().procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601"),
                new CodeInput("95810", "CPT", null))).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(list).build(request, "h", NOTHING_SET);
        JsonNode entries = JsonSafe.parse(built.json()).node().at("/context/draftOrders/entry");

        // A label the tool knows goes out as the uri it uses for it.
        assertThat(entries.at("/0/resource/code/coding/0/system").asString()).isEqualTo(CaseResources.CPT);
        // Anything else is not sent, and the format of the code decides.
        assertThat(entries.at("/1/resource/code/coding/0/system").asString()).isEqualTo(CaseResources.HCPCS);
        // What the tester typed is theirs to send, a label included.
        assertThat(entries.at("/2/resource/code/coding/0/system").asString()).isEqualTo("CPT");
        // Each of the first two is explained beside the request. Nothing is said about the third.
        assertThat(built.notes()).filteredOn(n -> n.contains("95810") || n.contains("E0601")).containsExactly(
                "The code list gives the coding system for 95810 as the label \"CPT\", which is not a uri. The uri this tool uses for CPT "
                        + "was sent in its place: http://www.ama-assn.org/go/cpt.",
                "The code list gives the coding system for E0601 as \"Durable equipment\". That is not a uri, and it is not a label this "
                        + "tool knows, so it was not sent as the system.",
                "The coding system for E0601 was taken from its format: HCPCS Level II.");
    }

    @Test
    void theBuilderReportsEachOrderItSentWithTheCodeOnIt() {
        // This list is what lets an answer be tied to its code, so it must match the body exactly.
        CrdRunRequest request = request().procedureCodes(List.of(CodeInput.of(" 95810 "), CodeInput.of("E0601"))).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null).build(request, "h", NOTHING_SET);
        assertThat(built.orders()).containsExactly(new SentOrder("ServiceRequest/service-request-1", "95810"),
                new SentOrder("ServiceRequest/service-request-2", "E0601"));
        // Reading the orders back out of the body gives the same list, which is the path a tester's own body takes.
        assertThat(SentOrder.fromBody(built.json())).isEqualTo(built.orders());
        JsonNode entries = JsonSafe.parse(built.json()).node().at("/context/draftOrders/entry");
        assertThat(entries.at("/1/resource/id").asString()).isEqualTo("service-request-2");
        assertThat(entries.at("/1/resource/code/coding/0/code").asString()).isEqualTo("E0601");
    }

    @Test
    void theOrdersOfATestersOwnBodyAreReadWithoutAssumingItsShape() {
        assertThat(SentOrder.fromBody(null)).isEmpty();
        assertThat(SentOrder.fromBody("not json")).isEmpty();
        assertThat(SentOrder.fromBody("{\"hook\":\"order-sign\"}")).isEmpty();
        // A vendor's ids, an order with no code, an order with no id and an entry that is not a resource.
        String body = "{\"context\":{\"draftOrders\":{\"entry\":[{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"abc\","
                + "\"code\":{\"coding\":[{\"code\":\"70553\"},{\"code\":\"ignored-second\"}]}}},"
                + "{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"no-code\"}},"
                + "{\"resource\":{\"resourceType\":\"MedicationRequest\",\"medicationCodeableConcept\":{\"coding\":[{\"code\":\"J1745\"}]}}},"
                + "{\"fullUrl\":\"x\"},\"text\"]}}}";
        assertThat(SentOrder.fromBody(body)).containsExactly(new SentOrder("ServiceRequest/abc", "70553"),
                new SentOrder("ServiceRequest/no-code", null), new SentOrder(null, "J1745"));
        assertThat(new SentOrder("ServiceRequest/no-code", null).label()).isEqualTo("ServiceRequest/no-code");
        assertThat(new SentOrder("ServiceRequest/abc", "70553").label()).isEqualTo("70553");
    }

    @Test
    void orderSelectCarriesItsSelectionsAndPrefetchIsOptional() {
        JsonNode select = build(request().hook(" Order-Select ").build(), NOTHING_SET);
        assertThat(select.get("hook").asString()).isEqualTo("order-select");
        assertThat(select.at("/context/selections")).hasSize(2);
        assertThat(select.at("/context/selections/1").asString()).isEqualTo("ServiceRequest/service-request-2");
        JsonNode noPrefetch = build(request().includePrefetch(false).build(), NOTHING_SET);
        assertThat(noPrefetch.has("prefetch")).isFalse();
        assertThat(build(request().includePrefetch(null).build(), NOTHING_SET).has("prefetch")).isTrue();
    }
}
