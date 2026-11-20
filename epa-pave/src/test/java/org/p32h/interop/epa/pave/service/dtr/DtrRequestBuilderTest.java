package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Form;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the DTR steps send, built from the form and from what an earlier step handed on: the
 * package request, the QuestionnaireResponse of a loop step, and the response put together for a
 * static questionnaire.
 */
class DtrRequestBuilderTest {

    private static final String ORDER_ONE = "ServiceRequest/service-request-1";
    private static final String ORDER_TWO = "ServiceRequest/service-request-2";

    private final DtrRequestBuilder builder = new DtrRequestBuilder(null);

    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        assertThat(parsed.ok()).isTrue();
        return parsed.node();
    }

    private static List<JsonNode> named(JsonNode parameters, String name) {
        List<JsonNode> out = new ArrayList<>();
        parameters.get("parameter").forEach(parameter -> {
            if (name.equals(parameter.get("name").asString())) {
                out.add(parameter);
            }
        });
        return out;
    }

    private static DtrPackageRequest.Builder twoCodes() {
        return DtrFixtures.packageRequest("S123456789").procedureCodes(List.of(CodeInput.of("95810"), CodeInput.of("E0601")));
    }

    // ---- the package request ----

    @Test
    void thePackageRequestNamesTheCoverageEachOrderAndWhatTheyReferTo() {
        DtrRequestBuilder.Package built = builder.buildPackage(twoCodes().build(), ResolvedText::plain);
        JsonNode sent = json(built.json());
        assertThat(sent.get("resourceType").asString()).isEqualTo("Parameters");
        assertThat(named(sent, "coverage")).singleElement().satisfies(coverage -> {
            assertThat(coverage.at("/resource/resourceType").asString()).isEqualTo("Coverage");
            assertThat(coverage.at("/resource/beneficiary/reference").asString()).isEqualTo("Patient/patient-1");
            assertThat(coverage.at("/resource/subscriberId").asString()).isEqualTo("SUB-PLACEHOLDER-1");
            // A coverage status that was not supplied is not sent as active.
            assertThat(coverage.at("/resource").has("status")).isFalse();
        });
        // One order for each code, under the ids CRD sends them with, each naming the same patient, requester and coverage.
        assertThat(named(sent, "order")).extracting(order -> order.at("/resource/id").asString())
                .containsExactly("service-request-1", "service-request-2");
        assertThat(named(sent, "order")).extracting(order -> order.at("/resource/code/coding/0/code").asString()).containsExactly("95810", "E0601");
        assertThat(named(sent, "order")).allSatisfy(order -> {
            assertThat(order.at("/resource/subject/reference").asString()).isEqualTo("Patient/patient-1");
            assertThat(order.at("/resource/requester/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
            assertThat(order.at("/resource/insurance/0/reference").asString()).isEqualTo("Coverage/coverage-1");
            assertThat(order.at("/resource/occurrenceDateTime").asString()).isEqualTo("2026-03-20");
        });
        // Nothing an order or the coverage refers to is left pointing at nothing.
        assertThat(named(sent, "referenced")).extracting(referenced -> referenced.at("/resource/resourceType").asString())
                .containsExactly("Patient", "PractitionerRole", "Practitioner");
        assertThat(named(sent, "referenced").get(2).at("/resource/identifier/0/value").asString()).isEqualTo(DtrFixtures.NPI);
        assertThat(named(sent, "questionnaire")).isEmpty();
        assertThat(named(sent, "context")).isEmpty();
        assertThat(built.codes()).containsExactly("95810", "E0601");
        assertThat(built.sentCodes()).containsExactly("95810", "E0601");
        assertThat(built.canonicals()).isEmpty();
        assertThat(built.notes()).contains("The coding system for 95810 was taken from its format: CPT.",
                "The coding system for E0601 was taken from its format: HCPCS Level II.");
    }

    @Test
    void onlyTheOrderCrdsAnswerAppliedToIsSentAndItKeepsTheIdCrdSentItWith() {
        // CRD was asked about two codes and its answer about documentation applied to the second order only.
        DtrRequestBuilder.Package built = builder.buildPackage(twoCodes().carry(Map.of(NextStepView.CARRY_ORDER, List.of(ORDER_TWO))).build(),
                ResolvedText::plain);
        JsonNode sent = json(built.json());
        assertThat(named(sent, "order")).singleElement().satisfies(order -> {
            assertThat(order.at("/resource/id").asString()).isEqualTo("service-request-2");
            assertThat(order.at("/resource/code/coding/0/code").asString()).isEqualTo("E0601");
        });
        assertThat(built.notes()).contains("Only the order CRD's answer applied to was sent: " + ORDER_TWO + ".");
        // The case is still about both codes, which is what the step after this one starts from.
        assertThat(built.codes()).containsExactly("95810", "E0601");
        assertThat(built.sentCodes()).containsExactly("E0601");

        // An order this tool does not build is not guessed at: every order goes, and the note says why.
        DtrRequestBuilder.Package unknown = builder.buildPackage(twoCodes().carry(Map.of(NextStepView.CARRY_ORDER,
                List.of("ServiceRequest/vendor-order-9"))).build(), ResolvedText::plain);
        assertThat(named(json(unknown.json()), "order")).hasSize(2);
        assertThat(unknown.notes()).contains("CRD handed on the order ServiceRequest/vendor-order-9, which is not one of the orders this tool "
                + "builds from the procedure codes, so every order was sent.");
        // Both orders handed on: both sent, and said to be CRD's.
        DtrRequestBuilder.Package both = builder.buildPackage(twoCodes().carry(Map.of(NextStepView.CARRY_ORDER, List.of(ORDER_ONE, ORDER_TWO)))
                .build(), ResolvedText::plain);
        assertThat(both.sentCodes()).containsExactly("95810", "E0601");
        assertThat(both.notes()).contains("The order CRD's answer applied to was sent, under the id CRD sent it with.");
    }

    @Test
    void everyCanonicalHandedOnIsAskedForInOrderAndTheFirstAssertionIdIsSentWithANoteAboutTheRest() {
        DtrRequestBuilder.Package built = builder.buildPackage(DtrFixtures.packageRequest("S123456789").carry(Map.of(
                NextStepView.CARRY_QUESTIONNAIRE, List.of("https://example.org/q/one", "https://example.org/q/two", "https://example.org/q/one", " "),
                NextStepView.CARRY_COVERAGE_ASSERTION_ID, List.of("assertion-1", "assertion-2"))).build(), ResolvedText::plain);
        JsonNode sent = json(built.json());
        // Nothing is reduced to the first questionnaire. A repeat and a blank are not asked for twice.
        assertThat(named(sent, "questionnaire")).extracting(q -> q.get("valueCanonical").asString())
                .containsExactly("https://example.org/q/one", "https://example.org/q/two");
        assertThat(built.canonicals()).containsExactly("https://example.org/q/one", "https://example.org/q/two");
        assertThat(built.notes()).contains("The request asks for the questionnaires CRD named: https://example.org/q/one, "
                + "https://example.org/q/two.");
        // The operation takes one context, so one is sent, and the result says there were more.
        assertThat(named(sent, "context")).singleElement().satisfies(context ->
                assertThat(context.get("valueString").asString()).isEqualTo("assertion-1"));
        assertThat(built.notes()).contains("The coverage assertion id CRD handed on was sent as the context. CRD handed on 2 of them. The "
                + "first was sent, because the operation takes one.");
    }

    @Test
    void theCodingSystemComesFromTheTesterThenTheCodeListThenTheFormatOfTheCode() {
        FakeCodeList codeList = new FakeCodeList().row(ProcedureCode.builder().code("G0398").system("https://example.org/p32h-placeholder/"
                + "CodeSystem/local").build());
        DtrRequestBuilder withList = new DtrRequestBuilder(codeList);
        DtrRequestBuilder.Package built = withList.buildPackage(DtrFixtures.packageRequest("S123456789").procedureCodes(List.of(
                new CodeInput("95810", "https://example.org/p32h-placeholder/CodeSystem/typed", null), CodeInput.of("G0398"), CodeInput.of("ZZ-1")))
                .build(), ResolvedText::plain);
        assertThat(named(json(built.json()), "order")).extracting(order -> order.at("/resource/code/coding/0/system").asString("(none)"))
                .containsExactly("https://example.org/p32h-placeholder/CodeSystem/typed", "https://example.org/p32h-placeholder/CodeSystem/local",
                        "(none)");
        assertThat(built.notes()).contains("No coding system could be worked out for ZZ-1, so it was sent without one.");
    }

    @Test
    void placeholdersOfTheEnvironmentFillTheIdentifierSystemsAndAreLeftOutWithANoteWhenUnset() {
        FakeGateway gateway = new FakeGateway().variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id")
                .variable("payerName", "Placeholder payer");
        DtrRequestBuilder.Package resolved = builder.buildPackage(DtrFixtures.packageRequest("S123456789").build(),
                template -> gateway.resolve(template, Map.of()));
        JsonNode coverage = named(json(resolved.json()), "coverage").get(0).get("resource");
        assertThat(coverage.at("/identifier/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/member-id");
        assertThat(coverage.at("/payor/0/display").asString()).isEqualTo("Placeholder payer");
        DtrRequestBuilder.Package unset = builder.buildPackage(DtrFixtures.packageRequest("S123456789").build(),
                template -> new FakeGateway().resolve(template, Map.of()));
        assertThat(named(json(unset.json()), "coverage").get(0).at("/resource/identifier/0").has("system")).isFalse();
        assertThat(unset.notes()).anySatisfy(note -> assertThat(note).contains("memberIdSystem is not set"));
    }

    @Test
    void whatATestersOwnPackageBodyAsksForIsReadFromTheBodyAndNothingIsInventedForABodyThatCannotBeRead() {
        DtrRequestBuilder.Package read = DtrRequestBuilder.readPackage("{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"order\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"code\":{\"coding\":[{\"code\":\"70553\"}]}}},"
                + "{\"name\":\"order\",\"resource\":{\"resourceType\":\"DeviceRequest\",\"codeCodeableConcept\":{\"coding\":[{\"code\":\"E0601\"}]}}},"
                + "{\"name\":\"referenced\",\"resource\":{\"resourceType\":\"PractitionerRole\",\"code\":[{\"coding\":[{\"code\":\"doctor\"}]}]}},"
                + "{\"name\":\"questionnaire\",\"valueCanonical\":\"https://example.org/q/one\"},"
                + "{\"name\":\"questionnaire\",\"valueUri\":\"https://example.org/q/two\"},{\"name\":\"context\",\"valueString\":\"assertion-1\"}]}");
        assertThat(read.codes()).containsExactly("70553", "E0601");
        assertThat(read.sentCodes()).containsExactly("70553", "E0601");
        assertThat(read.canonicals()).containsExactly("https://example.org/q/one", "https://example.org/q/two");
        for (String unreadable : new String[] {null, "", "{\"resourceType\": ", "[]", "{\"resourceType\":\"Bundle\"}"}) {
            DtrRequestBuilder.Package nothing = DtrRequestBuilder.readPackage(unreadable);
            assertThat(nothing.codes()).isEmpty();
            assertThat(nothing.canonicals()).isEmpty();
        }
    }

    // ---- a step of the loop ----

    /**
     * The notes apart from the two that every response this tool builds or adds to can carry: the one about a response started
     * from nothing, and the one about where the answers came from. Each of those has tests of its own.
     */
    private static List<String> others(List<String> notes) {
        return notes.stream().filter(note -> !note.startsWith("No response was given to start from") && !note.startsWith("Each answer added")
                && !note.startsWith("The provider on the form is named as the author of the response")).toList();
    }

    @Test
    void theFirstStepOfTheLoopCarriesTheQuestionnaireInsideANewResponse() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/no-id\"}";
        DtrRequestBuilder.LoopBody first = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()),
                DtrFixtures.member("S123456789"), DtrFixtures.CLOCK);
        JsonNode sent = json(first.json());
        assertThat(sent.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(sent.get("status").asString()).isEqualTo("in-progress");
        // A contained resource is referred to by its id, so one that arrived without an id is given one, and the result says so.
        assertThat(sent.get("questionnaire").asString()).isEqualTo("#adaptive-questionnaire");
        assertThat(sent.at("/contained/0/id").asString()).isEqualTo("adaptive-questionnaire");
        // DTR 2.0.1 dtr-questionnaire-adapt: the questionnaire inside has no url, and exactly one derivedFrom, the one being completed.
        assertThat(sent.at("/contained/0").has("url")).isFalse();
        assertThat(sent.at("/contained/0/derivedFrom")).hasSize(1);
        assertThat(sent.at("/contained/0/derivedFrom/0").asString()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/no-id");
        assertThat(others(first.notes())).hasSize(2).anySatisfy(note -> assertThat(note).contains("was given the id adaptive-questionnaire"))
                .anySatisfy(note -> assertThat(note).isEqualTo("The questionnaire was copied into the QuestionnaireResponse and shaped to "
                        + "the DTR 2.0.1 adaptive questionnaire profile (dtr-questionnaire-adapt): its url was taken off, because the profile "
                        + "allows none; derivedFrom was set to https://example.org/p32h-placeholder/Questionnaire/no-id."));
        // What the response leaves out that DTR 2.0.1 requires is listed as gaps, each naming the profile.
        assertThat(first.gaps()).extracting(DtrProfileCheck.Gap::element).containsExactly("QuestionnaireResponse.extension:context",
                "QuestionnaireResponse.extension:intendedUse", "QuestionnaireResponse.item", "Questionnaire.title", "Questionnaire.text",
                "Questionnaire.extension:questionnaireAdaptive");
        // The member is named by the local id of the Patient resource, never by the member ID.
        assertThat(sent.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(first.json()).doesNotContain("S123456789");
        assertThat(sent.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(sent.has("item")).isFalse();
        // The body sent is the response itself, and the same response is what the changes are worked out from.
        assertThat(json(first.json())).isEqualTo(first.response());
    }

    private static final String ADAPTIVE_MARKER = "{\"url\":\"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive\","
            + "\"valueUrl\":\"https://example.org/p32h-placeholder/fhir\"}";
    private static final String BRANCHING = "{\"resourceType\":\"Questionnaire\",\"id\":\"adaptive\",\"url\":\"https://example.org/"
            + "p32h-placeholder/Questionnaire/adaptive\",\"version\":\"1.0.0\",\"title\":\"Adaptive\",\"text\":{\"status\":\"generated\","
            + "\"div\":\"<div xmlns=\\\"http://www.w3.org/1999/xhtml\\\">Adaptive</div>\"},\"meta\":{\"profile\":[\"http://hl7.org/fhir/us/"
            + "davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search\"]},\"extension\":[" + ADAPTIVE_MARKER + "],\"status\":\"active\","
            + "\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\",\"text\":\"First\"},{\"linkId\":\"q2\",\"type\":\"string\","
            + "\"text\":\"Second\",\"enableWhen\":[{\"question\":\"q1\",\"operator\":\"=\",\"answerBoolean\":true}]}]}";

    @Test
    void theCopyOfAnAdaptiveQuestionnaireDeclaresTheAdaptiveProfileItNowMeetsAndTheNoteSaysSo() {
        DtrRequestBuilder.LoopBody first = builder.buildNextQuestion(DtrAnswers.read(BRANCHING, null, List.of()), answering("S123456789"),
                DtrFixtures.CLOCK, null);
        JsonNode copy = first.response().at("/contained/0");
        // dtr-questionnaire-adapt-search requires a url (1..1 in DTR 2.0.1) and the copy has none, so it declares dtr-questionnaire-adapt.
        assertThat(copy.at("/meta/profile")).hasSize(1);
        assertThat(copy.at("/meta/profile/0").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
        assertThat(copy.has("url")).isFalse();
        assertThat(first.notes()).anySatisfy(note -> assertThat(note).startsWith("The questionnaire was copied into the QuestionnaireResponse "
                + "and shaped to the DTR 2.0.1 adaptive questionnaire profile (dtr-questionnaire-adapt): ").contains("the profile it declared, "
                + "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search, was replaced by "
                + "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt, because the copy is now the questionnaire "
                + "inside an adaptive response, which is what that profile describes"));
        // The copy meets what dtr-questionnaire-adapt asks of it: the marker, a title, a narrative and one derivedFrom.
        assertThat(first.gaps()).extracting(DtrProfileCheck.Gap::element).noneMatch(element -> element.startsWith("Questionnaire."));
    }

    @Test
    void theCopyOfAStaticQuestionnaireKeepsWhatItSaidItWasAndTheGapsSayWhatTheAdaptiveProfileStillLacks() {
        String unmarked = BRANCHING.replace("dtr-questionnaire-adapt-search", "dtr-std-questionnaire").replace("\"extension\":[" + ADAPTIVE_MARKER + "],", "");
        DtrRequestBuilder.LoopBody first = builder.buildNextQuestion(DtrAnswers.read(unmarked, null, List.of()),
                answering("S123456789"), DtrFixtures.CLOCK, null);
        JsonNode copy = first.response().at("/contained/0");
        // It is not marked adaptive, so this tool does not declare it adaptive: it keeps the profile it came with.
        assertThat(copy.at("/meta/profile/0").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire");
        assertThat(first.gaps()).extracting(DtrProfileCheck.Gap::element).contains("Questionnaire.extension:questionnaireAdaptive",
                "Questionnaire.url (dtr-std-questionnaire)");
        assertThat(first.gaps()).filteredOn(gap -> gap.element().equals("Questionnaire.url (dtr-std-questionnaire)")).singleElement()
                .satisfies(gap -> assertThat(gap.sentence()).isEqualTo("The questionnaire inside the response still declares the DTR 2.0.1 "
                        + "profile dtr-std-questionnaire, which requires a url, and the copy has none, because the DTR 2.0.1 adaptive "
                        + "questionnaire profile allows none. It is not marked adaptive, so this tool leaves the profile it declares as it came."));
    }

    @Test
    void aFirstCallWithNoAnswerCarriesNoItemsAndOneThatAddsAnAnswerKeepsThemWithoutConditions() {
        DtrRequestBuilder.LoopBody bare = builder.buildNextQuestion(DtrAnswers.read(BRANCHING, null, List.of()), answering("S123456789"),
                DtrFixtures.CLOCK, null);
        // SDC: on the first call neither the response nor the questionnaire inside it has items.
        assertThat(bare.response().at("/contained/0").has("item")).isFalse();
        assertThat(bare.response().has("item")).isFalse();
        assertThat(bare.notes()).anySatisfy(note -> assertThat(note).contains("its 2 items were left out, because SDC has the first "
                + "next-question call carry no items and the service asks the questions itself"));

        DtrRequestBuilder.LoopBody answered = builder.buildNextQuestion(DtrAnswers.read(BRANCHING, null, List.of(DtrAnswerInput.of("q1", "true"))),
                answering("S123456789"), DtrFixtures.CLOCK, null);
        JsonNode inside = answered.response().at("/contained/0");
        // The answer has to sit under its item, so the items stay, and dtr-questionnaire-adapt allows no enableWhen on them.
        assertThat(inside.get("item")).hasSize(2);
        assertThat(inside.get("item")).noneMatch(item -> item.has("enableWhen") || item.has("enableBehavior"));
        assertThat(answered.response().at("/item/0/linkId").asString()).isEqualTo("q1");
        assertThat(answered.notes()).anySatisfy(note -> assertThat(note).contains("the enableWhen conditions of its items were taken off, "
                + "because the profile allows none. The items were kept, because the answers given in this step sit under them"));
        assertThat(answered.notes()).noneMatch(note -> note.contains("items were left out"));
    }

    @Test
    void theResponseGoesOutInsideAParametersResourceOnlyWhenAParameterToPutItUnderIsNamed() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/q\"}";
        DtrRequestBuilder.LoopBody wrapped = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()),
                DtrRequestBuilder.Filler.of(null), DtrFixtures.CLOCK, " questionnaire-response-in ");
        JsonNode body = json(wrapped.json());
        assertThat(body.get("resourceType").asString()).isEqualTo("Parameters");
        assertThat(body.at("/parameter/0/name").asString()).isEqualTo("questionnaire-response-in");
        // The response itself is kept apart from the body, because what changed is worked out from the response.
        assertThat(body.at("/parameter/0/resource")).isEqualTo(wrapped.response());
        assertThat(wrapped.response().get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(wrapped.notes()).contains("The QuestionnaireResponse was put inside a Parameters resource, under the parameter "
                + "questionnaire-response-in, as the request asked.");
        // With no name given the body is the response, and nothing is said about a wrapper.
        for (String none : new String[] {null, "", "  "}) {
            DtrRequestBuilder.LoopBody bare = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()),
                    DtrRequestBuilder.Filler.of(null), DtrFixtures.CLOCK, none);
            assertThat(json(bare.json())).isEqualTo(bare.response());
            assertThat(bare.notes()).noneMatch(note -> note.contains("Parameters"));
        }
    }

    @Test
    void theCopyInsideAFirstResponseNamesTheQuestionnaireBeingCompletedBeforeAnythingItWasItselfDerivedFrom() {
        String base = "https://example.org/p32h-placeholder/Questionnaire/base";
        String canonical = "https://example.org/p32h-placeholder/Questionnaire/derived|2.0";
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"https://example.org/p32h-placeholder/"
                + "Questionnaire/derived\",\"version\":\"2.0\",\"derivedFrom\":[\"" + base + "\"]}";
        DtrRequestBuilder.LoopBody copied = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()), null, DtrFixtures.CLOCK);
        JsonNode sent = copied.response();
        // DTR 2.0.1 allows the questionnaire inside exactly one derivedFrom, so the one being completed replaces what it was derived from,
        // and the note names what was replaced. Its url and its version are taken off: the canonical in derivedFrom carries them.
        assertThat(sent.at("/contained/0/derivedFrom")).hasSize(1);
        assertThat(sent.at("/contained/0/derivedFrom/0").asString()).isEqualTo(canonical);
        assertThat(sent.at("/contained/0").has("version")).isFalse();
        assertThat(OnyxDtrConventions.completes(sent)).containsExactly(canonical);
        assertThat(copied.notes()).contains("The questionnaire was copied into the QuestionnaireResponse and shaped to the DTR 2.0.1 adaptive "
                + "questionnaire profile (dtr-questionnaire-adapt): its url was taken off, because the profile allows none; its version was "
                + "taken off, because derivedFrom carries it and SDC 4.0.0-ballot allows none; derivedFrom was set to " + canonical
                + " alone, in place of " + base + ", because the profile allows exactly one.");

        // A questionnaire that already says it is derived from itself is left as it is, with no note about it.
        String already = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"" + base + "\",\"derivedFrom\":[\"" + base + "\"]}";
        DtrRequestBuilder.LoopBody unchanged = builder.buildNextQuestion(DtrAnswers.read(already, null, List.of()), null, DtrFixtures.CLOCK);
        assertThat(unchanged.response().at("/contained/0/derivedFrom")).hasSize(1);
        assertThat(unchanged.notes()).noneMatch(note -> note.contains("derivedFrom was set"));

        // A questionnaire with no url cannot be named, so nothing is made up for it.
        DtrRequestBuilder.LoopBody unnamed = builder.buildNextQuestion(DtrAnswers.read("{\"resourceType\":\"Questionnaire\",\"id\":\"q\"}",
                null, List.of()), null, DtrFixtures.CLOCK);
        assertThat(unnamed.response().at("/contained/0").has("derivedFrom")).isFalse();
        assertThat(OnyxDtrConventions.completes(unnamed.response())).isEmpty();
    }

    @Test
    void aLaterStepSendsTheResponseTheServiceReturnedWithTheAnswersPutWhereTheQuestionnairePutsThem() {
        String soFar = "{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"service-id-7\",\"contained\":[{\"resourceType\":\"Questionnaire\","
                + "\"id\":\"q\",\"item\":[{\"linkId\":\"g1\",\"text\":\"Group\",\"type\":\"group\",\"item\":[{\"linkId\":\"q1\",\"text\":\"First\","
                + "\"type\":\"boolean\",\"item\":[{\"linkId\":\"q1a\",\"text\":\"Follow-up\",\"type\":\"string\"}]},{\"linkId\":\"q2\",\"type\":"
                + "\"integer\"}]}]}],\"questionnaire\":\"#q\",\"status\":\"in-progress\",\"extension\":[{\"url\":\"https://example.org/x\","
                + "\"valueString\":\"kept\"}],\"item\":[{\"linkId\":\"g1\",\"item\":[{\"linkId\":\"q1\",\"extension\":[{\"url\":"
                + "\"https://example.org/item\",\"valueString\":\"kept\"}],\"answer\":[{\"valueBoolean\":true}]}]},"
                + "{\"linkId\":\"vanished\",\"answer\":[{\"valueString\":\"left behind\"}]}]}";
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(DtrAnswers.read(null, soFar, List.of(DtrAnswerInput.of("q1a", "more"),
                DtrAnswerInput.of("q2", "3"))), answering("S123456789"), DtrFixtures.CLOCK, null);
        JsonNode sent = built.response();
        // What the service put on the response is kept: its id, its extension, and what it put on an item.
        assertThat(sent.get("id").asString()).isEqualTo("service-id-7");
        assertThat(sent.at("/extension/0/valueString").asString()).isEqualTo("kept");
        assertThat(sent.at("/item/0/item/0/extension/0/valueString").asString()).isEqualTo("kept");
        assertThat(sent.get("status").asString()).isEqualTo("in-progress");
        // A group holds its questions, and the follow-up of a question sits beneath that question's answer.
        assertThat(sent.at("/item/0/linkId").asString()).isEqualTo("g1");
        assertThat(sent.at("/item/0/text").asString()).isEqualTo("Group");
        assertThat(sent.at("/item/0/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(sent.at("/item/0/item/0/answer/0/item/0/linkId").asString()).isEqualTo("q1a");
        assertThat(sent.at("/item/0/item/0/answer/0/item/0/answer/0/valueString").asString()).isEqualTo("more");
        assertThat(sent.at("/item/0/item/1/answer/0/valueInteger").asInt()).isEqualTo(3);
        // An answer with no question to go under is not sent, and is not dropped in silence either.
        assertThat(built.json()).doesNotContain("left behind");
        assertThat(others(built.notes())).containsExactly("The response so far stated no time it was authored, so the time of this step was "
                + "put on it.", "The response so far holds an answer for vanished, which is not a question of its "
                + "questionnaire. It was not sent: a response is built from the questionnaire it answers.");
        // DTR 2.0.1 requires authored, and the time of this step is a fact this tool knows.
        assertThat(sent.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        // The provider on the form is named as the author of the response, by its local id.
        assertThat(sent.at("/author/reference").asString()).isEqualTo("Practitioner/practitioner-1");
    }

    /** Someone answering for the member: the provider is on the form, as a step that adds an answer requires. */
    private static DtrRequestBuilder.Filler answering(String memberId) {
        return new DtrRequestBuilder.Filler(memberId == null ? null : DtrFixtures.member(memberId), DtrFixtures.provider(), List.of());
    }

    private static final String ORIGIN = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";
    private static final String FIVE_QUESTIONS = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
            + "{\"linkId\":\"q1\",\"type\":\"string\"},{\"linkId\":\"q2\",\"type\":\"string\"},{\"linkId\":\"q3\",\"type\":\"string\"},"
            + "{\"linkId\":\"q4\",\"type\":\"string\"},{\"linkId\":\"q5\",\"type\":\"string\"}]}";

    private static String origin(String source) {
        return "\"extension\":[{\"url\":\"" + ORIGIN + "\",\"extension\":[{\"url\":\"source\",\"valueCode\":\"" + source + "\"}]}],";
    }

    @Test
    void anAnswerAPersonGivesSaysSoAndNamesTheProviderOnTheFormAsItsAuthor() {
        DtrRequestBuilder.Filler filler = new DtrRequestBuilder.Filler(DtrFixtures.member("S123456789"), DtrFixtures.provider(),
                List.of(ORDER_ONE, ORDER_TWO));
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(DtrAnswers.read(FIVE_QUESTIONS, null, List.of(DtrAnswerInput.of("q1", "yes"))),
                filler, DtrFixtures.CLOCK, null);
        JsonNode answer = built.response().at("/item/0/answer/0");
        assertThat(answer.get("valueString").asString()).isEqualTo("yes");
        // The DTR guide requires this of a client: the source, and the person as the author.
        assertThat(answer.get("extension")).hasSize(1);
        assertThat(answer.at("/extension/0/url").asString()).isEqualTo(ORIGIN);
        assertThat(answer.at("/extension/0/extension/0/url").asString()).isEqualTo("source");
        assertThat(answer.at("/extension/0/extension/0/valueCode").asString()).isEqualTo("manual");
        assertThat(answer.at("/extension/0/extension/1/url").asString()).isEqualTo("author");
        assertThat(answer.at("/extension/0/extension/1/extension/0/url").asString()).isEqualTo("practitioner");
        // The provider is named by the local id of the Practitioner resource, never by the NPI or a name.
        assertThat(answer.at("/extension/0/extension/1/extension/0/valueReference/reference").asString()).isEqualTo("Practitioner/practitioner-1");
        assertThat(built.json()).doesNotContain(DtrFixtures.NPI).doesNotContain("Provider");
        assertThat(built.notes()).contains("Each answer added in this step was marked with the information-origin extension DTR 2.0.1 "
                + "requires of a client: given by a person, or changed by one where it had been pre-populated. The provider on the form is "
                + "named as its author, by the id this tool gives the Practitioner in every request, as DTR 2.0.1 requires of an answer a "
                + "person gave.");

        // A response started from nothing names the coverage and each order in qr-context, as DTR 2.0.1 has it, and no qr-coverage.
        JsonNode response = built.response();
        assertThat(response.get("extension")).hasSize(3);
        assertThat(response.get("extension")).extracting(extension -> extension.get("url").asString()).containsOnly(QR_CONTEXT);
        assertThat(response.get("extension")).extracting(extension -> extension.at("/valueReference/reference").asString())
                .containsExactly("Coverage/coverage-1", ORDER_ONE, ORDER_TWO);
        assertThat(built.notes()).contains("No response was given to start from, so this tool built one. It names the coverage of the case, "
                + "and the 2 orders it is for. Each is named in a qr-context extension, as DTR 2.0.1 has it. The references use the ids this "
                + "tool gives those resources in every request.");
        // Nothing states an intended use, so none is sent, and the gap names the 2.0.1 profile and what would supply one.
        // The questionnaire of this test has no url, title or narrative, so the copy inside cannot carry them either.
        assertThat(built.gaps()).extracting(DtrProfileCheck.Gap::element).containsExactly("QuestionnaireResponse.extension:intendedUse",
                "Questionnaire.title", "Questionnaire.text", "Questionnaire.extension:questionnaireAdaptive", "Questionnaire.derivedFrom");
        assertThat(built.gaps().get(0).sentence()).isEqualTo("The DTR 2.0.1 QuestionnaireResponse profile (dtr-questionnaireresponse) "
                + "requires at least one intendedUse extension, from the CRD 2.0.1 value set DocReason. None is stated: it comes from the "
                + "variable dtrIntendedUse, or from what CRD said the documentation is for (doc-purpose), and neither stated one. This tool "
                + "does not assume one.");

        // With no provider on the form an answer cannot name its author, which DTR 2.0.1 requires (dtrx-1). The service refuses
        // such a step before building anything, so the builder never writes an answer that breaks the invariant.
        DtrAnswers.Posted unauthored = DtrAnswers.read(FIVE_QUESTIONS, null, List.of(DtrAnswerInput.of("q1", "yes")));
        assertThat(DtrRequestBuilder.marksAnAnswer(unauthored)).isTrue();
        assertThatThrownBy(() -> builder.buildNextQuestion(unauthored, DtrRequestBuilder.Filler.of(null), DtrFixtures.CLOCK, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DtrRequestBuilder.marksAnAnswer(DtrAnswers.read(FIVE_QUESTIONS, null, List.of()))).isFalse();
    }

    private static final String QR_CONTEXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";

    @Test
    void aPrePopulatedAnswerThatIsChangedBecomesAnOverrideAndAnAnswerLeftAsItWasKeepsTheOriginItHad() {
        String soFar = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[" + FIVE_QUESTIONS + "],\"questionnaire\":\"#q\","
                + "\"status\":\"in-progress\",\"extension\":[{\"url\":\"https://example.org/p32h-placeholder/payer-extension\",\"valueString\":\"kept\"}],"
                + "\"item\":[{\"linkId\":\"q1\",\"answer\":[{" + origin("auto") + "\"valueString\":\"from the payer\"}]},"
                + "{\"linkId\":\"q2\",\"answer\":[{" + origin("override") + "\"valueString\":\"changed before\"}]},"
                + "{\"linkId\":\"q3\",\"answer\":[{" + origin("manual") + "\"valueString\":\"typed before\"}]},"
                + "{\"linkId\":\"q4\",\"answer\":[{" + origin("auto") + "\"valueString\":\"left alone\"}]},"
                + "{\"linkId\":\"q5\",\"answer\":[{" + origin("auto") + "\"valueString\":\"posted again\"}]}]}";
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(DtrAnswers.read(null, soFar, List.of(DtrAnswerInput.of("q1", "new"),
                DtrAnswerInput.of("q2", "newer"), DtrAnswerInput.of("q3", "retyped"), DtrAnswerInput.of("q5", "posted again"))),
                new DtrRequestBuilder.Filler(null, DtrFixtures.provider(), List.of()), DtrFixtures.CLOCK, null);
        JsonNode items = built.response().get("item");
        // Pre-populated and now changed by a person, or changed before and changed again: an override, with an author.
        assertThat(items.at("/0/answer/0/valueString").asString()).isEqualTo("new");
        assertThat(items.at("/0/answer/0/extension/0/extension/0/valueCode").asString()).isEqualTo("override");
        assertThat(items.at("/0/answer/0/extension/0/extension/1/url").asString()).isEqualTo("author");
        assertThat(items.at("/1/answer/0/extension/0/extension/0/valueCode").asString()).isEqualTo("override");
        // Typed by a person before and typed again: still manual.
        assertThat(items.at("/2/answer/0/extension/0/extension/0/valueCode").asString()).isEqualTo("manual");
        assertThat(items.at("/2/answer/0/extension")).hasSize(1);
        // Not posted at all, and posted again with the value it already had: neither was filled in or changed, so both stay as the
        // payer sent them, with no author.
        for (int untouched : new int[] {3, 4}) {
            assertThat(items.at("/" + untouched + "/answer/0/extension/0/extension")).as("item " + untouched).hasSize(1);
            assertThat(items.at("/" + untouched + "/answer/0/extension/0/extension/0/valueCode").asString()).isEqualTo("auto");
        }
        // A response that was given to start from keeps what the payer put on it, and nothing is added at its top.
        assertThat(built.response().get("extension")).hasSize(1);
        assertThat(built.response().at("/extension/0/valueString").asString()).isEqualTo("kept");
        assertThat(built.notes()).noneMatch(note -> note.startsWith("No response was given to start from"));
    }

    @Test
    void anAnswerPostedAsAFhirAnswerThatSaysWhereItCameFromIsUsedAsWritten() {
        JsonNode stated = DtrFixtures.json("{" + origin("auto") + "\"valueString\":\"as written\"}");
        JsonNode silent = DtrFixtures.json("{\"extension\":[{\"url\":\"https://example.org/p32h-placeholder/other\",\"valueBoolean\":true}],"
                + "\"valueString\":\"gets an origin\"}");
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(DtrAnswers.read(FIVE_QUESTIONS, null, List.of(
                new DtrAnswerInput("q1", List.of(stated)), new DtrAnswerInput("q2", List.of(silent)))),
                new DtrRequestBuilder.Filler(null, DtrFixtures.provider(), List.of()), DtrFixtures.CLOCK, null);
        // It says where it came from already. That is the tester's statement, and it is not replaced or added to.
        assertThat(built.response().at("/item/0/answer/0")).isEqualTo(stated);
        // It says nothing about where it came from, so it is marked, and what it carried is kept beside the mark.
        JsonNode marked = built.response().at("/item/1/answer/0");
        assertThat(marked.get("extension")).hasSize(2);
        assertThat(marked.at("/extension/0/url").asString()).isEqualTo("https://example.org/p32h-placeholder/other");
        assertThat(marked.at("/extension/1/url").asString()).isEqualTo(ORIGIN);
        assertThat(marked.at("/extension/1/extension/0/valueCode").asString()).isEqualTo("manual");
        assertThat(marked.get("valueString").asString()).isEqualTo("gets an origin");
    }

    @Test
    void aStaticResponseThisToolPutsTogetherMarksItsAnswersTheSameWay() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/form\","
                + "\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\",\"required\":true}]}";
        DtrAnswers.Posted posted = DtrAnswers.read(questionnaire, null, List.of(DtrAnswerInput.of("q1", "true")));
        List<String> notes = new ArrayList<>();
        ObjectNode response = builder.buildResponse(posted, EnableWhenEvaluator.evaluate(posted.questionnaire(), posted.merged()), true,
                new DtrRequestBuilder.Filler(DtrFixtures.member("S123456789"), DtrFixtures.provider(), List.of(ORDER_ONE)), DtrFixtures.CLOCK, notes);
        assertThat(response.get("status").asString()).isEqualTo("completed");
        assertThat(response.at("/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(response.at("/item/0/answer/0/extension/0/url").asString()).isEqualTo(ORIGIN);
        assertThat(response.at("/item/0/answer/0/extension/0/extension/0/valueCode").asString()).isEqualTo("manual");
        assertThat(response.at("/item/0/answer/0/extension/0/extension/1/extension/0/valueReference/reference").asString())
                .isEqualTo("Practitioner/practitioner-1");
        assertThat(response.at("/extension/0/valueReference/reference").asString()).isEqualTo("Coverage/coverage-1");
        assertThat(response.at("/extension/1/valueReference/reference").asString()).isEqualTo(ORDER_ONE);
        assertThat(notes).anySatisfy(note -> assertThat(note).startsWith("Each answer added in this step was marked"))
                .anySatisfy(note -> assertThat(note).startsWith("No response was given to start from, so this tool built one. It names the "
                        + "coverage of the case, and the order it is for."));
    }

    @Test
    void aResponseTheServiceCalledCompleteIsSentAsInProgressOnlyWhenAnAnswerIsAddedOrChanged() {
        String completed = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"string\"}]}],\"questionnaire\":\"#q\",\"status\":\"completed\",\"authored\":\"2026-03-09\","
                + "\"item\":[{\"linkId\":\"q1\","
                + "\"answer\":[{\"valueString\":\"given\"}]}]}";
        DtrRequestBuilder.LoopBody untouched = builder.buildNextQuestion(DtrAnswers.read(null, completed, List.of()), null, DtrFixtures.CLOCK);
        assertThat(untouched.response().get("status").asString()).isEqualTo("completed");
        assertThat(untouched.notes()).isEmpty();
        DtrRequestBuilder.LoopBody changed = builder.buildNextQuestion(DtrAnswers.read(null, completed, List.of(DtrAnswerInput.of("q1", "other"))),
                answering(null), DtrFixtures.CLOCK, null);
        assertThat(changed.response().get("status").asString()).isEqualTo("in-progress");
        assertThat(others(changed.notes())).containsExactly("The response so far had the status completed. An answer was added or changed, so it was "
                + "sent as in-progress.");
        // The response that was posted is not altered: the builder works on a copy.
        assertThat(DtrAnswers.read(null, completed, List.of()).response().get("status").asString()).isEqualTo("completed");
    }

    // ---- the response for a static questionnaire ----

    private static final String STATIC = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/static\","
            + "\"version\":\"2.0\",\"item\":[{\"linkId\":\"q1\",\"type\":\"boolean\",\"required\":true},{\"linkId\":\"q2\",\"type\":\"string\","
            + "\"required\":true,\"enableWhen\":[{\"question\":\"q1\",\"operator\":\"=\",\"answerBoolean\":true}]},"
            + "{\"linkId\":\"g1\",\"type\":\"group\",\"repeats\":true,\"item\":[{\"linkId\":\"q3\",\"type\":\"integer\"}]}]}";

    private ObjectNode respond(String startFrom, boolean complete, List<String> notes, DtrAnswerInput... answers) {
        DtrAnswers.Posted posted = DtrAnswers.read(STATIC, startFrom, List.of(answers));
        assertThat(posted.errors()).isEmpty();
        Form evaluated = EnableWhenEvaluator.evaluate(posted.questionnaire(), posted.merged());
        return builder.buildResponse(posted, evaluated, complete, answering("S123456789"), DtrFixtures.CLOCK, notes);
    }

    @Test
    void aStaticResponseNamesItsQuestionnaireHoldsOnlyWhatTheAnswersEnableAndSaysWhatItLeftOut() {
        List<String> notes = new ArrayList<>();
        ObjectNode response = respond(null, true, notes, DtrAnswerInput.of("q1", "false"), DtrAnswerInput.of("q2", "typed before q1 was changed"),
                DtrAnswerInput.of("q3", "4"));
        assertThat(response.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(response.get("questionnaire").asString()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/static|2.0");
        assertThat(response.get("status").asString()).isEqualTo("completed");
        assertThat(response.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(response.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        // The answer to the question the first answer disables is not in the response, and the note says so.
        assertThat(response.toString()).doesNotContain("typed before");
        assertThat(response.get("item")).extracting(item -> item.get("linkId").asString()).containsExactly("q1", "g1");
        assertThat(response.at("/item/1/item/0/answer/0/valueInteger").asInt()).isEqualTo(4);
        assertThat(others(notes)).containsExactly("The answer given to q2 was left out of the response, because the answers given disable that question.",
                "The group g1 repeats. This tool answers it once: it does not put several instances of a group into a response.");
        assertThat(respond(null, false, new ArrayList<>(), DtrAnswerInput.of("q1", "true")).get("status").asString()).isEqualTo("in-progress");
    }

    @Test
    void aStaticResponseStartsFromTheOneThePackageArrivedWithAndKeepsWhatThePayerPutOnIt() {
        String arrived = "{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"payer-id-3\",\"questionnaire\":"
                + "\"https://example.org/p32h-placeholder/Questionnaire/static|2.0\",\"status\":\"in-progress\",\"subject\":{\"reference\":"
                + "\"Patient/payer-patient\"},\"extension\":[{\"url\":\"https://example.org/context\",\"valueString\":\"assertion-1\"}],"
                + "\"item\":[{\"linkId\":\"q1\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"gone\",\"answer\":[{\"valueString\":\"x\"}]}]}";
        List<String> notes = new ArrayList<>();
        ObjectNode response = respond(arrived, true, notes, DtrAnswerInput.of("q2", "added by the tester"));
        assertThat(response.get("id").asString()).isEqualTo("payer-id-3");
        assertThat(response.at("/extension/0/valueString").asString()).isEqualTo("assertion-1");
        // The subject the payer named is kept, not replaced by this tool's own.
        assertThat(response.at("/subject/reference").asString()).isEqualTo("Patient/payer-patient");
        assertThat(response.get("status").asString()).isEqualTo("completed");
        assertThat(response.at("/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(response.at("/item/1/answer/0/valueString").asString()).isEqualTo("added by the tester");
        assertThat(notes).contains("The response to start from holds an answer for gone, which is not a question of this questionnaire. It "
                + "was left out of the response.");
    }

    @Test
    void aQuestionnaireWithNoUrlCannotBeNamedByTheResponseAndTheResultSaysSo() {
        DtrAnswers.Posted posted = DtrAnswers.read("{\"resourceType\":\"Questionnaire\",\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]}", null,
                List.of(DtrAnswerInput.of("q1", "x")));
        List<String> notes = new ArrayList<>();
        ObjectNode response = builder.buildResponse(posted, EnableWhenEvaluator.evaluate(posted.questionnaire(), posted.merged()), true,
                answering(null), DtrFixtures.CLOCK, notes);
        assertThat(response.has("questionnaire")).isFalse();
        assertThat(response.has("subject")).isFalse();
        assertThat(others(notes)).containsExactly("The questionnaire has no url, so the response does not name the questionnaire it answers.",
                "The response names no subject, because the request names no member.");
    }
}
