package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

/** What the PAS request builder puts on the wire for a submission and for an inquiry, and what it says it left out. */
class PasRequestBuilderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String CLAIM = "claim-00000000-0000-4000-8000-000000000001";
    private static final String BUNDLE = "urn:uuid:00000000-0000-4000-8000-0000000000b1";
    private static final String ANSWERS = "{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"qr-sleep-1\",\"status\":\"completed\","
            + "\"item\":[{\"linkId\":\"1\",\"answer\":[{\"valueString\":\"Placeholder answer about daytime sleepiness\"}]}]}";
    private static final String REPORT = "Placeholder report: attended sleep study, synthetic text.";

    private static final Function<String, ResolvedText> NO_VARIABLES = variables(Map.of());

    /** Resolves a placeholder from a map, and leaves it unresolved when the map has no value for it. */
    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            String name = template.substring(2, template.length() - 2);
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    private static PasRequestBuilder builder(String mode) {
        return new PasRequestBuilder(new FakeCodeList(), mode, CLOCK);
    }

    private static PasSubmitRequest.Builder request() {
        return PasSubmitRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").lineOfBusiness("Medicare").coverageStatus("active").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .dateOfService("2026-03-20");
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

    @Test
    void aSubmissionIsABundleWhoseClaimTiesTheMemberTheCoverageTheProviderAndOneItemForEachCode() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().build(), CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode bundle = bundle(built);

        assertThat(bundle.get("resourceType").asString()).isEqualTo("Bundle");
        assertThat(bundle.get("type").asString()).isEqualTo("collection");
        assertThat(bundle.at("/identifier/system").asString()).isEqualTo("urn:ietf:rfc:3986");
        assertThat(bundle.at("/identifier/value").asString()).isEqualTo(BUNDLE);
        assertThat(bundle.get("timestamp").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        // The claim comes first, and no insurer is sent because no payer was named.
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole");

        JsonNode claim = claim(built);
        assertThat(claim.get("use").asString()).isEqualTo("preauthorization");
        assertThat(claim.get("status").asString()).isEqualTo("active");
        assertThat(claim.at("/identifier/0/value").asString()).isEqualTo(CLAIM);
        assertThat(claim.at("/identifier/0").has("system")).isFalse();
        assertThat(claim.at("/patient/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(claim.at("/provider/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(claim.at("/insurance/0/coverage/reference").asString()).isEqualTo("Coverage/coverage-1");
        assertThat(claim.at("/insurance/0/focal").asBoolean()).isTrue();
        assertThat(claim.has("insurer")).isFalse();
        assertThat(claim.get("created").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(claim.get("item")).hasSize(2);
        assertThat(claim.at("/item/0/sequence").asInt()).isEqualTo(1);
        assertThat(claim.at("/item/0/productOrService/coding/0/code").asString()).isEqualTo("95810");
        assertThat(claim.at("/item/0/productOrService/coding/0/system").asString()).isEqualTo("http://www.ama-assn.org/go/cpt");
        assertThat(claim.at("/item/0/productOrService/coding/0/display").asString()).isEqualTo("Sleep study, attended");
        assertThat(claim.at("/item/0/servicedDate").asString()).isEqualTo("2026-03-20");
        assertThat(claim.at("/item/1/sequence").asInt()).isEqualTo(2);
        assertThat(claim.at("/item/1/productOrService/coding/0/system").asString()).isEqualTo("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets");
        assertThat(claim.has("supportingInfo")).isFalse();
        assertThat(claim.has("diagnosis")).isFalse();

        // The member ID goes out without its spaces, on the patient and on the coverage alike.
        assertThat(bundle.at("/entry/1/resource/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(bundle.at("/entry/2/resource/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(bundle.at("/entry/2/resource/status").asString()).isEqualTo("active");
        assertThat(bundle.at("/entry/3/resource/identifier/0/value").asString()).isEqualTo("1234567893");
        assertThat(built.json()).doesNotContain("S12345678   01");

        assertThat(built.sent()).isEqualTo(new SentClaim(CLAIM, List.of(new SentClaim.Item(1, "95810"), new SentClaim.Item(2, "E0601"))));
        assertThat(built.attachmentMode()).isNull();
    }

    @Test
    void whatTheBuilderWroteIsWhatTheReaderOfARequestBodyFinds() {
        // The mock answers from the body, and a tester's own body is read the same way. Both must agree with the builder.
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().build(), CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(SentClaim.fromBody(built.json())).isEqualTo(built.sent());
    }

    @Test
    void everythingLeftOutOrChosenByTheToolIsSaidInANote() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().build(), CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(built.notes()).contains(
                "The claim identifier was sent without an identifier system: the variable claimIdentifierSystem is not set.",
                "The claim type was set to professional, which is this tool's default and not something known about the request. Set the "
                        + "variable claimType to send another.",
                "The claim priority was set to normal, which is this tool's default and not something known about the request. Set the "
                        + "variable claimPriority to send another.",
                "No diagnosis was sent, because none was supplied.",
                "No insurer organization was sent: neither payerIdentifier nor payerName is set.",
                "The bundle entries were sent without a fullUrl: the variable fhirServer is not set. The references between the resources "
                        + "are relative, such as Patient/patient-1.",
                "The coding system for 95810 was taken from its format: CPT.");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("request type and the certification type").contains("were not sent"));
        JsonNode claim = claim(built);
        assertThat(claim.at("/type/coding/0/code").asString()).isEqualTo("professional");
        assertThat(claim.at("/priority/coding/0/code").asString()).isEqualTo("normal");
    }

    @Test
    void aValueTheTesterStatesIsUsedInPlaceOfADefaultAndThenNothingIsNoted() {
        Map<String, String> stated = Map.of("claimIdentifierSystem", "https://example.org/p32h-placeholder/claims", "claimType", "institutional",
                "claimPriority", "stat", "fhirServer", "https://ehr.example.invalid/fhir/", "payerName", "Placeholder payer",
                "payerIdentifier", "PAYER-PLACEHOLDER-1", "memberIdSystem", "https://example.org/p32h-placeholder/member-id");
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().build(), CLAIM, BUNDLE, variables(stated));
        JsonNode bundle = bundle(built);
        JsonNode claim = claim(built);
        assertThat(claim.at("/identifier/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/claims");
        assertThat(claim.at("/type/coding/0/code").asString()).isEqualTo("institutional");
        assertThat(claim.at("/priority/coding/0/code").asString()).isEqualTo("stat");
        // With a payer named, the insurer is a resource of the bundle and the claim refers to it.
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Organization", "Practitioner", "PractitionerRole");
        assertThat(claim.at("/insurer/reference").asString()).isEqualTo("Organization/organization-insurer-1");
        // With a base stated, every entry says where its resource lives, with no doubled slash.
        assertThat(bundle.at("/entry/0/fullUrl").asString()).isEqualTo("https://ehr.example.invalid/fhir/Claim/claim-1");
        assertThat(bundle.at("/entry/1/fullUrl").asString()).isEqualTo("https://ehr.example.invalid/fhir/Patient/patient-1");
        assertThat(built.notes()).noneMatch(note -> note.contains("this tool's default") || note.contains("fullUrl")
                || note.contains("identifier system") || note.contains("insurer"));
    }

    @Test
    void nothingAboutTheMemberIsInventedWhenItWasNotSupplied() {
        PasSubmitRequest bare = request().member(MemberDetail.builder().memberId("S123456789").build()).dateOfService(null).build();
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(bare, CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode patient = bundle(built).at("/entry/1/resource");
        JsonNode coverage = bundle(built).at("/entry/2/resource");
        assertThat(patient.has("name")).isFalse();
        assertThat(patient.has("birthDate")).isFalse();
        assertThat(patient.has("gender")).isFalse();
        // A coverage status that was not returned is not "active".
        assertThat(coverage.has("status")).isFalse();
        assertThat(claim(built).at("/item/0").has("servicedDate")).isFalse();
        assertThat(built.notes()).contains("No coverage status was sent, because none was supplied.",
                "No date of service was sent on the items, because none was supplied.");
    }

    @Test
    void theCodeListDecidesACodingSystemBeforeTheFormatOfTheCodeDoes() {
        FakeCodeList codeList = new FakeCodeList().row(ProcedureCode.builder().code("95810").system("https://example.org/p32h-placeholder/codes")
                .authRequired(AuthRequirement.REQUIRED).build());
        PasRequestBuilder.Built built = new PasRequestBuilder(codeList, StartupChecks.ATTACHMENT_FHIR, CLOCK)
                .submit(request().build(), CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(claim(built).at("/item/0/productOrService/coding/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/codes");
    }

    @Test
    void aDiagnosisIsSentWithTheSystemItsFormatDecidesAndOneOfAnotherFormatIsSentWithoutASystem() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request().diagnosisCodes(List.of(
                new CodeInput("G47.33", null, "Obstructive sleep apnea"), CodeInput.of("g4733"), new CodeInput("327.23", null, null),
                new CodeInput("X1", "https://example.org/p32h-placeholder/dx", null))).build(), CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode diagnosis = claim(built).get("diagnosis");
        assertThat(diagnosis).hasSize(4);
        assertThat(diagnosis.at("/0/sequence").asInt()).isEqualTo(1);
        assertThat(diagnosis.at("/0/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(diagnosis.at("/0/diagnosisCodeableConcept/coding/0/code").asString()).isEqualTo("G47.33");
        assertThat(diagnosis.at("/0/diagnosisCodeableConcept/coding/0/display").asString()).isEqualTo("Obstructive sleep apnea");
        assertThat(diagnosis.at("/1/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(diagnosis.at("/2/diagnosisCodeableConcept/coding/0").has("system")).isFalse();
        assertThat(diagnosis.at("/3/diagnosisCodeableConcept/coding/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/dx");
        assertThat(diagnosis.at("/3/sequence").asInt()).isEqualTo(4);
        assertThat(built.notes()).contains("The coding system for the diagnosis G47.33 was taken from its format: ICD-10-CM.",
                "No coding system could be worked out for the diagnosis 327.23, so it was sent without one.");
        assertThat(built.notes()).doesNotContain("No diagnosis was sent, because none was supplied.");
    }

    // ---- the answers DTR handed on ----

    @Test
    void theQuestionnaireAnswersHandedOnFromDtrTravelInTheBundleAndTheClaimRefersToThem() {
        PasSubmitRequest request = request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS))).build();
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request, CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode bundle = bundle(built);
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "QuestionnaireResponse");
        // The answers a tester gave reach the wire exactly as they were given.
        assertThat(bundle.at("/entry/5/resource")).isEqualTo(JsonSafe.parse(ANSWERS).node());
        assertThat(built.json()).contains("Placeholder answer about daytime sleepiness");
        JsonNode info = claim(built).at("/supportingInfo/0");
        assertThat(info.get("sequence").asInt()).isEqualTo(1);
        assertThat(info.at("/category/coding/0/system").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASSupportingInfoType");
        assertThat(info.at("/category/coding/0/code").asString()).isEqualTo("additionalInformation");
        assertThat(info.at("/valueReference/reference").asString()).isEqualTo("QuestionnaireResponse/qr-sleep-1");
        assertThat(built.notes()).contains("The questionnaire answers handed on from DTR travel in the bundle as QuestionnaireResponse/qr-sleep-1 "
                + "and are named in the claim's supporting information.");
        // Questionnaire answers are not an attachment, so they set no attachment mode.
        assertThat(built.attachmentMode()).isNull();
    }

    @Test
    void answersThatCarryNoIdAreGivenOneSoTheClaimCanReferToThemAndTheNoteSaysSo() {
        String noId = ANSWERS.replace("\"id\":\"qr-sleep-1\",", "");
        PasSubmitRequest request = request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(noId))).build();
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(request, CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(bundle(built).at("/entry/5/resource/id").asString()).isEqualTo("questionnaire-response-1");
        assertThat(claim(built).at("/supportingInfo/0/valueReference/reference").asString()).isEqualTo("QuestionnaireResponse/questionnaire-response-1");
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("carried no id").contains("questionnaire-response-1"));
    }

    @Test
    void answersThatAreNotAQuestionnaireResponseAreAProblemAndNeverQuietlyDropped() {
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS)))).isNull();
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of())).isNull();
        assertThat(PasRequestBuilder.questionnaireProblem(null)).isNull();
        // A blank entry is no answers at all, which is not a problem.
        assertThat(PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(" ")))).isNull();
        for (String bad : List.of("{\"resourceType\": ", "[1]", "{\"resourceType\":\"Questionnaire\"}", "\"text\"")) {
            String problem = PasRequestBuilder.questionnaireProblem(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS, bad)));
            assertThat(problem).as(bad).contains("not a QuestionnaireResponse");
            // The message never quotes what was handed on: it is a tester's answers.
            assertThat(problem).doesNotContain("Questionnaire\"").doesNotContain("text");
        }
    }

    // ---- attachments, both ways ----

    private static PasSubmitRequest withAttachments() {
        return request().attachments(List.of(new PasAttachment("Sleep study report", "text/plain", REPORT, null),
                new PasAttachment("Referral letter", "application/pdf", null, "https://documents.example.invalid/referral-1.pdf"))).build();
    }

    @Test
    void inFhirModeTheDocumentTravelsInTheBundleAndTheClaimRefersToIt() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(withAttachments(), CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode bundle = bundle(built);
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "DocumentReference",
                "DocumentReference");
        JsonNode inline = bundle.at("/entry/5/resource");
        assertThat(inline.get("id").asString()).isEqualTo("document-reference-1");
        assertThat(inline.get("status").asString()).isEqualTo("current");
        assertThat(inline.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(inline.at("/content/0/attachment/contentType").asString()).isEqualTo("text/plain");
        assertThat(inline.at("/content/0/attachment/title").asString()).isEqualTo("Sleep study report");
        assertThat(new String(Base64.getDecoder().decode(inline.at("/content/0/attachment/data").asString()), StandardCharsets.UTF_8)).isEqualTo(REPORT);
        assertThat(inline.at("/content/0/attachment").has("url")).isFalse();
        JsonNode byUrl = bundle.at("/entry/6/resource");
        assertThat(byUrl.at("/content/0/attachment/url").asString()).isEqualTo("https://documents.example.invalid/referral-1.pdf");
        assertThat(byUrl.at("/content/0/attachment").has("data")).isFalse();

        JsonNode claim = claim(built);
        assertThat(claim.get("supportingInfo")).hasSize(2);
        assertThat(claim.at("/supportingInfo/0/valueReference/reference").asString()).isEqualTo("DocumentReference/document-reference-1");
        assertThat(claim.at("/supportingInfo/1/valueReference/reference").asString()).isEqualTo("DocumentReference/document-reference-2");
        assertThat(claim.at("/supportingInfo/1/sequence").asInt()).isEqualTo(2);
        assertThat(built.attachmentMode()).isEqualTo("fhir");
        assertThat(built.notes()).contains("Attachment 1 (\"Sleep study report\") travels in the bundle as DocumentReference/document-reference-1, "
                + "with its content inline. No document type code was sent: this tool has none.",
                "Attachment 2 (\"Referral letter\") travels in the bundle as DocumentReference/document-reference-2, by its url. No document "
                        + "type code was sent: this tool has none.");
    }

    @Test
    void inX12ModeTheClaimNamesTheAttachmentByItsControlNumberAndTheBundleDoesNotCarryTheDocument() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_X12_275).submit(withAttachments(), CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode bundle = bundle(built);
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole");
        // Neither the text of the document, nor its base64 form, nor its url is on the wire.
        assertThat(built.json()).doesNotContain(REPORT).doesNotContain("referral-1.pdf")
                .doesNotContain(Base64.getEncoder().encodeToString(REPORT.getBytes(StandardCharsets.UTF_8)));

        JsonNode claim = claim(built);
        assertThat(claim.get("supportingInfo")).hasSize(2);
        assertThat(claim.at("/supportingInfo/0/valueReference/identifier/value").asString()).isEqualTo(CLAIM + "-A1");
        assertThat(claim.at("/supportingInfo/0/valueReference/display").asString()).isEqualTo("Sleep study report");
        assertThat(claim.at("/supportingInfo/0/valueReference").has("reference")).isFalse();
        assertThat(claim.at("/supportingInfo/0/valueReference/identifier").has("system")).isFalse();
        assertThat(claim.at("/supportingInfo/1/valueReference/identifier/value").asString()).isEqualTo(CLAIM + "-A2");
        assertThat(claim.at("/supportingInfo/0/category/coding/0/code").asString()).isEqualTo("additionalInformation");
        assertThat(built.attachmentMode()).isEqualTo("x12-275");
        assertThat(built.notes()).contains("Attachment 1 (\"Sleep study report\") was not put in the bundle. In the attachment mode x12-275 a "
                + "document travels separately in an X12 275 transaction, which this tool does not send. The claim names it by the attachment "
                + "control number " + CLAIM + "-A1.");

        // The system of the control number is sent only when the tester states one.
        PasRequestBuilder.Built withSystem = builder(StartupChecks.ATTACHMENT_X12_275).submit(withAttachments(), CLAIM, BUNDLE,
                variables(Map.of("attachmentControlNumberSystem", "https://example.org/p32h-placeholder/acn")));
        assertThat(claim(withSystem).at("/supportingInfo/0/valueReference/identifier/system").asString())
                .isEqualTo("https://example.org/p32h-placeholder/acn");
    }

    @Test
    void theQuestionnaireAnswersTravelInTheBundleInEitherModeAndComeBeforeTheAttachments() {
        PasSubmitRequest request = request().carry(Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(ANSWERS)))
                .attachments(List.of(new PasAttachment("Sleep study report", "text/plain", REPORT, null))).build();
        PasRequestBuilder.Built x12 = builder(StartupChecks.ATTACHMENT_X12_275).submit(request, CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(types(bundle(x12))).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "QuestionnaireResponse");
        assertThat(claim(x12).at("/supportingInfo/0/valueReference/reference").asString()).isEqualTo("QuestionnaireResponse/qr-sleep-1");
        assertThat(claim(x12).at("/supportingInfo/1/valueReference/identifier/value").asString()).isEqualTo(CLAIM + "-A1");
        assertThat(claim(x12).at("/supportingInfo/1/sequence").asInt()).isEqualTo(2);

        PasRequestBuilder.Built fhir = builder(StartupChecks.ATTACHMENT_FHIR).submit(request, CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(types(bundle(fhir))).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole", "QuestionnaireResponse",
                "DocumentReference");
    }

    // ---- the inquiry ----

    private static PasInquireRequest.Builder inquiry() {
        return PasInquireRequest.builder().member(MemberDetail.builder().memberId("S123456789").lineOfBusiness("Medicare").build())
                .provider(ProviderDetail.builder().npi("1234567893").build());
    }

    @Test
    void anInquiryNamesTheRequestByItsClaimIdentifierWithTheMemberAndTheProvider() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).inquire(inquiry().build(), CLAIM, BUNDLE, NO_VARIABLES);
        JsonNode bundle = bundle(built);
        assertThat(types(bundle)).containsExactly("Claim", "Patient", "Coverage", "Practitioner", "PractitionerRole");
        JsonNode claim = claim(built);
        assertThat(claim.at("/identifier/0/value").asString()).isEqualTo(CLAIM);
        assertThat(claim.get("use").asString()).isEqualTo("preauthorization");
        assertThat(claim.at("/patient/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(claim.at("/provider/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(claim.has("item")).isFalse();
        assertThat(claim.has("supportingInfo")).isFalse();
        assertThat(built.sent()).isEqualTo(new SentClaim(CLAIM, List.of()));
        assertThat(built.attachmentMode()).isNull();
        assertThat(built.notes().get(0)).isEqualTo("The inquiry names the request by its claim identifier and lists no item, so it asks about "
                + "the request as a whole.");
        assertThat(built.notes()).doesNotContain("No diagnosis was sent, because none was supplied.");
    }

    @Test
    void anInquiryThatIsGivenTheCodesOfTheSubmissionListsThemInOrderSoEachDecisionCanBeTied() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).inquire(inquiry()
                .procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601"))).build(), CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(claim(built).get("item")).hasSize(2);
        assertThat(claim(built).at("/item/1/productOrService/coding/0/code").asString()).isEqualTo("E0601");
        assertThat(built.sent().items()).containsExactly(new SentClaim.Item(1, "95810"), new SentClaim.Item(2, "E0601"));
        assertThat(built.notes().get(0)).contains("in the order given");
        assertThat(SentClaim.fromBody(built.json())).isEqualTo(built.sent());
    }

    @Test
    void aBuiltRequestIsNeverPrintedWithItsBody() {
        PasRequestBuilder.Built built = builder(StartupChecks.ATTACHMENT_FHIR).submit(withAttachments(), CLAIM, BUNDLE, NO_VARIABLES);
        assertThat(built.toString()).doesNotContain("Placeholder-Surname").doesNotContain("S1234567801").doesNotContain("Bundle");
        assertThat(withAttachments().toString()).doesNotContain("Placeholder-Surname").doesNotContain(REPORT);
        assertThat(new PasAttachment("Sleep study report", "text/plain", REPORT, null).toString()).doesNotContain(REPORT);
        assertThat(inquiry().build().toString()).doesNotContain("S123456789");
    }
}
