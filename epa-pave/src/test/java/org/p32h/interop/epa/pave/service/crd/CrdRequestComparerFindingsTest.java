package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.compare.CaseValue;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.model.compare.NotFound;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * This tool's own request, edited by hand the way a vendor's sample can differ from it, for the
 * cases where pairing by position, by key or by the last two segments of a url would compare two
 * things that are not the same and report the difference as one in how the request is built.
 */
class CrdRequestComparerFindingsTest {

    /** When the request is built, fixed so that two builds of one case are the same request, authoredOn included. */
    private static final java.time.Clock BUILT_AT = java.time.Clock.fixed(java.time.Instant.parse("2026-03-18T14:05:09Z"), java.time.ZoneOffset.UTC);

    private static final String HOOK_INSTANCE = "11111111-1111-4111-8111-111111111111";
    private static final String ONLY_HOOK_INSTANCE = "hookInstance | DIFFERENT_VALUE | EVERY_CALL";

    private final FakeGateway gateway = new FakeGateway();
    private final FakeCodeList codeList = new FakeCodeList();

    private Function<String, ResolvedText> variables() {
        return template -> gateway.resolve(template, Map.of());
    }

    private String sent(CrdRunRequest request) {
        return new CrdRequestBuilder(codeList, BUILT_AT).build(request, HOOK_INSTANCE, variables()).json();
    }

    private RequestComparison compare(CrdRunRequest form) {
        RequestComparison comparison = new CrdRequestComparer(new CrdRequestBuilder(codeList, BUILT_AT)).compare(form, variables());
        CompareWords.assertWords(comparison, CrdRequestComparerTest.MEMBER_DATA);
        return comparison;
    }

    private static String edited(String request, Consumer<ObjectNode> edit) {
        ObjectNode root = (ObjectNode) JsonSafe.parse(request).node();
        edit.accept(root);
        return root.toString();
    }

    private static String line(Difference difference) {
        return difference.path() + " | " + difference.kind() + " | " + difference.group();
    }

    private static List<String> lines(RequestComparison comparison) {
        return comparison.differences().stream().map(CrdRequestComparerFindingsTest::line).toList();
    }

    private static Difference at(RequestComparison comparison, String path) {
        return comparison.differences().stream().filter(difference -> difference.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no difference at " + path + " among " + lines(comparison)));
    }

    private static CaseValue source(RequestComparison comparison, String field) {
        return comparison.caseSource().stream().filter(value -> value.field().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("no case value " + field));
    }

    // ---- F1: the same orders in another order ----

    @Test
    void theSameOrdersInAnotherOrderAreComparedOrderByOrderAndDifferOnlyInTheirLabels() {
        List<CodeInput> codes = List.of(CodeInput.of("95810"), CodeInput.of("E0601"));
        CrdRunRequest.Builder form = CrdRequestComparerTest.form("order-sign", 2, true).procedureCodes(codes);
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 2, true).procedureCodes(List.of(codes.get(1), codes.get(0))).build()),
                root -> {
                    ((ObjectNode) root.at("/context/draftOrders/entry/0/resource")).put("id", "vsr-a");
                    ((ObjectNode) root.at("/context/draftOrders/entry/1/resource")).put("id", "vsr-b");
                });
        RequestComparison comparison = compare(form.rawBody(pasted).build());
        assertThat(lines(comparison)).containsExactly(ONLY_HOOK_INSTANCE,
                "context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS",
                "context.draftOrders.entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE | LABELS");
        // The tool's first order, 95810, is the pasted request's second.
        assertThat(comparison.differences().get(1).toolPointer()).isEqualTo("/context/draftOrders/entry/0/resource/id");
        assertThat(comparison.differences().get(1).yourPointer()).isEqualTo("/context/draftOrders/entry/1/resource/id");

        // The same with the codes read from the pasted request: the tool builds in the pasted order, and nothing differs but the labels.
        RequestComparison read = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(lines(read)).containsExactly(ONLY_HOOK_INSTANCE,
                "context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS",
                "context.draftOrders.entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE | LABELS");
    }

    // ---- F2: an absolute url on another server ----

    @Test
    void anAbsoluteUrlOnAServerTheRequestDoesNotNameIsNotTakenToPointAtTheResourceTheRequestCarries() {
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()), root -> {
            ((ObjectNode) root.at("/prefetch/patient")).put("id", "8842");
            ((ObjectNode) root.at("/context")).put("patientId", "8842");
            ((ObjectNode) root.at("/prefetch/coverage/beneficiary")).put("reference", "Patient/8842");
            ((ObjectNode) root.at("/context/draftOrders/entry/0/resource/subject")).put("reference",
                    "https://other.example.invalid/fhir/Patient/8842");
        });
        RequestComparison comparison = compare(CrdRequestComparerTest.form("order-sign", 1, true).rawBody(pasted).build());
        Difference subject = at(comparison, "context.draftOrders.entry[ServiceRequest].resource.subject.reference");
        assertThat(subject.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(subject.group()).isEqualTo(DifferenceGroup.BUILD);
        assertThat(subject.explanation()).doesNotContain("same matched resource").contains("cannot be seen from the requests");
        // Only the two references that do follow the renamed patient are counted on it.
        assertThat(at(comparison, "prefetch.patient.id").referencesFollowing()).isEqualTo(2);

        // On the server the request names, the same url names the Patient the request carries under that id.
        String onItsServer = edited(pasted, root -> root.put("fhirServer", "https://other.example.invalid/fhir"));
        Difference written = at(compare(CrdRequestComparerTest.form("order-sign", 1, true).rawBody(onItsServer).build()),
                "context.draftOrders.entry[ServiceRequest].resource.subject.reference");
        assertThat(written.kind()).isEqualTo(DifferenceKind.WRITTEN_DIFFERENTLY);
        assertThat(written.explanation()).startsWith("Both point at the same matched resource, written in different forms");
    }

    // ---- F3: a patientId that names a Patient the request does not carry ----

    @Test
    void aPatientTheRequestDoesNotNameAsItsPatientIsNotReadAsTheMember() {
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()),
                root -> ((ObjectNode) root.at("/context")).put("patientId", "other-9"));
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        for (String field : List.of("member.firstName", "member.lastName", "member.dateOfBirth", "member.gender")) {
            assertThat(comparison.caseSource()).extracting(CaseValue::field).doesNotContain(field);
            NotFound missing = comparison.notFound().stream().filter(value -> value.field().equals(field)).findFirst().orElseThrow();
            assertThat(missing.note()).isEqualTo("context.patientId names a Patient your request does not carry.");
        }
        // The member ID is still on a Coverage, and the answer says what that rests on.
        CaseValue memberId = source(comparison, "member.memberId");
        assertThat(memberId.path()).startsWith("prefetch.coverage.");
        assertThat(memberId.note()).isEqualTo("context.patientId names a Patient your request does not carry. The member ID was read from a "
                + "Coverage, and nothing shows that the Coverage is that patient's.");
    }

    // ---- F4: a prefetched Practitioner under another key ----

    @Test
    void theToolsPractitionerIsComparedWithThePractitionerItsNpiWasReadFrom() {
        String npi = Npi.withCheckDigit("123456789");
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()), root -> {
            ObjectNode other = ((ObjectNode) root.get("prefetch")).putObject("practitioner2");
            other.put("resourceType", "Practitioner").put("id", "practitioner-2");
            other.putArray("identifier").addObject().put("system", "http://hl7.org/fhir/sid/us-npi").put("value", npi);
            ((ObjectNode) root.get("context")).put("userId", "Practitioner/practitioner-2");
        });
        RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(source(comparison, "provider.npi").value().value()).isEqualTo(npi);
        // The NPI the tool wrote is the one it read, so it is not a difference in the case.
        assertThat(lines(comparison)).noneMatch(line -> line.contains("identifier"));
        assertThat(lines(comparison)).contains("prefetch[Practitioner] | DIFFERENT_KEY | BUILD", "prefetch.practitioner | ONLY_IN_YOURS | BUILD");
        assertThat(at(comparison, "prefetch[Practitioner]").explanation()).contains("The tool's was built from what was read out of yours, "
                + "so the two are compared with each other.");
    }

    // ---- F5: two resources with one type and id ----

    @Test
    void aReferenceToATypeAndIdThatTheRequestCarriesTwiceIsListedAndNoted() {
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()), root -> {
            ((ObjectNode) root.at("/prefetch/patient")).put("id", "A");
            ((ObjectNode) root.at("/context")).put("patientId", "A");
            ((ObjectNode) root.at("/prefetch/coverage/beneficiary")).put("reference", "Patient/A");
            ((ObjectNode) root.at("/context/draftOrders/entry/0/resource/subject")).put("reference", "Patient/A");
            ObjectNode twin = ((ArrayNode) root.at("/context/draftOrders/entry")).addObject().putObject("resource");
            twin.put("resourceType", "Patient").put("id", "A");
        });
        RequestComparison comparison = compare(CrdRequestComparerTest.form("order-sign", 1, true).rawBody(pasted).build());
        assertThat(at(comparison, "prefetch.patient.id").referencesFollowing()).isZero();
        for (String path : List.of("context.patientId", "context.draftOrders.entry[ServiceRequest].resource.subject.reference",
                "prefetch.coverage.beneficiary.reference")) {
            Difference reference = at(comparison, path);
            assertThat(reference.group()).as(path).isEqualTo(DifferenceGroup.BUILD);
            assertThat(reference.explanation()).as(path).startsWith("Your request carries more than one resource with the type and id this "
                    + "reference names, so which one it means cannot be told.");
        }
        assertThat(comparison.notes()).contains("Your request carries 2 Patient resources with the same id, at "
                + "context.draftOrders.entry[1].resource and prefetch.patient. A reference to that id can mean either.");
    }

    // ---- F6: the remark about spaces ----

    @Test
    void theRemarkAboutSpacesIsMadeOnlyWhereTheTwoMemberIdsDifferInNothingElse() {
        String sent = sent(CrdRequestComparerTest.form("order-sign", 1, true).build());
        String other = edited(sent, root -> {
            for (String resource : List.of("/prefetch/patient", "/prefetch/coverage")) {
                for (JsonNode identifier : root.at(resource + "/identifier")) {
                    ((ObjectNode) identifier).put("value", "S222222222");
                }
            }
        });
        CrdRunRequest.Builder form = CrdRequestComparerTest.form("order-sign", 1, true)
                .member(MemberDetail.builder().memberId("S1234567801").build());
        RequestComparison different = compare(form.rawBody(other).build());
        List<Difference> values = different.differences().stream().filter(difference -> difference.path().endsWith(".value")
                && difference.path().contains(".identifier[")).toList();
        assertThat(values).isNotEmpty().allSatisfy(difference -> assertThat(difference.explanation())
                .isEqualTo("The two requests hold different text here."));

        String spaced = edited(sent, root -> ((ObjectNode) root.at("/prefetch/patient/identifier/0")).put("value", "S12345678 01"));
        RequestComparison onlySpaces = compare(form.rawBody(spaced).build());
        assertThat(onlySpaces.differences()).filteredOn(difference -> difference.path().startsWith("prefetch.patient.identifier"))
                .singleElement().satisfies(difference -> assertThat(difference.explanation()).isEqualTo("The two requests hold different "
                        + "text here. They differ only in spaces: one has spaces where the other has none. This tool removes the spaces from "
                        + "a member ID before it sends it."));
    }

    // ---- F7: the note about a fhirServer ----

    @Test
    void theNoteAboutAFhirServerSaysWhatTheStructureShowsAndNoMore() {
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()), root -> {
            root.put("fhirServer", "https://ehr.example.invalid/fhir");
            ((ObjectNode) root.at("/prefetch/coverage")).put("id", "cov-9");
            ((ObjectNode) root.at("/context/draftOrders/entry/0/resource/insurance/0")).put("reference", "Coverage/cov-9");
        });
        RequestComparison comparison = compare(CrdRequestComparerTest.form("order-sign", 1, true).rawBody(pasted).build());
        assertThat(comparison.notes()).contains("Your request names a fhirServer. An id in it may also be used to fetch a resource from "
                + "that server, so a renamed id can matter there.");
        assertThat(comparison.notes()).noneMatch(note -> note.contains("ids also name resources"));
    }

    // ---- F8: JSON nested deeper than the parser reads ----

    @Test
    void jsonNestedDeeperThanTheParserReadsIsSaidToBeThatWithThePlace() {
        for (int levels : List.of(600, 1200)) {
            String nested = "{\"url\":\"https://example.invalid/x\",\"extension\":[".repeat(levels) + "]}".repeat(levels);
            String pasted = sent(CrdRequestComparerTest.form("order-sign", 1, true).build())
                    .replaceFirst("\"resourceType\" : \"Patient\",", "\"resourceType\" : \"Patient\",\n\"extension\" : [" + nested + "],");
            assertThat(pasted).as("the extension was inserted").contains("example.invalid/x");
            RequestComparison comparison = compare(CrdRunRequest.builder().rawBody(pasted).build());
            assertThat(comparison.comparable()).isFalse();
            if (JsonSafe.parse(pasted).ok()) {
                continue;
            }
            assertThat(comparison.whyNot()).as(levels + " levels").matches("The two cannot be compared field by field, because your request nests "
                    + "deeper than this tool reads \\(more than \\d+ levels\\), first at line \\d+, column \\d+\\.");
            assertThat(comparison.whyNot()).doesNotContain("could not be read as JSON");
        }
    }

    // ---- F10: a member ID read from a subscriber id ----

    @Test
    void aMemberIdReadFromTheSubscriberIdSaysSo() {
        String pasted = edited(sent(CrdRequestComparerTest.form("order-sign", 1, true).build()), root -> {
            ((ObjectNode) root.at("/prefetch/patient")).remove("identifier");
            ((ObjectNode) root.at("/prefetch/coverage")).remove("identifier");
        });
        CaseValue memberId = source(compare(CrdRunRequest.builder().rawBody(pasted).build()), "member.memberId");
        assertThat(memberId.path()).isEqualTo("prefetch.coverage.subscriberId");
        assertThat(memberId.note()).isEqualTo("Read from Coverage.subscriberId, because no identifier is typed as a member number. "
                + "Coverage.subscriberId is the subscriber's ID, which for a dependent can differ from the member's own.");
    }
}
