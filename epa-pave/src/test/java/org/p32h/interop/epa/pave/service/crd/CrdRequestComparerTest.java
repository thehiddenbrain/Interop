package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.compare.CaseValue;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.model.compare.GroupCount;
import org.p32h.interop.epa.pave.model.compare.NotFound;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.CaseField;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import tools.jackson.databind.JsonNode;

/**
 * The comparison of a pasted CRD request with the one this tool builds, through the real builder,
 * reader, glossary and engine.
 *
 * <p>Every comparison a test makes is also held to the rule on words: no sentence says what a
 * service expects or which request is right, and none quotes a member's name or date of birth.
 */
class CrdRequestComparerTest {

    /** Generated with the algorithm the application validates with, never typed by hand. */
    static final String NPI = Npi.withCheckDigit("198765432");
    private static final String HOOK_INSTANCE = "11111111-1111-4111-8111-111111111111";
    private static final String SURNAME = "Placeholder-Surname";
    private static final String BIRTH_DATE = "1970-01-31";
    private static final String ONLY_HOOK_INSTANCE = "hookInstance | DIFFERENT_VALUE | EVERY_CALL";
    /** The names, dates of birth and addresses of every fixture: none may appear in a sentence. */
    static final String[] MEMBER_DATA = {SURNAME, BIRTH_DATE, "Given", "Sample-Member", "Robin", "Quinn", "1968-04-12",
            "1 Placeholder Street", "Sampletown", "Sample-Provider", "Alex", "Form-Surname"};

    private final FakeGateway gateway = new FakeGateway();
    private final FakeCodeList codeList = new FakeCodeList();

    private CrdRequestBuilder builder() {
        return new CrdRequestBuilder(codeList);
    }

    private Function<String, ResolvedText> variables(Map<String, String> typedForThisRun) {
        return template -> gateway.resolve(template, typedForThisRun);
    }

    private RequestComparison compare(CrdRunRequest form) {
        RequestComparison comparison = new CrdRequestComparer(builder()).compare(form, variables(form.variables()));
        CompareWords.assertWords(comparison, MEMBER_DATA);
        return comparison;
    }

    static String fixture(String name) {
        try (InputStream in = CrdRequestComparerTest.class.getResourceAsStream("/compare/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String line(Difference difference) {
        return difference.path() + " | " + difference.kind() + " | " + difference.group();
    }

    private static List<String> lines(RequestComparison comparison) {
        return comparison.differences().stream().map(CrdRequestComparerTest::line).toList();
    }

    private static Difference at(RequestComparison comparison, String path) {
        return comparison.differences().stream().filter(difference -> difference.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no difference at " + path + " among " + lines(comparison)));
    }

    private static CaseValue source(RequestComparison comparison, String field) {
        return comparison.caseSource().stream().filter(value -> value.field().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("no case value " + field));
    }

    private static NotFound notFound(RequestComparison comparison, String field) {
        return comparison.notFound().stream().filter(value -> value.field().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError(field + " is not reported as not found"));
    }

    /** A case as the form holds it once a member and a provider have been looked up. */
    static CrdRunRequest.Builder form(String hook, int codes, boolean prefetch) {
        List<CodeInput> procedureCodes = List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601"),
                new CodeInput("70553", "http://www.ama-assn.org/go/cpt", null)).subList(0, codes);
        return CrdRunRequest.builder().hook(hook)
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName(SURNAME).dateOfBirth(BIRTH_DATE).gender("F")
                        .planName("Placeholder Plan").coverageStatus("active").subscriberId("SUB-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().npi(NPI).firstName("Pat").lastName("Provider").build())
                .procedureCodes(procedureCodes).dateOfService("2026-03-20").includePrefetch(prefetch ? null : Boolean.FALSE);
    }

    private String sent(CrdRunRequest request) {
        return builder().build(request, HOOK_INSTANCE, variables(Map.of())).json();
    }

    private void configureEveryVariable() {
        gateway.variable("fhirServer", "https://ehr.example.invalid/fhir").variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id")
                .variable("payerName", "Placeholder payer").variable("payerIdentifier", "PAYER-PLACEHOLDER")
                .variable("payerIdentifierSystem", "https://example.org/p32h-placeholder/payer-id");
    }

    // ---- the tool against itself ----

    @Test
    void aRequestThisToolBuiltDiffersFromTheOneItBuildsAgainOnlyInItsHookInstance() {
        for (boolean configured : List.of(false, true)) {
            if (configured) {
                configureEveryVariable();
            }
            for (String hook : List.of("order-sign", "order-select")) {
                for (int codes : List.of(1, 3)) {
                    for (boolean prefetch : List.of(true, false)) {
                        String described = hook + ", " + codes + " code(s), prefetch " + prefetch + ", variables set " + configured;
                        String sent = sent(form(hook, codes, prefetch).build());

                        // With the form that built it.
                        RequestComparison withForm = compare(form(hook, codes, prefetch).rawBody(sent).build());
                        // With a form that holds nothing the request can say for itself. Without
                        // prefetch a request carries neither the member nor the provider.
                        CrdRunRequest.Builder bare = CrdRunRequest.builder().rawBody(sent);
                        if (!prefetch) {
                            bare.includePrefetch(false).member(MemberDetail.builder().memberId("S1234567801").build())
                                    .provider(ProviderDetail.builder().npi(NPI).build());
                        }
                        for (RequestComparison comparison : List.of(withForm, compare(bare.build()))) {
                            assertThat(comparison.comparable()).as(described).isTrue();
                            assertThat(lines(comparison)).as(described).containsExactly(ONLY_HOOK_INSTANCE);
                            assertThat(comparison.headline()).isEqualTo("The two requests differ in 1 place: 1 that differs on every call.");
                            assertThat(comparison.hook()).isEqualTo(hook);
                            // Not only no difference found: put the id back and the two texts are the same text.
                            assertThat(comparison.toolBody().replace(CrdRequestComparer.HOOK_INSTANCE, HOOK_INSTANCE)).as(described).isEqualTo(sent);
                            assertThat(comparison.yourBody()).isEqualTo(sent);
                            assertThat(comparison.counts()).extracting(GroupCount::count).containsExactly(0, 0, 0, 1, 0);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theOneDifferenceIsExplainedAsTheOneThatDiffersOnEveryCall() {
        String sent = sent(form("order-sign", 1, true).build());
        Difference hookInstance = compare(CrdRunRequest.builder().rawBody(sent).build()).differences().get(0);
        assertThat(hookInstance.group()).isEqualTo(DifferenceGroup.EVERY_CALL);
        assertThat(hookInstance.toolValue().value()).isEqualTo("(a new id is generated when the request is sent)");
        assertThat(hookInstance.yourValue().value()).isEqualTo(HOOK_INSTANCE);
        assertThat(hookInstance.what()).isEqualTo("An id for this one call of the hook, which the sender makes anew for every call.");
        assertThat(hookInstance.explanation()).isEqualTo("The two requests hold different text here. A sender makes a new one for every call. "
                + "Nothing is sent for a comparison, so the tool's side holds a text in its place.");
    }

    // ---- the reader reads what the builder writes ----

    @Test
    void theCaseReadOutOfARequestThisToolBuiltBuildsTheSameRequestAgain() {
        List<String> memberIds = List.of("S12345678   01", "S123456789");
        List<ProviderDetail> providers = List.of(ProviderDetail.builder().npi(NPI).firstName("Pat").lastName("Provider").build(),
                ProviderDetail.builder().npi(NPI).organizationName("Placeholder Clinic").build(), ProviderDetail.builder().npi(NPI).build());
        int built = 0;
        for (boolean configured : List.of(false, true)) {
            if (configured) {
                configureEveryVariable();
            }
            for (String hook : List.of("order-sign", "order-select")) {
                for (int codes : List.of(1, 3)) {
                    for (boolean prefetch : List.of(true, false)) {
                        for (String memberId : memberIds) {
                            for (ProviderDetail provider : providers) {
                                CrdRunRequest original = form(hook, codes, prefetch).provider(provider)
                                        .member(form(hook, codes, prefetch).build().member().toBuilder().memberId(memberId).build()).build();
                                String sent = sent(original);
                                CrdRequestComparer.Case read = CrdRequestComparer.merge(
                                        CrdRunRequest.builder().includePrefetch(original.includePrefetch()).build(),
                                        CrdRequestReader.read(JsonSafe.parse(sent).node()));
                                assertThat(sent(read.request)).as(hook + ", " + codes + " code(s), prefetch " + prefetch + ", member ID ["
                                        + memberId + "], variables set " + configured).isEqualTo(sent);
                                // With prefetch the request says everything, and all of it was found.
                                assertThat(read.lacking).isEqualTo(prefetch ? List.of() : List.of("no member ID", "no NPI"));
                                built++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(built).isEqualTo(96);
    }

    @Test
    void everyValueOfTheCaseIsReadFromThePlaceTheBuilderWritesItTo() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(sent(form("order-select", 3, true).build())).build());
        assertThat(comparison.caseSource()).extracting(CaseValue::field, CaseValue::source, CaseValue::path, value -> value.value().value())
                .containsExactly(
                        tuple("hook", "hook", "order-select"),
                        tuple("member.memberId", "prefetch.patient.identifier[0].value", "S1234567801"),
                        tuple("member.firstName", "prefetch.patient.name[0].given[0]", "Given"),
                        tuple("member.lastName", "prefetch.patient.name[0].family", SURNAME),
                        tuple("member.dateOfBirth", "prefetch.patient.birthDate", BIRTH_DATE),
                        tuple("member.gender", "prefetch.patient.gender", "female"),
                        tuple("member.subscriberId", "prefetch.coverage.subscriberId", "SUB-PLACEHOLDER-1"),
                        tuple("member.planName", "prefetch.coverage.class[0].value", "Placeholder Plan"),
                        tuple("member.coverageStatus", "prefetch.coverage.status", "active"),
                        tuple("provider.npi", "prefetch.practitioner.identifier[0].value", NPI),
                        tuple("provider.firstName", "prefetch.practitioner.name[0].given[0]", "Pat"),
                        tuple("provider.lastName", "prefetch.practitioner.name[0].family", "Provider"),
                        // A code, its system and its description are three values, and each is reported with its own place.
                        tuple("procedureCodes[0].code", "context.draftOrders.entry[0].resource.code.coding[0].code", "95810"),
                        tuple("procedureCodes[0].system", "context.draftOrders.entry[0].resource.code.coding[0].system", "http://www.ama-assn.org/go/cpt"),
                        tuple("procedureCodes[0].description", "context.draftOrders.entry[0].resource.code.coding[0].display", "Sleep study, attended"),
                        tuple("procedureCodes[1].code", "context.draftOrders.entry[1].resource.code.coding[0].code", "E0601"),
                        tuple("procedureCodes[1].system", "context.draftOrders.entry[1].resource.code.coding[0].system",
                                "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets"),
                        tuple("procedureCodes[2].code", "context.draftOrders.entry[2].resource.code.coding[0].code", "70553"),
                        tuple("procedureCodes[2].system", "context.draftOrders.entry[2].resource.code.coding[0].system", "http://www.ama-assn.org/go/cpt"),
                        tuple("dateOfService", "context.draftOrders.entry[0].resource.occurrenceDateTime", "2026-03-20"));
        assertThat(source(comparison, "member.memberId").pointer()).isEqualTo("/prefetch/patient/identifier/0/value");
        assertThat(source(comparison, "member.memberId").label()).isEqualTo("Member ID");
        assertThat(source(comparison, "procedureCodes[0].system").pointer()).isEqualTo("/context/draftOrders/entry/0/resource/code/coding/0/system");
        assertThat(source(comparison, "procedureCodes[0].system").label()).isEqualTo("System of procedure code 1");
        assertThat(source(comparison, "procedureCodes[0].description").label()).isEqualTo("Description of procedure code 1");
        // What a request never carries, and what this one does not state, is said to be missing, and nothing is put in its place.
        assertThat(comparison.notFound()).extracting(NotFound::field, NotFound::lookedIn).containsExactly(
                org.assertj.core.groups.Tuple.tuple("member.lineOfBusiness", notFound(comparison, "member.lineOfBusiness").lookedIn()),
                org.assertj.core.groups.Tuple.tuple("procedureCodes[1].description", "context.draftOrders.entry[1].resource.code.coding[0].display"),
                org.assertj.core.groups.Tuple.tuple("procedureCodes[2].description", "context.draftOrders.entry[2].resource.code.coding[0].display"));
        assertThat(notFound(comparison, "member.lineOfBusiness").note()).startsWith("A CRD request does not carry the line of business");
        assertThat(notFound(comparison, "procedureCodes[1].description").note()).isEqualTo("The coding states no display. This tool writes a "
                + "display only when it is given a description.");
        assertThat(notFound(comparison, "procedureCodes[1].description").label()).isEqualTo("Description of procedure code 2");
    }

    private static org.assertj.core.groups.Tuple tuple(String field, String path, String value) {
        return org.assertj.core.groups.Tuple.tuple(field, CaseValue.Source.YOUR_REQUEST, path, value);
    }

    @Test
    void aSystemTheCodeListSuppliedIsReadBackAsTheSystemOfTheCode() {
        // The line of business chose the row, and a request does not carry the line of business.
        // The system it led to is in the request, so the same request is still built again.
        codeList.row(ProcedureCode.builder().code("70553").system("urn:from-the-code-list").lineOfBusiness("Medicare")
                .authRequired(AuthRequirement.UNKNOWN).build());
        CrdRunRequest original = form("order-sign", 1, true).procedureCodes(List.of(CodeInput.of("70553")))
                .member(MemberDetail.builder().memberId("S123456789").lineOfBusiness("Medicare").build()).build();
        String sent = sent(original);
        assertThat(sent).contains("urn:from-the-code-list");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(sent).build());
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE);
        // The code list would have led this tool to the same system, so there is nothing to note.
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW);
    }

    private static final String OWN_SYSTEM = "Left to work the system out for itself";

    @Test
    void aSystemReadFromThePastedRequestIsUsedAndTheResultSaysWhichOneThisToolWritesByItself() {
        // The sample writes its HCPCS code under another system than the one this tool takes from the format of the code.
        String pasted = fixture("vendor-shaped-order-sign.json").replace("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                "urn:oid:2.16.840.1.113883.6.285");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        // Used as read, so that the two orders describe the same code. Unsaid, that would hide how this tool itself writes the code.
        assertThat(JsonSafe.parse(comparison.toolBody()).node().at("/context/draftOrders/entry/1/resource/code/coding/0/system").asString())
                .isEqualTo("urn:oid:2.16.840.1.113883.6.285");
        assertThat(comparison.notes()).contains("Your request states the system of the code at "
                + "context.draftOrders.entry[1].resource.codeCodeableConcept.coding[0].code, and the tool's request was built with that system. "
                + "Left to work the system out for itself, from the code list or else from the format of the code, this tool writes "
                + "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets.");
        assertThat(comparison.notes()).filteredOn(note -> note.contains(OWN_SYSTEM)).hasSize(1);

        // A code whose format says nothing, under a system only the sample knows: by itself this tool writes none.
        String unknown = sent(form("order-sign", 1, true).procedureCodes(List.of(new CodeInput("XYZ-1", "https://example.invalid/local-codes", null))).build());
        assertThat(compare(CrdRunRequest.builder().rawBody(unknown).build()).notes()).contains("Your request states the system of the code at "
                + "context.draftOrders.entry[0].resource.code.coding[0].code, and the tool's request was built with that system. Left to work "
                + "the system out for itself, from the code list or else from the format of the code, this tool writes none.");

        // Where this tool writes the same system by itself there is nothing to note.
        assertThat(compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build()).notes())
                .noneMatch(note -> note.contains(OWN_SYSTEM));
    }

    @Test
    void aCodeOnTheFormIsBuiltWithTheSystemThisToolChoosesAndADifferentSystemInThePastedRequestIsADifference() {
        String pasted = sent(form("order-sign", 1, true).procedureCodes(List.of(new CodeInput("95810", "urn:oid:2.16.840.1.113883.6.12", null))).build());
        RequestComparison comparison = compare(form("order-sign", 1, true).procedureCodes(List.of(CodeInput.of("95810"))).rawBody(pasted).build());
        String coding = "context.draftOrders.entry[ServiceRequest].resource.code.coding";
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE, coding + "[http://www.ama-assn.org/go/cpt] | ONLY_IN_TOOL | BUILD",
                coding + "[urn:oid:2.16.840.1.113883.6.12] | ONLY_IN_YOURS | BUILD");
        assertThat(at(comparison, coding + "[http://www.ama-assn.org/go/cpt]").explanation()).isEqualTo("This list is matched by system. The "
                + "tool's request has an element with this system that your request has no counterpart for. The other request has an unmatched "
                + "element here too: the two were not compared with each other, because they do not share a system. When a code comes without "
                + "a system, this tool takes one from the code list, or else from the format of the code.");
        assertThat(comparison.notes()).noneMatch(note -> note.contains(OWN_SYSTEM));
    }

    // ---- this tool's own request, edited by hand ----

    private static final String SAME_TEXT = "hold the same reference text";

    /** The request with the id of its prefetched patient changed and nothing else: no reference to it is changed with it. */
    private static String withThePatientRenamed(String request) {
        JsonNode root = JsonSafe.parse(request).node();
        ((tools.jackson.databind.node.ObjectNode) root.at("/prefetch/patient")).put("id", "8842");
        return root.toString();
    }

    @Test
    void aRenamedIdWhoseReferencesWereNotRenamedWithItIsNotLeftToReadAsALabelAndNothingMore() {
        // What comes of editing this tool's own request by hand: the patient renamed, and every reference to it left as it was.
        String sent = sent(form("order-sign", 1, true).build());
        RequestComparison comparison = compare(form("order-sign", 1, true).rawBody(withThePatientRenamed(sent)).build());
        // No text of a reference differs, so the id is the one difference, and it says that no reference follows it.
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE, "prefetch.patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(at(comparison, "prefetch.patient.id").referencesFollowing()).isZero();
        // The same texts now point past the patient of the pasted request, and that is said.
        String sameText = comparison.notes().stream().filter(note -> note.contains(SAME_TEXT)).findFirst().orElseThrow();
        assertThat(sameText).startsWith("At context.patientId and at 2 other places the two requests hold the same reference text. In the "
                + "tool's request it points at a Patient the request carries.").endsWith("The Patient that your request carries in its place is "
                + "labelled differently, so in your request the same text does not point at that Patient.");
        // The pasted request names no fhirServer, so where its references point is said as a fact about it as well.
        assertThat(comparison.notes()).anyMatch(note -> note.startsWith("Your request points at a Patient it does not carry, at context.patientId"));
    }

    @Test
    void theSameIsSaidWhenThePastedRequestNamesAServerItsReferencesCouldBeFetchedFrom() {
        configureEveryVariable();
        String sent = sent(form("order-select", 3, true).build());
        assertThat(sent).contains("https://ehr.example.invalid/fhir");
        RequestComparison comparison = compare(form("order-select", 3, true).rawBody(withThePatientRenamed(sent)).build());
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE, "prefetch.patient.id | DIFFERENT_VALUE | LABELS");
        // With a server named, a reference to a resource the request does not carry is not remarked on. What is said is about the two requests.
        assertThat(comparison.notes()).noneMatch(note -> note.contains("it does not carry"));
        assertThat(comparison.notes()).filteredOn(note -> note.contains(SAME_TEXT)).singleElement().satisfies(note -> assertThat(note)
                .startsWith("At context.patientId and at 4 other places the two requests hold the same reference text."));
        assertThat(comparison.notes()).contains("Your request names a fhirServer, so its ids also name resources on that server.");
    }

    @Test
    void aRequestThatUsesItsOwnLabelsThroughoutGetsNoSuchNote() {
        for (String name : List.of("vendor-shaped-order-sign.json", "no-prefetch-order-select.json")) {
            RequestComparison comparison = compare(form("order-sign", 1, true).rawBody(fixture(name)).build());
            assertThat(comparison.comparable()).as(name).isTrue();
            assertThat(comparison.notes()).as(name).noneMatch(note -> note.contains(SAME_TEXT));
        }
        String sent = sent(form("order-select", 3, true).build());
        assertThat(compare(CrdRunRequest.builder().rawBody(sent).build()).notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW);
    }

    // ---- a request shaped the way a vendor might shape it ----

    @Test
    void everyIdentifierInTheFixturesIsAPlaceholderThatPassesTheChecksTheApplicationApplies() {
        JsonNode vendor = JsonSafe.parse(fixture("vendor-shaped-order-sign.json")).node();
        String npi = vendor.at("/prefetch/practitionerRequest/identifier/0/value").asString();
        assertThat(Npi.isValid(npi)).isTrue();
        assertThat(npi).isEqualTo(Npi.withCheckDigit(npi.substring(0, 9))).isEqualTo(NPI);
        assertThat(vendor.at("/prefetch/coverageRequest/subscriberId").asString()).matches("S\\d{9}");
        for (String name : List.of("vendor-shaped-order-sign.json", "no-prefetch-order-select.json")) {
            assertThat(JsonSafe.parse(fixture(name)).ok()).as(name).isTrue();
        }
    }

    @Test
    void aVendorShapedRequestGivesExactlyTheseDifferencesAndNoOthers() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build());
        String order = "context.draftOrders.entry[ServiceRequest #1]";
        assertThat(lines(comparison)).containsExactly(
                "hookInstance | DIFFERENT_VALUE | EVERY_CALL",
                // The tool names a PractitionerRole as the user and the sample names the Practitioner.
                "context.userId | DIFFERENT_VALUE | BUILD",
                order + ".resource.id | DIFFERENT_VALUE | LABELS",
                order + ".resource.intent | DIFFERENT_VALUE | BUILD",
                // The sample holds a date and time, and the tool sends a date.
                order + ".resource.occurrenceDateTime | DIFFERENT_VALUE | CASE",
                order + ".resource.requester.reference | DIFFERENT_VALUE | BUILD",
                order + ".resource.meta | ONLY_IN_YOURS | BUILD",
                // The extra extension.
                order + ".resource.extension | ONLY_IN_YOURS | BUILD",
                order + ".resource.encounter | ONLY_IN_YOURS | BUILD",
                order + ".resource.authoredOn | ONLY_IN_YOURS | BUILD",
                order + ".fullUrl | ONLY_IN_YOURS | BUILD",
                // The second order is a DeviceRequest in the sample and a ServiceRequest in the tool's request.
                "context.draftOrders.entry[ServiceRequest #2] | ONLY_IN_TOOL | BUILD",
                "context.draftOrders.entry[DeviceRequest] | ONLY_IN_YOURS | BUILD",
                "context.encounterId | ONLY_IN_YOURS | BUILD",
                // Other prefetch keys, other ids.
                "prefetch[Patient] | DIFFERENT_KEY | BUILD",
                "prefetch[Patient].id | DIFFERENT_VALUE | LABELS",
                // The member ID is in another place: the sample's Patient carries a record number, not a member ID.
                "prefetch[Patient].identifier[no system] | ONLY_IN_TOOL | BUILD",
                "prefetch[Patient].identifier[https://ehr.example.invalid/ids/mrn] | ONLY_IN_YOURS | BUILD",
                "prefetch[Patient].name[0].given[1] | ONLY_IN_YOURS | CASE",
                "prefetch[Patient].name[0].use | ONLY_IN_YOURS | BUILD",
                "prefetch[Patient].address | ONLY_IN_YOURS | BUILD",
                "prefetch[Coverage] | DIFFERENT_KEY | BUILD",
                "prefetch[Coverage].id | DIFFERENT_VALUE | LABELS",
                "prefetch[Coverage].identifier | ONLY_IN_TOOL | BUILD",
                "prefetch[Coverage].class[plan].name | ONLY_IN_YOURS | BUILD",
                "prefetch[Coverage].period | ONLY_IN_YOURS | BUILD",
                "prefetch[Coverage].payor | ONLY_IN_YOURS | BUILD",
                "prefetch.practitionerRole | ONLY_IN_TOOL | BUILD",
                "prefetch[Practitioner] | DIFFERENT_KEY | BUILD",
                "prefetch[Practitioner].id | DIFFERENT_VALUE | LABELS",
                "prefetch.encounterRequest | ONLY_IN_YOURS | BUILD",
                "fhirServer | ONLY_IN_YOURS | BUILD",
                "fhirAuthorization | ONLY_IN_YOURS | BUILD",
                "vendorTraceId | ONLY_IN_YOURS | NOT_CLASSIFIED");
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.whyNot()).isNull();
        assertThat(comparison.hook()).isEqualTo("order-sign");
        assertThat(comparison.headline()).isEqualTo("The two requests differ in 34 places: 26 in how the request is built, 2 in the case they "
                + "describe, 4 in labels, 1 that differs on every call, and 1 that this tool does not classify.");
        assertThat(comparison.counts()).extracting(GroupCount::group, GroupCount::count).containsExactly(
                org.assertj.core.groups.Tuple.tuple(DifferenceGroup.BUILD, 26), org.assertj.core.groups.Tuple.tuple(DifferenceGroup.CASE, 2),
                org.assertj.core.groups.Tuple.tuple(DifferenceGroup.LABELS, 4), org.assertj.core.groups.Tuple.tuple(DifferenceGroup.EVERY_CALL, 1),
                org.assertj.core.groups.Tuple.tuple(DifferenceGroup.NOT_CLASSIFIED, 1));
        assertThat(comparison.counts().get(0).label()).isEqualTo("How the request is built");
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW,
                "Your request carries an order as a DeviceRequest. This tool builds a ServiceRequest for each procedure code, so the two orders "
                        + "are different kinds of resource and each is listed whole.",
                "Your request names a fhirServer, so its ids also name resources on that server.");
    }

    @Test
    void theIdsOfAVendorShapedRequestAreLabelsAndTheReferencesThatFollowThemAreCountedNotListed() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build());
        Difference patient = at(comparison, "prefetch[Patient].id");
        assertThat(patient.toolValue().value()).isEqualTo("patient-1");
        assertThat(patient.yourValue().value()).isEqualTo("pat-8842");
        assertThat(patient.toolPointer()).isEqualTo("/prefetch/patient/id");
        assertThat(patient.yourPointer()).isEqualTo("/prefetch/patientRequest/id");
        // context.patientId, the order's subject and the coverage's beneficiary: none of them is listed.
        assertThat(patient.referencesFollowing()).isEqualTo(3);
        assertThat(patient.what()).isEqualTo("The id of the resource, which is its label inside the request and not a member ID.");
        assertThat(at(comparison, "prefetch[Coverage].id").referencesFollowing()).isEqualTo(1);
        assertThat(at(comparison, "prefetch[Practitioner].id").referencesFollowing()).isZero();
        assertThat(lines(comparison)).noneMatch(line -> line.startsWith("context.patientId") || line.contains("subject") || line.contains("beneficiary")
                || line.contains("insurance"));

        // The two references that are listed point at another kind of resource, which no relabelling explains.
        Difference user = at(comparison, "context.userId");
        assertThat(user.toolValue().value()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(user.yourValue().value()).isEqualTo("Practitioner/prac-9001");
        assertThat(user.explanation()).isEqualTo("The two references do not follow one matched pair of resources: the tool's points at a "
                + "PractitionerRole in the tool's request, and yours points at a Practitioner in your request.");
        assertThat(at(comparison, "context.draftOrders.entry[ServiceRequest #1].resource.requester.reference").explanation())
                .isEqualTo(user.explanation());
    }

    @Test
    void whatIsSaidAboutAVendorShapedRequestFollowsFromItsStructureAndFromHowThisToolBuilds() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build());
        Difference key = at(comparison, "prefetch[Patient]");
        assertThat(key.toolValue().value()).isEqualTo("patient");
        assertThat(key.yourValue().value()).isEqualTo("patientRequest");
        assertThat(key.what()).isEqualTo("The member, as a FHIR Patient resource.");
        assertThat(key.explanation()).isEqualTo("The tool's request holds this Patient under the key `patient` and your request holds it under "
                + "`patientRequest`. A CDS service names the prefetch keys it looks for in its discovery response, and reads only those. "
                + "GET /api/crd/discover lists the keys the service names.");
        assertThat(at(comparison, "prefetch.practitionerRole").explanation()).isEqualTo("The tool's request has the key `practitionerRole` here "
                + "and your request does not. It holds a PractitionerRole resource. A CDS service names the prefetch keys it looks for in its "
                + "discovery response, and reads only those. GET /api/crd/discover lists the keys the service names.");

        Difference date = at(comparison, "context.draftOrders.entry[ServiceRequest #1].resource.occurrenceDateTime");
        assertThat(date.toolValue().value()).isEqualTo("2026-03-20");
        assertThat(date.yourValue().value()).isEqualTo("2026-03-20T09:30:00-04:00");
        assertThat(date.what()).isEqualTo("When the order is to be carried out.");
        assertThat(date.explanation()).isEqualTo("The two requests hold different text here. One is a date and the other is a date and time on "
                + "that same date. This tool sends the date of service as a date, with no time.");

        assertThat(at(comparison, "fhirServer").explanation()).isEqualTo("Your request has this element and the tool's request does not. This "
                + "tool sends fhirServer only when the variable fhirServer has a value.");
        assertThat(at(comparison, "fhirAuthorization").explanation()).endsWith("This tool does not build fhirAuthorization.");
        assertThat(at(comparison, "context.encounterId").explanation()).endsWith("This tool does not build an encounterId.");
        assertThat(at(comparison, "prefetch[Coverage].payor").explanation()).endsWith("This tool names a payer only when the variable "
                + "payerIdentifier or payerName has a value.");
        assertThat(at(comparison, "context.draftOrders.entry[DeviceRequest]").explanation()).isEqualTo("Your request has this DeviceRequest in the "
                + "Bundle and the tool's request has no DeviceRequest left to compare it with. This tool builds one ServiceRequest for each "
                + "procedure code.");
        assertThat(at(comparison, "prefetch[Patient].identifier[no system]").explanation()).isEqualTo("This list is matched by system. The tool's "
                + "request has an element with no system that your request has no counterpart for. The other request has an unmatched element "
                + "here too: the two were not compared with each other, because they do not share a system. This tool writes the member ID "
                + "as an identifier typed MB, with a system only when the variable memberIdSystem has a value.");

        // The element nobody described is listed, is not put in a group, and says so.
        Difference unknown = at(comparison, "vendorTraceId");
        assertThat(unknown.what()).isNull();
        assertThat(unknown.explanation()).isEqualTo("Your request has this element and the tool's request does not. This tool has no "
                + "description of this element, so the difference is not classified.");
        // A value one list of names has and the other lacks is a difference in the case.
        Difference given = at(comparison, "prefetch[Patient].name[0].given[1]");
        assertThat(given.yourValue().value()).isEqualTo("Quinn");
        assertThat(given.toolValue().presence()).isEqualTo(Presence.ABSENT);
        assertThat(given.yourPointer()).isEqualTo("/prefetch/patientRequest/name/0/given/1");
    }

    @Test
    void theCaseOfAVendorShapedRequestIsReadFromWhereTheSampleHoldsIt() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build());
        // The member ID in another place: no identifier is typed as a member number, so the subscriber id of the coverage is read.
        assertThat(source(comparison, "member.memberId").path()).isEqualTo("prefetch.coverageRequest.subscriberId");
        assertThat(source(comparison, "member.memberId").value().value()).isEqualTo("S123456789");
        assertThat(source(comparison, "provider.npi").path()).isEqualTo("prefetch.practitionerRequest.identifier[0].value");
        assertThat(source(comparison, "procedureCodes[1].code").path()).isEqualTo("context.draftOrders.entry[1].resource.codeCodeableConcept.coding[0].code");
        assertThat(source(comparison, "procedureCodes[1].system").path()).isEqualTo("context.draftOrders.entry[1].resource.codeCodeableConcept.coding[0].system");
        assertThat(source(comparison, "dateOfService").value().value()).isEqualTo("2026-03-20");
        assertThat(source(comparison, "dateOfService").note()).isEqualTo("Your request holds a date and time here. This tool sends the date of "
                + "service as a date, so the date was used.");
        assertThat(comparison.caseSource()).allSatisfy(value -> assertThat(value.source()).isEqualTo(CaseValue.Source.YOUR_REQUEST));
        JsonNode tool = JsonSafe.parse(comparison.toolBody()).node();
        assertThat(tool.at("/prefetch/patient/identifier/0/value").asString()).isEqualTo("S123456789");
        assertThat(tool.at("/prefetch/practitioner/identifier/0/value").asString()).isEqualTo(NPI);
        assertThat(tool.at("/context/draftOrders/entry/1/resource/code/coding/0/code").asString()).isEqualTo("E0601");
        // Both texts are indented the same way, and the pasted one keeps its own order of keys.
        assertThat(comparison.yourBody()).startsWith("{\n  \"hookInstance\" : \"5f0e8a57").contains("\n  \"fhirServer\" : ");
        assertThat(comparison.toolBody()).startsWith("{\n  \"hook\" : \"order-sign\",\n  \"hookInstance\" : ");
    }

    // ---- the form wins, and what is borrowed is never mixed between two members ----

    @Test
    void aValueOnTheFormIsUsedAndReportedAsTheFormsAndTheRestIsReadFromThePastedRequest() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json"))
                .member(MemberDetail.builder().memberId("S123456789").lastName("Form-Surname").build()).dateOfService("2026-04-01").build());
        assertThat(source(comparison, "member.lastName").source()).isEqualTo(CaseValue.Source.FORM);
        assertThat(source(comparison, "member.lastName").path()).isNull();
        assertThat(source(comparison, "member.lastName").value().value()).isEqualTo("Form-Surname");
        assertThat(source(comparison, "member.firstName").source()).isEqualTo(CaseValue.Source.YOUR_REQUEST);
        assertThat(source(comparison, "dateOfService").source()).isEqualTo(CaseValue.Source.FORM);
        // What the form says differently is a difference in the case.
        Difference family = at(comparison, "prefetch[Patient].name[0].family");
        assertThat(line(family)).isEqualTo("prefetch[Patient].name[0].family | DIFFERENT_VALUE | CASE");
        assertThat(family.toolValue().value()).isEqualTo("Form-Surname");
        assertThat(family.yourValue().value()).isEqualTo("Sample-Member");
        assertThat(family.what()).isEqualTo("The family name.");
        assertThat(at(comparison, "context.draftOrders.entry[ServiceRequest #1].resource.occurrenceDateTime").explanation())
                .startsWith("The two requests hold different text here. This tool sends the date of service as a date");
        assertThat(comparison.headline()).startsWith("The two requests differ in 35 places: 26 in how the request is built, 3 in the case they describe");
    }

    @Test
    void theDetailsOfAnotherMemberAreNeverPutOnTheMemberOfTheForm() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json"))
                .member(MemberDetail.builder().memberId("S999999999").build()).build());
        assertThat(comparison.notes()).contains("The member ID on the form and the member ID in your request are not the same. The tool's "
                + "request was built for the member on the form, and the member details in your request were not used for it.");
        assertThat(source(comparison, "member.memberId").source()).isEqualTo(CaseValue.Source.FORM);
        assertThat(comparison.caseSource()).extracting(CaseValue::field).doesNotContain("member.firstName", "member.lastName", "member.dateOfBirth",
                "member.gender", "member.subscriberId", "member.planName", "member.coverageStatus");
        NotFound name = notFound(comparison, "member.lastName");
        assertThat(name.lookedIn()).isEqualTo("prefetch.patientRequest.name[0].family");
        assertThat(name.note()).isEqualTo("Your request holds this for another member ID than the one on the form, so it was not used.");
        // The provider is the same one, so the provider's name is still read.
        assertThat(source(comparison, "provider.lastName").source()).isEqualTo(CaseValue.Source.YOUR_REQUEST);
        assertThat(comparison.toolBody()).contains("S999999999").doesNotContain("Sample-Member").doesNotContain("1968-04-12").contains("Sample-Provider");
        assertThat(lines(comparison)).contains("prefetch[Patient].name | ONLY_IN_YOURS | BUILD", "prefetch[Patient].birthDate | ONLY_IN_YOURS | BUILD",
                "prefetch[Coverage].subscriberId | ONLY_IN_YOURS | BUILD");
    }

    @Test
    void theNameOfAnotherProviderIsNeverPutOnTheNpiOfTheForm() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json"))
                .provider(ProviderDetail.builder().npi("1234567893").build()).build());
        assertThat(comparison.notes()).contains("The NPI on the form and the NPI in your request are not the same. The tool's request was built "
                + "for the provider on the form, and the provider's name in your request was not used for it.");
        assertThat(notFound(comparison, "provider.lastName").note()).isEqualTo("Your request holds this for another NPI than the one on the form, "
                + "so it was not used.");
        assertThat(comparison.toolBody()).contains("1234567893").doesNotContain("Sample-Provider");
        Difference npi = at(comparison, "prefetch[Practitioner].identifier[http://hl7.org/fhir/sid/us-npi].value");
        assertThat(npi.group()).isEqualTo(DifferenceGroup.CASE);
        assertThat(npi.what()).isEqualTo("The practitioner's identifier itself, which is where an NPI travels.");
    }

    @Test
    void anNpiReadFromThePastedRequestThatARunWouldRefuseIsUsedAndTheResultSaysSo() {
        String body = fixture("vendor-shaped-order-sign.json").replace(NPI, "1234567890");
        assertThat(Npi.isValid("1234567890")).isFalse();
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(body).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.notes()).contains("The NPI read from your request does not pass the NPI check digit. A run from the form refuses "
                + "such an NPI. It was used here so that both requests describe the same provider.");
        assertThat(lines(comparison)).noneMatch(line -> line.contains("us-npi"));
    }

    @Test
    void aMemberIdPastedWithSpacesIsSentWithoutThemAndBothFormsAreShown() {
        String body = sent(form("order-sign", 1, true).build()).replace("S1234567801", "S12345678   01");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(body).build());
        CaseValue memberId = source(comparison, "member.memberId");
        assertThat(memberId.value().value()).isEqualTo("S12345678   01");
        assertThat(memberId.note()).isEqualTo("This tool removes the spaces from a member ID before it sends it.");
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE, "prefetch.patient.identifier[MB].value | DIFFERENT_VALUE | CASE",
                "prefetch.coverage.identifier[MB].value | DIFFERENT_VALUE | CASE");
        Difference value = at(comparison, "prefetch.patient.identifier[MB].value");
        assertThat(value.toolValue().value()).isEqualTo("S1234567801");
        assertThat(value.yourValue().value()).isEqualTo("S12345678   01");
        assertThat(value.explanation()).isEqualTo("The two requests hold different text here. They differ only in spaces: one has spaces where "
                + "the other has none. This tool removes the spaces from a member ID before it sends it.");
    }

    // ---- the hook ----

    @Test
    void aPastedRequestThatNamesAHookThisToolBuildsDecidesTheHookWhateverTheFormSays() {
        String sent = sent(form("order-select", 1, true).build());
        RequestComparison comparison = compare(form("order-sign", 1, true).rawBody(sent).build());
        assertThat(comparison.hook()).isEqualTo("order-select");
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE);
        assertThat(comparison.notes()).contains("The form says order-sign and your request names the hook order-select. The tool's request was "
                + "built for order-select, so that the two can be compared.");
        assertThat(source(comparison, "hook").source()).isEqualTo(CaseValue.Source.YOUR_REQUEST);
        // When the form says the same, or says nothing, there is nothing to note.
        assertThat(compare(form("order-select", 1, true).rawBody(sent).build()).notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW);
        assertThat(compare(form(null, 1, true).rawBody(sent).build()).notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW);
    }

    @Test
    void aPastedRequestThatNamesAnotherHookOrNoneIsComparedWithTheHookOfTheFormAndTheHookIsADifference() {
        String sent = sent(form("order-select", 1, true).build());
        RequestComparison other = compare(form("order-select", 1, true).rawBody(sent.replace("\"order-select\"", "\"appointment-book\"")).build());
        assertThat(other.hook()).isEqualTo("order-select");
        assertThat(lines(other)).containsExactly("hook | DIFFERENT_VALUE | BUILD", ONLY_HOOK_INSTANCE);
        assertThat(other.notes()).contains("Your request names a hook this tool does not build. The tool's request was built for order-select.");

        // A hook is exact: another letter case is another hook, and the difference says that case is all that differs.
        RequestComparison cased = compare(form("order-select", 1, true).rawBody(sent.replace("\"order-select\"", "\"Order-Select\"")).build());
        assertThat(at(cased, "hook").explanation()).isEqualTo("The two requests hold different text here. They differ only in letter case.");

        String noHook = sent(form("order-sign", 1, true).build()).replace("\"hook\" : \"order-sign\",", "");
        RequestComparison none = compare(form(null, 1, true).rawBody(noHook).build());
        assertThat(none.hook()).isEqualTo("order-sign");
        assertThat(lines(none)).containsExactly("hook | ONLY_IN_TOOL | BUILD", ONLY_HOOK_INSTANCE);
        assertThat(source(none, "hook").source()).isEqualTo(CaseValue.Source.FORM);
        assertThat(source(none, "hook").note()).isEqualTo("The form names no hook, which this tool reads as order-sign.");
    }

    // ---- references that point outside the request ----

    @Test
    void aRequestWithoutPrefetchIsComparedOrderByOrderAndWhatItPointsAtOutsideItselfIsNoted() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("no-prefetch-order-select.json")).includePrefetch(false)
                .member(MemberDetail.builder().memberId("S123456789").build()).provider(ProviderDetail.builder().npi(NPI).build()).build());
        String first = "context.draftOrders.entry[ServiceRequest #1].resource";
        String second = "context.draftOrders.entry[ServiceRequest #2].resource";
        assertThat(lines(comparison)).containsExactly(
                ONLY_HOOK_INSTANCE,
                "context.userId | DIFFERENT_VALUE | BUILD",
                "context.patientId | DIFFERENT_VALUE | BUILD",
                // The sample selects one of its two orders and the tool selects every order it builds.
                "context.selections[0] | ONLY_IN_TOOL | BUILD",
                first + ".id | DIFFERENT_VALUE | LABELS",
                first + ".subject.reference | DIFFERENT_VALUE | BUILD",
                first + ".requester.reference | DIFFERENT_VALUE | BUILD",
                first + ".insurance[0].reference | DIFFERENT_VALUE | BUILD",
                first + ".quantityQuantity | ONLY_IN_YOURS | BUILD",
                second + ".id | DIFFERENT_VALUE | LABELS",
                second + ".subject.reference | DIFFERENT_VALUE | BUILD",
                second + ".requester.reference | DIFFERENT_VALUE | BUILD",
                second + ".insurance[0].reference | DIFFERENT_VALUE | BUILD");
        // The selection of the second order follows its relabelling. The first order is selected by the tool only.
        assertThat(at(comparison, second + ".id").referencesFollowing()).isEqualTo(1);
        assertThat(at(comparison, first + ".id").referencesFollowing()).isZero();
        assertThat(at(comparison, "context.selections[0]").explanation()).isEqualTo("The tool's list has a reference here with no counterpart in "
                + "your list. This tool selects every order it builds, and sends selections for order-select only.");
        // Neither request carries the patient, so nothing shows that the two ids name one patient.
        assertThat(at(comparison, "context.patientId").explanation()).isEqualTo("The two references do not follow one matched pair of resources: "
                + "the tool's points at a Patient that the tool's request does not contain, and yours points at a Patient that your request "
                + "does not contain. Whether the two name the same Patient cannot be seen from the requests.");
        // Stated as a fact about each request, in the same words for both.
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW,
                "Your request points at a PractitionerRole it does not carry, at context.userId, "
                        + "context.draftOrders.entry[0].resource.requester.reference, and context.draftOrders.entry[1].resource.requester.reference. "
                        + "It names no fhirServer to fetch it from.",
                "Your request points at a Patient it does not carry, at context.patientId, context.draftOrders.entry[0].resource.subject.reference, "
                        + "and context.draftOrders.entry[1].resource.subject.reference. It names no fhirServer to fetch it from.",
                "Your request points at a Coverage it does not carry, at context.draftOrders.entry[0].resource.insurance[0].reference and "
                        + "context.draftOrders.entry[1].resource.insurance[0].reference. It names no fhirServer to fetch it from.",
                "The tool's request points at a PractitionerRole it does not carry, at context.userId, "
                        + "context.draftOrders.entry[0].resource.requester.reference, and context.draftOrders.entry[1].resource.requester.reference. "
                        + "It names no fhirServer to fetch it from.",
                "The tool's request points at a Patient it does not carry, at context.patientId, "
                        + "context.draftOrders.entry[0].resource.subject.reference, and context.draftOrders.entry[1].resource.subject.reference. It names "
                        + "no fhirServer to fetch it from.",
                "The tool's request points at a Coverage it does not carry, at context.draftOrders.entry[0].resource.insurance[0].reference and "
                        + "context.draftOrders.entry[1].resource.insurance[0].reference. It names no fhirServer to fetch it from.");
        assertThat(comparison.toolBody()).doesNotContain("prefetch");
    }

    @Test
    void aRequestThatNamesAFhirServerIsNotSaidToPointOutsideItself() {
        String withServer = fixture("no-prefetch-order-select.json").replaceFirst("\\{", "{\"fhirServer\": \"https://ehr.example.invalid/fhir\",");
        gateway.variable("fhirServer", "https://ehr.example.invalid/fhir");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(withServer).includePrefetch(false)
                .member(MemberDetail.builder().memberId("S123456789").build()).provider(ProviderDetail.builder().npi(NPI).build()).build());
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW,
                "Your request names a fhirServer, so its ids also name resources on that server.");
        assertThat(lines(comparison)).doesNotContain("fhirServer | ONLY_IN_YOURS | BUILD").hasSize(13);
    }

    @Test
    void theToolsPrefetchBesideARequestWithNoneIsOneDifferenceForTheWholeOfIt() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("no-prefetch-order-select.json"))
                .member(MemberDetail.builder().memberId("S123456789").build()).provider(ProviderDetail.builder().npi(NPI).build()).build());
        Difference prefetch = at(comparison, "prefetch");
        assertThat(line(prefetch)).isEqualTo("prefetch | ONLY_IN_TOOL | BUILD");
        assertThat(prefetch.toolPointer()).isEqualTo("/prefetch");
        assertThat(prefetch.yourPointer()).isNull();
        assertThat(prefetch.yourValue().presence()).isEqualTo(Presence.ABSENT);
        assertThat(prefetch.explanation()).isEqualTo("The tool's request has this element and your request does not. This tool sends prefetch "
                + "unless the form says to leave it out.");
        assertThat(lines(comparison)).filteredOn(line -> line.startsWith("prefetch")).hasSize(1);
        // The tool's request now carries what it points at, and the pasted one still does not.
        assertThat(comparison.notes()).filteredOn(note -> note.startsWith("The tool's request points at")).isEmpty();
        assertThat(comparison.notes()).filteredOn(note -> note.startsWith("Your request points at")).hasSize(3);
    }

    // ---- what cannot be compared ----

    private static final String WHOLE_CASE_LACKING = "the form has no member ID, no NPI, and no procedure code, and they could not be read from "
            + "your request";

    @Test
    void textThatIsNotJsonIsNotComparedAndTheAnswerSaysWhereParsingStoppedWithoutQuotingTheText() {
        String pasted = "{\"hook\": \"order-sign\",\n \"note\": Placeholder-Surname born 1970-01-31}";
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.whyNot()).isEqualTo("The two cannot be compared field by field, because your request could not be read as JSON "
                + "(line 2, column 10).");
        assertThat(comparison.headline()).isEqualTo("The two requests were not compared field by field.");
        // Both texts are still returned, the pasted one exactly as it was written.
        assertThat(comparison.yourBody()).isEqualTo(pasted);
        assertThat(comparison.toolBody()).isNull();
        assertThat(comparison.hook()).isNull();
        assertThat(comparison.notes()).containsExactly("The tool's own request is not shown: " + WHOLE_CASE_LACKING + ".");
        assertThat(comparison.differences()).isEmpty();
        // Nothing was counted, so no count of zero is offered that could be read as "no differences".
        assertThat(comparison.counts()).isEmpty();
        // And nothing was looked for in a text that could not be read, so nothing is reported as looked for and not found.
        assertThat(comparison.notFound()).isEmpty();

        // With a case on the form, the tool's request is still built and shown beside it.
        RequestComparison withForm = compare(form("order-select", 1, true).rawBody(pasted).build());
        assertThat(withForm.comparable()).isFalse();
        assertThat(withForm.hook()).isEqualTo("order-select");
        assertThat(JsonSafe.parse(withForm.toolBody()).node().get("hook").asString()).isEqualTo("order-select");
        assertThat(withForm.notes()).isEmpty();
    }

    @Test
    void jsonThatIsNotAnObjectIsNotComparedAndTheAnswerSaysWhatItIs() {
        Map<String, String> kinds = Map.of("[{\"hook\":\"order-sign\"}]", "a list", "\"order-sign\"", "a text", "42", "a number", "true",
                "true or false", "null", "a null");
        kinds.forEach((pasted, kind) -> {
            RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
            assertThat(comparison.comparable()).as(pasted).isFalse();
            assertThat(comparison.whyNot()).isEqualTo("The two cannot be compared field by field, because your request is JSON but not a JSON "
                    + "object: it is " + kind + ".");
            assertThat(comparison.toolBody()).isNull();
        });
        assertThat(compare(CrdRunRequest.builder().rawBody("[1, 2]").build()).yourBody()).isEqualTo("[ 1, 2 ]");
    }

    @Test
    void anEmptyObjectIsNotComparedBecauseThereIsNothingInIt() {
        RequestComparison comparison = compare(form("order-sign", 1, true).rawBody(" {} ").build());
        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.whyNot()).isEqualTo("The two cannot be compared field by field, because your request is an empty JSON object.");
        assertThat(comparison.yourBody()).isEqualTo("{ }");
        assertThat(comparison.toolBody()).contains("\"hook\" : \"order-sign\"");
        assertThat(comparison.differences()).isEmpty();
    }

    @Test
    void aKeyThatAppearsTwiceIsNotedAndTheLastValueIsTheOneCompared() {
        String sent = sent(form("order-select", 1, true).build());
        String twice = sent.replaceFirst("\\{", "{\"hook\": \"order-sign\",");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(twice).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW,
                "Your request has a key that appears more than once in one object. Only the last value of such a key is shown and was compared.");
        assertThat(comparison.hook()).isEqualTo("order-select");
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE);
        assertThat(comparison.yourBody()).doesNotContain("order-sign");
    }

    @Test
    void aPlaceholderWithNoValueDoesNotStopTheComparisonAndIsNamed() {
        String sent = sent(form("order-sign", 1, true).build());
        String pasted = sent.replace("\"" + SURNAME + "\"", "\"{{memberSurname}}\"").replace("\"Given\"", "\"{{ memberFirst }}\"");
        RequestComparison comparison = compare(form("order-sign", 1, true).rawBody(pasted).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW,
                "Your request has placeholders with no value: {{memberSurname}}, {{memberFirst}}. It was compared as pasted, with every "
                        + "placeholder left in place. A value is looked for among the values typed for this run, in the active environment and in "
                        + "application.yml, and not in the variables of a collection.");
        assertThat(comparison.yourBody()).contains("{{memberSurname}}");
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE, "prefetch.patient.name[0].family | DIFFERENT_VALUE | CASE",
                "prefetch.patient.name[0].given[0] | ONLY_IN_TOOL | CASE", "prefetch.patient.name[0].given[0] | ONLY_IN_YOURS | CASE");

        // Typed for this run, both are filled in exactly as a run fills them, and nothing is left to note.
        RequestComparison filled = compare(form("order-sign", 1, true).rawBody(pasted)
                .variables(Map.of("memberSurname", SURNAME, "memberFirst", "Given")).build());
        assertThat(lines(filled)).containsExactly(ONLY_HOOK_INSTANCE);
        assertThat(filled.notes()).containsExactly(CrdRequestComparer.WHAT_IT_CANNOT_SHOW);
        assertThat(filled.yourBody()).isEqualTo(sent);
    }

    @Test
    void aPlaceholderThatLeavesTheTextUnreadableIsNamedBesideTheReasonItCannotBeCompared() {
        // Written without quotes, as a vendor sample writes a number. It is not JSON until it has a value.
        String sent = sent(form("order-sign", 1, true).build());
        String pasted = sent.replace("\"hook\" : \"order-sign\"", "\"hook\" : {{hookName}}");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.whyNot()).isEqualTo("The two cannot be compared field by field, because your request could not be read as JSON "
                + "(line 2, column 13).");
        assertThat(comparison.notes()).hasSize(2);
        assertThat(comparison.notes().get(0)).startsWith("Your request has a placeholder with no value: {{hookName}}. It was compared as pasted");
        assertThat(comparison.yourBody()).isEqualTo(pasted);
        RequestComparison filled = compare(CrdRunRequest.builder().rawBody(pasted).variables(Map.of("hookName", "\"order-sign\"")).build());
        assertThat(lines(filled)).containsExactly(ONLY_HOOK_INSTANCE);
    }

    @Test
    void aFormAndARequestThatTogetherGiveNoMemberIdCannotBeComparedAndTheAnswerSaysExactlyWhatIsMissing() {
        String pasted = "{\"hook\":\"order-sign\",\"context\":{\"userId\":\"Practitioner/prac-1\",\"patientId\":\"S123456789\",\"draftOrders\":{"
                + "\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"S123456789\","
                + "\"code\":{\"coding\":[{\"code\":\"95810\"}]}}}]}},\"prefetch\":{\"doc\":{\"resourceType\":\"Practitioner\",\"id\":\"prac-1\","
                + "\"identifier\":[{\"system\":\"http://hl7.org/fhir/sid/us-npi\",\"value\":\"" + NPI + "\"}]}}}";
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(comparison.comparable()).isFalse();
        // The patient id of the context and the id of a resource look like a member ID here, and neither is taken for one.
        assertThat(comparison.whyNot()).isEqualTo("This tool could not build its own request to compare with yours: the form has no member ID, "
                + "and it could not be read from your request.");
        assertThat(comparison.toolBody()).isNull();
        assertThat(comparison.hook()).isNull();
        assertThat(comparison.yourBody()).startsWith("{\n  \"hook\" : \"order-sign\",");
        assertThat(notFound(comparison, "member.memberId").lookedIn()).startsWith("an identifier typed as a member number (MB)");
        // What was found is still reported, so the tester can see how far the request got.
        assertThat(source(comparison, "provider.npi").value().value()).isEqualTo(NPI);
        assertThat(source(comparison, "procedureCodes[0].code").value().value()).isEqualTo("95810");
        assertThat(source(comparison, "procedureCodes[0].code").note()).isNull();
        // The coding states a code and nothing else, and what this tool does then is said where the system was looked for.
        NotFound system = notFound(comparison, "procedureCodes[0].system");
        assertThat(system.lookedIn()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[0].system");
        assertThat(system.note()).isEqualTo("The coding states no system. This tool then takes one from its code list, or else from the format "
                + "of the code, and writes none when neither gives one.");
        assertThat(system.candidates()).isEmpty();
        assertThat(notFound(comparison, "procedureCodes[0].description").lookedIn()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[0].display");
        assertThat(comparison.notes()).isEmpty();
        assertThat(comparison.differences()).isEmpty();

        // The member ID typed on the form is all that was missing.
        RequestComparison withMember = compare(CrdRunRequest.builder().rawBody(pasted).member(MemberDetail.builder().memberId("S123456789").build()).build());
        assertThat(withMember.comparable()).isTrue();
        assertThat(lines(withMember)).contains("prefetch.patient | ONLY_IN_TOOL | BUILD");
    }

    @Test
    void whenMoreThanOneThingIsMissingEachIsNamed() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"context\":{}}").build());
        assertThat(comparison.whyNot()).isEqualTo("This tool could not build its own request to compare with yours: " + WHOLE_CASE_LACKING + ".");
        RequestComparison two = compare(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"context\":{}}")
                .procedureCodes(List.of(CodeInput.of("95810"))).build());
        assertThat(two.whyNot()).isEqualTo("This tool could not build its own request to compare with yours: the form has no member ID and no "
                + "NPI, and they could not be read from your request.");
        assertThat(source(two, "procedureCodes").source()).isEqualTo(CaseValue.Source.FORM);
        assertThat(source(two, "procedureCodes").value().value()).isEqualTo("95810");
    }

    @Test
    void candidatesThatDisagreeAreListedWithTheirPlacesSoTheTesterCanChoose() {
        String pasted = fixture("vendor-shaped-order-sign.json").replace("\"subscriberId\": \"S123456789\",",
                "\"subscriberId\": \"S123456789\", \"identifier\": [{\"value\": \"S987654321\"}],");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(comparison.comparable()).isFalse();
        NotFound memberId = notFound(comparison, "member.memberId");
        assertThat(memberId.note()).startsWith("Your request holds 2 different member IDs, so none was used.");
        assertThat(memberId.candidates()).extracting(NotFound.Candidate::path, candidate -> candidate.value().value()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("prefetch.coverageRequest.identifier[0].value", "S987654321"),
                org.assertj.core.groups.Tuple.tuple("prefetch.coverageRequest.subscriberId", "S123456789"));
        assertThat(memberId.candidates().get(0).pointer()).isEqualTo("/prefetch/coverageRequest/identifier/0/value");
    }

    // ---- secrets ----

    @Test
    void aSecretThatFillsAPlaceholderIsComparedAndShownAsItsMaskAndNeverAsItsValue() {
        gateway.variable("accessToken", "hunter2-value");
        String pasted = fixture("vendor-shaped-order-sign.json").replace("placeholder-token-for-a-test-fixture", "{{accessToken}}");
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.notes()).contains("Your request takes a value from a secret variable: accessToken. A secret is shown and compared "
                + "as its mask, never as its value.");
        assertThat(comparison.yourBody()).contains("[secret accessToken]");
        assertThat(TestMvc.mapper().writeValueAsString(comparison)).doesNotContain("hunter2-value");
        assertThat(at(comparison, "fhirAuthorization").yourValue().value()).contains("[secret accessToken]");
    }

    // ---- what goes to the browser, and what is printed ----

    @Test
    void theAnswerLeavesOutWhatIsNotThereAndNamesItsPartsAsDocumented() {
        JsonNode comparable = TestMvc.mapper().valueToTree(compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build()));
        assertThat(comparable.propertyNames()).containsExactly("comparable", "headline", "hook", "counts", "notes", "caseSource", "notFound",
                "differences", "toolBody", "yourBody");
        assertThat(comparable.get("comparable").asBoolean()).isTrue();
        JsonNode label = comparable.at("/differences/15");
        assertThat(label.propertyNames()).containsExactly("path", "kind", "kindLabel", "group", "toolValue", "yourValue", "toolType", "yourType",
                "what", "explanation", "referencesFollowing", "toolPointer", "yourPointer");
        assertThat(label.get("kind").asString()).isEqualTo("DIFFERENT_VALUE");
        assertThat(label.get("kindLabel").asString()).isEqualTo("A different value");
        assertThat(label.get("group").asString()).isEqualTo("LABELS");
        assertThat(label.at("/toolValue/presence").asString()).isEqualTo("VALUE");
        assertThat(label.at("/toolValue/value").asString()).isEqualTo("patient-1");
        assertThat(label.get("toolType").asString()).isEqualTo("string");
        assertThat(label.get("referencesFollowing").asInt()).isEqualTo(3);
        // An element only one request has: the other value is absent, with no value, no type and no pointer.
        JsonNode only = comparable.at("/differences/31");
        assertThat(only.get("path").asString()).isEqualTo("fhirServer");
        assertThat(only.at("/toolValue").propertyNames()).containsExactly("presence");
        assertThat(only.at("/toolValue/presence").asString()).isEqualTo("ABSENT");
        assertThat(only.has("toolType")).isFalse();
        assertThat(only.has("toolPointer")).isFalse();
        assertThat(only.has("referencesFollowing")).isFalse();
        // The element nobody described has no description, and the property is left out, not sent as null.
        assertThat(comparable.at("/differences/33").has("what")).isFalse();
        assertThat(comparable.at("/caseSource/1").propertyNames()).containsExactlyInAnyOrder("field", "label", "path", "pointer", "source", "value");
        assertThat(comparable.at("/caseSource/1/source").asString()).isEqualTo("YOUR_REQUEST");
        assertThat(comparable.at("/counts/0").propertyNames()).containsExactlyInAnyOrder("count", "description", "group", "label");
        assertThat(comparable.at("/notFound/0").propertyNames()).containsExactlyInAnyOrder("candidates", "field", "label", "note");

        JsonNode not = TestMvc.mapper().valueToTree(compare(CrdRunRequest.builder().rawBody("not json").build()));
        assertThat(not.propertyNames()).containsExactly("comparable", "whyNot", "headline", "counts", "notes", "caseSource", "notFound", "differences",
                "yourBody");
        assertThat(not.get("comparable").asBoolean()).isFalse();
    }

    @Test
    void nothingAComparisonHoldsIsPrintedByItsToString() {
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json").replace(
                "\"subscriberId\": \"S123456789\",", "\"subscriberId\": \"S123456789\", \"identifier\": [{\"value\": \"S987654321\"}],")).build());
        RequestComparison compared = compare(CrdRunRequest.builder().rawBody(fixture("vendor-shaped-order-sign.json")).build());
        List<Object> printed = new ArrayList<>(List.of(comparison, compared));
        printed.addAll(comparison.caseSource());
        printed.addAll(comparison.notFound());
        comparison.notFound().forEach(entry -> printed.addAll(entry.candidates()));
        printed.addAll(compared.caseSource());
        printed.addAll(compared.differences());
        printed.addAll(compared.counts());
        assertThat(printed).hasSizeGreaterThan(60);
        for (Object value : printed) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain("S123456789").doesNotContain("S987654321")
                    .doesNotContain("Sample-Member").doesNotContain("Robin").doesNotContain("1968-04-12").doesNotContain(NPI)
                    .doesNotContain("Sample-Provider").doesNotContain("hookInstance\"").doesNotContain("resourceType").doesNotContain("pat-8842")
                    .doesNotContain("Placeholder Street");
        }
        assertThat(compared.toString()).isEqualTo("RequestComparison[comparable=true, differences=34]");
        assertThat(compared.caseSource().get(1).toString()).isEqualTo("CaseValue[member.memberId, YOUR_REQUEST]");
        assertThat(notFound(comparison, "member.memberId").toString()).isEqualTo("NotFound[member.memberId, candidates=2]");
        assertThat(notFound(comparison, "member.memberId").candidates().get(0).toString()).isEqualTo("Candidate[prefetch.coverageRequest.identifier[0].value]");
        assertThat(CrdRequestReader.read(JsonSafe.parse(fixture("vendor-shaped-order-sign.json")).node()).found(CaseField.MEMBER_ID).toString())
                .isEqualTo("Found[prefetch.coverageRequest.subscriberId]");
    }

    @Test
    void theSizeThatIsComparedIsMeasuredInBytes() {
        assertThat(CrdRequestComparer.tooLarge(null)).isFalse();
        assertThat(CrdRequestComparer.tooLarge("x".repeat(CrdRequestComparer.MAX_BYTES))).isFalse();
        assertThat(CrdRequestComparer.tooLarge("x".repeat(CrdRequestComparer.MAX_BYTES + 1))).isTrue();
        // A character outside ASCII takes more than one byte, so fewer of them reach the limit.
        assertThat(CrdRequestComparer.tooLarge(Character.toString(0xE9).repeat(CrdRequestComparer.MAX_BYTES / 2 + 1))).isTrue();
        assertThat(CrdRequestComparer.MAX_BYTES).isEqualTo(2 * 1024 * 1024);
    }
}
