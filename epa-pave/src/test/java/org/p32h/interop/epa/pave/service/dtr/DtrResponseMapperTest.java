package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.dtr.AnswerOptionView;
import org.p32h.interop.epa.pave.model.dtr.ConditionState;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.ExtensionView;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.dtr.PackageBundleView;
import org.p32h.interop.epa.pave.model.dtr.PackageResourceView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireResponseView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.model.dtr.ResponseItemView;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * The DTR mapper on its own: what reaches the designed view, what reaches the fields that were
 * returned but not interpreted, and that nothing a service sends is dropped, for a success and for
 * every kind of fault.
 */
class DtrResponseMapperTest {

    private static final String PACKAGE = EpaOperation.DTR_QUESTIONNAIRE_PACKAGE.name();
    private static final String IN_BUNDLE = "parameter[0].resource.entry";

    private final DtrResponseMapper mapper = new DtrResponseMapper();

    private static String fixture(String name) {
        try (InputStream in = DtrResponseMapperTest.class.getResourceAsStream("/dtr/" + name)) {
            assertThat(in).as("the fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("The fixture " + name + " could not be read.", e);
        }
    }

    private DtrReading readPackage(String body) {
        return mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, body), List.of());
    }

    private DtrReading readStep(int status, String body) {
        return mapper.readNextQuestion(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, status, body), null);
    }

    private static List<String> paths(DtrReading reading) {
        return reading.additionalFields().stream().map(AdditionalField::path).toList();
    }

    private static List<String> values(List<Shown> shown) {
        return shown.stream().map(Shown::orNull).toList();
    }

    /** A package holding one questionnaire built from the given properties, as a Bundle. */
    private static String bundleOf(String questionnaireProperties) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\""
                + (questionnaireProperties.isEmpty() ? "" : "," + questionnaireProperties) + "}}]}";
    }

    // ---- nothing silently dropped ----

    @Test
    void aFieldTheMapperWasNeverToldAboutReachesTheAdditionalFieldsAtEveryDepthOfAPackage() {
        DtrReading reading = readPackage(fixture("package-everything.json"));
        assertThat(reading.outcome()).isEqualTo(DtrOutcome.PACKAGE_LOADED);
        // On the package, on a parameter, on the bundle and its entry, on the questionnaire, on a
        // nested item, on an enableWhen, on an answer option and the coding inside it, on a library,
        // a value set, the response the package arrived with, an answer in it, and a resource of a
        // kind this tool has no view for. In document order, and nothing else.
        assertThat(paths(reading)).containsExactly(
                "id",
                "vendorTraceId",
                "parameter[0].vendorParameterNote",
                "parameter[0].resource.timestamp",
                IN_BUNDLE + "[0].vendorEntryFlag",
                IN_BUNDLE + "[0].resource.meta.versionId",
                IN_BUNDLE + "[0].resource.vendorQuestionnaireField",
                IN_BUNDLE + "[0].resource.item[0].item[0].vendorNestedItemField",
                IN_BUNDLE + "[0].resource.item[0].item[1].enableWhen[0].vendorEnableWhenField",
                IN_BUNDLE + "[0].resource.item[0].item[1].answerOption[0].valueCoding.vendorCodingField",
                IN_BUNDLE + "[0].resource.item[0].item[1].answerOption[0].vendorOptionField",
                IN_BUNDLE + "[1].resource.type.coding[0].code",
                IN_BUNDLE + "[2].resource.compose.include[0].version",
                IN_BUNDLE + "[3].resource.vendorResponseField",
                IN_BUNDLE + "[3].resource.item[0].item[0].answer[0].vendorAnswerField",
                IN_BUNDLE + "[4].resource.vendorThing");
        // Each keeps its value as JSON text, so a number, a string and a boolean stay what they were.
        assertThat(reading.additionalFields()).filteredOn(f -> f.path().endsWith("vendorEnableWhenField")).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("1"));
        assertThat(reading.additionalFields()).filteredOn(f -> f.path().endsWith("vendorEntryFlag")).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("true"));
        assertThat(reading.additionalFields()).filteredOn(f -> f.path().endsWith("vendorNestedItemField")).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("\"kept\""));
        assertThat(reading.additionalFields()).allSatisfy(f -> assertThat(f.kind()).isEqualTo(AdditionalField.Kind.FIELD));
    }

    @Test
    void aFieldTheMapperWasNeverToldAboutReachesTheAdditionalFieldsAtEveryDepthOfALoopResponse() {
        String body = "{\"resourceType\":\"Parameters\",\"vendorEnvelopeField\":1,\"parameter\":[{\"name\":\"return\",\"vendorParameterField\":2,"
                + "\"resource\":{\"resourceType\":\"QuestionnaireResponse\",\"vendorResponseField\":3,\"contained\":[{\"resourceType\":"
                + "\"Questionnaire\",\"id\":\"q\",\"vendorQuestionnaireField\":4,\"item\":[{\"linkId\":\"g1\",\"type\":\"group\",\"item\":["
                + "{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"choice\",\"vendorItemField\":5,\"answerOption\":[{\"valueCoding\":"
                + "{\"code\":\"a\"},\"vendorOptionField\":6}],\"enableWhen\":[{\"question\":\"q0\",\"operator\":\"exists\",\"answerBoolean\":"
                + "false,\"vendorEnableWhenField\":7}]}]}]},{\"resourceType\":\"ValueSet\",\"id\":\"vs\",\"vendorValueSetField\":8}],"
                + "\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"item\":[{\"linkId\":\"g1\",\"vendorResponseItemField\":9,\"item\":["
                + "{\"linkId\":\"q1\",\"answer\":[{\"valueCoding\":{\"code\":\"a\",\"vendorCodingField\":10},\"vendorAnswerField\":11}]}]}]}}]}";
        DtrReading reading = readStep(200, body);
        assertThat(reading.detail().responseKind()).isEqualTo(DtrResponseKind.PARAMETERS);
        assertThat(reading.detail().responseNote()).isEqualTo("The QuestionnaireResponse arrived inside a Parameters resource.");
        String response = "parameter[0].resource";
        assertThat(paths(reading)).containsExactly(
                "vendorEnvelopeField",
                "parameter[0].vendorParameterField",
                response + ".vendorResponseField",
                response + ".contained[0].vendorQuestionnaireField",
                response + ".contained[0].item[0].item[0].vendorItemField",
                response + ".contained[0].item[0].item[0].answerOption[0].vendorOptionField",
                response + ".contained[0].item[0].item[0].enableWhen[0].vendorEnableWhenField",
                response + ".contained[1].vendorValueSetField",
                response + ".item[0].vendorResponseItemField",
                response + ".item[0].item[0].answer[0].valueCoding.vendorCodingField",
                response + ".item[0].item[0].answer[0].vendorAnswerField");
        // The designed view was still built from the same body.
        assertThat(reading.detail().form().questionCount()).isEqualTo(1);
        assertThat(reading.detail().form().answeredCount()).isEqualTo(1);
        assertThat(reading.detail().resources()).singleElement().satisfies(resource ->
                assertThat(resource.resourceType().value()).isEqualTo("ValueSet"));
    }

    // ---- the designed view ----

    @Test
    void everyPartOfAQuestionnaireIsInTheDesignedView() {
        DtrReading reading = readPackage(fixture("package-everything.json"));
        assertThat(reading.detail().responseKind()).isEqualTo(DtrResponseKind.PARAMETERS);
        assertThat(reading.detail().responseNote()).isEqualTo("The package arrived as a Parameters resource holding: PackageBundle (Bundle), "
                + "Outcome (OperationOutcome).");
        assertThat(reading.detail().kindRule()).startsWith("A questionnaire is adaptive when");

        QuestionnaireView questionnaire = reading.detail().questionnaires().get(0);
        assertThat(reading.detail().questionnaires()).hasSize(1);
        assertThat(questionnaire.position()).isEqualTo(1);
        assertThat(questionnaire.foundAt()).isEqualTo(IN_BUNDLE + "[0].resource");
        // Title, status, url and version, and everything else that names it.
        assertThat(questionnaire.title().value()).isEqualTo("Everything questionnaire (placeholder)");
        assertThat(questionnaire.subject()).isEqualTo("Everything questionnaire (placeholder)");
        assertThat(questionnaire.status().code()).isEqualTo("active");
        assertThat(questionnaire.status().display()).isEqualTo("Active");
        assertThat(questionnaire.url().value()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/everything");
        assertThat(questionnaire.version().value()).isEqualTo("3.0.0");
        assertThat(questionnaire.canonical()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/everything|3.0.0");
        assertThat(questionnaire.id().value()).isEqualTo("everything");
        assertThat(questionnaire.name().value()).isEqualTo("Everything");
        assertThat(questionnaire.date().value()).isEqualTo("2026-01-01");
        assertThat(questionnaire.publisher().value()).isEqualTo("Placeholder payer");
        assertThat(questionnaire.description().value()).isEqualTo("Exercises every part of the designed view.");
        assertThat(values(questionnaire.profiles())).containsExactly("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire");
        assertThat(values(questionnaire.derivedFrom())).containsExactly("https://example.org/p32h-placeholder/Questionnaire/base");
        // The kind, with what decided it.
        assertThat(questionnaire.kind()).isEqualTo(QuestionnaireKind.STATIC);
        assertThat(questionnaire.kindBasis()).isEqualTo("Static, because it declares the standard questionnaire profile "
                + "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire.");
        assertThat(questionnaire.questionCount()).isEqualTo(4);
        assertThat(questionnaire.requiredCount()).isEqualTo(2);
        assertThat(questionnaire.usable()).isTrue();
        assertThat(questionnaire.answersBasis()).isEqualTo("The conditions were evaluated against the answers in the QuestionnaireResponse "
                + "the package arrived with.");
        // The CQL library it names, and an extension made of nested extensions, shown as what it holds.
        assertThat(questionnaire.extensions()).extracting(extension -> extension.url().value()).containsExactly(
                "http://hl7.org/fhir/StructureDefinition/cqf-library",
                "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-launchContext");
        assertThat(questionnaire.extensions().get(0).value().value()).isEqualTo("https://example.org/p32h-placeholder/Library/everything-logic");
        assertThat(questionnaire.extensions().get(1).value().value()).contains("\"valueCode\":\"Patient\"").contains("\"code\":\"patient\"");
        // The value set it carries inside itself.
        assertThat(questionnaire.contained()).singleElement().satisfies(contained -> {
            assertThat(contained.resourceType().value()).isEqualTo("ValueSet");
            assertThat(contained.id().value()).isEqualTo("vs-contained");
            assertThat(values(contained.concepts())).containsExactly("Contained one (s|c1)");
        });
        // The resource as returned, for the next step to post back.
        assertThat(JsonSafe.parse(questionnaire.json()).node().get("vendorQuestionnaireField").asString()).isEqualTo("kept");
    }

    @Test
    void everyItemIsShownIncludingNestedGroupsWithItsOptionsItsFlagsAndEveryConditionWithWhetherItHeld() {
        QuestionnaireView questionnaire = readPackage(fixture("package-everything.json")).detail().questionnaires().get(0);
        assertThat(questionnaire.items()).extracting(item -> item.linkId().value()).containsExactly("g1", "d1", "q4");

        ItemView group = questionnaire.items().get(0);
        assertThat(group.path()).isEqualTo("item[0]");
        assertThat(group.type().code()).isEqualTo("group");
        assertThat(group.type().display()).isEqualTo("Group of items");
        assertThat(group.question()).isFalse();
        assertThat(group.required().value()).isEqualTo("false");
        assertThat(group.repeats().value()).isEqualTo("false");
        assertThat(group.items()).extracting(item -> item.linkId().value()).containsExactly("q1", "q2", "g2");

        ItemView first = group.items().get(0);
        assertThat(first.path()).isEqualTo("item[0].item[0]");
        assertThat(first.text().value()).isEqualTo("First question");
        assertThat(first.type().code()).isEqualTo("boolean");
        assertThat(first.question()).isTrue();
        assertThat(first.required().value()).isEqualTo("true");
        // Absent is not false: the questionnaire did not say whether it repeats.
        assertThat(first.repeats().presence()).isEqualTo(Presence.ABSENT);
        assertThat(first.readOnly().presence()).isEqualTo(Presence.ABSENT);
        assertThat(first.enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(first.enabledReason()).isNull();
        // The answer the package arrived with for it.
        assertThat(values(first.prepopulated())).containsExactly("true");

        ItemView second = group.items().get(1);
        assertThat(second.prefix().value()).isEqualTo("1.2");
        assertThat(second.type().code()).isEqualTo("choice");
        assertThat(second.repeats().value()).isEqualTo("true");
        assertThat(second.readOnly().value()).isEqualTo("false");
        assertThat(second.answerValueSet().value()).isEqualTo("#vs-contained");
        assertThat(second.answerOptions()).extracting(AnswerOptionView::label).containsExactly("Option A", "Option B");
        assertThat(second.answerOptions()).extracting(AnswerOptionView::answerAs).containsExactly("a", "b");
        assertThat(second.answerOptions().get(0).value().value()).isEqualTo("Option A (s|a)");
        assertThat(second.answerOptions().get(0).initialSelected().value()).isEqualTo("true");
        assertThat(second.answerOptions().get(1).initialSelected().presence()).isEqualTo(Presence.ABSENT);
        assertThat(values(second.prepopulated())).containsExactly("Option B (b)");
        assertThat(second.extensions()).singleElement().satisfies(extension -> {
            assertThat(extension.url().value()).isEqualTo("http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl");
            assertThat(extension.value().value()).isEqualTo("http://hl7.org/fhir/questionnaire-item-control|drop-down");
        });
        // Its condition, with whether it evaluated true and why.
        assertThat(second.enableBehavior().code()).isEqualTo("all");
        assertThat(second.enableWhen()).singleElement().satisfies(condition -> {
            assertThat(condition.question().value()).isEqualTo("q1");
            assertThat(condition.operator().code()).isEqualTo("=");
            assertThat(condition.operator().display()).isEqualTo("equals");
            assertThat(condition.answer().value()).isEqualTo("true");
            assertThat(condition.state()).isEqualTo(ConditionState.TRUE);
            assertThat(condition.explanation()).isEqualTo("The condition holds.");
        });
        assertThat(second.enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(second.enabledReason()).isEqualTo("Enabled: its condition holds.");

        // A group inside a group, and the initial value the questionnaire itself states.
        ItemView inner = group.items().get(2);
        assertThat(inner.path()).isEqualTo("item[0].item[2]");
        assertThat(inner.items()).singleElement().satisfies(third -> {
            assertThat(third.path()).isEqualTo("item[0].item[2].item[0]");
            assertThat(third.linkId().value()).isEqualTo("q3");
            assertThat(values(third.initial())).containsExactly("3");
            assertThat(third.prepopulated()).isEmpty();
        });

        assertThat(questionnaire.items().get(1).type().code()).isEqualTo("display");
        assertThat(questionnaire.items().get(1).question()).isFalse();

        // A type and an operator FHIR does not define are shown as they came, and the condition is not guessed at.
        ItemView odd = questionnaire.items().get(2);
        assertThat(odd.type().recognised()).isFalse();
        assertThat(odd.type().display()).isEqualTo("Unrecognised code: slider");
        EnableWhenView unevaluated = odd.enableWhen().get(0);
        assertThat(unevaluated.operator().recognised()).isFalse();
        assertThat(unevaluated.operator().display()).isEqualTo("Unrecognised code: ~");
        assertThat(unevaluated.state()).isEqualTo(ConditionState.NOT_EVALUATED);
        assertThat(unevaluated.explanation()).isEqualTo("Not evaluated: its operator is not one FHIR defines.");
        assertThat(odd.enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        assertThat(odd.enabledReason()).isEqualTo("Undetermined: a condition that decides it could not be evaluated. It is still shown.");
    }

    @Test
    void whatTravelsWithTheQuestionnaireIsShownTheLibraryTheValueSetTheResponseAndTheBundleItself() {
        DtrReading reading = readPackage(fixture("package-everything.json"));
        assertThat(reading.detail().bundles()).singleElement().satisfies((PackageBundleView bundle) -> {
            assertThat(bundle.foundAt()).isEqualTo("parameter[0].resource");
            assertThat(bundle.type().value()).isEqualTo("collection");
            assertThat(bundle.id().value()).isEqualTo("bundle-1");
            assertThat(bundle.entryCount()).isEqualTo(5);
            assertThat(values(bundle.fullUrls())).containsExactly("https://example.org/p32h-placeholder/Questionnaire/everything",
                    "https://example.org/p32h-placeholder/Library/everything-logic");
        });

        List<PackageResourceView> resources = reading.detail().resources();
        assertThat(resources).extracting(resource -> resource.resourceType().value()).containsExactly("Library", "ValueSet", "Basic");
        PackageResourceView library = resources.get(0);
        assertThat(library.foundAt()).isEqualTo(IN_BUNDLE + "[1].resource");
        assertThat(library.url().value()).isEqualTo("https://example.org/p32h-placeholder/Library/everything-logic");
        assertThat(library.version().value()).isEqualTo("3.0.0");
        assertThat(library.name().value()).isEqualTo("EverythingLogic");
        assertThat(library.title().value()).isEqualTo("Everything logic (placeholder)");
        assertThat(library.status().value()).isEqualTo("active");
        // The CQL is said to have arrived and how much of it. Its bytes are in the wire view.
        assertThat(library.content()).hasSize(2);
        assertThat(library.content().get(0).contentType().value()).isEqualTo("text/cql");
        assertThat(library.content().get(0).dataPresence()).isEqualTo(Presence.VALUE);
        assertThat(library.content().get(0).dataCharacters()).isEqualTo(12);
        assertThat(library.content().get(1).contentType().value()).isEqualTo("application/elm+json");
        assertThat(library.content().get(1).url().value()).isEqualTo("https://example.org/p32h-placeholder/elm/everything.json");
        assertThat(library.content().get(1).dataPresence()).isEqualTo(Presence.ABSENT);
        assertThat(library.content().get(1).dataCharacters()).isNull();
        // A value set lists its concepts, each with whatever it carries.
        assertThat(values(resources.get(1).concepts())).containsExactly("Option A (s|a)", "s|b");
        assertThat(resources.get(2).id().value()).isEqualTo("other-1");

        // The QuestionnaireResponse the package carries, in full.
        assertThat(reading.detail().questionnaireResponses()).singleElement().satisfies((QuestionnaireResponseView response) -> {
            assertThat(response.foundAt()).isEqualTo(IN_BUNDLE + "[3].resource");
            assertThat(response.id().value()).isEqualTo("qr-1");
            assertThat(response.questionnaire().value()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/everything|3.0.0");
            assertThat(response.status().code()).isEqualTo("in-progress");
            assertThat(response.status().display()).isEqualTo("In progress");
            assertThat(response.authored().value()).isEqualTo("2026-01-15T09:30:00-05:00");
            assertThat(response.subject().value()).isEqualTo("Patient/patient-1 (Placeholder member)");
            assertThat(response.author().value()).isEqualTo("Practitioner/practitioner-1");
            assertThat(response.extensions()).singleElement().satisfies((ExtensionView extension) ->
                    assertThat(extension.value().value()).isEqualTo("assertion-1"));
            assertThat(response.answeredCount()).isEqualTo(2);
            ResponseItemView group = response.items().get(0);
            assertThat(group.linkId().value()).isEqualTo("g1");
            assertThat(group.answers()).isEmpty();
            assertThat(group.items()).extracting(item -> item.path()).containsExactly("item[0].item[0]", "item[0].item[1]");
            assertThat(values(group.items().get(0).answers())).containsExactly("true");
            assertThat(values(group.items().get(1).answers())).containsExactly("Option B (s|b)");
            assertThat(JsonSafe.parse(response.json()).node().get("vendorResponseField").asString()).isEqualTo("kept");
        });

        // An OperationOutcome that came with the package is rendered issue by issue, beside the questionnaire.
        assertThat(reading.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.severity().value()).isEqualTo("information");
            assertThat(issue.code().value()).isEqualTo("informational");
            assertThat(issue.diagnostics().value()).isEqualTo("One questionnaire applies.");
        });
    }

    @Test
    void absentNullAndEmptyAreThreeDifferentThings() {
        DtrReading reading = readPackage(bundleOf("\"title\":null,\"description\":\"\",\"status\":\"\",\"url\":\"https://example.org/p32h-placeholder/"
                + "Questionnaire/sparse\",\"item\":[{\"linkId\":\"q1\",\"text\":null,\"type\":\"string\",\"required\":null,\"answerOption\":[],"
                + "\"enableWhen\":[]}]"));
        QuestionnaireView questionnaire = reading.detail().questionnaires().get(0);
        assertThat(questionnaire.title().presence()).isEqualTo(Presence.NULL);
        assertThat(questionnaire.description().presence()).isEqualTo(Presence.EMPTY);
        assertThat(questionnaire.version().presence()).isEqualTo(Presence.ABSENT);
        assertThat(questionnaire.status().presence()).isEqualTo(Presence.EMPTY);
        assertThat(questionnaire.status().code()).isNull();
        // With no title it is named by its url, never by the word null.
        assertThat(questionnaire.subject()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/sparse");
        ItemView item = questionnaire.items().get(0);
        assertThat(item.text().presence()).isEqualTo(Presence.NULL);
        assertThat(item.required().presence()).isEqualTo(Presence.NULL);
        assertThat(item.repeats().presence()).isEqualTo(Presence.ABSENT);
        // A null required flag is not "required", so the question is not counted as one.
        assertThat(questionnaire.requiredCount()).isZero();
        // An empty list is told apart from a list that was not sent: it is listed as returned and empty.
        assertThat(reading.additionalFields()).extracting(AdditionalField::path, AdditionalField::presence).containsExactly(
                org.assertj.core.groups.Tuple.tuple("entry[0].resource.item[0].answerOption", Presence.EMPTY),
                org.assertj.core.groups.Tuple.tuple("entry[0].resource.item[0].enableWhen", Presence.EMPTY));
        assertThat(reading.additionalFields()).extracting(AdditionalField::value).containsOnly("[]");

        // A package whose entry list is empty, null or absent says which, and none of them is a questionnaire.
        DtrReading emptyList = readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}");
        assertThat(emptyList.outcome()).isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        assertThat(emptyList.detail().bundles().get(0).entryCount()).isZero();
        // The bundle view states the emptiness itself, so the empty list is not listed a second time.
        assertThat(emptyList.additionalFields()).isEmpty();
        assertThat(readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").outcome()).isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        // Something else where the list should be is not lost.
        DtrReading wrongShape = readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":\"see the attachment\"}");
        assertThat(wrongShape.outcome()).isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        assertThat(paths(wrongShape)).containsExactly("entry");
    }

    @Test
    void aKeyThatAppearsTwiceInTheBodyIsPointedOut() {
        DtrReading reading = readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"type\":\"searchset\",\"entry\":[]}");
        assertThat(reading.additionalFields()).singleElement().satisfies(note -> {
            assertThat(note.kind()).isEqualTo(AdditionalField.Kind.NOTE);
            assertThat(note.value()).contains("repeats a key");
        });
    }

    // ---- both shapes of package, and several questionnaires ----

    @Test
    void aPackageIsReadWhetherItArrivesAsParametersAsABundleOrAsOneInsideTheOther() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/a\","
                + "\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]}";
        DtrReading bundle = readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + questionnaire + "}]}");
        assertThat(bundle.detail().responseKind()).isEqualTo(DtrResponseKind.BUNDLE);
        assertThat(bundle.detail().responseNote()).isEqualTo("The package arrived as a Bundle.");
        assertThat(bundle.detail().questionnaires().get(0).foundAt()).isEqualTo("entry[0].resource");

        DtrReading parameters = readPackage("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"return\",\"resource\":" + questionnaire + "}]}");
        assertThat(parameters.detail().responseKind()).isEqualTo(DtrResponseKind.PARAMETERS);
        assertThat(parameters.detail().questionnaires().get(0).foundAt()).isEqualTo("parameter[0].resource");
        assertThat(parameters.detail().bundles()).isEmpty();

        // A Parameters resource whose parameter has parts, one of them a bundle that holds the questionnaire.
        DtrReading nested = readPackage("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"package\",\"part\":[{\"name\":\"bundle\","
                + "\"resource\":{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + questionnaire + "}]}}]}]}");
        assertThat(nested.outcome()).isEqualTo(DtrOutcome.PACKAGE_LOADED);
        assertThat(nested.detail().questionnaires().get(0).foundAt()).isEqualTo("parameter[0].part[0].resource.entry[0].resource");
        assertThat(nested.additionalFields()).isEmpty();

        // A bare Questionnaire is still a questionnaire, and the result says it was not wrapped.
        DtrReading bare = readPackage(questionnaire);
        assertThat(bare.outcome()).isEqualTo(DtrOutcome.PACKAGE_LOADED);
        assertThat(bare.detail().responseKind()).isEqualTo(DtrResponseKind.QUESTIONNAIRE);
        assertThat(bare.detail().responseNote()).contains("not wrapped in a package");
        assertThat(List.of(bundle.outcome(), parameters.outcome())).containsOnly(DtrOutcome.PACKAGE_LOADED);
    }

    @Test
    void aPackageWithSeveralQuestionnairesListsThemAllAndTiesEachResponseOnlyToTheQuestionnaireItNames() {
        String first = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/a\",\"title\":\"A\","
                + "\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\"},{\"linkId\":\"q2\",\"type\":\"string\",\"enableWhen\":[{\"question\":\"q1\","
                + "\"operator\":\"=\",\"answerBoolean\":true}]}]}";
        String second = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/b\",\"title\":\"B\","
                + "\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\"},{\"linkId\":\"q2\",\"type\":\"string\",\"enableWhen\":[{\"question\":\"q1\","
                + "\"operator\":\"=\",\"answerBoolean\":true}]}]}";
        String answersForB = "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://example.org/p32h-placeholder/Questionnaire/b\","
                + "\"status\":\"in-progress\",\"item\":[{\"linkId\":\"q1\",\"answer\":[{\"valueBoolean\":true}]}]}";
        DtrReading reading = readPackage("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + first + "},{\"resource\":"
                + second + "},{\"resource\":" + answersForB + "}]}");
        assertThat(reading.detail().questionnaires()).extracting(QuestionnaireView::subject).containsExactly("A", "B");
        assertThat(reading.detail().questionnaires()).extracting(QuestionnaireView::position).containsExactly(1, 2);
        // The answers belong to B. They enable B's second question and leave A's as it was.
        assertThat(reading.detail().questionnaires().get(0).items().get(1).enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(reading.detail().questionnaires().get(1).items().get(1).enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(reading.detail().questionnaires().get(0).items().get(0).prepopulated()).isEmpty();
        assertThat(values(reading.detail().questionnaires().get(1).items().get(0).prepopulated())).containsExactly("true");
        assertThat(reading.detail().questionnaires().get(0).answersBasis()).contains("with no answers");
        // Each questionnaire is its own answer to an expectation, and its own entry in the ledger.
        assertThat(reading.answers()).extracting(answer -> answer.subject()).containsExactly(
                "https://example.org/p32h-placeholder/Questionnaire/a", "https://example.org/p32h-placeholder/Questionnaire/b");
        assertThat(reading.ledgerCodes()).containsExactly("questionnaires=2", "questionnaire[1]: questionnaire-kind=static",
                "questionnaire[1]: questions=2", "questionnaire[2]: questionnaire-kind=static", "questionnaire[2]: questions=2");
        // Each questionnaire says which response the package carries for it, so the step that answers it knows where to start.
        assertThat(reading.detail().questionnaires().get(0).arrivedWith()).isNull();
        assertThat(reading.detail().questionnaires().get(1).arrivedWith()).isEqualTo("entry[2].resource")
                .isEqualTo(reading.detail().questionnaireResponses().get(0).foundAt());
    }

    @Test
    void aResponseThatCarriesItsQuestionnaireInsideItselfIsTiedByWhatThatQuestionnaireIsDerivedFrom() {
        String adaptive = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/adaptive\","
                + "\"version\":\"2.0\",\"title\":\"Adaptive\",\"extension\":[{\"url\":\"http://hl7.org/fhir/uv/sdc/StructureDefinition/"
                + "sdc-questionnaire-questionnaireAdaptive\",\"valueUrl\":\"https://example.org/p32h-placeholder/fhir\"}]}";
        String other = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/other\","
                + "\"title\":\"Other\",\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\"}]}";
        String draft = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"inside\","
                + "%s\"status\":\"active\"}],\"questionnaire\":\"#inside\",\"status\":\"in-progress\"}";
        String bundle = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + other + "},{\"resource\":"
                + adaptive + "},{\"resource\":%s}]}";

        // The shape the guides publish: no url of its own, and derived from the questionnaire being completed.
        DtrReading derived = readPackage(bundle.formatted(draft.formatted(
                "\"derivedFrom\":[\"https://example.org/p32h-placeholder/Questionnaire/adaptive|2.0\"],")));
        assertThat(derived.detail().questionnaires()).extracting(QuestionnaireView::subject).containsExactly("Other", "Adaptive");
        assertThat(derived.detail().questionnaires().get(0).arrivedWith()).isNull();
        assertThat(derived.detail().questionnaires().get(1).arrivedWith()).isEqualTo("entry[2].resource");
        // The questionnaire inside the response is not a questionnaire of the package, and is not counted as one.
        assertThat(derived.ledgerCodes()).contains("questionnaires=2");
        // Nothing of it is lost: what the package step does not interpret is listed as returned.
        assertThat(derived.additionalFields()).extracting(field -> field.path()).contains("entry[2].resource.contained[0].derivedFrom[0]");

        // Derived from a questionnaire the package does not hold: tied to neither.
        DtrReading elsewhere = readPackage(bundle.formatted(draft.formatted(
                "\"derivedFrom\":[\"https://example.org/p32h-placeholder/Questionnaire/elsewhere\"],")));
        assertThat(elsewhere.detail().questionnaires()).extracting(QuestionnaireView::arrivedWith).containsOnlyNulls();
        // Saying nothing about where it comes from, among two questionnaires: tied to neither. Nothing is guessed.
        DtrReading unnamed = readPackage(bundle.formatted(draft.formatted("")));
        assertThat(unnamed.detail().questionnaires()).extracting(QuestionnaireView::arrivedWith).containsOnlyNulls();
    }

    // ---- a fault is read with the same care as a success ----

    @Test
    void anOperationOutcomeIsReadIssueByIssueWhateverStatusCarriedItAndItsExtraFieldsAreKept() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"id\":\"oo-1\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\","
                + "\"details\":{\"text\":\"The order is missing.\",\"coding\":[{\"system\":\"s\",\"code\":\"missing-order\"}]},"
                + "\"diagnostics\":\"Parameters.parameter[1]\",\"expression\":[\"Parameters.parameter[1]\"],\"vendorIssueField\":true},"
                + "{\"severity\":\"warning\",\"code\":\"informational\"}],\"vendorTraceId\":\"t-1\"}";
        for (int status : new int[] {400, 500, 200}) {
            DtrReading reading = mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, status, outcome), List.of());
            assertThat(reading.detail().responseKind()).as("HTTP " + status).isEqualTo(DtrResponseKind.OPERATION_OUTCOME);
            assertThat(reading.detail().issues()).hasSize(2);
            assertThat(reading.detail().issues().get(0).severity().value()).isEqualTo("error");
            assertThat(reading.detail().issues().get(0).code().value()).isEqualTo("invalid");
            assertThat(reading.detail().issues().get(0).detailsText().value()).isEqualTo("The order is missing.");
            assertThat(values(reading.detail().issues().get(0).detailsCodes())).containsExactly("s|missing-order");
            assertThat(values(reading.detail().issues().get(0).expression())).containsExactly("Parameters.parameter[1]");
            assertThat(paths(reading)).as("HTTP " + status).containsExactly("id", "issue[0].vendorIssueField", "vendorTraceId");
        }
        assertThat(mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 400, outcome), List.of()).outcome())
                .isEqualTo(DtrOutcome.REJECTED);
        assertThat(mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 500, outcome), List.of()).outcome())
                .isEqualTo(DtrOutcome.SERVICE_ERROR);
        // With a success status it is the service answering and sending no questionnaire.
        assertThat(mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, outcome), List.of()).outcome())
                .isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        // The loop reads the same body the same way.
        DtrReading step = readStep(400, outcome);
        assertThat(step.outcome()).isEqualTo(DtrOutcome.REJECTED);
        assertThat(step.detail().issues()).hasSize(2);
        assertThat(paths(step)).containsExactly("id", "issue[0].vendorIssueField", "vendorTraceId");
        assertThat(readStep(200, outcome).outcome()).isEqualTo(DtrOutcome.NO_QUESTION_RETURNED);
    }

    @Test
    void aPackageThatArrivesWithAnErrorStatusIsStillShownInFullButIsNotALoadedPackage() {
        DtrReading reading = mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 500,
                fixture("package-everything.json")), List.of());
        assertThat(reading.outcome()).isEqualTo(DtrOutcome.SERVICE_ERROR);
        assertThat(reading.detail().questionnaires()).hasSize(1);
        assertThat(reading.detail().questionnaires().get(0).items()).hasSize(3);
        assertThat(paths(reading)).hasSize(16).contains("vendorTraceId");
    }

    @Test
    void aBodyThatCannotBeParsedIsShownAsReceivedAndNeverReadAsAPackage() {
        DtrReading truncated = readPackage("{\"resourceType\": \"Parameters\", \"parameter\": [");
        assertThat(truncated.outcome()).isEqualTo(DtrOutcome.MALFORMED_RESPONSE);
        assertThat(truncated.detail().responseKind()).isEqualTo(DtrResponseKind.NOT_JSON);
        assertThat(truncated.detail().responseNote()).contains("wire view").contains("not valid JSON");
        // The text as received is on the result itself, not only in the wire view, which is collapsed by default.
        assertThat(truncated.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"resourceType\": \"Parameters\", \"parameter\": ["));
        assertThat(truncated.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.RAW_BODY);
        assertThat(truncated.answers()).isEmpty();
        assertThat(truncated.ledgerCodes()).isEmpty();

        DtrReading html = mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 502, "<html>Bad Gateway</html>"), List.of());
        assertThat(html.outcome()).isEqualTo(DtrOutcome.SERVICE_ERROR);
        assertThat(html.detail().responseKind()).isEqualTo(DtrResponseKind.NOT_JSON);
        assertThat(html.additionalFields()).extracting(AdditionalField::value).containsExactly("<html>Bad Gateway</html>");

        DtrReading rejectedAsText = readStep(400, "Bad request: the response is missing");
        assertThat(rejectedAsText.outcome()).isEqualTo(DtrOutcome.REJECTED);
        assertThat(rejectedAsText.additionalFields()).extracting(AdditionalField::value).containsExactly("Bad request: the response is missing");
        DtrReading truncatedStep = readStep(200, "{\"resourceType\":\"QuestionnaireResponse\",");
        assertThat(truncatedStep.outcome()).isEqualTo(DtrOutcome.MALFORMED_RESPONSE);
        assertThat(truncatedStep.detail().form()).isNull();
        assertThat(truncatedStep.additionalFields()).singleElement().satisfies(f -> assertThat(f.kind()).isEqualTo(AdditionalField.Kind.RAW_BODY));
    }

    @Test
    void anEmptyBodyIsMalformedAndIsNotTheSameAsAPackageThatHoldsNoQuestionnaire() {
        DtrReading emptyBody = readPackage(null);
        assertThat(emptyBody.outcome()).isEqualTo(DtrOutcome.MALFORMED_RESPONSE);
        assertThat(emptyBody.detail().responseKind()).isEqualTo(DtrResponseKind.NO_BODY);
        assertThat(emptyBody.detail().responseNote()).isEqualTo("The response has no body.");
        assertThat(emptyBody.additionalFields()).isEmpty();
        assertThat(readPackage("{\"resourceType\":\"Parameters\"}").outcome()).isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        assertThat(readPackage("{\"resourceType\":\"Parameters\",\"parameter\":[]}").detail().responseNote())
                .isEqualTo("The package arrived as a Parameters resource that holds no parameters.");
        DtrReading emptyStep = readStep(200, null);
        assertThat(emptyStep.outcome()).isEqualTo(DtrOutcome.MALFORMED_RESPONSE);
        assertThat(emptyStep.detail().responseKind()).isEqualTo(DtrResponseKind.NO_BODY);
    }

    @Test
    void transportFailuresHaveTheirOwnOutcomesAndNoContent() {
        DtrReading timeout = mapper.readPackage(TestExchanges.timeout(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE), List.of("https://example.org/q"));
        assertThat(timeout.outcome()).isEqualTo(DtrOutcome.TIMEOUT);
        assertThat(timeout.detail().responseKind()).isEqualTo(DtrResponseKind.NO_RESPONSE);
        assertThat(timeout.detail().responseNote()).isEqualTo("No response arrived.");
        // What was asked for is still known. Nothing is said to be "not returned": no response arrived to return anything.
        assertThat(timeout.detail().requested()).containsExactly("https://example.org/q");
        assertThat(timeout.detail().notReturned()).isEmpty();
        DtrReading unreachable = mapper.readNextQuestion(TestExchanges.unavailable(EpaOperation.DTR_NEXT_QUESTION), null);
        assertThat(unreachable.outcome()).isEqualTo(DtrOutcome.UNREACHABLE);
        assertThat(unreachable.answers()).isEmpty();
        assertThat(unreachable.additionalFields()).isEmpty();
        assertThat(unreachable.detail().form()).isNull();
        assertThat(PACKAGE).isEqualTo("DTR_QUESTIONNAIRE_PACKAGE");
    }

    @Test
    void jsonOfAnotherShapeIsListedInFullAndIsNeverTakenForAQuestionnaire() {
        DtrReading array = readPackage("[{\"resourceType\":\"Questionnaire\"}]");
        assertThat(array.detail().responseKind()).isEqualTo(DtrResponseKind.OTHER_JSON);
        assertThat(array.outcome()).isEqualTo(DtrOutcome.MALFORMED_QUESTIONNAIRE);
        assertThat(paths(array)).containsExactly("[0].resourceType");

        DtrReading other = readPackage("{\"resourceType\":\"Patient\",\"id\":\"p-1\"}");
        assertThat(other.outcome()).isEqualTo(DtrOutcome.MALFORMED_QUESTIONNAIRE);
        assertThat(other.detail().responseNote()).isEqualTo("The body is a Patient resource, which is not one of the shapes a questionnaire "
                + "package arrives in. All of it is listed below.");
        assertThat(paths(other)).containsExactly("resourceType", "id");
        assertThat(readPackage("{\"questionnaire\":\"https://example.org/q\"}").detail().responseNote())
                .startsWith("The body is a JSON object with no resourceType");

        // A response from the loop where a package was asked for is shown as what it is, and is not a package.
        DtrReading response = readPackage("{\"resourceType\":\"QuestionnaireResponse\",\"status\":\"completed\"}");
        assertThat(response.detail().responseKind()).isEqualTo(DtrResponseKind.QUESTIONNAIRE_RESPONSE);
        assertThat(response.outcome()).isEqualTo(DtrOutcome.MALFORMED_QUESTIONNAIRE);
        assertThat(response.detail().questionnaireResponses()).hasSize(1);

        // And the other way round: a package where the loop expected a response.
        DtrReading packageInTheLoop = readStep(200, "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}");
        assertThat(packageInTheLoop.outcome()).isEqualTo(DtrOutcome.MALFORMED_QUESTIONNAIRE);
        assertThat(packageInTheLoop.detail().responseNote()).isEqualTo("The body is a Bundle resource, which is not a QuestionnaireResponse. All "
                + "of it is listed below.");
        assertThat(paths(packageInTheLoop)).containsExactly("resourceType", "type", "entry");
    }

    // ---- what the loop returns ----

    @Test
    void aLoopResponseWithNoQuestionnaireInsideItCannotBeReadAsQuestionsAndTheResultSaysWhy() {
        DtrReading reading = readStep(200, "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://example.org/q\","
                + "\"status\":\"in-progress\",\"item\":[{\"linkId\":\"q1\",\"answer\":[{\"valueString\":\"given\"}]}]}");
        assertThat(reading.outcome()).isEqualTo(DtrOutcome.MALFORMED_QUESTIONNAIRE);
        assertThat(reading.detail().responseNote()).contains("carries no questionnaire inside itself");
        // The response itself is still shown in full, answers included.
        assertThat(reading.detail().questionnaireResponses()).hasSize(1);
        assertThat(values(reading.detail().questionnaireResponses().get(0).items().get(0).answers())).containsExactly("given");
        assertThat(reading.detail().form()).isNull();
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void whereEachAnswerCameFromIsInTheDesignedViewAndAPartOfItNobodyAnticipatedIsStillListed() {
        String origin = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";
        String body = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"string\"},{\"linkId\":\"q2\",\"type\":\"string\"},{\"linkId\":\"q3\",\"type\":\"string\"}]}],"
                + "\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"item\":["
                + "{\"linkId\":\"q1\",\"answer\":[{\"extension\":[{\"url\":\"" + origin + "\",\"extension\":[{\"url\":\"source\","
                + "\"valueCode\":\"auto\"}]}],\"valueString\":\"pre-populated\"}]},"
                + "{\"linkId\":\"q2\",\"answer\":[{\"extension\":[{\"url\":\"https://example.org/p32h-placeholder/score\",\"valueInteger\":3},"
                + "{\"url\":\"" + origin + "\",\"extension\":[{\"url\":\"source\",\"valueCode\":\"override\"},{\"url\":\"author\",\"extension\":["
                + "{\"url\":\"role\",\"valueCodeableConcept\":{\"coding\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/practitioner-role\","
                + "\"code\":\"doctor\",\"display\":\"Doctor\"}]}},{\"url\":\"practitioner\",\"valueReference\":{\"reference\":"
                + "\"Practitioner/practitioner-1\"}}]},{\"url\":\"surprise\",\"valueString\":\"never heard of it\"}]}],"
                + "\"valueString\":\"changed\"}]},"
                + "{\"linkId\":\"q3\",\"answer\":[{\"valueString\":\"says nothing\"}]}]}";
        DtrReading reading = readStep(200, body);
        List<ResponseItemView> items = reading.detail().questionnaireResponses().get(0).items();
        // One origin for each answer, in the same order as the answers.
        assertThat(items).allSatisfy(item -> assertThat(item.origins()).hasSameSizeAs(item.answers()));
        assertThat(items.get(0).origins().get(0).source().value()).isEqualTo("auto");
        assertThat(items.get(0).origins().get(0).author().presence()).isEqualTo(Presence.ABSENT);
        assertThat(items.get(1).origins().get(0).source().value()).isEqualTo("override");
        assertThat(items.get(1).origins().get(0).author().value()).isEqualTo("Practitioner/practitioner-1");
        assertThat(values(items.get(1).origins().get(0).roles())).singleElement().satisfies(role -> assertThat(role).contains("Doctor"));
        // An answer that says nothing about where it came from is shown as saying nothing, which the guide does not allow.
        assertThat(items.get(2).origins().get(0).source().presence()).isEqualTo(Presence.ABSENT);
        // Any other extension on an answer is shown by its url and its value.
        assertThat(items.get(1).answerExtensions()).singleElement().satisfies(extension -> {
            assertThat(extension.url().value()).isEqualTo("https://example.org/p32h-placeholder/score");
            assertThat(extension.value().value()).isEqualTo("3");
        });
        assertThat(items.get(0).answerExtensions()).isEmpty();
        // What the origin held that this tool was never told about is not swallowed with the rest of it.
        assertThat(paths(reading)).containsExactly("item[1].answer[0].extension[1].extension[2].url",
                "item[1].answer[0].extension[1].extension[2].valueString");
    }

    @Test
    void whatChangedIsWorkedOutFromTheResponseThatWasSentAndTheOneThatCameBack() {
        String before = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"boolean\"},{\"linkId\":\"q2\",\"text\":\"Second\",\"type\":\"string\"}]}],"
                + "\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"item\":[{\"linkId\":\"q1\",\"answer\":[{\"valueBoolean\":false}]},"
                + "{\"linkId\":\"q2\",\"answer\":[{\"valueString\":\"given\"}]}]}";
        String after = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"boolean\"},{\"linkId\":\"q3\",\"text\":\"Third\",\"type\":\"string\","
                + "\"required\":true}]}],\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"item\":[{\"linkId\":\"q1\",\"answer\":"
                + "[{\"valueBoolean\":false}]}]}";
        DtrReading reading = mapper.readNextQuestion(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, after), DtrFixtures.json(before));
        assertThat(reading.outcome()).isEqualTo(DtrOutcome.NEXT_QUESTION);
        assertThat(reading.detail().form().changes().appeared()).containsExactly(new QuestionRef("q3", "Third"));
        assertThat(reading.detail().form().changes().disappeared()).containsExactly(new QuestionRef("q2", "Second"));
        assertThat(reading.detail().form().changes().answersDropped()).containsExactly(new QuestionRef("q2", "Second"));
        assertThat(reading.detail().form().asked()).extracting(QuestionRef::linkId).containsExactly("q3");
        assertThat(reading.detail().form().requiredUnanswered()).extracting(QuestionRef::linkId).containsExactly("q3");
        // The summary names questions. It never quotes an answer.
        assertThat(reading.detail().form().changes().summary()).contains("q3 \"Third\"").contains("q2 \"Second\"").doesNotContain("given");
        assertThat(reading.detail().form().answers()).singleElement().satisfies(answer -> {
            assertThat(answer.linkId()).isEqualTo("q1");
            assertThat(answer.text()).isEqualTo("First");
            assertThat(answer.values()).containsExactly("false");
        });
    }
}
