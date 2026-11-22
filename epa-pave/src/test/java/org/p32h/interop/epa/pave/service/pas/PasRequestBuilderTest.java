package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import tools.jackson.databind.JsonNode;

/**
 * What the PAS request builder puts on the wire for a submission and for an inquiry, held to Da
 * Vinci PAS 2.2.1 (input/fsh of the guide's 2.2.1 tag), and what it says it left out. Each element
 * R-39 adds is held one by one in {@link Pas221SubmitTest}.
 */
class PasRequestBuilderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String CLAIM = "claim-00000000-0000-4000-8000-000000000001";
    private static final String BUNDLE = "urn:uuid:00000000-0000-4000-8000-0000000000b1";
    /** Answers as DTR hands them on: they name the member, the coverage, the practitioner and the first order by their local ids. */
    private static final String ANSWERS = "{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"qr-sleep-1\",\"status\":\"completed\","
            + "\"subject\":{\"reference\":\"Patient/patient-1\"},\"extension\":[{\"url\":\"http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context\","
            + "\"valueReference\":{\"reference\":\"ServiceRequest/service-request-1\"}},{\"url\":\"http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context\","
            + "\"valueReference\":{\"reference\":\"Coverage/coverage-1\"}}],"
            + "\"item\":[{\"linkId\":\"1\",\"answer\":[{\"valueString\":\"Placeholder answer about daytime sleepiness\",\"extension\":[{\"url\":"
            + "\"http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin\",\"extension\":[{\"url\":\"author\","
            + "\"valueReference\":{\"reference\":\"Practitioner/practitioner-1\"}}]}]}]}]}";
    private static final String REPORT = "Placeholder report: attended sleep study, synthetic text.";
    private static final String HCPCS_201 = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    private static final Function<String, ResolvedText> NO_VARIABLES = variables(Map.of());
    private static final Map<String, String> ALL = Map.ofEntries(Map.entry("claimIdentifierSystem", "https://example.org/p32h-placeholder/claims"),
            Map.entry("payerName", "Placeholder payer"), Map.entry("payerIdentifier", "PAYER-PLACEHOLDER-1"),
            Map.entry("payerIdentifierSystem", "https://example.org/p32h-placeholder/payers"),
            Map.entry("itemTraceNumberSystem", "https://example.org/p32h-placeholder/trace"),
            Map.entry("memberIdSystem", "https://example.org/p32h-placeholder/member-id"), Map.entry("pasServiceType", "1"),
            Map.entry("pasRequestType", "HS"), Map.entry("coverageRelationship", "self"));

    /** Resolves a placeholder from a map, and leaves it unresolved when the map has no value for it. */
    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            String name = template.substring(2, template.length() - 2);
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    private static PasRequestBuilder builder(String mode) {
        AtomicInteger next = new AtomicInteger();
        return new PasRequestBuilder(new FakeCodeList(), mode, CLOCK,
                () -> UUID.fromString(String.format("00000000-0000-4000-8000-%012d", next.incrementAndGet())));
    }

    private static PasRequestBuilder.Built submit(PasSubmitRequest request, Map<String, String> values) {
        return builder(StartupChecks.ATTACHMENT_FHIR).submit(request, CLAIM, true, BUNDLE, variables(values));
    }

    private static PasSubmitRequest.Builder request() {
        return PasSubmitRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").lineOfBusiness("Medicare").coverageStatus("active").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .dateOfService("2026-03-20");
    }

    /** The request with what the values cannot supply: the provider's phone and the place of service. */
    private static PasSubmitRequest.Builder complete() {
        return request().provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").phone("555-0100").build())
                .placeOfService("11");
    }

    private static JsonNode bundle(PasRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    private static List<String> types(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        bundle.get("entry").forEach(entry -> out.add(entry.get("resource").get("resourceType").asString()));
        return out;
    }

    private static JsonNode claim(PasRequestBuilder.Built built) {
        return bundle(built).get("entry").get(0).get("resource");
    }

    private static JsonNode resource(PasRequestBuilder.Built built, String type) {
        for (JsonNode entry : bundle(built).get("entry")) {
            if (type.equals(entry.get("resource").get("resourceType").asString())) {
                return entry.get("resource");
            }
        }
        return null;
    }

    private static JsonNode named(PasRequestBuilder.Built built, JsonNode holder, String reference) {
        return PasBundles.resolve(bundle(built), PasBundles.fullUrlOf(bundle(built), holder.get("resourceType").asString()), reference);
    }

    private static JsonNode extension(JsonNode element, String url) {
        for (JsonNode extension : element.path("extension")) {
            if (url.equals(extension.get("url").asString())) {
                return extension;
            }
        }
        return null;
    }

    // ---- the bundle ----

    @Test
    void everyEntryHasAFullUrlAndEveryReferenceInsideTheBundleResolvesToAnEntry() {
        // PAS 2.2.1 PASRequestBundle: entry.fullUrl 1..1, and with no address of their own the
        // resources are named by urn:uuid, which a relative reference cannot reach.
        PasRequestBuilder.Built built = submit(request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build(), Map.of());
        JsonNode bundle = bundle(built);

        List<String> fullUrls = new ArrayList<>();
        bundle.get("entry").forEach(entry -> fullUrls.add(entry.get("fullUrl").asString()));
        assertThat(fullUrls).allSatisfy(url -> assertThat(url).matches("urn:uuid:[0-9a-f-]{36}"));
        assertThat(new HashSet<>(fullUrls)).hasSize(fullUrls.size());
        assertThat(PasBundles.unresolved(bundle)).isEmpty();
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("Every entry carries a full url of the form urn:uuid:"));

        // With a base stated, the full urls are addresses and the references stay relative to them.
        Map<String, String> based = new HashMap<>(Map.of("fhirServer", "https://ehr.example.invalid/fhir/"));
        PasRequestBuilder.Built withBase = submit(request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build(), based);
        assertThat(bundle(withBase).at("/entry/0/fullUrl").asString()).isEqualTo("https://ehr.example.invalid/fhir/Claim/claim-1");
        assertThat(claim(withBase).at("/patient/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(PasBundles.unresolved(bundle(withBase))).isEmpty();
    }

    @Test
    void theAnswersHandedOnFromDtrNameTheOrderAndTheBundleCarriesIt() {
        // D-135: the answers name ServiceRequest/service-request-1, and the PAS order for the first item carries that id.
        PasRequestBuilder.Built built = submit(request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build(), Map.of());
        JsonNode answers = resource(built, "QuestionnaireResponse");
        JsonNode order = named(built, answers, answers.at("/extension/0/valueReference/reference").asString());
        assertThat(order.get("id").asString()).isEqualTo("service-request-1");
        assertThat(order.at("/code/coding/0/code").asString()).isEqualTo("95810");
        assertThat(named(built, answers, answers.at("/extension/1/valueReference/reference").asString()).get("resourceType").asString())
                .isEqualTo("Coverage");
        assertThat(named(built, answers, answers.at("/item/0/answer/0/extension/0/extension/0/valueReference/reference").asString())
                .get("resourceType").asString()).isEqualTo("Practitioner");
        // What a person answered reaches the wire unchanged; only the references were pointed at the entries.
        assertThat(answers.at("/item/0/answer/0/valueString").asString()).isEqualTo("Placeholder answer about daytime sleepiness");
        JsonNode info = claim(built).at("/supportingInfo/0");
        assertThat(named(built, claim(built), info.at("/valueReference/reference").asString()).get("id").asString()).isEqualTo("qr-sleep-1");
        assertThat(info.at("/category/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes");
        assertThat(info.at("/category/coding/0/code").asString()).isEqualTo("additionalInformation");
        assertThat(built.attachmentMode()).isNull();
    }

    @Test
    void aSubmissionIsAPasClaimWithOneItemAndOneOrderForEachCode() {
        PasRequestBuilder.Built built = submit(request().build(), Map.of());
        JsonNode bundle = bundle(built);
        assertThat(bundle.get("type").asString()).isEqualTo("collection");
        assertThat(bundle.at("/identifier/value").asString()).isEqualTo(BUNDLE);
        // Whole seconds in UTC, whatever the zone of the clock: the vendor refused fractions and a local offset.
        assertThat(bundle.get("timestamp").asString()).isEqualTo("2026-03-10T15:00:00Z");
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "ServiceRequest",
                "ServiceRequest");

        JsonNode claim = claim(built);
        assertThat(claim.get("use").asString()).isEqualTo("preauthorization");
        assertThat(claim.get("status").asString()).isEqualTo("active");
        assertThat(claim.at("/identifier/0/value").asString()).isEqualTo(CLAIM);
        assertThat(named(built, claim, claim.at("/provider/reference").asString()).get("resourceType").asString()).isEqualTo("PractitionerRole");
        assertThat(claim.at("/insurance/0/focal").asBoolean()).isTrue();

        JsonNode item = claim.at("/item/1");
        assertThat(item.get("sequence").asInt()).isEqualTo(2);
        // PAS 2.2.1 X12278RequestedServiceType names HCPCS with http and no s.
        assertThat(item.at("/productOrService/coding/0/system").asString()).isEqualTo(HCPCS_201);
        assertThat(claim.at("/item/0/productOrService/coding/0/system").asString()).isEqualTo("http://www.ama-assn.org/go/cpt");
        assertThat(item.get("servicedDate").asString()).isEqualTo("2026-03-20");
        assertThat(extension(item, "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber")
                .at("/valueIdentifier/value").asString()).isEqualTo(CLAIM + "-2");
        JsonNode certification = extension(item, "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType");
        assertThat(certification.at("/valueCodeableConcept/coding/0/system").asString()).isEqualTo("https://codesystem.x12.org/005010/1322");
        assertThat(certification.at("/valueCodeableConcept/coding/0/code").asString()).isEqualTo("I");
        JsonNode order = named(built, claim, extension(item, "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-requestedService")
                .at("/valueReference/reference").asString());
        assertThat(order.get("id").asString()).isEqualTo("service-request-2");
        assertThat(order.get("intent").asString()).isEqualTo("order");
        assertThat(order.at("/code/coding/0/system").asString()).isEqualTo(HCPCS_201);
        // PASServiceRequest allows only a Timing as the occurrence.
        assertThat(order.has("occurrenceDateTime")).isFalse();
        assertThat(order.at("/occurrenceTiming/event/0").asString()).isEqualTo("2026-03-20");

        assertThat(bundle.at("/entry/1/resource/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(built.json()).doesNotContain("S12345678   01");
        assertThat(built.sent()).isEqualTo(new SentClaim(CLAIM, List.of(new SentClaim.Item(1, "95810", CLAIM + "-1"),
                new SentClaim.Item(2, "E0601", CLAIM + "-2"))));
        assertThat(SentClaim.fromBody(built.json())).isEqualTo(built.sent());
    }

    // ---- what the profiles require and nobody supplied ----

    @Test
    void whatTheProfilesRequireAndNobodySuppliedIsLeftOutAndSaidFirstWithTheCount() {
        PasRequestBuilder.Built built = submit(request().build(), Map.of());
        assertThat(built.leftOut()).containsExactly("PASClaim insurer", "PASBeneficiary identifier.system",
                "PASCoverage subscriber (rule self-beneficiary)", "PASCoverage payor", "PASPractitionerRole telecom (rule pd-1)",
                "PASClaim identifier.system", "PASClaim item.extension[itemTraceNumber] system", "PASClaim item.category",
                "PASClaim item.extension[requestType]", "PASClaim item.location[x]");
        assertThat(built.notes().get(0)).isEqualTo("This request goes out without 10 elements PAS 2.2.1 requires, because nobody "
                + "supplied them: PASClaim insurer; PASBeneficiary identifier.system; PASCoverage subscriber (rule self-beneficiary); "
                + "PASCoverage payor; PASPractitionerRole telecom (rule pd-1); PASClaim identifier.system; PASClaim "
                + "item.extension[itemTraceNumber] system; PASClaim item.category; PASClaim item.extension[requestType]; PASClaim "
                + "item.location[x]. The service may refuse the request for that alone.");
        assertThat(claim(built).has("insurer")).isFalse();
        assertThat(claim(built).at("/item/0").has("category")).isFalse();
        assertThat(built.notes()).contains("No service type was sent on the items: none was chosen on the Service card and the value "
                + "pasServiceType is not set. PAS 2.2.1 PASClaim requires item.category, an X12 service type code such as 1 for medical "
                + "care: choose it under Service details, or add PAS service type under Values in Configuration > Environments.");
        assertThat(built.notes()).contains("Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit provider fields. PAS 2.2.1 "
                + "PASPractitionerRole, on US Core 7.0.0 PractitionerRole, needs a phone, a fax or an endpoint on the requesting provider's role.");
        assertThat(built.notes()).contains("The claim identifier was sent without an identifier system: the variable claimIdentifierSystem "
                + "is not set. PAS 2.2.1 gives the claim identifier the profile PASIdentifier, which requires a system: add "
                + "claimIdentifierSystem under Values in Configuration > Environments.");
        // Must-support elements are said, as information, and never counted.
        assertThat(built.notes()).contains("No relationship of the member to the subscriber was sent: the variable coverageRelationship is "
                + "not set. PAS 2.2.1 PASCoverage marks it must-support.");
        assertThat(built.notes()).contains("No level of service was sent: none was chosen on the Service card. PAS 2.2.1 PASClaim marks it "
                + "must-support.");
        // Nothing of an older version of the guide is named.
        assertThat(String.join(" ", built.notes())).doesNotContain("2.0.1").doesNotContain("2.2.0").doesNotContain("3.1.1");
    }

    @Test
    void whenTheValuesAreSuppliedNothingIsLeftOutAndTheInsurerIsTheOneTheClaimAndTheCoveragePointAt() {
        PasRequestBuilder.Built built = submit(complete().build(), ALL);
        assertThat(built.leftOut()).isEmpty();
        assertThat(built.notes().get(0)).doesNotStartWith("This request goes out without");
        JsonNode claim = claim(built);
        assertThat(claim.at("/identifier/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/claims");
        JsonNode insurer = named(built, claim, claim.at("/insurer/reference").asString());
        assertThat(insurer.get("resourceType").asString()).isEqualTo("Organization");
        assertThat(insurer.get("active").asBoolean()).isTrue();
        assertThat(insurer.get("name").asString()).isEqualTo("Placeholder payer");
        assertThat(insurer.at("/type/0/coding/0/system").asString()).isEqualTo("https://codesystem.x12.org/005010/98");
        assertThat(insurer.at("/type/0/coding/0/code").asString()).isEqualTo("PR");
        JsonNode coverage = resource(built, "Coverage");
        assertThat(named(built, coverage, coverage.at("/payor/0/reference").asString())).isEqualTo(insurer);
        assertThat(coverage.has("payor") && coverage.at("/payor/0").has("identifier")).isFalse();
        // The relationship self, with the X12 code beside the FHIR one, makes the member the subscriber.
        assertThat(coverage.at("/relationship/coding/0/code").asString()).isEqualTo("self");
        assertThat(coverage.at("/relationship/coding/1/system").asString()).isEqualTo("https://codesystem.x12.org/005010/1069");
        assertThat(coverage.at("/relationship/coding/1/code").asString()).isEqualTo("18");
        assertThat(named(built, coverage, coverage.at("/subscriber/reference").asString()).get("resourceType").asString()).isEqualTo("Patient");
        JsonNode item = claim.at("/item/0");
        assertThat(item.at("/category/coding/0/system").asString()).isEqualTo("https://codesystem.x12.org/005010/1365");
        assertThat(item.at("/category/coding/0/code").asString()).isEqualTo("1");
        assertThat(extension(item, "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-serviceItemRequestType")
                .at("/valueCodeableConcept/coding/0/code").asString()).isEqualTo("HS");
    }

    @Test
    void aCoverageStatusNobodyGaveIsNeverSentAsActiveAndOneThatIsNotActiveIsSentAsGiven() {
        PasSubmitRequest bare = complete().member(MemberDetail.builder().memberId("S123456789").build()).dateOfService(null).build();
        PasRequestBuilder.Built built = submit(bare, ALL);
        JsonNode coverage = resource(built, "Coverage");
        assertThat(coverage.has("status")).isFalse();
        assertThat(built.leftOut()).containsExactly("PASBeneficiary name", "PASBeneficiary gender", "PASCoverage status");
        assertThat(built.notes()).contains("No coverage status was sent, because none was supplied. PAS 2.2.1 PASCoverage requires a status "
                + "and allows only \"active\". Looking the member up, or stating the coverage status on the form, supplies it.");
        JsonNode patient = resource(built, "Patient");
        assertThat(patient.has("name")).isFalse();
        assertThat(patient.has("gender")).isFalse();
        assertThat(claim(built).at("/item/0").has("servicedDate")).isFalse();

        PasRequestBuilder.Built cancelled = submit(complete().member(MemberDetail.builder().memberId("S123456789").firstName("Given")
                .lastName("Placeholder-Surname").gender("female").coverageStatus("cancelled").build()).build(), ALL);
        assertThat(resource(cancelled, "Coverage").get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.leftOut()).isEmpty();
        assertThat(cancelled.notes()).contains("The coverage status was sent as \"cancelled\", as it was supplied. PAS 2.2.1 PASCoverage allows "
                + "only \"active\", so the service may refuse the request for that alone.");
    }

    @Test
    void aClaimIdentifierThatWasNotMintedHereSaysNothingAboutTheCertificationType() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().build(), CLAIM, false, BUNDLE, NO_VARIABLES);
        assertThat(extension(claim(built).at("/item/0"), "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType")).isNull();
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("No certification type was sent on the items"));
        // PAS 2.2.1 PASClaim makes it 1..1 on every item, so its absence is counted.
        assertThat(built.leftOut()).contains("PASClaim item.extension[certificationType]");
    }

    @Test
    void aProviderKnownOnlyAsAnOrganizationIsTheRequestorOrganization() {
        PasRequestBuilder.Built built = submit(request().provider(ProviderDetail.builder().npi("1234567893").organizationName("Placeholder Sleep "
                + "Clinic").build()).build(), Map.of());
        assertThat(types(bundle(built))).containsExactly("Claim", "Patient", "Coverage", "Organization", "ServiceRequest", "ServiceRequest");
        JsonNode requestor = named(built, claim(built), claim(built).at("/provider/reference").asString());
        assertThat(requestor.get("resourceType").asString()).isEqualTo("Organization");
        assertThat(requestor.get("name").asString()).isEqualTo("Placeholder Sleep Clinic");
        assertThat(requestor.get("active").asBoolean()).isTrue();
        assertThat(requestor.at("/identifier/0/value").asString()).isEqualTo("1234567893");
        // No Practitioner with an organization's name in it.
        assertThat(built.json()).doesNotContain("\"Practitioner\"");
        assertThat(PasBundles.unresolved(bundle(built))).isEmpty();
    }

    @Test
    void theHcpcsSystemCanBeOverriddenAndASystemOutsideTheValueSetIsSaid() {
        PasRequestBuilder.Built built = submit(request().build(), Map.of("pasHcpcsSystem", "urn:oid:2.16.840.1.113883.6.285"));
        assertThat(claim(built).at("/item/1/productOrService/coding/0/system").asString()).isEqualTo("urn:oid:2.16.840.1.113883.6.285");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("The code E0601 was sent with the system "
                + "urn:oid:2.16.840.1.113883.6.285, which is not one of the systems of the value set X12278RequestedServiceType"));
    }

    @Test
    void theCodeListDecidesACodingSystemBeforeTheFormatOfTheCodeDoes() {
        FakeCodeList codeList = new FakeCodeList().row(ProcedureCode.builder().code("95810").system("https://example.org/p32h-placeholder/codes")
                .authRequired(AuthRequirement.REQUIRED).build())
                .row(ProcedureCode.builder().code("E0601").system("HCPCS").authRequired(AuthRequirement.REQUIRED).build());
        PasRequestBuilder.Built built = new PasRequestBuilder(codeList, StartupChecks.ATTACHMENT_FHIR, CLOCK)
                .submit(request().build(), CLAIM, true, BUNDLE, NO_VARIABLES);
        assertThat(claim(built).at("/item/0/productOrService/coding/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/codes");
        // A label in the list is replaced by the uri PAS 2.2.1 names.
        assertThat(claim(built).at("/item/1/productOrService/coding/0/system").asString()).isEqualTo(HCPCS_201);
    }

    @Test
    void aDiagnosisWithNoSystemThatCanBeWorkedOutIsSentWithANoteAboutTheBinding() {
        PasRequestBuilder.Built built = submit(request().diagnosisCodes(List.of(new CodeInput("G47.33", null, "Obstructive sleep apnea"),
                new CodeInput("327.23", null, null))).build(), Map.of());
        JsonNode diagnosis = claim(built).get("diagnosis");
        assertThat(diagnosis.at("/0/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(diagnosis.at("/1/diagnosisCodeableConcept/coding/0").has("system")).isFalse();
        assertThat(built.notes()).contains("No coding system could be worked out for the diagnosis 327.23, so it was sent without one. PAS 2.2.1 "
                + "binds the diagnosis, required, to the value set X12278DiagnosisCodes, so the service may refuse it. State its system with the code.");
    }

    @Test
    void aCodeWithoutTheSystemItsRequiredBindingNeedsIsCountedAmongWhatTheRequestLeftOut() {
        // PAS 2.2.1 PASClaimBase binds item.productOrService and diagnosis.diagnosisCodeableConcept, required; PASServiceRequest binds code.
        PasRequestBuilder.Built built = submit(complete().procedureCodes(List.of(CodeInput.of("ABC12")))
                .diagnosisCodes(List.of(CodeInput.of("XYZ"))).build(), ALL);
        assertThat(built.leftOut()).containsExactly("PASClaim diagnosis.diagnosisCodeableConcept system (diagnosis XYZ)",
                "PASServiceRequest code system (code ABC12)", "PASClaim item.productOrService system (code ABC12)");
        assertThat(built.notes().get(0)).startsWith("This request goes out without 3 elements PAS 2.2.1 requires");
        assertThat(built.notes()).contains("The order ServiceRequest/service-request-1 carries the code ABC12 with no system, as its item "
                + "does. PAS 2.2.1 PASServiceRequest binds code, required, to the value set X12278RequestedServiceType.");
        assertThat(claim(built).at("/item/0/productOrService/coding/0").has("system")).isFalse();
        // A code whose system is known is not counted.
        assertThat(submit(complete().build(), ALL).leftOut()).isEmpty();
    }

    @Test
    void aReferenceTheBundleCannotResolveIsCountedAmongWhatTheRequestLeftOut() {
        // The answers name Practitioner/practitioner-1, and a provider known only as an organization brings no Practitioner.
        Map<String, String> values = new HashMap<>(ALL);
        values.put("pasReportType", "09");
        PasRequestBuilder.Built built = submit(complete().provider(ProviderDetail.builder().npi("1234567893").organizationName("Placeholder "
                + "Sleep Clinic").build()).carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build(), values);
        assertThat(PasBundles.unresolved(bundle(built))).isNotEmpty();
        assertThat(built.leftOut()).containsExactly("PASRequestBundle entry for Practitioner/practitioner-1");
        assertThat(built.notes().get(0)).startsWith("This request goes out without 1 element PAS 2.2.1 requires");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("These references inside the bundle name a resource the "
                + "bundle does not carry: Practitioner/practitioner-1. PAS 2.2.1 requires (SHALL)"));
    }

    @Test
    void anInquiryWithNoPayerNamesTheInquiryProfileItBuilds() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).inquire(inquiry().build(), CLAIM, List.of(CodeInput.of("95810")),
                "given on the form", BUNDLE, NO_VARIABLES);
        assertThat(built.leftOut()).contains("PASClaimInquiry insurer").doesNotContain("PASClaim insurer");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("PAS 2.2.1 PASClaimInquiry requires the insurer (PASClaimBase, "
                + "which PASClaim and PASClaimInquiry share, makes it 1..1)"));
    }

    @Test
    void answersThatCarryNoIdAreGivenOneSoTheClaimCanReferToThemAndTheNoteSaysSo() {
        String noId = ANSWERS.replace("\"id\":\"qr-sleep-1\",", "");
        PasRequestBuilder.Built built = submit(request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(noId))).build(), Map.of());
        assertThat(resource(built, "QuestionnaireResponse").get("id").asString()).isEqualTo("questionnaire-response-1");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("carried no id").contains("questionnaire-response-1"));
    }

    @Test
    void answersThatAreNotAQuestionnaireResponseAreAProblemAndNeverQuietlyDropped() {
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS)))).isNull();
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of())).isNull();
        assertThat(PasRequestBuilder.questionnaireProblem(null)).isNull();
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(" ")))).isNull();
        for (String bad : List.of("{\"resourceType\": ", "[1]", "{\"resourceType\":\"Questionnaire\"}", "\"text\"")) {
            String problem = PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS, bad)));
            assertThat(problem).as(bad).contains("not a QuestionnaireResponse");
            assertThat(problem).doesNotContain("Questionnaire\"").doesNotContain("text");
        }
    }

    // ---- attachments, both ways ----

    private static final CodeInput NOTE_TYPE = new CodeInput("11506-3", "http://loinc.org", "Progress note");
    private static final CodeInput CLINICAL_NOTE = new CodeInput("clinical-note",
            "http://hl7.org/fhir/us/core/CodeSystem/us-core-documentreference-category", null);

    private static PasSubmitRequest withAttachments() {
        return request().attachments(List.of(new PasAttachment("Sleep study report", "text/plain", REPORT, null, NOTE_TYPE, CLINICAL_NOTE),
                new PasAttachment("Referral letter", "application/pdf", null, "https://documents.example.invalid/referral-1.pdf"))).build();
    }

    @Test
    void inFhirModeTheDocumentTravelsInTheBundleWithTheTypeAndCategoryTheTesterGave() {
        PasRequestBuilder.Built built = submit(withAttachments(), Map.of());
        JsonNode bundle = bundle(built);
        assertThat(types(bundle)).endsWith("DocumentReference", "DocumentReference");
        JsonNode inline = named(built, claim(built), claim(built).at("/supportingInfo/0/valueReference/reference").asString());
        assertThat(inline.get("id").asString()).isEqualTo("document-reference-1");
        assertThat(inline.at("/type/coding/0/code").asString()).isEqualTo("11506-3");
        assertThat(inline.at("/category/0/coding/0/code").asString()).isEqualTo("clinical-note");
        assertThat(new String(Base64.getDecoder().decode(inline.at("/content/0/attachment/data").asString()), StandardCharsets.UTF_8)).isEqualTo(REPORT);
        JsonNode byUrl = named(built, claim(built), claim(built).at("/supportingInfo/1/valueReference/reference").asString());
        assertThat(byUrl.at("/content/0/attachment/url").asString()).isEqualTo("https://documents.example.invalid/referral-1.pdf");
        assertThat(byUrl.has("type")).isFalse();
        // The second document has neither, and US Core 7.0.0 DocumentReference, the parent of PASDocumentReference, requires both.
        assertThat(built.leftOut()).contains("PASDocumentReference type (attachment 2)",
                "PASDocumentReference category (attachment 2)").doesNotContain("PASDocumentReference type (attachment 1)");
        // PAS 2.2.1 forbids the url the second one was given, and requires its data.
        assertThat(built.leftOut()).contains("PASDocumentReference content.attachment.data (attachment 2)");
        assertThat(built.attachmentMode()).isEqualTo("fhir");
        assertThat(built.notes()).contains("Attachment 1 (\"Sleep study report\") travels in the bundle as DocumentReference/document-reference-1, "
                + "with its content inline.");
    }

    @Test
    void inX12ModeTheClaimOnlyNamesTheAttachmentAndTheNoteSaysNoDocumentIsSent() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_X12_275).submit(withAttachments(), CLAIM, true, BUNDLE, NO_VARIABLES);
        assertThat(types(bundle(built))).doesNotContain("DocumentReference");
        assertThat(built.json()).doesNotContain(REPORT).doesNotContain("referral-1.pdf");
        JsonNode claim = claim(built);
        assertThat(claim.at("/supportingInfo/0/valueReference/identifier/value").asString()).isEqualTo(CLAIM + "-A1");
        assertThat(claim.at("/supportingInfo/0/category/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes");
        assertThat(built.attachmentMode()).isEqualTo("x12-275");
        assertThat(built.notes()).contains("Attachment 1 (\"Sleep study report\") was not put in the bundle. In the attachment mode x12-275 the "
                + "claim only names the document, by the attachment control number " + CLAIM + "-A1, for an X12 275 transaction that this "
                + "tool does not send. The service receives no document from this tool. In PAS 2.2.1 a 275 is produced by the intermediary "
                + "from the bundle, not sent by the client beside it. The document type 11506-3 and category clinical-note given with "
                + "it were not sent either, because only the DocumentReference the bundle would carry has a place for them.");
        // The second attachment was given neither, so its note says nothing about them.
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("Attachment 2 (").endsWith("not sent by the client beside it."));
        assertThat(built.notes()).contains("Neither attachment mode is the PAS 2.2.1 way of sending documentation after the payer has asked "
                + "for it: that is a claim update that replaces the earlier claim, or the CDex operation $submit-attachment, and this tool "
                + "builds neither.");
    }

    // ---- the inquiry ----

    private static PasInquireRequest.Builder inquiry() {
        return PasInquireRequest.builder().member(MemberDetail.builder().memberId("S123456789").lineOfBusiness("Medicare").build())
                .provider(ProviderDetail.builder().npi("1234567893").build());
    }

    @Test
    void anInquiryListsTheItemsOfTheSubmissionWithTheirTraceNumbersAndNoOrders() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).inquire(inquiry().build(), CLAIM,
                List.of(CodeInput.of("95810"), CodeInput.of("E0601")), "handed on from the submission", BUNDLE, NO_VARIABLES);
        JsonNode claim = claim(built);
        assertThat(types(bundle(built))).doesNotContain("ServiceRequest");
        assertThat(claim.get("item")).hasSize(2);
        assertThat(claim.at("/item/1/productOrService/coding/0/code").asString()).isEqualTo("E0601");
        assertThat(extension(claim.at("/item/1"), "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber")
                .at("/valueIdentifier/value").asString()).isEqualTo(CLAIM + "-2");
        assertThat(extension(claim.at("/item/1"), "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-requestedService")).isNull();
        // An inquiry does not require a category, so its absence is not counted.
        assertThat(built.leftOut()).doesNotContain("PASClaim item.category");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("lists the procedure codes handed on from the submission as items"));
        assertThat(built.sent().items()).containsExactly(new SentClaim.Item(1, "95810", CLAIM + "-1"), new SentClaim.Item(2, "E0601", CLAIM + "-2"));
        assertThat(SentClaim.fromBody(built.json())).isEqualTo(built.sent());
        assertThat(PasBundles.unresolved(bundle(built))).isEmpty();
    }

    @Test
    void anInquiryWithNoItemKnownIsStillSentAndSaysThat221DoesNotRequireOne() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).inquire(inquiry().build(), CLAIM, List.of(), null, BUNDLE,
                NO_VARIABLES);
        assertThat(claim(built).has("item")).isFalse();
        // PAS 2.2.1 PASClaimInquiry has no item 1..*; only PASClaim does.
        assertThat(built.leftOut()).noneMatch(element -> element.contains("item"));
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).endsWith("PAS 2.2.1 does not require an item on an inquiry."));
    }

    @Test
    void aBuiltRequestIsNeverPrintedWithItsBody() {
        PasRequestBuilder.Built built = submit(withAttachments(), Map.of());
        assertThat(built.toString()).doesNotContain("Placeholder-Surname").doesNotContain("S1234567801").doesNotContain("Bundle");
        assertThat(withAttachments().toString()).doesNotContain("Placeholder-Surname").doesNotContain(REPORT);
        assertThat(new PasAttachment("Sleep study report", "text/plain", REPORT, null).toString()).doesNotContain(REPORT);
        assertThat(inquiry().build().toString()).doesNotContain("S123456789");
    }
}
