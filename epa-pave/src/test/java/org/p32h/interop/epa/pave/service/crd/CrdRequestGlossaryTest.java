package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.service.compare.JsonComparer;
import org.p32h.interop.epa.pave.service.compare.ElementPath;
import org.p32h.interop.epa.pave.service.compare.ElementRole;
import org.p32h.interop.epa.pave.service.compare.Glossary;
import org.p32h.interop.epa.pave.service.compare.ResourceIndex;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * The glossary: what it says each element is, which part it says the element plays, and that
 * what it says about this tool's own builder is true of the builder.
 */
class CrdRequestGlossaryTest {

    /** When the request is built, fixed so that two builds of one case are the same request, authoredOn included. */
    private static final java.time.Clock BUILT_AT = java.time.Clock.fixed(java.time.Instant.parse("2026-03-18T14:05:09Z"), java.time.ZoneOffset.UTC);

    private final CrdRequestGlossary glossary = new CrdRequestGlossary();

    private Glossary.Entry fhir(String path) {
        return glossary.describe(new ElementPath("prefetch.*.somewhere", path));
    }

    private Glossary.Entry document(String path) {
        return glossary.describe(new ElementPath(path, null));
    }

    // ---- roles ----

    @Test
    void aRoleBelongsToTheElementAndNotToTheDifference() {
        // The examples the groups are defined by.
        assertThat(document("hookInstance").role()).isEqualTo(ElementRole.EVERY_CALL);
        assertThat(fhir("ServiceRequest.code.coding.system").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Patient.name.family").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("Patient.id").role()).isEqualTo(ElementRole.LABEL);
        assertThat(document("context.patientId").role()).isEqualTo(ElementRole.LABEL);
        assertThat(fhir("ServiceRequest.status").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("ServiceRequest.meta.profile").role()).isEqualTo(ElementRole.BUILD);
        // A code that classifies is part of how the request is built. A code that says what was ordered is part of the case.
        assertThat(fhir("Patient.identifier.type.coding.code").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Coverage.class.type.coding.code").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("ServiceRequest.code.coding.code").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("DeviceRequest.codeCodeableConcept.coding.code").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("ServiceRequest.occurrenceDateTime").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("Practitioner.identifier.value").role()).isEqualTo(ElementRole.CASE);
        // The status of a coverage says something about the member. The status of a draft order does not.
        assertThat(fhir("Coverage.status").role()).isEqualTo(ElementRole.CASE);
    }

    @Test
    void whereTheRoleDependsOnTheRequestTheElementIsDescribedAndGivenNoRole() {
        for (String path : List.of("Condition.code.coding.code", "Organization.identifier.value", "Patient.meta.lastUpdated", "Coverage.payor.display",
                "ServiceRequest.extension.valueCode")) {
            assertThat(fhir(path)).as(path).isNotNull();
            assertThat(fhir(path).what()).as(path).isNotBlank();
            assertThat(fhir(path).role()).as(path).isNull();
        }
        assertThat(document("fhirAuthorization.access_token").role()).isNull();
    }

    @Test
    void anElementTheGlossaryIsNotSureOfIsNotDescribedAtAll() {
        assertThat(document("vendorTraceId")).isNull();
        assertThat(document("context.somethingNew")).isNull();
        assertThat(document("")).isNull();
        // A resource type this glossary has no entry for, and an element of one it does.
        assertThat(fhir("Condition")).isNull();
        assertThat(fhir("Condition.clinicalStatus")).isNull();
        assertThat(fhir("ServiceRequest.specimen")).isNull();
        // An id that is not the id of a resource is not said to be a label.
        assertThat(fhir("ServiceRequest.extension.id")).isNull();
    }

    @Test
    void theShapesEveryResourceSharesAreKnownOnAnyResourceType() {
        assertThat(fhir("Condition.id").role()).isEqualTo(ElementRole.LABEL);
        assertThat(fhir("Condition.resourceType").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Condition.meta.profile").what()).isEqualTo("The profiles the resource says it conforms to.");
        assertThat(fhir("Condition.code.coding.system").what()).isEqualTo("The url of the code system the code comes from.");
        assertThat(fhir("Condition.identifier.system").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Condition.subject.reference").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Condition.onsetPeriod.start").what()).isEqualTo("The start of the period.");
        assertThat(fhir("RelatedPerson.name.given").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("RelatedPerson.address.city").role()).isEqualTo(ElementRole.CASE);
        assertThat(fhir("RelatedPerson.telecom.system").role()).isEqualTo(ElementRole.BUILD);
        assertThat(fhir("Condition.extension").what()).startsWith("An extension:");
        assertThat(fhir("Condition.extension.extension.url").what()).isEqualTo("The url that names the extension.");
        assertThat(fhir("Condition.modifierExtension").role()).isEqualTo(ElementRole.BUILD);
    }

    @Test
    void aTopLevelKeyWithADotInItIsNotDescribedAsTheElementOfThatName() {
        JsonNode tool = JsonSafe.parse("{\"hook\":\"order-sign\"}").node();
        JsonNode yours = JsonSafe.parse("{\"hook\":\"order-sign\",\"context.patientId\":\"x\"}").node();
        assertThat(new JsonComparer(CrdRequestGlossary.RULES, glossary).compare(tool, yours).differences()).singleElement().satisfies(difference -> {
            assertThat(difference.path()).isEqualTo("\"context.patientId\"");
            assertThat(difference.what()).isNull();
            assertThat(difference.group()).isEqualTo(DifferenceGroup.NOT_CLASSIFIED);
        });
    }

    @Test
    void theHookRequestIsDescribedBeforeTheResourceThatHappensToSitThere() {
        // The draft orders are a Bundle, and what a tester needs to hear first is that they are the orders.
        assertThat(glossary.describe(new ElementPath("context.draftOrders", "Bundle")).what()).isEqualTo("The orders being worked on, as a FHIR Bundle.");
        assertThat(glossary.describe(new ElementPath("context.draftOrders.type", "Bundle.type")).what())
                .isEqualTo("What kind of Bundle this is, for example collection or searchset.");
        // A prefetch entry that is a resource is described as that resource, and one that is not as an entry.
        assertThat(glossary.describe(new ElementPath("prefetch.*", "Patient")).what()).isEqualTo("The member, as a FHIR Patient resource.");
        assertThat(glossary.describe(new ElementPath("prefetch.*", null)).what()).isEqualTo("One entry of prefetch, under its key.");
        assertThat(glossary.describe(new ElementPath("prefetch.*", "Condition")).what()).isEqualTo("One entry of prefetch, under its key.");
    }

    // ---- everything this tool writes is described ----

    private static final FakeGateway CONFIGURED = new FakeGateway().variable("fhirServer", "https://ehr.example.invalid/fhir")
            .variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id").variable("payerName", "Placeholder payer")
            .variable("payerIdentifier", "PAYER-PLACEHOLDER").variable("payerIdentifierSystem", "https://example.org/p32h-placeholder/payer-id");

    private static CrdRunRequest.Builder everything() {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                        .gender("F").planName("Placeholder Plan").coverageStatus("active").subscriberId("SUB-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .dateOfService("2026-03-20");
    }

    private static JsonNode build(CrdRunRequest request, FakeGateway gateway) {
        return JsonSafe.parse(new CrdRequestBuilder(null, BUILT_AT).build(request, "h", template -> gateway.resolve(template, Map.of())).json()).node();
    }

    /** Every element of a document, under the two paths the engine asks the glossary with. */
    private static void elements(JsonNode node, String document, String fhir, Set<ElementPath> out) {
        String type = ResourceIndex.resourceType(node);
        String here = type != null ? type : fhir;
        if (!document.isEmpty()) {
            out.add(new ElementPath(document, here));
        }
        if (node.isObject()) {
            boolean freeKeys = CrdRequestGlossary.RULES.freeKeyedAt(document);
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String key = member.getKey();
                elements(member.getValue(), document.isEmpty() ? key : document + "." + (freeKeys ? "*" : key),
                        here == null ? null : here + "." + key, out);
            }
        } else if (node.isArray()) {
            // An element of a list sits at the path of the list.
            node.forEach(element -> elements(element, document, here, out));
        }
    }

    @Test
    void everyElementThisToolsBuilderWritesIsDescribed() {
        Set<ElementPath> written = new LinkedHashSet<>();
        elements(build(everything().build(), CONFIGURED), "", null, written);
        elements(build(everything().hook("order-select").provider(ProviderDetail.builder().npi("1234567893")
                .organizationName("Placeholder Clinic").build()).build(), CONFIGURED), "", null, written);
        assertThat(written).hasSizeGreaterThan(60);
        List<String> unknown = new ArrayList<>();
        for (ElementPath element : written) {
            if (glossary.describe(element) == null) {
                unknown.add(element.toString());
            }
        }
        assertThat(unknown).as("a difference in an element this tool itself writes would be reported as not classified").isEmpty();
    }

    // ---- the words ----

    private static final Pattern SECOND_SENTENCE = Pattern.compile("[.!?]\\s+\\p{Lu}");

    @Test
    void everyDescriptionIsOnePlainSentenceThatSaysWhatTheElementIsAndNothingAboutWhatAServiceExpects() {
        List<String> sentences = new ArrayList<>();
        CrdRequestGlossary.listed().values().forEach(entry -> sentences.add(entry.what()));
        // The shapes are not listed by path, so one path of each is asked for.
        for (String path : List.of("X.resourceType", "X.id", "X.meta", "X.text", "X.contained", "X.meta.profile", "X.meta.lastUpdated",
                "X.meta.versionId", "X.extension", "X.modifierExtension", "X.extension.url", "X.extension.valueString", "X.identifier",
                "X.identifier.type.coding.code", "X.identifier.type.coding.display", "X.identifier.type.text", "X.identifier.type.coding",
                "X.identifier.system", "X.identifier.value", "X.identifier.type", "X.identifier.use", "X.identifier.assigner", "X.a.coding",
                "X.a.coding.system", "X.a.coding.code", "X.a.coding.display", "X.a.coding.version", "X.a.reference", "X.a.display", "X.period.start",
                "X.period.end", "X.name.family", "X.name.given", "X.name.prefix", "X.name.suffix", "X.name.text", "X.name.use", "X.address",
                "X.address.line", "X.address.use", "X.telecom", "X.telecom.system", "X.telecom.value", "X.telecom.use")) {
            assertThat(fhir(path)).as(path).isNotNull();
            sentences.add(fhir(path).what());
        }
        assertThat(sentences).hasSizeGreaterThan(250);
        for (String sentence : sentences) {
            assertThat(sentence).as(sentence).endsWith(".").matches("\\p{Lu}.*");
            assertThat(SECOND_SENTENCE.matcher(sentence).find()).as("one sentence: " + sentence).isFalse();
            CompareWords.assertSaysNothingAboutWhatIsRight(sentence);
        }
    }

    @Test
    void everyRemarkPassesTheSameRule() {
        Set<String> remarks = new LinkedHashSet<>();
        List<String> documents = new ArrayList<>(List.of("hookInstance", "fhirServer", "fhirAuthorization", "context.encounterId", "context.selections",
                "prefetch", "prefetch.*", "context.draftOrders.entry", "context.draftOrders.entry.fullUrl", "hook"));
        List<String> resources = new ArrayList<>(CrdRequestGlossary.listed().keySet());
        for (DifferenceKind kind : DifferenceKind.values()) {
            documents.forEach(path -> remarks.add(String.valueOf(glossary.remark(new ElementPath(path, null), kind))));
            resources.forEach(path -> remarks.add(String.valueOf(glossary.remark(new ElementPath("prefetch.*.x", path), kind))));
        }
        remarks.remove("null");
        assertThat(remarks).hasSizeGreaterThan(15);
        remarks.forEach(CompareWords::assertSaysNothingAboutWhatIsRight);
        assertThat(glossary.remark(new ElementPath("hook", null), DifferenceKind.DIFFERENT_VALUE)).isNull();
        assertThat(glossary.remark(new ElementPath("", null), DifferenceKind.DIFFERENT_TYPE)).isNull();
    }

    // ---- what the remarks say about this tool is true of this tool ----

    private static final FakeGateway NOTHING_SET = new FakeGateway();

    @Test
    void theRemarksAboutVariablesAreTrueOfTheBuilder() {
        JsonNode bare = build(everything().build(), NOTHING_SET);
        JsonNode configured = build(everything().build(), CONFIGURED);
        assertThat(glossary.remark(new ElementPath("fhirServer", null), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool sends fhirServer only when the variable fhirServer has a value.");
        assertThat(bare.has("fhirServer")).isFalse();
        assertThat(configured.has("fhirServer")).isTrue();

        assertThat(glossary.remark(new ElementPath("prefetch.*.identifier", "Patient.identifier"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("This tool writes the member ID as an identifier typed MB, with a system only when the variable memberIdSystem has a "
                        + "value, and a medical record number typed MR only when the variable patientMrn has a value.");
        assertThat(glossary.remark(new ElementPath("prefetch.*.identifier", "Coverage.identifier"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("This tool writes the member ID as an identifier typed MB, with a system only when the variable memberIdSystem has a value.");
        assertThat(bare.at("/prefetch/patient/identifier")).hasSize(1);
        assertThat(build(everything().build(), new FakeGateway().variable("patientMrn", "MRN-PLACEHOLDER-1"))
                .at("/prefetch/patient/identifier/1/type/coding/0/code").asString()).isEqualTo("MR");
        for (String resource : List.of("patient", "coverage")) {
            assertThat(bare.at("/prefetch/" + resource + "/identifier/0/type/coding/0/code").asString()).isEqualTo("MB");
            assertThat(bare.at("/prefetch/" + resource + "/identifier/0").has("system")).isFalse();
            assertThat(configured.at("/prefetch/" + resource + "/identifier/0").has("system")).isTrue();
        }

        assertThat(glossary.remark(new ElementPath("prefetch.*.payor", "Coverage.payor"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool names a payer only when the variable payerIdentifier or payerName has a value.");
        assertThat(bare.at("/prefetch/coverage").has("payor")).isFalse();
        assertThat(configured.at("/prefetch/coverage").has("payor")).isTrue();
        assertThat(build(everything().build(), new FakeGateway().variable("payerName", "Placeholder payer")).at("/prefetch/coverage").has("payor")).isTrue();

        // Said only of two values that differ in their spaces and in nothing else, which is the difference it explains.
        ElementPath identifierValue = new ElementPath("prefetch.*.identifier.value", "Coverage.identifier.value");
        assertThat(glossary.remark(identifierValue, DifferenceKind.DIFFERENT_VALUE, Shown.of("S1234567801"), Shown.of("S12345678 01")))
                .isEqualTo("This tool removes the spaces from a member ID before it sends it.");
        assertThat(glossary.remark(identifierValue, DifferenceKind.DIFFERENT_VALUE, Shown.of("S1234567801"), Shown.of("S222222222"))).isNull();
        assertThat(glossary.remark(identifierValue, DifferenceKind.DIFFERENT_VALUE, Shown.of("S1234567801"), Shown.empty())).isNull();
        assertThat(glossary.remark(identifierValue, DifferenceKind.DIFFERENT_VALUE)).isNull();
        assertThat(bare.at("/prefetch/patient/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(bare.at("/prefetch/coverage/identifier/0/value").asString()).isEqualTo("S1234567801");
    }

    @Test
    void theRemarksAboutWhatTheBuilderLeavesOutAreTrueOfTheBuilder() {
        JsonNode sign = build(everything().build(), CONFIGURED);
        JsonNode select = build(everything().hook("order-select").build(), CONFIGURED);
        assertThat(glossary.remark(new ElementPath("fhirAuthorization", null), DifferenceKind.ONLY_IN_YOURS)).isEqualTo("This tool does not build fhirAuthorization.");
        assertThat(glossary.remark(new ElementPath("context.encounterId", null), DifferenceKind.ONLY_IN_YOURS)).isEqualTo("This tool does not build an encounterId.");
        assertThat(glossary.remark(new ElementPath("context.draftOrders.entry.fullUrl", "Bundle.entry.fullUrl"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool does not write a fullUrl on the entries of its draft orders.");
        assertThat(glossary.remark(new ElementPath("context.selections", null), DifferenceKind.ONLY_IN_YOURS)).isEqualTo("This tool selects every order it builds, and sends selections for order-select only.");
        for (JsonNode request : List.of(sign, select)) {
            assertThat(request.has("fhirAuthorization")).isFalse();
            assertThat(request.at("/context").has("encounterId")).isFalse();
            request.at("/context/draftOrders/entry").forEach(entry -> assertThat(entry.propertyNames()).containsExactly("resource"));
        }
        assertThat(sign.at("/context").has("selections")).isFalse();
        assertThat(select.at("/context/selections")).hasSize(2).hasSameSizeAs(select.at("/context/draftOrders/entry"));

        assertThat(glossary.remark(new ElementPath("context.draftOrders.entry", "Bundle.entry"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("This tool builds one ServiceRequest for each procedure code.");
        assertThat(sign.at("/context/draftOrders/entry")).hasSize(2).allSatisfy(entry ->
                assertThat(entry.at("/resource/resourceType").asString()).isEqualTo("ServiceRequest"));

        assertThat(glossary.remark(new ElementPath("prefetch", null), DifferenceKind.ONLY_IN_TOOL)).isEqualTo("This tool sends prefetch unless the form says to leave it out.");
        assertThat(glossary.remark(new ElementPath("prefetch", null), DifferenceKind.ONLY_IN_YOURS)).isEqualTo("This tool left prefetch out because the form said so.");
        assertThat(sign.has("prefetch")).isTrue();
        assertThat(build(everything().includePrefetch(false).build(), CONFIGURED).has("prefetch")).isFalse();
    }

    @Test
    void theRemarksAboutTheCaseAreTrueOfTheBuilder() {
        assertThat(glossary.remark(new ElementPath("x", "ServiceRequest.occurrenceDateTime"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("This tool writes the date of service as occurrenceDateTime.");
        assertThat(glossary.remark(new ElementPath("x", "ServiceRequest.occurrencePeriod"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool writes the date of service as occurrenceDateTime.");
        JsonNode built = build(everything().build(), NOTHING_SET);
        assertThat(built.at("/context/draftOrders/entry/0/resource/occurrenceDateTime").asString()).isEqualTo("2026-03-20");
        assertThat(built.at("/context/draftOrders/entry/0/resource").has("occurrencePeriod")).isFalse();

        assertThat(glossary.remark(new ElementPath("x", "ServiceRequest.code.coding"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("When a code comes without a system, this tool takes one from the code list, or else from the format of the code.");
        assertThat(built.at("/context/draftOrders/entry/1/resource/code/coding/0/system").asString())
                .isEqualTo("urn:oid:2.16.840.1.113883.6.285");
        assertThat(glossary.remark(new ElementPath("x", "ServiceRequest.code.coding.system"), DifferenceKind.DIFFERENT_VALUE))
                .isEqualTo("For a HCPCS Level II code this tool writes urn:oid:2.16.840.1.113883.6.285, the address the CRD 2.0.1 "
                        + "ServiceRequest value set gives HCPCS, unless the variable hcpcsSystem names another.");
        assertThat(build(everything().build(), new FakeGateway().variable("hcpcsSystem", "urn:another-hcpcs-address"))
                .at("/context/draftOrders/entry/1/resource/code/coding/0/system").asString()).isEqualTo("urn:another-hcpcs-address");

        // authoredOn is the moment of building, so a pasted order's own is a difference on every call.
        assertThat(glossary.remark(new ElementPath("x", "ServiceRequest.authoredOn"), DifferenceKind.DIFFERENT_VALUE))
                .isEqualTo("This tool writes the moment it builds the request as authoredOn, so its value is new on every call.");
        assertThat(built.at("/context/draftOrders/entry/0/resource/authoredOn").asString()).isEqualTo("2026-03-18T14:05:09Z");

        // A provider known only by an organization name is an Organization that the PractitionerRole names.
        assertThat(glossary.remark(new ElementPath("x", "PractitionerRole.organization"), DifferenceKind.ONLY_IN_TOOL))
                .isEqualTo("This tool sends a provider known only by an organization name as an Organization, which the PractitionerRole names.");
        JsonNode organization = build(everything().provider(org.p32h.interop.epa.pave.model.ProviderDetail.builder().npi("1234567893")
                .organizationName("Placeholder Clinic").build()).build(), NOTHING_SET);
        assertThat(organization.at("/prefetch/practitionerRole/organization/reference").asString()).isEqualTo("Organization/organization-1");
        assertThat(organization.at("/prefetch").has("practitioner")).isFalse();

        // The period is sent from the lookup's dates.
        assertThat(glossary.remark(new ElementPath("x", "Coverage.period"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool sends the coverage period only when the member lookup gives the coverage dates.");
        assertThat(built.at("/prefetch/coverage").has("period")).isFalse();
        assertThat(build(everything().member(MemberDetail.builder().memberId("S123456789").coverageStart("2026-01-01").build()).build(),
                NOTHING_SET).at("/prefetch/coverage/period/start").asString()).isEqualTo("2026-01-01");

        assertThat(glossary.remark(new ElementPath("x", "Patient.gender"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool sends a gender only when the case gives one it can read as male, female, other or unknown.");
        assertThat(glossary.remark(new ElementPath("x", "Coverage.status"), DifferenceKind.ONLY_IN_YOURS))
                .isEqualTo("This tool sends a coverage status only when the case gives one of active, cancelled, draft or entered-in-error.");
        JsonNode unreadable = build(everything().member(MemberDetail.builder().memberId("S123456789").gender("X").coverageStatus("Termed").build()).build(),
                NOTHING_SET);
        assertThat(unreadable.at("/prefetch/patient").has("gender")).isFalse();
        assertThat(unreadable.at("/prefetch/coverage").has("status")).isFalse();
        assertThat(built.at("/prefetch/patient/gender").asString()).isEqualTo("female");
        assertThat(built.at("/prefetch/coverage/status").asString()).isEqualTo("active");
    }

    @Test
    void whatTheRulesSayAboutAHookRequestIsWhatAHookRequestIs() {
        assertThat(CrdRequestGlossary.RULES.freeKeyedAt("prefetch")).isTrue();
        assertThat(CrdRequestGlossary.RULES.freeKeyedAt("context")).isFalse();
        assertThat(CrdRequestGlossary.RULES.bareTypeAt("context.patientId")).isEqualTo("Patient");
        assertThat(CrdRequestGlossary.RULES.bareTypeAt("context.encounterId")).isEqualTo("Encounter");
        // The user and the selections always name their own resource type.
        assertThat(CrdRequestGlossary.RULES.referenceAt("context.userId")).isTrue();
        assertThat(CrdRequestGlossary.RULES.bareTypeAt("context.userId")).isNull();
        assertThat(CrdRequestGlossary.RULES.referenceAt("context.selections")).isTrue();
        assertThat(CrdRequestGlossary.RULES.referenceAt("hook")).isFalse();
    }
}
