package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(sent.at("/contained/0/url").asString()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/no-id");
        // The guides tie an adaptive response to the questionnaire being completed by what the questionnaire inside it is derived from.
        assertThat(sent.at("/contained/0/derivedFrom")).hasSize(1);
        assertThat(sent.at("/contained/0/derivedFrom/0").asString()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/no-id");
        assertThat(first.notes()).hasSize(2).anySatisfy(note -> assertThat(note).contains("was given the id adaptive-questionnaire"))
                .anySatisfy(note -> assertThat(note).isEqualTo("The copy of the questionnaire inside the QuestionnaireResponse was marked as "
                        + "derived from https://example.org/p32h-placeholder/Questionnaire/no-id, which is how the DTR and SDC guides tie an "
                        + "adaptive response to the questionnaire being completed."));
        // The member is named by the local id of the Patient resource, never by the member ID.
        assertThat(sent.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(first.json()).doesNotContain("S123456789");
        assertThat(sent.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(sent.has("item")).isFalse();
        // The body sent is the response itself, and the same response is what the changes are worked out from.
        assertThat(json(first.json())).isEqualTo(first.response());
    }

    @Test
    void theResponseGoesOutInsideAParametersResourceOnlyWhenAParameterToPutItUnderIsNamed() {
        String questionnaire = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/q\"}";
        DtrRequestBuilder.LoopBody wrapped = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()), null, DtrFixtures.CLOCK,
                " questionnaire-response-in ");
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
            DtrRequestBuilder.LoopBody bare = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()), null, DtrFixtures.CLOCK, none);
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
        JsonNode sent = builder.buildNextQuestion(DtrAnswers.read(questionnaire, null, List.of()), null, DtrFixtures.CLOCK).response();
        // Nothing the payer stated is taken away: its own derivedFrom follows the questionnaire being completed.
        assertThat(sent.at("/contained/0/derivedFrom")).hasSize(2);
        assertThat(sent.at("/contained/0/derivedFrom/0").asString()).isEqualTo(canonical);
        assertThat(sent.at("/contained/0/derivedFrom/1").asString()).isEqualTo(base);
        assertThat(OnyxDtrConventions.completes(sent)).containsExactly(canonical, base);

        // A questionnaire that already says it is derived from itself is left as it is, with no note about it.
        String already = "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"" + base + "\",\"derivedFrom\":[\"" + base + "\"]}";
        DtrRequestBuilder.LoopBody unchanged = builder.buildNextQuestion(DtrAnswers.read(already, null, List.of()), null, DtrFixtures.CLOCK);
        assertThat(unchanged.response().at("/contained/0/derivedFrom")).hasSize(1);
        assertThat(unchanged.notes()).noneMatch(note -> note.contains("derived from"));

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
                DtrAnswerInput.of("q2", "3"))), DtrFixtures.member("S123456789"), DtrFixtures.CLOCK);
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
        assertThat(built.notes()).containsExactly("The response so far holds an answer for vanished, which is not a question of its "
                + "questionnaire. It was not sent: a response is built from the questionnaire it answers.");
    }

    @Test
    void aResponseTheServiceCalledCompleteIsSentAsInProgressOnlyWhenAnAnswerIsAddedOrChanged() {
        String completed = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"string\"}]}],\"questionnaire\":\"#q\",\"status\":\"completed\",\"item\":[{\"linkId\":\"q1\","
                + "\"answer\":[{\"valueString\":\"given\"}]}]}";
        DtrRequestBuilder.LoopBody untouched = builder.buildNextQuestion(DtrAnswers.read(null, completed, List.of()), null, DtrFixtures.CLOCK);
        assertThat(untouched.response().get("status").asString()).isEqualTo("completed");
        assertThat(untouched.notes()).isEmpty();
        DtrRequestBuilder.LoopBody changed = builder.buildNextQuestion(DtrAnswers.read(null, completed, List.of(DtrAnswerInput.of("q1", "other"))),
                null, DtrFixtures.CLOCK);
        assertThat(changed.response().get("status").asString()).isEqualTo("in-progress");
        assertThat(changed.notes()).containsExactly("The response so far had the status completed. An answer was added or changed, so it was "
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
        return builder.buildResponse(posted, evaluated, complete, DtrFixtures.member("S123456789"), DtrFixtures.CLOCK, notes);
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
        assertThat(notes).containsExactly("The answer given to q2 was left out of the response, because the answers given disable that question.",
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
        ObjectNode response = builder.buildResponse(posted, EnableWhenEvaluator.evaluate(posted.questionnaire(), posted.merged()), true, null,
                DtrFixtures.CLOCK, notes);
        assertThat(response.has("questionnaire")).isFalse();
        assertThat(response.has("subject")).isFalse();
        assertThat(notes).containsExactly("The questionnaire has no url, so the response does not name the questionnaire it answers.",
                "The response names no subject, because the request names no member.");
    }
}
