package org.p32h.interop.epa.pave.service.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import tools.jackson.databind.JsonNode;

/**
 * The comparison engine, one rule per test, each on the smallest pair of documents that shows it.
 * The first document is always the tool's and the second the tester's.
 */
class JsonComparerTest {

    /** JSON written with single quotes, so a test reads as the document it describes. */
    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text.replace('\'', '"'));
        assertThat(parsed.ok()).as("the test document is JSON: " + text).isTrue();
        return parsed.node();
    }

    private static JsonComparer.Result compare(String tool, String yours) {
        return new JsonComparer(CompareRules.none(), Glossary.NONE).compare(json(tool), json(yours));
    }

    private static List<Difference> differences(String tool, String yours) {
        return compare(tool, yours).differences();
    }

    private static Difference only(String tool, String yours) {
        List<Difference> all = differences(tool, yours);
        assertThat(all).as("exactly one difference between " + tool + " and " + yours).hasSize(1);
        return all.get(0);
    }

    private static String line(Difference difference) {
        return difference.path() + " | " + difference.kind();
    }

    private static List<String> lines(List<Difference> differences) {
        return differences.stream().map(JsonComparerTest::line).toList();
    }

    // ---- objects ----

    @Test
    void twoDocumentsThatHoldTheSameAreEqualWhateverTheOrderOfTheirKeys() {
        assertThat(differences("{'a':1,'b':{'c':[1,2],'d':null}}", "{'b':{'d':null,'c':[1,2]},'a':1}")).isEmpty();
        assertThat(compare("{'a':1}", "{'a':1}").notes()).isEmpty();
    }

    @Test
    void aKeyOnlyOneDocumentHasIsOneDifferenceForEverythingBeneathIt() {
        Difference inTool = only("{'k':1,'a':{'b':{'c':1,'d':[1,2,3]}}}", "{'k':1}");
        assertThat(inTool.path()).isEqualTo("a");
        assertThat(inTool.kind()).isEqualTo(DifferenceKind.ONLY_IN_TOOL);
        assertThat(inTool.kindLabel()).isEqualTo("Only in the tool's request");
        assertThat(inTool.toolPointer()).isEqualTo("/a");
        assertThat(inTool.yourPointer()).isNull();
        assertThat(inTool.toolValue().value()).isEqualTo("{\"b\":{\"c\":1,\"d\":[1,2,3]}}");
        assertThat(inTool.toolType()).isEqualTo("object");
        assertThat(inTool.yourValue().presence()).isEqualTo(Presence.ABSENT);
        assertThat(inTool.yourType()).isNull();
        assertThat(inTool.explanation()).startsWith("The tool's request has this element and your request does not.");

        Difference inYours = only("{'k':1}", "{'k':1,'a':{'b':{'c':1}}}");
        assertThat(inYours.kind()).isEqualTo(DifferenceKind.ONLY_IN_YOURS);
        assertThat(inYours.toolPointer()).isNull();
        assertThat(inYours.yourPointer()).isEqualTo("/a");
        assertThat(inYours.explanation()).startsWith("Your request has this element and the tool's request does not.");
    }

    @Test
    void aKeyThatHoldsPathPunctuationIsQuotedInThePathAndEscapedInThePointer() {
        Difference difference = only("{'k':1,'a.b/c':{'x':1}}", "{'k':1,'a.b/c':{'x':2}}");
        assertThat(difference.path()).isEqualTo("\"a.b/c\".x");
        assertThat(difference.toolPointer()).isEqualTo("/a.b~1c/x");
    }

    // ---- absent, null and empty are three different things ----

    @Test
    void anExplicitNullAgainstNoKeyIsADifferenceAndIsWordedAsANull() {
        Difference difference = only("{'k':1,'x':null}", "{'k':1}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.ONLY_IN_TOOL);
        assertThat(difference.toolValue().presence()).isEqualTo(Presence.NULL);
        assertThat(difference.toolType()).isEqualTo("null");
        assertThat(difference.yourValue().presence()).isEqualTo(Presence.ABSENT);
        assertThat(difference.explanation()).startsWith("The tool's request has this element, written as an explicit null, and your request "
                + "does not have it at all.");
    }

    @Test
    void anEmptyTextAgainstNoKeyIsADifferenceAndIsWordedAsEmpty() {
        Difference difference = only("{'k':1}", "{'k':1,'x':''}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.ONLY_IN_YOURS);
        assertThat(difference.yourValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(difference.yourType()).isEqualTo("string");
        assertThat(difference.explanation()).startsWith("Your request has this element, as an empty text, and the tool's request does not "
                + "have it at all.");
        assertThat(only("{'k':1,'x':[]}", "{'k':1}").explanation()).contains("as an empty list");
        assertThat(only("{'k':1,'x':{}}", "{'k':1}").explanation()).contains("as an empty object");
    }

    @Test
    void anEmptyListAgainstAnExplicitNullIsADifferenceThatNamesBoth() {
        Difference difference = only("{'x':[]}", "{'x':null}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
        assertThat(difference.toolValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(difference.toolType()).isEqualTo("array");
        assertThat(difference.yourValue().presence()).isEqualTo(Presence.NULL);
        assertThat(difference.yourType()).isEqualTo("null");
        assertThat(difference.explanation()).startsWith("The tool's request writes this as a list and your request writes it as an explicit null.");
    }

    @Test
    void anEmptyListAndAnEmptyObjectAreToldApartByTheirType() {
        Difference difference = only("{'x':[]}", "{'x':{}}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
        assertThat(difference.toolValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(difference.yourValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(difference.toolType()).isEqualTo("array");
        assertThat(difference.yourType()).isEqualTo("object");
    }

    @Test
    void anEmptyTextListOrObjectAgainstAFullOneIsOneDifferenceWordedAsEmpty() {
        Difference text = only("{'x':''}", "{'x':'a'}");
        assertThat(text.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(text.toolValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(text.explanation()).startsWith("The tool's request has an empty text here and your request has a value.");

        Difference list = only("{'x':[{'a':1},{'a':2}]}", "{'x':[]}");
        assertThat(list.path()).isEqualTo("x");
        assertThat(list.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(list.yourValue().presence()).isEqualTo(Presence.EMPTY);
        assertThat(list.explanation()).startsWith("Your request has an empty list here and the tool's request has a list with 2 elements.");

        Difference object = only("{'x':{}}", "{'x':{'a':1}}");
        assertThat(object.explanation()).startsWith("The tool's request has an empty object here and your request has an object with 1 member.");
    }

    @Test
    void twoNullsAndTwoEmptiesAreEqual() {
        assertThat(differences("{'a':null,'b':[],'c':{},'d':''}", "{'a':null,'b':[],'c':{},'d':''}")).isEmpty();
    }

    // ---- scalars ----

    @Test
    void aStringAgainstANumberIsADifferentTypeEvenWhenTheTwoReadTheSame() {
        Difference difference = only("{'x':'1'}", "{'x':1}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
        assertThat(difference.kindLabel()).isEqualTo("A different JSON type");
        assertThat(difference.toolType()).isEqualTo("string");
        assertThat(difference.yourType()).isEqualTo("number");
        assertThat(difference.toolValue().value()).isEqualTo("1");
        assertThat(difference.yourValue().value()).isEqualTo("1");
        assertThat(difference.explanation()).startsWith("The tool's request writes this as text (a JSON string) and your request writes it as a "
                + "number. They read the same, but the two are different JSON types.");
        // When they do not read the same there is nothing more to say than the two types.
        assertThat(only("{'x':'a'}", "{'x':1}").explanation()).doesNotContain("read the same");
        assertThat(only("{'x':true}", "{'x':'true'}").kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
    }

    @Test
    void anObjectAgainstAListIsOneDifferenceOfType() {
        Difference difference = only("{'x':{'a':{'b':1}}}", "{'x':[{'a':{'b':1}}]}");
        assertThat(difference.path()).isEqualTo("x");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
        assertThat(difference.explanation()).startsWith("The tool's request writes this as an object and your request writes it as a list.");
    }

    @Test
    void trueAgainstFalseIsADifferentValue() {
        Difference difference = only("{'x':true}", "{'x':false}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(difference.explanation()).startsWith("The tool's request says true here and your request says false.");
    }

    @Test
    void theSameNumberWrittenWithAnotherPrecisionIsNeitherEqualNorADifferentValue() {
        for (String[] pair : new String[][] {{"1", "1.0"}, {"1.0", "1.00"}, {"100", "1e2"}, {"0", "0.0"}}) {
            Difference difference = only("{'x':" + pair[0] + "}", "{'x':" + pair[1] + "}");
            assertThat(difference.kind()).as(pair[0] + " against " + pair[1]).isEqualTo(DifferenceKind.WRITTEN_DIFFERENTLY);
            assertThat(difference.kindLabel()).isEqualTo("The same value written differently");
            assertThat(difference.explanation()).startsWith("Both are the same number, written differently. FHIR treats the written precision "
                    + "of a decimal as meaningful, so this is listed and not treated as equal.");
        }
        Difference kept = only("{'x':1.50}", "{'x':1.5}");
        assertThat(kept.toolValue().value()).isEqualTo("1.50");
        assertThat(kept.yourValue().value()).isEqualTo("1.5");
        assertThat(differences("{'x':1.50}", "{'x':1.50}")).isEmpty();
        assertThat(only("{'x':1}", "{'x':2}").kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(only("{'x':1.5}", "{'x':1.6}").explanation()).startsWith("The two requests hold different numbers here.");
    }

    @Test
    void aNumberWithAVeryLargeExponentIsComparedWithoutBeingWrittenOut() {
        assertThat(lines(differences("{'x':[1e999999999]}", "{'x':[1]}"))).containsExactly("x[0] | ONLY_IN_TOOL", "x[0] | ONLY_IN_YOURS");
        assertThat(only("{'x':1e999999999}", "{'x':1}").kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(only("{'x':1e999999999}", "{'x':10e999999998}").kind()).isEqualTo(DifferenceKind.WRITTEN_DIFFERENTLY);
    }

    @Test
    void stringsAreComparedExactlyAndTheExplanationNeverQuotesThem() {
        Difference difference = only("{'x':'Placeholder-Surname'}", "{'x':'Sample-Surname'}");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(difference.explanation()).isEqualTo("The two requests hold different text here. This tool has no description of this "
                + "element, so the difference is not classified.");
        assertThat(difference.toolValue().value()).isEqualTo("Placeholder-Surname");
        assertThat(difference.yourValue().value()).isEqualTo("Sample-Surname");
    }

    @Test
    void stringsThatDifferOnlyInLetterCaseAreSaidToDifferOnlyInThat() {
        assertThat(only("{'x':'Order-Sign'}", "{'x':'order-sign'}").explanation())
                .startsWith("The two requests hold different text here. They differ only in letter case.");
    }

    @Test
    void stringsThatDifferOnlyInSpacesAreSaidToDifferOnlyInThat() {
        assertThat(only("{'x':'abc '}", "{'x':'abc'}").explanation())
                .startsWith("The two requests hold different text here. They differ only in spaces at the start or the end.");
        assertThat(only("{'x':'a  b'}", "{'x':'a b'}").explanation())
                .startsWith("The two requests hold different text here. They differ only in repeated spaces.");
        // A member ID written with spaces against its no-space form.
        assertThat(only("{'x':'S12345678   01'}", "{'x':'S1234567801'}").explanation())
                .startsWith("The two requests hold different text here. They differ only in spaces: one has spaces where the other has none.");
        // Case and spaces together are two differences, so neither sentence is used.
        assertThat(only("{'x':'A b'}", "{'x':'ab'}").explanation()).doesNotContain("only in");
    }

    @Test
    void aDateAgainstADateAndTimeOnTheSameDateIsSaidToBeThat() {
        assertThat(only("{'x':'2026-03-20'}", "{'x':'2026-03-20T10:30:00-05:00'}").explanation())
                .startsWith("The two requests hold different text here. One is a date and the other is a date and time on that same date.");
        assertThat(only("{'x':'2026-03-20T10:30:00Z'}", "{'x':'2026-03-20'}").explanation()).contains("a date and time on that same date");
        assertThat(only("{'x':'2026-03-20'}", "{'x':'2026-03-21T10:30:00Z'}").explanation()).doesNotContain("same date");
    }

    // ---- lists ----

    private static final String BUNDLE = "{'resourceType':'Bundle','entry':[%s]}";

    private static String entry(String type, String id, String rest) {
        return "{'resource':{'resourceType':'" + type + "'" + (id == null ? "" : ",'id':'" + id + "'") + (rest.isEmpty() ? "" : "," + rest) + "}}";
    }

    @Test
    void bundleEntriesAreMatchedByResourceTypeAndIdWhereBothSidesCarryThem() {
        String tool = BUNDLE.formatted(entry("ServiceRequest", "a", "'status':'draft'") + "," + entry("ServiceRequest", "b", "'status':'draft'"));
        String yours = BUNDLE.formatted(entry("ServiceRequest", "b", "'status':'active'") + "," + entry("ServiceRequest", "a", "'status':'draft'"));
        Difference difference = only(tool, yours);
        // The tool's second request is the other document's first: they share an id.
        assertThat(difference.path()).isEqualTo("entry[ServiceRequest #2].resource.status");
        assertThat(difference.toolPointer()).isEqualTo("/entry/1/resource/status");
        assertThat(difference.yourPointer()).isEqualTo("/entry/0/resource/status");
    }

    @Test
    void oneEntryOfATypeLeftOnEachSideIsMatchedWhateverItsId() {
        String tool = BUNDLE.formatted(entry("ServiceRequest", "service-request-1", "'status':'draft'"));
        String yours = BUNDLE.formatted(entry("Patient", "p", "") + "," + entry("ServiceRequest", "sr-77", "'status':'active'"));
        assertThat(lines(differences(tool, yours))).containsExactly(
                "entry[ServiceRequest].resource.id | DIFFERENT_VALUE",
                "entry[ServiceRequest].resource.status | DIFFERENT_VALUE",
                "entry[Patient] | ONLY_IN_YOURS");
        Difference patient = differences(tool, yours).get(2);
        assertThat(patient.yourPointer()).isEqualTo("/entry/0");
        assertThat(patient.explanation()).startsWith("Your request has this Patient in the Bundle and the tool's request has no Patient left "
                + "to compare it with.");
    }

    @Test
    void severalEntriesOfATypeAreMatchedByPositionAmongThatTypeAndNumberedInThePath() {
        String tool = BUNDLE.formatted(entry("ServiceRequest", "a", "'priority':'routine'") + "," + entry("Patient", "p", "") + ","
                + entry("ServiceRequest", "b", "'priority':'routine'"));
        String yours = BUNDLE.formatted(entry("ServiceRequest", "x", "'priority':'routine'") + "," + entry("ServiceRequest", "y", "'priority':'stat'")
                + "," + entry("ServiceRequest", "z", "") + "," + entry("Patient", "p", ""));
        assertThat(lines(differences(tool, yours))).containsExactly(
                "entry[ServiceRequest #1].resource.id | DIFFERENT_VALUE",
                "entry[ServiceRequest #2].resource.id | DIFFERENT_VALUE",
                "entry[ServiceRequest #2].resource.priority | DIFFERENT_VALUE",
                "entry[ServiceRequest #3] | ONLY_IN_YOURS");
        assertThat(differences(tool, yours).get(1).toolPointer()).isEqualTo("/entry/2/resource/id");
        assertThat(differences(tool, yours).get(1).yourPointer()).isEqualTo("/entry/1/resource/id");
    }

    @Test
    void anEntryWithNoResourceIsMatchedWithAnotherSuchEntry() {
        String tool = BUNDLE.formatted("{'fullUrl':'urn:uuid:00000000-0000-4000-8000-000000000001','note':'a'}");
        String yours = BUNDLE.formatted(entry("Patient", "p", "") + ",{'fullUrl':'urn:uuid:00000000-0000-4000-8000-000000000001','note':'b'}");
        assertThat(lines(differences(tool, yours))).containsExactly("entry[no resource].note | DIFFERENT_VALUE", "entry[Patient] | ONLY_IN_YOURS");
    }

    @Test
    void aListCalledEntryOutsideABundleIsMatchedByPosition() {
        String tool = "{'entry':[" + entry("ServiceRequest", "a", "") + "," + entry("Patient", "p", "") + "]}";
        String yours = "{'entry':[" + entry("Patient", "p", "") + "," + entry("ServiceRequest", "a", "") + "]}";
        assertThat(lines(differences(tool, yours))).containsExactly("entry[0].resource | DIFFERENT_VALUE", "entry[1].resource | DIFFERENT_VALUE");
    }

    @Test
    void objectsThatAllCarryAUrlAreMatchedByUrlWhateverTheirOrder() {
        String tool = "{'extension':[{'url':'http://example.org/a','valueString':'one'},{'url':'http://example.org/b','valueString':'two'}]}";
        String yours = "{'extension':[{'url':'http://example.org/b','valueString':'TWO'},{'url':'http://example.org/c','valueCode':'x'},"
                + "{'url':'http://example.org/a','valueString':'one'}]}";
        List<Difference> all = differences(tool, yours);
        assertThat(lines(all)).containsExactly("extension[http://example.org/b].valueString | DIFFERENT_VALUE",
                "extension[http://example.org/c] | ONLY_IN_YOURS");
        assertThat(all.get(0).toolPointer()).isEqualTo("/extension/1/valueString");
        assertThat(all.get(0).yourPointer()).isEqualTo("/extension/0/valueString");
        assertThat(all.get(1).explanation()).startsWith("This list is matched by url. Your request has an element with this url that the "
                + "tool's request has no counterpart for.");
    }

    @Test
    void equalUrlsAreMatchedByPositionAmongThemselves() {
        String tool = "{'extension':[{'url':'u','valueString':'a'},{'url':'u','valueString':'b'}]}";
        String yours = "{'extension':[{'url':'u','valueString':'a'},{'url':'u','valueString':'c'},{'url':'u','valueString':'d'}]}";
        assertThat(lines(differences(tool, yours))).containsExactly("extension[u #2].valueString | DIFFERENT_VALUE", "extension[u #3] | ONLY_IN_YOURS");
    }

    @Test
    void objectsThatCarryASystemAreMatchedBySystemWhateverTheirOrder() {
        String tool = "{'coding':[{'system':'http://example.org/one','code':'a'},{'system':'http://example.org/two','code':'b'}]}";
        String yours = "{'coding':[{'system':'http://example.org/two','code':'c'},{'system':'http://example.org/one','code':'a'}]}";
        Difference difference = only(tool, yours);
        assertThat(difference.path()).isEqualTo("coding[http://example.org/two].code");
        assertThat(difference.toolPointer()).isEqualTo("/coding/1/code");
        assertThat(difference.yourPointer()).isEqualTo("/coding/0/code");
    }

    @Test
    void equalSystemsAreMatchedByPositionAmongThemselves() {
        String tool = "{'coding':[{'system':'s','code':'a'},{'system':'s','code':'b'}]}";
        String yours = "{'coding':[{'system':'s','code':'a'},{'system':'s','code':'c'}]}";
        assertThat(only(tool, yours).path()).isEqualTo("coding[s #2].code");
    }

    @Test
    void elementsWithNoSystemAreMatchedAmongThemselvesByPosition() {
        String tool = "{'identifier':[{'system':'s','value':'1'},{'value':'x'}]}";
        String yours = "{'identifier':[{'value':'y'},{'system':'s','value':'1'}]}";
        Difference difference = only(tool, yours);
        assertThat(difference.path()).isEqualTo("identifier[no system].value");
        assertThat(difference.toolPointer()).isEqualTo("/identifier/1/value");
        assertThat(difference.yourPointer()).isEqualTo("/identifier/0/value");
    }

    @Test
    void anElementWithNoSystemIsNeverMatchedWithOneThatHasASystem() {
        List<Difference> all = differences("{'identifier':[{'value':'S123456789'}]}", "{'identifier':[{'system':'http://example.org/ids','value':'S123456789'}]}");
        assertThat(lines(all)).containsExactly("identifier[no system] | ONLY_IN_TOOL", "identifier[http://example.org/ids] | ONLY_IN_YOURS");
        assertThat(all.get(0).explanation()).startsWith("This list is matched by system. The tool's request has an element with no system "
                + "that your request has no counterpart for. The other request has an unmatched element here too: the two were not "
                + "compared with each other, because they do not share a system.");
        assertThat(all.get(1).explanation()).startsWith("This list is matched by system. Your request has an element with this system that "
                + "the tool's request has no counterpart for. The other request has an unmatched element here too");
    }

    @Test
    void scalarListsAreEqualWhenTheyAreTheSameList() {
        assertThat(differences("{'given':['Given','Middle']}", "{'given':['Given','Middle']}")).isEmpty();
    }

    @Test
    void theSameValuesInAnotherOrderIsOneDifferenceOfOrder() {
        Difference difference = only("{'given':['a','b','c']}", "{'given':['c','a','b']}");
        assertThat(difference.path()).isEqualTo("given");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_ORDER);
        assertThat(difference.kindLabel()).isEqualTo("A different order");
        assertThat(difference.toolValue().value()).isEqualTo("[\"a\",\"b\",\"c\"]");
        assertThat(difference.yourValue().value()).isEqualTo("[\"c\",\"a\",\"b\"]");
        assertThat(difference.explanation()).startsWith("Both lists hold the same values, in a different order.");
    }

    @Test
    void scalarListsThatHoldDifferentValuesReportWhatEachHasAndTheOtherLacks() {
        List<Difference> all = differences("{'given':['a','b','b']}", "{'given':['c','b','a',1]}");
        assertThat(lines(all)).containsExactly("given[2] | ONLY_IN_TOOL", "given[0] | ONLY_IN_YOURS", "given[3] | ONLY_IN_YOURS");
        assertThat(all.get(0).toolPointer()).isEqualTo("/given/2");
        assertThat(all.get(0).yourPointer()).isNull();
        assertThat(all.get(0).explanation()).startsWith("This value is in the tool's list and not in yours.");
        assertThat(all.get(1).yourPointer()).isEqualTo("/given/0");
        assertThat(all.get(1).explanation()).startsWith("This value is in your list and not in the tool's.");
    }

    @Test
    void aTextAndANumberInAListAreNotTheSameValue() {
        assertThat(lines(differences("{'x':['1']}", "{'x':[1]}"))).containsExactly("x[0] | ONLY_IN_TOOL", "x[0] | ONLY_IN_YOURS");
    }

    @Test
    void theSameNumberWrittenDifferentlyInsideAListIsReportedAsThat() {
        Difference difference = only("{'x':[2,1]}", "{'x':[2,1.0]}");
        assertThat(difference.path()).isEqualTo("x[1]");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.WRITTEN_DIFFERENTLY);
    }

    @Test
    void anyOtherListIsMatchedByPosition() {
        List<Difference> all = differences("{'name':[{'family':'A'},{'family':'B'}]}", "{'name':[{'family':'A'},{'family':'C'},{'family':'D'}]}");
        assertThat(lines(all)).containsExactly("name[1].family | DIFFERENT_VALUE", "name[2] | ONLY_IN_YOURS");
        assertThat(all.get(1).explanation()).startsWith("Your request has more elements in this list: it has one at this position and the "
                + "tool's request does not.");
        // A list that mixes scalars and objects is matched by position too.
        assertThat(lines(differences("{'x':[1,{'a':1}]}", "{'x':[{'a':1},1]}"))).containsExactly("x[0] | DIFFERENT_TYPE", "x[1] | DIFFERENT_TYPE");
    }

    // ---- objects whose keys are free ----

    private static final CompareRules FREE_KEYS = new CompareRules(Set.of("prefetch"), Map.of());
    private static final String FREE_TOOL = "{'prefetch':{'patient':{'resourceType':'Patient','id':'patient-1','gender':'male'},"
            + "'coverage':{'resourceType':'Coverage','id':'c1','status':'active'}}}";
    private static final String FREE_YOURS = "{'prefetch':{'extra':{'resourceType':'Encounter','id':'e1'},"
            + "'member':{'resourceType':'Patient','id':'8842','gender':'female'},'coverage':{'resourceType':'Coverage','id':'c1','status':'draft'}}}";

    @Test
    void aFreeKeyedObjectMatchesByKeyFirstAndThenByResourceTypeUnderADifferentKey() {
        List<Difference> all = new JsonComparer(FREE_KEYS, Glossary.NONE).compare(json(FREE_TOOL), json(FREE_YOURS)).differences();
        assertThat(lines(all)).containsExactly(
                "prefetch[Patient] | DIFFERENT_KEY",
                "prefetch[Patient].id | DIFFERENT_VALUE",
                "prefetch[Patient].gender | DIFFERENT_VALUE",
                "prefetch.coverage.status | DIFFERENT_VALUE",
                "prefetch.extra | ONLY_IN_YOURS");
        Difference key = all.get(0);
        assertThat(key.kindLabel()).isEqualTo("A different key for the same content");
        assertThat(key.toolValue().value()).isEqualTo("patient");
        assertThat(key.yourValue().value()).isEqualTo("member");
        assertThat(key.toolPointer()).isEqualTo("/prefetch/patient");
        assertThat(key.yourPointer()).isEqualTo("/prefetch/member");
        assertThat(key.explanation()).startsWith("The tool's request holds this Patient under the key `patient` and your request holds it "
                + "under `member`.");
        assertThat(all.get(2).toolPointer()).isEqualTo("/prefetch/patient/gender");
        assertThat(all.get(2).yourPointer()).isEqualTo("/prefetch/member/gender");
        assertThat(all.get(4).explanation()).startsWith("Your request has the key `extra` here and the tool's request does not. It holds an "
                + "Encounter resource.");
    }

    @Test
    void twoMembersOfOneTypeLeftOnASideAreNotMatchedBecauseThatWouldBeAGuess() {
        String yours = "{'prefetch':{'one':{'resourceType':'Patient','id':'1'},'two':{'resourceType':'Patient','id':'2'},"
                + "'coverage':{'resourceType':'Coverage','id':'c1','status':'active'}}}";
        List<Difference> all = new JsonComparer(FREE_KEYS, Glossary.NONE).compare(json(FREE_TOOL), json(yours)).differences();
        assertThat(lines(all)).containsExactly("prefetch.patient | ONLY_IN_TOOL", "prefetch.one | ONLY_IN_YOURS", "prefetch.two | ONLY_IN_YOURS");
    }

    @Test
    void anObjectTheRulesDoNotNameIsMatchedByKeyAlone() {
        assertThat(lines(differences(FREE_TOOL, FREE_YOURS))).containsExactly("prefetch.patient | ONLY_IN_TOOL",
                "prefetch.coverage.status | DIFFERENT_VALUE", "prefetch.extra | ONLY_IN_YOURS", "prefetch.member | ONLY_IN_YOURS");
    }

    // ---- resources of different types ----

    @Test
    void twoResourcesOfDifferentTypesAreOneDifferenceAndAreNotComparedBeneath() {
        Difference difference = only("{'x':{'resourceType':'Coverage','id':'c1','status':'active'}}",
                "{'x':{'resourceType':'Organization','id':'c1','name':'Placeholder Payer'}}");
        assertThat(difference.path()).isEqualTo("x");
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(difference.toolValue().value()).startsWith("{\"resourceType\":\"Coverage\"");
        assertThat(difference.yourValue().value()).startsWith("{\"resourceType\":\"Organization\"");
        assertThat(difference.explanation()).startsWith("The tool's request holds a Coverage resource here and your request holds an "
                + "Organization resource. Resources of different types are not compared element by element, so each is given whole.");
    }

    private static final String BARE = "{'x':{'resourceType':'Coverage','id':'coverage-1','status':'active'},"
            + "'order':{'resourceType':'ServiceRequest','id':'sr','insurance':[{'reference':'Coverage/coverage-1'}]}}";
    private static final String WRAPPED = "{'x':{'resourceType':'Bundle','type':'searchset','total':1,'entry':[%s"
            + "{'fullUrl':'https://ehr.example.invalid/fhir/Coverage/cov-9','resource':{'resourceType':'Coverage','id':'cov-9','status':'draft'}}]},"
            + "'order':{'resourceType':'ServiceRequest','id':'sr','insurance':[{'reference':'Coverage/cov-9'}]}}";

    @Test
    void aResourceAgainstABundleThatHoldsOneOfItsTypeIsOneDifferenceAndTheTwoResourcesAreThenCompared() {
        List<Difference> all = differences(BARE, WRAPPED.formatted(""));
        assertThat(lines(all)).containsExactly("x | DIFFERENT_VALUE", "x.id | DIFFERENT_VALUE", "x.status | DIFFERENT_VALUE");
        Difference wrapping = all.get(0);
        assertThat(wrapping.explanation()).startsWith("The tool's request holds a Coverage resource here and your request holds a Bundle with "
                + "one Coverage in it. The two Coverage resources are compared with each other in the differences that follow, and the rest "
                + "of the Bundle is given whole here.");
        // The whole Bundle is the value, so its type, its total and the full url of its entry are not passed over.
        assertThat(wrapping.yourValue().value()).contains("searchset", "total", "fullUrl");
        assertThat(wrapping.toolPointer()).isEqualTo("/x");
        assertThat(wrapping.yourPointer()).isEqualTo("/x");
        // What is compared beneath is pointed at where it really sits in each document.
        assertThat(all.get(2).toolPointer()).isEqualTo("/x/status");
        assertThat(all.get(2).yourPointer()).isEqualTo("/x/entry/0/resource/status");
        // The two resources are a matched pair, so the reference that follows their ids is counted and not listed.
        assertThat(all.get(1).group()).isEqualTo(DifferenceGroup.LABELS);
        assertThat(all.get(1).referencesFollowing()).isEqualTo(1);
    }

    @Test
    void theSameHoldsWhenItIsTheToolsDocumentThatWrapsTheResource() {
        List<Difference> all = differences(WRAPPED.formatted(""), BARE);
        assertThat(lines(all)).containsExactly("x | DIFFERENT_VALUE", "x.id | DIFFERENT_VALUE", "x.status | DIFFERENT_VALUE");
        assertThat(all.get(0).explanation()).startsWith("Your request holds a Coverage resource here and the tool's request holds a Bundle with "
                + "one Coverage in it.");
        assertThat(all.get(2).toolPointer()).isEqualTo("/x/entry/0/resource/status");
        assertThat(all.get(2).yourPointer()).isEqualTo("/x/status");
    }

    @Test
    void aBundleThatHoldsNoneOrSeveralOfTheTypeIsNotOpenedBecauseWhichOneIsMeantWouldBeAGuess() {
        String second = "{'resource':{'resourceType':'Coverage','id':'cov-8','status':'active'}},";
        assertThat(lines(differences(BARE, WRAPPED.formatted(second)))).containsExactly("x | DIFFERENT_VALUE",
                "order.insurance[0].reference | DIFFERENT_VALUE");
        String none = "{'x':{'resourceType':'Bundle','entry':[{'resource':{'resourceType':'Patient','id':'p'}}]},"
                + "'order':{'resourceType':'ServiceRequest','id':'sr','insurance':[{'reference':'Coverage/coverage-1'}]}}";
        List<Difference> all = differences(BARE, none);
        assertThat(lines(all)).containsExactly("x | DIFFERENT_VALUE");
        assertThat(all.get(0).explanation()).startsWith("The tool's request holds a Coverage resource here and your request holds a Bundle "
                + "resource. Resources of different types are not compared element by element");
    }

    // ---- the depth limit ----

    /** A chain of objects with a scalar at the given level, the top of the document being level one. */
    private static String nested(int leafLevel, String leaf) {
        return "{'a':".repeat(leafLevel - 1) + leaf + "}".repeat(leafLevel - 1);
    }

    @Test
    void aDifferenceAtTheDeepestLevelIsStillFound() {
        JsonComparer.Result result = compare(nested(JsonComparer.MAX_DEPTH, "1"), nested(JsonComparer.MAX_DEPTH, "2"));
        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().get(0).kind()).isEqualTo(DifferenceKind.DIFFERENT_VALUE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void nothingBelowTheDeepestLevelIsComparedAndTheResultSaysSo() {
        JsonComparer.Result result = compare("{'s':1,'deep':" + nested(JsonComparer.MAX_DEPTH, "1") + "}",
                "{'s':2,'deep':" + nested(JsonComparer.MAX_DEPTH, "2") + "}");
        // The shallow difference is reported. The one a level too deep is not, and is not passed over in silence.
        assertThat(lines(result.differences())).containsExactly("s | DIFFERENT_VALUE");
        assertThat(result.notes()).singleElement().satisfies(note -> assertThat(note)
                .startsWith("The comparison stopped at 64 levels of nesting, at deep.a.a.")
                .endsWith("Nothing deeper was compared, so a difference below that level is not listed."));
    }

    @Test
    void aDocumentNestedFarBeyondTheLimitIsComparedWithoutFailing() {
        String deep = "[".repeat(400) + "1" + "]".repeat(400);
        JsonComparer.Result result = compare("{'x':" + deep + "}", "{'x':" + deep.replace('1', '2') + "}");
        assertThat(result.differences()).isEmpty();
        assertThat(result.notes()).hasSize(1);
    }

    // ---- order ----

    @Test
    void differencesComeInTheOrderOfTheToolsDocumentAndWhatOnlyTheOtherHasFollowsItsNeighbours() {
        List<Difference> all = differences("{'a':1,'b':{'x':1,'w':1},'c':[{'k':1}],'d':['p']}",
                "{'z':0,'d':['q'],'c':[{'k':2},{'k':3}],'b':{'y':3,'x':2,'w':1},'a':9}");
        assertThat(lines(all)).containsExactly("a | DIFFERENT_VALUE", "b.x | DIFFERENT_VALUE", "b.y | ONLY_IN_YOURS", "c[0].k | DIFFERENT_VALUE",
                "c[1] | ONLY_IN_YOURS", "d[0] | ONLY_IN_TOOL", "d[0] | ONLY_IN_YOURS", "z | ONLY_IN_YOURS");
    }

    // ---- what is shown ----

    @Test
    void aLongValueIsCutAtTwoThousandCharactersAndSaysSo() {
        String longText = "x".repeat(3000);
        Difference text = only("{'k':1,'x':'" + longText + "'}", "{'k':1}");
        assertThat(text.toolValue().value()).startsWith("x".repeat(2000) + " ... (cut here: the first 2,000 of 3,000 characters are shown)");
        assertThat(text.toolValue().value()).hasSize(2000 + " ... (cut here: the first 2,000 of 3,000 characters are shown)".length());

        Difference container = only("{'k':1,'x':['" + longText + "']}", "{'k':1}");
        assertThat(container.toolValue().value()).startsWith("[\"xxx").endsWith(" ... (cut here: the first 2,000 of 3,004 characters are shown)");
        assertThat(only("{'k':1,'x':'" + "y".repeat(2000) + "'}", "{'k':1}").toolValue().value()).isEqualTo("y".repeat(2000));
    }

    @Test
    void aDifferenceAtTheTopOfTheDocumentHasANameAndAnEmptyPointer() {
        Difference difference = only("{'a':1}", "[1]");
        assertThat(difference.path()).isEqualTo("(the whole request)");
        assertThat(difference.toolPointer()).isEmpty();
        assertThat(difference.yourPointer()).isEmpty();
        assertThat(difference.kind()).isEqualTo(DifferenceKind.DIFFERENT_TYPE);
    }

    // ---- the glossary decides what an element is and which group a difference belongs to ----

    private static final Glossary TEST_GLOSSARY = new Glossary() {
        @Override
        public Entry describe(ElementPath element) {
            String key = element.fhir() != null ? element.fhir() : element.document();
            return switch (key) {
                case "hookInstance" -> new Entry("An id for this one call.", ElementRole.EVERY_CALL);
                case "Patient.birthDate" -> new Entry("The date of birth.", ElementRole.CASE);
                case "Patient.identifier.system" -> new Entry("The kind of identifier, as a url.", ElementRole.BUILD);
                case "Patient.identifier" -> new Entry("One identifier.", ElementRole.BUILD);
                case "Patient.id" -> new Entry("The label of the resource.", ElementRole.LABEL);
                case "Patient.name.given" -> new Entry("The given names.", ElementRole.CASE);
                case "Patient.meta.lastUpdated" -> new Entry("When the record last changed.", null);
                case "Patient" -> new Entry("A person.", ElementRole.BUILD);
                default -> null;
            };
        }

        @Override
        public String remark(ElementPath element, DifferenceKind kind) {
            return "hookInstance".equals(element.document()) && kind == DifferenceKind.DIFFERENT_VALUE ? "A new one is made for every call." : null;
        }
    };

    private static List<Difference> withGlossary(String tool, String yours) {
        return new JsonComparer(CompareRules.none(), TEST_GLOSSARY).compare(json(tool), json(yours)).differences();
    }

    @Test
    void aDifferenceInAValueTakesTheGroupOfItsElementAndTheWordsOfTheGlossary() {
        List<Difference> all = withGlossary("{'hookInstance':'a','p':{'resourceType':'Patient','birthDate':'1970-01-31',"
                        + "'identifier':[{'system':'http://example.org/a','value':'1'}],'name':[{'given':['Given']}]}}",
                "{'hookInstance':'b','p':{'resourceType':'Patient','birthDate':'1971-02-01',"
                        + "'identifier':[{'system':'http://example.org/b','value':'1'}],'name':[{'given':['Given','Middle']}]}}");
        assertThat(all).extracting(Difference::path, Difference::kind, Difference::group).containsExactly(
                org.assertj.core.groups.Tuple.tuple("hookInstance", DifferenceKind.DIFFERENT_VALUE, DifferenceGroup.EVERY_CALL),
                org.assertj.core.groups.Tuple.tuple("p.birthDate", DifferenceKind.DIFFERENT_VALUE, DifferenceGroup.CASE),
                // An element one document has and the other does not is a difference in how the request is built.
                org.assertj.core.groups.Tuple.tuple("p.identifier[http://example.org/a]", DifferenceKind.ONLY_IN_TOOL, DifferenceGroup.BUILD),
                org.assertj.core.groups.Tuple.tuple("p.identifier[http://example.org/b]", DifferenceKind.ONLY_IN_YOURS, DifferenceGroup.BUILD),
                // A value one list of scalars has and the other lacks is a difference in the value of the list.
                org.assertj.core.groups.Tuple.tuple("p.name[0].given[1]", DifferenceKind.ONLY_IN_YOURS, DifferenceGroup.CASE));
        assertThat(all.get(0).what()).isEqualTo("An id for this one call.");
        assertThat(all.get(0).explanation()).isEqualTo("The two requests hold different text here. A new one is made for every call.");
        assertThat(all.get(1).what()).isEqualTo("The date of birth.");
        assertThat(all.get(2).what()).isEqualTo("One identifier.");
    }

    @Test
    void anElementOnlyOneDocumentHasIsADifferenceInHowTheRequestIsBuiltWhateverItsRole() {
        List<Difference> all = withGlossary("{'k':1,'hookInstance':'a','p':{'resourceType':'Patient','birthDate':'1970-01-31'}}",
                "{'k':1,'p':{'resourceType':'Patient'},'q':{'resourceType':'Patient','id':'q'}}");
        assertThat(all).extracting(Difference::path, Difference::group).containsExactly(
                org.assertj.core.groups.Tuple.tuple("hookInstance", DifferenceGroup.BUILD),
                org.assertj.core.groups.Tuple.tuple("p.birthDate", DifferenceGroup.BUILD),
                org.assertj.core.groups.Tuple.tuple("q", DifferenceGroup.BUILD));
        // A whole resource is described as the resource it is.
        assertThat(all.get(2).what()).isEqualTo("A person.");
        assertThat(all.get(2).explanation()).isEqualTo("Your request has this element and the tool's request does not. It holds a Patient resource.");
    }

    @Test
    void anElementTheGlossaryDoesNotKnowIsNotClassifiedAndTheExplanationSaysSo() {
        Difference unknown = withGlossary("{'vendorField':1}", "{'vendorField':2}").get(0);
        assertThat(unknown.group()).isEqualTo(DifferenceGroup.NOT_CLASSIFIED);
        assertThat(unknown.what()).isNull();
        assertThat(unknown.explanation()).isEqualTo("The two requests hold different numbers here. This tool has no description of this "
                + "element, so the difference is not classified.");
        // Not even a missing element is put in a group when nothing is known about it.
        assertThat(withGlossary("{'k':1,'vendorField':1}", "{'k':1}").get(0).group()).isEqualTo(DifferenceGroup.NOT_CLASSIFIED);
    }

    @Test
    void anElementTheGlossaryKnowsWithoutARoleIsDescribedAndItsValueDifferenceIsNotClassified() {
        Difference difference = withGlossary("{'p':{'resourceType':'Patient','meta':{'lastUpdated':'2026-01-01T00:00:00Z'}}}",
                "{'p':{'resourceType':'Patient','meta':{'lastUpdated':'2026-02-01T00:00:00Z'}}}").get(0);
        assertThat(difference.what()).isEqualTo("When the record last changed.");
        assertThat(difference.group()).isEqualTo(DifferenceGroup.NOT_CLASSIFIED);
        assertThat(difference.explanation()).isEqualTo("The two requests hold different text here. This tool does not say which group a "
                + "difference in the value of this element belongs to.");
    }

    // ---- what the engine prints ----

    @Test
    void neitherAResultNorADifferencePrintsAValue() {
        JsonComparer.Result result = compare("{'name':'Placeholder-Surname'}", "{'name':'Sample-Surname'}");
        assertThat(result.toString()).isEqualTo("Result[differences=1, notes=0]");
        assertThat(result.differences().get(0).toString()).isEqualTo("Difference[name, DIFFERENT_VALUE, NOT_CLASSIFIED]");
    }

    @Test
    void aComparisonNeedsTwoDocuments() {
        assertThatThrownBy(() -> new JsonComparer(null, null).compare(json("{}"), null)).isInstanceOf(IllegalArgumentException.class);
        // Without rules or a glossary the engine still compares, by structure alone.
        assertThat(new JsonComparer(null, null).compare(json("{'a':1}"), json("{'a':2}")).differences()).hasSize(1);
    }
}
