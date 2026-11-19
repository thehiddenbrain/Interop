package org.p32h.interop.epa.pave.service.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import tools.jackson.databind.JsonNode;

/**
 * Ids and the references that follow them. An id is a label: two requests can label the same
 * resource differently with no difference in meaning, as long as each uses its own label
 * throughout. The engine reports that once, and lists only a reference that points elsewhere.
 */
class LabelsAndReferencesTest {

    /** A document whose top level tells the engine where its bare ids and typed strings are, as a hook's context does. */
    private static final CompareRules RULES = new CompareRules(Set.of(),
            Map.of("context.patientId", "Patient", "context.userId", "", "context.selections", ""));

    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text.replace('\'', '"'));
        assertThat(parsed.ok()).as("the test document is JSON: " + text).isTrue();
        return parsed.node();
    }

    private static List<Difference> differences(String tool, String yours) {
        return new JsonComparer(RULES, Glossary.NONE).compare(json(tool), json(yours)).differences();
    }

    private static String line(Difference difference) {
        return difference.path() + " | " + difference.kind() + " | " + difference.group();
    }

    private static List<String> lines(List<Difference> differences) {
        return differences.stream().map(LabelsAndReferencesTest::line).toList();
    }

    /** One member with an order, a coverage and a context that all point at the patient by its id. */
    private static String request(String patientId, String subject, String beneficiary, String contextPatientId) {
        return "{'context':{'patientId':'" + contextPatientId + "','userId':'PractitionerRole/role-1'},"
                + "'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'" + subject + "'}},"
                + "'patient':{'resourceType':'Patient','id':'" + patientId + "'},"
                + "'coverage':{'resourceType':'Coverage','id':'c','beneficiary':{'reference':'" + beneficiary + "'}}}";
    }

    @Test
    void aResourceLabelledDifferentlyThroughoutIsOneDifferenceThatCountsTheReferencesFollowingIt() {
        List<Difference> all = differences(request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1"),
                request("8842", "Patient/8842", "Patient/8842", "8842"));
        assertThat(lines(all)).containsExactly("patient.id | DIFFERENT_VALUE | LABELS");
        Difference label = all.get(0);
        assertThat(label.toolValue().value()).isEqualTo("patient-1");
        assertThat(label.yourValue().value()).isEqualTo("8842");
        assertThat(label.toolPointer()).isEqualTo("/patient/id");
        // The bare id in the context, the order's subject and the coverage's beneficiary.
        assertThat(label.referencesFollowing()).isEqualTo(3);
        assertThat(label.explanation()).startsWith("The two requests give this Patient different ids. An id is a label inside one request. "
                + "3 references differ only because they follow this id, and are not listed one by one.");
    }

    @Test
    void anIdThatNoReferenceFollowsSaysSo() {
        List<Difference> all = differences("{'patient':{'resourceType':'Patient','id':'patient-1'}}", "{'patient':{'resourceType':'Patient','id':'8842'}}");
        assertThat(all).singleElement().satisfies(label -> {
            assertThat(label.group()).isEqualTo(DifferenceGroup.LABELS);
            assertThat(label.referencesFollowing()).isZero();
            assertThat(label.explanation()).startsWith("The two requests give this Patient different ids. An id is a label inside one request. "
                    + "No reference was found that differs only because it follows this one.");
        });
    }

    @Test
    void oneReferenceFollowingIsCountedInTheSingular() {
        List<Difference> all = differences("{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'Patient/a'}},"
                + "'patient':{'resourceType':'Patient','id':'a'}}", "{'order':{'resourceType':'ServiceRequest','id':'sr',"
                + "'subject':{'reference':'Patient/b'}},'patient':{'resourceType':'Patient','id':'b'}}");
        assertThat(all).singleElement().satisfies(label -> assertThat(label.explanation())
                .contains("1 reference differs only because it follows this id, and is not listed."));
    }

    @Test
    void aReferenceThatDoesNotFollowAMatchedPairIsListedAsADifferenceInHowTheRequestIsBuilt() {
        // The other request's order points at a patient that is not the one it carries.
        List<Difference> all = differences(request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1"),
                request("8842", "Patient/someone-else", "Patient/8842", "8842"));
        assertThat(lines(all)).containsExactly("order.subject.reference | DIFFERENT_VALUE | BUILD", "patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).toolValue().value()).isEqualTo("Patient/patient-1");
        assertThat(all.get(0).yourValue().value()).isEqualTo("Patient/someone-else");
        assertThat(all.get(0).explanation()).startsWith("The two references do not follow one matched pair of resources: the tool's points at "
                + "a Patient in the tool's request, and yours points at a Patient that your request does not contain.");
        assertThat(all.get(1).referencesFollowing()).isEqualTo(2);
    }

    @Test
    void aReferenceToAnotherKindOfResourceIsListedAndSaysWhatEachPointsAt() {
        String tool = "{'order':{'resourceType':'ServiceRequest','id':'sr','requester':{'reference':'PractitionerRole/role-1'}},"
                + "'role':{'resourceType':'PractitionerRole','id':'role-1'},'who':{'resourceType':'Practitioner','id':'p-1'}}";
        String yours = "{'order':{'resourceType':'ServiceRequest','id':'sr','requester':{'reference':'Practitioner/p-1'}},"
                + "'role':{'resourceType':'PractitionerRole','id':'role-1'},'who':{'resourceType':'Practitioner','id':'p-1'}}";
        List<Difference> all = differences(tool, yours);
        assertThat(lines(all)).containsExactly("order.requester.reference | DIFFERENT_VALUE | BUILD");
        assertThat(all.get(0).explanation()).startsWith("The two references do not follow one matched pair of resources: the tool's points at "
                + "a PractitionerRole in the tool's request, and yours points at a Practitioner in your request.");
    }

    @Test
    void aReferenceToWhatNeitherRequestCarriesIsListedAndSaysThatNothingShowsWhetherTheTwoAreTheSame() {
        String order = "{'context':{'patientId':'%s'},'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'%s'}}}";
        List<Difference> all = differences(order.formatted("patient-1", "Patient/patient-1"), order.formatted("8842", "Patient/8842"));
        // No Patient is in either request, so there is no pair these could follow, and no id difference to count them on.
        assertThat(lines(all)).containsExactly("context.patientId | DIFFERENT_VALUE | BUILD", "order.subject.reference | DIFFERENT_VALUE | BUILD");
        for (Difference difference : all) {
            assertThat(difference.explanation()).startsWith("The two references do not follow one matched pair of resources: the tool's points "
                    + "at a Patient that the tool's request does not contain, and yours points at a Patient that your request does not "
                    + "contain. Whether the two name the same Patient cannot be seen from the requests.");
        }
        // Two different kinds of resource are two different things, and nothing more needs saying.
        List<Difference> kinds = differences(order.formatted("x", "Patient/a"), order.formatted("x", "Group/b"));
        assertThat(kinds).singleElement().satisfies(difference -> assertThat(difference.explanation())
                .startsWith("The two references do not follow one matched pair of resources: the tool's points at a Patient that the tool's "
                        + "request does not contain, and yours points at a Group that your request does not contain.")
                .doesNotContain("Whether the two"));
    }

    @Test
    void theSameResourcePointedAtInAnotherFormIsWrittenDifferentlyAndIsListed() {
        List<Difference> all = differences(request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1"),
                request("8842", "https://ehr.example.invalid/fhir/Patient/8842", "Patient/8842", "Patient/8842"));
        assertThat(lines(all)).containsExactly("context.patientId | WRITTEN_DIFFERENTLY | BUILD", "order.subject.reference | WRITTEN_DIFFERENTLY | BUILD",
                "patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).explanation()).startsWith("Both point at the same matched resource, written in different forms: the tool's "
                + "request writes an id on its own and your request writes a resource type and an id.");
        assertThat(all.get(1).explanation()).startsWith("Both point at the same matched resource, written in different forms: the tool's "
                + "request writes a resource type and an id and your request writes an absolute url.");
        assertThat(all.get(2).referencesFollowing()).isEqualTo(1);
    }

    @Test
    void twoAbsoluteUrlsOnDifferentBasesAreListedEvenWhenTheyPointAtTheMatchedResource() {
        String tool = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'https://one.example.invalid/fhir/Patient/a'}},"
                + "'patient':{'resourceType':'Patient','id':'a'}}";
        String yours = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'https://two.example.invalid/fhir/Patient/a'}},"
                + "'patient':{'resourceType':'Patient','id':'a'}}";
        assertThat(differences(tool, yours)).singleElement().satisfies(difference -> {
            assertThat(line(difference)).isEqualTo("order.subject.reference | WRITTEN_DIFFERENTLY | BUILD");
            assertThat(difference.explanation()).startsWith("Both point at the same matched resource, written in different forms: each is an "
                    + "absolute url, on a different base url.");
        });
    }

    @Test
    void aReferenceThatNamesAnotherVersionOfTheMatchedResourceIsListedAndNotCountedAsFollowingItsId() {
        String document = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'%s'}},'patient':{'resourceType':'Patient','id':'%s'}}";
        List<Difference> all = differences(document.formatted("Patient/a", "a"), document.formatted("Patient/b/_history/3", "b"));
        assertThat(lines(all)).containsExactly("order.subject.reference | WRITTEN_DIFFERENTLY | BUILD", "patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).explanation()).startsWith("Both point at the same matched resource, written in different forms: they do not name "
                + "the same version of the resource.");
        assertThat(all.get(1).referencesFollowing()).isZero();
    }

    @Test
    void aReferenceWithASpaceAroundItIsNotReadAsOneAndTheDifferenceSaysSo() {
        String document = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'%s'}},'patient':{'resourceType':'Patient','id':'a'}}";
        List<Difference> all = differences(document.formatted("Patient/a"), document.formatted("Patient/a "));
        assertThat(lines(all)).containsExactly("order.subject.reference | DIFFERENT_VALUE | BUILD");
        assertThat(all.get(0).explanation()).startsWith("The two references do not follow one matched pair of resources: the tool's points at "
                + "a Patient in the tool's request, and yours is not written in a form this tool reads as a reference.");
    }

    @Test
    void aReferenceIsOnlySaidToFollowALabelWhenTheLabelsReallyDiffer() {
        // Both requests give the entry the same full url. One reference writes it in capitals: that is a difference in the text, not in a label.
        String bundle = "{'resourceType':'Bundle','entry':[{'fullUrl':'urn:uuid:0000000a-0000-4000-8000-000000000001','resource':{'resourceType':'Patient'}},"
                + "{'resource':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'%s'}}}]}";
        List<Difference> all = differences(bundle.formatted("urn:uuid:0000000a-0000-4000-8000-000000000001"),
                bundle.formatted("urn:uuid:0000000A-0000-4000-8000-000000000001"));
        assertThat(lines(all)).containsExactly("entry[ServiceRequest].resource.subject.reference | DIFFERENT_VALUE | NOT_CLASSIFIED");
        assertThat(all.get(0).explanation()).startsWith("The two requests hold different text here. They differ only in letter case.");
        assertThat(all.get(0).referencesFollowing()).isNull();
    }

    @Test
    void aReferenceIsOnlySaidToFollowTheLabelItUses() {
        // The ids differ, and the reference does not use the id: it uses the full url, which is the same in both.
        String bundle = "{'resourceType':'Bundle','entry':[{'fullUrl':'urn:uuid:0000000a-0000-4000-8000-000000000001',"
                + "'resource':{'resourceType':'Patient','id':'%s'}},{'resource':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'%s'}}}]}";
        List<Difference> all = differences(bundle.formatted("a", "urn:uuid:0000000a-0000-4000-8000-000000000001"),
                bundle.formatted("b", "urn:uuid:0000000A-0000-4000-8000-000000000001"));
        assertThat(lines(all)).containsExactly("entry[Patient].resource.id | DIFFERENT_VALUE | LABELS",
                "entry[ServiceRequest].resource.subject.reference | DIFFERENT_VALUE | NOT_CLASSIFIED");
        assertThat(all.get(0).referencesFollowing()).isZero();
        assertThat(all.get(1).explanation()).startsWith("The two requests hold different text here. They differ only in letter case.");
    }

    @Test
    void theFullUrlOfABundleEntryFollowsTheIdOfTheResourceItHolds() {
        String tool = "{'resourceType':'Bundle','entry':[{'fullUrl':'https://ehr.example.invalid/fhir/Patient/patient-1',"
                + "'resource':{'resourceType':'Patient','id':'patient-1'}}]}";
        String yours = "{'resourceType':'Bundle','entry':[{'fullUrl':'https://ehr.example.invalid/fhir/Patient/8842',"
                + "'resource':{'resourceType':'Patient','id':'8842'}}]}";
        assertThat(differences(tool, yours)).singleElement().satisfies(label -> {
            assertThat(line(label)).isEqualTo("entry[Patient].resource.id | DIFFERENT_VALUE | LABELS");
            assertThat(label.referencesFollowing()).isEqualTo(1);
        });
    }

    @Test
    void resourcesLabelledOnlyByTheFullUrlOfTheirEntryAreOneLabelDifferenceAtTheFirstPlaceThatUsesIt() {
        String bundle = "{'resourceType':'Bundle','entry':[{'fullUrl':'urn:uuid:%1$s','resource':{'resourceType':'Patient'}},"
                + "{'resource':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'urn:uuid:%1$s'}}},"
                + "{'resource':{'resourceType':'Coverage','id':'c','beneficiary':{'reference':'urn:uuid:%1$s'}}}]}";
        List<Difference> all = differences(bundle.formatted("00000000-0000-4000-8000-000000000001"),
                bundle.formatted("00000000-0000-4000-8000-000000000002"));
        assertThat(lines(all)).containsExactly("entry[Patient].fullUrl | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).referencesFollowing()).isEqualTo(2);
        assertThat(all.get(0).explanation()).startsWith("The two requests label this Patient differently, and this is one place where each "
                + "uses its own label. 2 other references differ for the same reason and are not listed one by one.");
    }

    @Test
    void aUrnIsFollowedWhateverTheLetterCaseOfItsUuid() {
        String bundle = "{'resourceType':'Bundle','entry':[{'fullUrl':'urn:uuid:%s','resource':{'resourceType':'Patient'}},"
                + "{'resource':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'urn:uuid:%s'}}}]}";
        List<Difference> all = differences(bundle.formatted("0000000A-0000-4000-8000-000000000001", "0000000a-0000-4000-8000-000000000001"),
                bundle.formatted("0000000B-0000-4000-8000-000000000002", "0000000b-0000-4000-8000-000000000002"));
        assertThat(lines(all)).containsExactly("entry[Patient].fullUrl | DIFFERENT_VALUE | LABELS");
    }

    @Test
    void aListOfReferencesIsMatchedThroughTheResourcesEachPointsAt() {
        String bundle = "{'context':{'selections':[%s],'draftOrders':{'resourceType':'Bundle','entry':["
                + "{'resource':{'resourceType':'ServiceRequest','id':'%s'}},{'resource':{'resourceType':'ServiceRequest','id':'%s'}}]}}}";
        // Each request selects both of its own orders: nothing differs but the labels.
        List<Difference> same = differences(bundle.formatted("'ServiceRequest/a','ServiceRequest/b'", "a", "b"),
                bundle.formatted("'ServiceRequest/x','ServiceRequest/y'", "x", "y"));
        assertThat(lines(same)).containsExactly("context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS",
                "context.draftOrders.entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE | LABELS");
        assertThat(same).extracting(Difference::referencesFollowing).containsExactly(1, 1);

        // The same two selections in the other order is one difference of order.
        List<Difference> reordered = differences(bundle.formatted("'ServiceRequest/a','ServiceRequest/b'", "a", "b"),
                bundle.formatted("'ServiceRequest/y','ServiceRequest/x'", "x", "y"));
        assertThat(lines(reordered)).containsExactly("context.selections | DIFFERENT_ORDER | NOT_CLASSIFIED",
                "context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS",
                "context.draftOrders.entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE | LABELS");

        // A selection with no counterpart is listed, with the element it sits at.
        List<Difference> extra = differences(bundle.formatted("'ServiceRequest/a'", "a", "b"),
                bundle.formatted("'ServiceRequest/x','ServiceRequest/unknown'", "x", "y"));
        assertThat(lines(extra)).containsExactly("context.selections[1] | ONLY_IN_YOURS | BUILD",
                "context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS",
                "context.draftOrders.entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE | LABELS");
        assertThat(extra.get(0).yourPointer()).isEqualTo("/context/selections/1");
        assertThat(extra.get(0).yourValue().value()).isEqualTo("ServiceRequest/unknown");
        assertThat(extra.get(0).explanation()).startsWith("Your list has a reference here with no counterpart in the tool's list.");
        assertThat(extra).extracting(Difference::referencesFollowing).containsExactly(null, 1, 0);
    }

    // ---- the same text is not always the same reference ----

    private static List<String> notes(String tool, String yours) {
        return new JsonComparer(RULES, Glossary.NONE).compare(json(tool), json(yours)).notes();
    }

    @Test
    void theSameReferenceTextIsNotTheSameReferenceWhenTheOtherRequestLabelsTheResourceDifferently() {
        // The other request renamed its patient and left every reference as it was.
        String tool = request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1");
        String yours = request("8842", "Patient/patient-1", "Patient/patient-1", "patient-1");
        List<Difference> all = differences(tool, yours);
        // No reference differs in its text, so none is listed, and the id says that none follows it.
        assertThat(lines(all)).containsExactly("patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).referencesFollowing()).isZero();
        // Left at that, the renamed id would read as a label and nothing more.
        assertThat(notes(tool, yours)).containsExactly("At context.patientId and at 2 other places the two requests hold the same reference "
                + "text. In the tool's request it points at a Patient the request carries. The Patient that your request carries in its place "
                + "is labelled differently, so in your request the same text does not point at that Patient.");
    }

    @Test
    void theSameIsSaidTheOtherWayRoundWhenItIsTheToolsRequestThatLabelsTheResourceDifferently() {
        // The tool's order points past its own patient, at the id the other request gives its patient.
        String tool = request("patient-1", "Patient/8842", "Patient/patient-1", "patient-1");
        String yours = request("8842", "Patient/8842", "Patient/8842", "8842");
        List<Difference> all = differences(tool, yours);
        assertThat(lines(all)).containsExactly("patient.id | DIFFERENT_VALUE | LABELS");
        assertThat(all.get(0).referencesFollowing()).isEqualTo(2);
        assertThat(notes(tool, yours)).containsExactly("At order.subject.reference the two requests hold the same reference text. In your "
                + "request it points at a Patient the request carries. The Patient that the tool's request carries in its place is labelled "
                + "differently, so in the tool's request the same text does not point at that Patient.");
    }

    @Test
    void twoPlacesAreCountedAsOneAndOneOther() {
        String tool = request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1");
        String yours = request("8842", "Patient/patient-1", "Patient/patient-1", "8842");
        assertThat(notes(tool, yours)).singleElement().satisfies(note -> assertThat(note)
                .startsWith("At order.subject.reference and at 1 other place the two requests hold the same reference text."));
        assertThat(differences(tool, yours)).singleElement().satisfies(label -> assertThat(label.referencesFollowing()).isEqualTo(1));
    }

    @Test
    void theSameReferenceTextIsTheSameReferenceWhereNothingShowsOtherwise() {
        // Each request uses its own label throughout.
        assertThat(notes(request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1"),
                request("8842", "Patient/8842", "Patient/8842", "8842"))).isEmpty();
        // The two label the patient alike, and both point at it.
        String alike = request("patient-1", "Patient/patient-1", "Patient/patient-1", "patient-1");
        assertThat(notes(alike, alike)).isEmpty();
        // Neither request carries what the text points at, so nothing is known about it in either.
        assertThat(notes(request("patient-1", "Patient/elsewhere", "Patient/patient-1", "patient-1"),
                request("8842", "Patient/elsewhere", "Patient/8842", "8842"))).isEmpty();
        // The text points at a resource only one request carries at all: that is listed as the element only one has.
        String order = "'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'Patient/a'}}";
        assertThat(notes("{" + order + ",'patient':{'resourceType':'Patient','id':'a'}}", "{" + order + "}")).isEmpty();
    }

    @Test
    void aSelectionThatIsTheSameTextIsLookedAtTheSameWay() {
        String bundle = "{'context':{'selections':['ServiceRequest/a','ServiceRequest/b'],'draftOrders':{'resourceType':'Bundle','entry':["
                + "{'resource':{'resourceType':'ServiceRequest','id':'%s'}},{'resource':{'resourceType':'ServiceRequest','id':'b'}}]}}}";
        // The other request renamed its first order and still selects it by the old id.
        List<Difference> all = differences(bundle.formatted("a"), bundle.formatted("x"));
        assertThat(lines(all)).containsExactly("context.draftOrders.entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE | LABELS");
        assertThat(notes(bundle.formatted("a"), bundle.formatted("x"))).containsExactly("At context.selections[0] the two requests hold the same "
                + "reference text. In the tool's request it points at a ServiceRequest the request carries. The ServiceRequest that your "
                + "request carries in its place is labelled differently, so in your request the same text does not point at that ServiceRequest.");
    }

    @Test
    void anEntryLabelledByAnotherFullUrlIsLabelledDifferentlyForAReferenceWrittenAsAUrl() {
        String bundle = "{'b':{'resourceType':'Bundle','entry':[{'fullUrl':'%s','resource':{'resourceType':'Patient','id':'p'}},"
                + "{'fullUrl':'urn:uuid:00000000-0000-4000-8000-0000000000aa','resource':{'resourceType':'ServiceRequest','id':'sr',"
                + "'subject':{'reference':'urn:uuid:00000000-0000-4000-8000-000000000001'}}}]}}";
        String tool = bundle.formatted("urn:uuid:00000000-0000-4000-8000-000000000001");
        String yours = bundle.formatted("urn:uuid:00000000-0000-4000-8000-000000000002");
        // The ids agree. The entry's address does not, and the order's subject is written as the tool's address in both.
        assertThat(lines(differences(tool, yours))).containsExactly("b.entry[Patient].fullUrl | DIFFERENT_VALUE | LABELS");
        assertThat(notes(tool, yours)).containsExactly("At b.entry[ServiceRequest].resource.subject.reference the two requests hold the same "
                + "reference text. In the tool's request it points at a Patient the request carries. The Patient that your request carries in "
                + "its place is labelled differently, so in your request the same text does not point at that Patient.");
    }

    @Test
    void aResourceCarriedTwiceIsFollowedThroughEitherCopy() {
        String document = "{'a':{'resourceType':'Patient','id':'%1$s'},'b':{'resourceType':'Patient','id':'%1$s'},"
                + "'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'Patient/%1$s'}}}";
        assertThat(lines(differences(document.formatted("p1"), document.formatted("9")))).containsExactly("a.id | DIFFERENT_VALUE | LABELS",
                "b.id | DIFFERENT_VALUE | LABELS");
    }

    @Test
    void textUnderTheKeyReferenceThatIsNotAReferenceIsComparedAsText() {
        // Outside any resource the key means nothing in particular.
        assertThat(lines(differences("{'reference':'ABC-1'}", "{'reference':'ABC-2'}"))).containsExactly("reference | DIFFERENT_VALUE | NOT_CLASSIFIED");
        // Inside one, text in neither request reads as a reference, so nothing is said about what it points at.
        List<Difference> inside = differences("{'r':{'resourceType':'Basic','subject':{'reference':'see the note'}}}",
                "{'r':{'resourceType':'Basic','subject':{'reference':'see the other note'}}}");
        assertThat(lines(inside)).containsExactly("r.subject.reference | DIFFERENT_VALUE | NOT_CLASSIFIED");
        assertThat(inside.get(0).explanation()).startsWith("The two requests hold different text here.");
    }

    @Test
    void anIdIsALabelOnlyBetweenTwoMatchedResources() {
        // The other object does not say it is a resource, so nothing is known about what its id is.
        assertThat(lines(differences("{'p':{'resourceType':'Patient','id':'1'}}", "{'p':{'id':'2'}}")))
                .containsExactly("p.resourceType | ONLY_IN_TOOL | NOT_CLASSIFIED", "p.id | DIFFERENT_VALUE | NOT_CLASSIFIED");
    }

    @Test
    void aReferenceOnlyOneRequestHasIsAnElementOnlyOneRequestHas() {
        String tool = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'reference':'Patient/a'}}}";
        String yours = "{'order':{'resourceType':'ServiceRequest','id':'sr','subject':{'display':'The member'}}}";
        assertThat(lines(differences(tool, yours)).stream().map(l -> l.substring(0, l.lastIndexOf(" | "))).toList())
                .containsExactly("order.subject.reference | ONLY_IN_TOOL", "order.subject.display | ONLY_IN_YOURS");
    }

    @Test
    void aListAtAReferencePlaceThatHoldsNoStringsIsComparedLikeAnyOtherList() {
        List<Difference> all = differences("{'context':{'selections':[{'a':1}]}}", "{'context':{'selections':[{'a':2}]}}");
        assertThat(all).singleElement().satisfies(d -> assertThat(d.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE));
    }
}
