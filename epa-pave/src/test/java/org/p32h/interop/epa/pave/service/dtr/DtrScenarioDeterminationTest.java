package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioExpect;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * Drives every bundled DTR scenario through the real scenario files, the real mock client, the
 * real request builder and the real mapper, and compares what comes out with what each file claims
 * about itself: the package it returns, every step of its adaptive loop along every path the file
 * lists, and every response its sample answers produce. A scenario file that stops producing what
 * it claims fails here.
 */
class DtrScenarioDeterminationTest {

    private final ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
    private final MockOnyxDtrClient client = new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP));
    private final DtrResponseMapper mapper = new DtrResponseMapper();
    private final DtrRequestBuilder builder = new DtrRequestBuilder(null);
    private final FakeGateway gateway = FakeGateway.unconfigured();

    /** A package fetch, with the body the request builder really builds. */
    private VendorExchange fetch(String scenarioId, String memberId, String npi) {
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE));
        String body = builder.buildPackage(DtrFixtures.packageRequest(memberId == null ? "S123456789" : memberId).build(), call::resolve).json();
        return client.questionnairePackage(call, ResolvedText.plain(body), MockSelection.of(scenarioId, memberId, npi));
    }

    private DtrReading packageOf(String scenarioId) {
        return mapper.readPackage(fetch(scenarioId, null, null), List.of());
    }

    /** One step of the loop for a scenario: the request the builder builds from what was posted, and what the mock and the mapper make of it. */
    private record Step(VendorExchange exchange, DtrReading reading) {
    }

    private Step next(String scenarioId, String questionnaire, String responseSoFar, List<DtrAnswerInput> answers) {
        DtrAnswers.Posted posted = DtrAnswers.read(questionnaire, responseSoFar, answers);
        assertThat(posted.errors()).as("what was posted for " + scenarioId).isEmpty();
        DtrRequestBuilder.LoopBody body = builder.buildNextQuestion(posted, DtrFixtures.filler("S123456789"), DtrFixtures.CLOCK, null);
        VendorExchange exchange = client.nextQuestion(gateway.prepare(CallIntent.of(EpaOperation.DTR_NEXT_QUESTION)),
                ResolvedText.plain(body.json()), MockSelection.of(scenarioId, null, null));
        return new Step(exchange, mapper.readNextQuestion(exchange, body.response()));
    }

    private static TestResult assess(String expected, DtrReading reading) {
        return Expectations.assess(expected, reading.outcome(), reading.asked(), reading.answers(), DtrOutcome.values(),
                DtrCodes.EXPECTATION_ELEMENTS).result();
    }

    /** Checks one claim of a scenario file against what was produced, and that the claim passes as a tester's expectation would. */
    private static void assertClaim(String what, ScenarioExpect expect, VendorExchange exchange, DtrReading reading) {
        assertThat(reading.outcome().name()).as(what + " outcome").isEqualTo(expect.outcome());
        if (expect.errorType() != null) {
            assertThat(exchange.failureType().name()).as(what + " error type").isEqualTo(expect.errorType());
        }
        assertThat(exchange.httpStatus()).as(what + " status").isEqualTo(expect.httpStatus());
        assertCodes(what, expect.outcome(), expect.codes(), reading);
    }

    private static void assertCodes(String what, String outcome, Map<String, String> codes, DtrReading reading) {
        if (!codes.isEmpty()) {
            assertThat(reading.answers()).as(what + " gives something to compare a code with").isNotEmpty();
        }
        codes.forEach((element, code) -> reading.answers().forEach(answer ->
                assertThat(answer.codes().get(element)).as(what + " " + element).isNotNull().contains(code)));
        // The claim, declared as an expectation, must pass through the same comparison a tester's would.
        StringBuilder declared = new StringBuilder(outcome);
        codes.forEach((element, code) -> declared.append("; ").append(element).append("=").append(code));
        assertThat(assess(declared.toString(), reading)).as(what + " as the expectation " + declared).isEqualTo(TestResult.PASS);
    }

    // ---- the files themselves ----

    @Test
    void everyBundledScenarioIsCompleteSyntheticAndUsesOnlyPlaceholderIdentifiers() {
        ScenarioRepository.Loaded loaded = scenarios.load("dtr");
        assertThat(loaded.problems()).isEmpty();
        assertThat(loaded.scenarios()).extracting(Scenario::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        Pattern looksLikeAnIdentifier = Pattern.compile("\"[A-Za-z]{0,3}\\d{8,}\"");
        for (Scenario scenario : loaded.scenarios()) {
            assertThat(scenario.id()).startsWith("dtr-");
            assertThat(scenario.label()).as(scenario.id()).isNotBlank();
            assertThat(scenario.description()).as(scenario.id()).isNotBlank();
            assertThat(scenario.main()).as(scenario.id() + " answers a package fetch").isNotNull();
            assertThat(scenario.expect()).as(scenario.id() + " claims an outcome").isNotNull();
            assertThat(scenario.basis()).as(scenario.id()).startsWith("Synthetic.");
            assertThat(scenario.sentinelMemberId()).as(scenario.id()).startsWith("P32H-");
            if (scenario.sentinelNpi() != null) {
                assertThat(Npi.isValid(scenario.sentinelNpi())).as(scenario.id() + " sentinel NPI").isTrue();
            }
            // Nothing in a file may look like a member ID or an NPI, apart from the sentinel NPI.
            String text = JsonSafe.compact(scenario.raw()).replace("\"9999999995\"", "\"\"");
            assertThat(looksLikeAnIdentifier.matcher(text).find()).as(scenario.id() + " holds something that looks like an identifier").isFalse();
            // A step that answers a call of the loop says what it claims to produce.
            scenario.steps().forEach((name, step) -> assertThat(step.expect()).as(scenario.id() + " step " + name).isNotNull());
        }
        assertThat(loaded.scenarios()).extracting(Scenario::sentinelMemberId).doesNotHaveDuplicates();
    }

    @Test
    void theScenariosThatModelWhatThePublishedGuidesDoNotDefineSayWhatTheyAssume() {
        for (Scenario scenario : scenarios.list("dtr")) {
            if (List.of(2, 7, 8, 9, 10, 13, 14, 15).contains(scenario.number())) {
                assertThat(scenario.assumption()).as(scenario.id()).isNotBlank();
            }
            // Every scenario with an adaptive loop states the rule its steps are chosen by, in words.
            if (!scenario.steps().isEmpty()) {
                assertThat(JsonSafe.presentText(JsonSafe.child(scenario.raw(), "nextQuestion"), "rule")).as(scenario.id()).isNotBlank();
            }
        }
    }

    // ---- the package ----

    @Test
    void eachSentinelMemberGetsThePackageItsScenarioIsFor() {
        Map<String, DtrOutcome> table = new LinkedHashMap<>();
        table.put("P32H-DOC-DTR", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-ADAPT", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-PREPOP", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-REQUIRED", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-ERR500", DtrOutcome.SERVICE_ERROR);
        table.put("P32H-DTR-MALFORMED", DtrOutcome.MALFORMED_QUESTIONNAIRE);
        table.put("P32H-DTR-EMPTY", DtrOutcome.NO_QUESTIONNAIRE);
        table.put("P32H-DTR-NOTHING", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-NOMEM", DtrOutcome.MEMBER_NOT_FOUND);
        table.put("P32H-DTR-NOPROV", DtrOutcome.PROVIDER_NOT_FOUND);
        table.put("P32H-DTR-BAD400", DtrOutcome.REJECTED);
        table.put("P32H-DTR-TIMEOUT", DtrOutcome.TIMEOUT);
        table.put("P32H-DTR-TWO", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-LOOPERR", DtrOutcome.PACKAGE_LOADED);
        table.put("P32H-DTR-NOBUNDLE", DtrOutcome.NO_QUESTIONNAIRE);
        assertThat(table).hasSize(15);
        table.forEach((sentinel, outcome) -> {
            VendorExchange exchange = fetch(null, sentinel, DtrFixtures.NPI);
            assertThat(mapper.readPackage(exchange, List.of()).outcome()).as(sentinel).isEqualTo(outcome);
            assertThat(exchange.scenarioNote()).as(sentinel).isEqualTo("selected by the sentinel member ID " + sentinel);
        });
        // An ordinary member gets the first scenario, and the result says it is the default.
        VendorExchange ordinary = fetch(null, "S123456789", DtrFixtures.NPI);
        assertThat(ordinary.scenarioId()).isEqualTo("dtr-01-static-linear");
        assertThat(ordinary.scenarioNote()).contains("the default");
        // The sentinel NPI gives provider not found for an ordinary member, as it does in CRD.
        assertThat(mapper.readPackage(fetch(null, "S123456789", "9999999995"), List.of()).outcome()).isEqualTo(DtrOutcome.PROVIDER_NOT_FOUND);
    }

    @Test
    void everyScenarioFileProducesThePackageItClaims() {
        List<Scenario> all = scenarios.list("dtr");
        assertThat(all).hasSize(15);
        for (Scenario scenario : all) {
            VendorExchange exchange = fetch(scenario.id(), null, null);
            DtrReading reading = mapper.readPackage(exchange, List.of());
            assertClaim(scenario.id(), scenario.expect(), exchange, reading);
            assertThat(scenario.expect().errorType()).as(scenario.id() + " states its error type").isNotNull();
            // Which scenario answered, and how it was chosen, travels on the exchange for the result to state.
            assertThat(exchange.scenarioId()).as(scenario.id()).isEqualTo(scenario.id());
            assertThat(exchange.scenarioNote()).as(scenario.id()).isEqualTo("chosen in the scenario selector");
            assertThat(exchange.request().sent()).isFalse();
        }
    }

    @Test
    void thePackageShapesAndKindsTheScenariosModelAreTheOnesTheySayTheyModel() {
        assertThat(packageOf("dtr-01-static-linear").detail().responseKind()).isEqualTo(DtrResponseKind.PARAMETERS);
        assertThat(packageOf("dtr-13-two-questionnaires").detail().responseKind()).isEqualTo(DtrResponseKind.BUNDLE);
        assertThat(packageOf("dtr-13-two-questionnaires").detail().questionnaires()).extracting(QuestionnaireView::kind)
                .containsExactly(QuestionnaireKind.STATIC, QuestionnaireKind.STATIC);
        assertThat(packageOf("dtr-02-adaptive-branching").detail().questionnaires().get(0).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        assertThat(packageOf("dtr-08-adaptive-nothing-required").detail().questionnaires().get(0).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        // The pre-populated package carries its response, and the questionnaire shows which answers arrived with it.
        DtrReading prepopulated = packageOf("dtr-03-prepopulated");
        assertThat(prepopulated.detail().questionnaireResponses()).hasSize(1);
        assertThat(prepopulated.detail().questionnaires().get(0).items().get(0).prepopulated()).extracting(s -> s.value())
                .containsExactly("Medical genetics");
        // Each fault scenario is the fault it says, and the two not-found scenarios are not faults at all.
        assertThat(packageOf("dtr-05-package-error-500").outcome().fault()).isTrue();
        assertThat(packageOf("dtr-09-member-not-found").outcome().fault()).isFalse();
        assertThat(fetch("dtr-09-member-not-found", null, null).failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(fetch("dtr-12-timeout", null, null).httpStatus()).isNull();
    }

    // ---- the adaptive loop ----

    @Test
    void everyStepOfEveryAdaptiveScenarioIsReachedByAPathAndProducesWhatItClaims() {
        int stepsWalked = 0;
        int adaptiveScenarios = 0;
        for (Scenario scenario : scenarios.list("dtr")) {
            if (scenario.steps().isEmpty()) {
                continue;
            }
            adaptiveScenarios++;
            String questionnaire = packageOf(scenario.id()).detail().questionnaires().get(0).json();
            Set<String> reached = new LinkedHashSet<>();
            List<JsonNode> paths = JsonSafe.elements(scenario.raw(), "paths");
            assertThat(paths).as(scenario.id() + " lists the paths a tester can take").isNotEmpty();
            for (JsonNode path : paths) {
                String soFar = null;
                for (JsonNode call : JsonSafe.elements(path, "calls")) {
                    String stepName = JsonSafe.text(call, "step");
                    String what = scenario.id() + ", path \"" + JsonSafe.text(path, "name") + "\", step " + stepName;
                    // The first call carries the questionnaire from the package. Every later call
                    // carries only the response the call before it returned.
                    Step step = next(scenario.id(), soFar == null ? questionnaire : null, soFar, DtrFixtures.answers(call.get("answers")));

                    assertThat(step.exchange().request().note()).as(what).contains("step \"" + stepName + "\"");
                    reached.add(stepName);
                    assertClaim(what, scenario.step(stepName).expect(), step.exchange(), step.reading());
                    FormStateView form = step.reading().detail().form();
                    if (!call.has("asked")) {
                        // A call the file lists no questions for is one the service answers with a fault, and no form comes back.
                        assertThat(step.reading().outcome().fault()).as(what + " is a fault").isTrue();
                        assertThat(form).as(what + " returns no form").isNull();
                        stepsWalked++;
                        continue;
                    }
                    assertThat(form).as(what + " returns a form").isNotNull();
                    assertThat(form.asked()).as(what + " asks").extracting(QuestionRef::linkId)
                            .containsExactlyElementsOf(DtrFixtures.texts(call.get("asked")));
                    if (call.has("appeared")) {
                        assertThat(form.changes().appeared()).as(what + " appeared").extracting(QuestionRef::linkId)
                                .containsExactlyElementsOf(DtrFixtures.texts(call.get("appeared")));
                    }
                    if (call.has("disappeared")) {
                        assertThat(form.changes().disappeared()).as(what + " disappeared").extracting(QuestionRef::linkId)
                                .containsExactlyElementsOf(DtrFixtures.texts(call.get("disappeared")));
                    }
                    soFar = step.reading().detail().questionnaireResponses().get(0).json();
                    stepsWalked++;
                }
            }
            assertThat(reached).as(scenario.id() + ": every step the file defines is reached by one of its paths")
                    .containsExactlyInAnyOrderElementsOf(scenario.steps().keySet());
        }
        assertThat(adaptiveScenarios).isEqualTo(3);
        assertThat(stepsWalked).isGreaterThanOrEqualTo(16);
    }

    @Test
    void theMockReturnsTheAnswersItWasSentAndTheResultSaysTheyAreNotFromTheFile() {
        String questionnaire = packageOf("dtr-02-adaptive-branching").detail().questionnaires().get(0).json();
        Step first = next("dtr-02-adaptive-branching", questionnaire, null, List.of());
        // Every step of the loop says so, the first included: it returns the subject and the time the response was sent with.
        assertThat(first.exchange().scenarioNote()).isEqualTo("chosen in the scenario selector"
                + ". The scenario file holds the questions and the status of this step. The answers are the ones that were sent, "
                + "returned for the questions the step still asks");
        String afterFirst = first.reading().detail().questionnaireResponses().get(0).json();
        Step second = next("dtr-02-adaptive-branching", null, afterFirst, List.of(DtrAnswerInput.of("mri-1-therapy", "yes")));
        JsonNode returned = JsonSafe.parse(second.exchange().responseBody()).node();
        // The answer that was sent is in what came back, as the coding of the option that was chosen.
        assertThat(returned.at("/item/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(returned.at("/item/0/answer/0/valueCoding/code").asString()).isEqualTo("yes");
        assertThat(returned.at("/item/0/answer/0/valueCoding/system").asString()).endsWith("/CodeSystem/yes-no");
        // So is what the tool put on the response when it first built it.
        assertThat(returned.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(returned.at("/authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        // The questions and the status are the step's.
        assertThat(returned.at("/status").asString()).isEqualTo("in-progress");
        assertThat(returned.at("/contained/0/item")).hasSize(2);
    }

    @Test
    void aNextQuestionCallTheScenarioDoesNotCoverIsRefusedAndNeverAnsweredWithAnotherStep() {
        // A static scenario defines no step of the loop at all.
        String staticQuestionnaire = packageOf("dtr-01-static-linear").detail().questionnaires().get(0).json();
        assertThatThrownBy(() -> next("dtr-01-static-linear", staticQuestionnaire, null, List.of()))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("scenarioId");
                    assertThat(error.message()).contains("dtr-01-static-linear").contains("\"next-question\"");
                }));
        // An adaptive scenario refuses answers its rule does not fit. It does not fall back to its first question.
        String adaptive = packageOf("dtr-02-adaptive-branching").detail().questionnaires().get(0).json();
        String afterFirst = next("dtr-02-adaptive-branching", adaptive, null, List.of()).reading().detail().questionnaireResponses().get(0).json();
        DtrAnswerInput unexpected = new DtrAnswerInput("mri-1-therapy", List.of(DtrFixtures.json("{\"valueCoding\":{\"code\":\"maybe\"}}")));
        assertThatThrownBy(() -> next("dtr-02-adaptive-branching", null, afterFirst, List.of(unexpected)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .contains("dtr-02-adaptive-branching").contains("does not expect"));
    }

    // ---- a response put together in the tool ----

    @Test
    void everyResponseAScenarioFileListsProducesWhatTheFileClaims() {
        int claims = 0;
        for (Scenario scenario : scenarios.list("dtr")) {
            List<JsonNode> responses = JsonSafe.elements(scenario.raw(), "responses");
            if (responses.isEmpty()) {
                continue;
            }
            DtrReading packageReading = packageOf(scenario.id());
            for (JsonNode claim : responses) {
                String what = scenario.id() + ", response \"" + JsonSafe.text(claim, "name") + "\"";
                Integer which = JsonSafe.integer(claim, "questionnaire");
                QuestionnaireView questionnaire = packageReading.detail().questionnaires().get(which == null ? 0 : which - 1);
                String startFrom = Boolean.TRUE.equals(JsonSafe.bool(claim, "startFromPackageResponse"))
                        ? packageReading.detail().questionnaireResponses().get(0).json() : null;

                DtrAnswers.Posted posted = DtrAnswers.read(questionnaire.json(), startFrom, DtrFixtures.answers(claim.get("answers")));
                assertThat(posted.errors()).as(what).isEmpty();
                assertThat(DtrResponseAssembler.problems(posted)).as(what).isEmpty();
                DtrResponseAssembler.Assembled assembled = DtrResponseAssembler.assemble(posted,
                        DtrFixtures.filler("S123456789"), DtrFixtures.CLOCK, builder, mapper);
                DtrReading reading = assembled.reading();

                JsonNode expect = claim.get("expect");
                String outcome = JsonSafe.text(expect, "outcome");
                assertThat(reading.outcome().name()).as(what + " outcome").isEqualTo(outcome);
                Map<String, String> codes = new LinkedHashMap<>();
                JsonSafe.properties(JsonSafe.child(expect, "codes")).forEach(code -> codes.put(code.getKey(), JsonSafe.text(code.getValue())));
                assertCodes(what, outcome, codes, reading);
                // Each required question left unanswered is named, and a complete response names none.
                assertThat(reading.detail().form().requiredUnanswered()).as(what + " unanswered").extracting(QuestionRef::linkId)
                        .containsExactlyElementsOf(DtrFixtures.texts(expect.get("unanswered")));
                // The response that was built carries the status the outcome says, and nothing was sent for it.
                assertThat(JsonSafe.text(assembled.response(), "status")).as(what)
                        .isEqualTo(reading.outcome() == DtrOutcome.RESPONSE_COMPLETED ? "completed" : "in-progress");
                assertThat(reading.detail().sent()).isFalse();
                assertThat(reading.detail().responseKind()).isEqualTo(DtrResponseKind.NOT_SENT);
                claims++;
            }
        }
        assertThat(claims).isGreaterThanOrEqualTo(12);
    }

    // ---- the distinction this tool exists for, in its DTR form ----

    @Test
    void aPackageWithNoQuestionnaireAndAServiceThatAsksNothingAreNeverTheSameAnswer() {
        VendorExchange emptyExchange = fetch(null, "P32H-DTR-EMPTY", DtrFixtures.NPI);
        DtrReading noQuestionnaire = mapper.readPackage(emptyExchange, List.of());
        String adaptive = packageOf("dtr-08-adaptive-nothing-required").detail().questionnaires().get(0).json();
        Step asked = next("dtr-08-adaptive-nothing-required", adaptive, null, List.of());
        DtrReading nothingRequired = asked.reading();

        // Both are HTTP 200 with no transport failure. Only the content tells them apart.
        assertThat(emptyExchange.httpStatus()).isEqualTo(200);
        assertThat(asked.exchange().httpStatus()).isEqualTo(200);
        assertThat(emptyExchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(asked.exchange().failureType()).isEqualTo(OnyxFailureType.NONE);

        assertThat(noQuestionnaire.outcome()).isEqualTo(DtrOutcome.NO_QUESTIONNAIRE);
        assertThat(nothingRequired.outcome()).isEqualTo(DtrOutcome.NOTHING_REQUIRED);
        assertThat(noQuestionnaire.outcome().label()).isNotEqualTo(nothingRequired.outcome().label());
        assertThat(noQuestionnaire.outcome().tone()).isEqualTo(Tone.NO_ANSWER);
        assertThat(nothingRequired.outcome().tone()).isEqualTo(Tone.CLEAR);

        // One is an empty package. The other is the service saying the response is complete, with a status to show for it.
        assertThat(noQuestionnaire.answers()).isEmpty();
        assertThat(noQuestionnaire.ledgerCodes()).isEmpty();
        assertThat(noQuestionnaire.detail().questionnaires()).isEmpty();
        assertThat(noQuestionnaire.detail().form()).isNull();
        assertThat(nothingRequired.ledgerCodes()).containsExactly("response-status=completed", "questions=0", "answered=0", "required-unanswered=0");
        assertThat(nothingRequired.detail().form().complete()).isTrue();

        // An expectation that fits one fails on the other, by outcome and by code.
        assertThat(assess("NO_QUESTIONNAIRE", noQuestionnaire)).isEqualTo(TestResult.PASS);
        assertThat(assess("NOTHING_REQUIRED", nothingRequired)).isEqualTo(TestResult.PASS);
        assertThat(assess("NOTHING_REQUIRED", noQuestionnaire)).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_QUESTIONNAIRE", nothingRequired)).isEqualTo(TestResult.FAIL);
        assertThat(assess("questions=0", nothingRequired)).isEqualTo(TestResult.PASS);
        assertThat(assess("questions=0", noQuestionnaire)).isEqualTo(TestResult.FAIL);
        assertThat(assess("response-status=completed", noQuestionnaire)).isEqualTo(TestResult.FAIL);
        // And neither is a completed response to a questionnaire that asked something.
        assertThat(assess("RESPONSE_COMPLETED", nothingRequired)).isEqualTo(TestResult.FAIL);
        assertThat(assess("RESPONSE_COMPLETED", noQuestionnaire)).isEqualTo(TestResult.FAIL);
    }

    @Test
    void notFoundIsToldApartFromAFaultThatCarriesTheSameKindOfStatus() {
        VendorExchange notFound = fetch(null, "P32H-DTR-NOMEM", DtrFixtures.NPI);
        VendorExchange rejected = fetch(null, "P32H-DTR-BAD400", DtrFixtures.NPI);
        // Both are HTTP 4xx. One is a finding about the test data, the other about the request.
        assertThat(notFound.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(rejected.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(mapper.readPackage(notFound, List.of()).outcome()).isEqualTo(DtrOutcome.MEMBER_NOT_FOUND);
        assertThat(mapper.readPackage(rejected, List.of()).outcome()).isEqualTo(DtrOutcome.REJECTED);
        // The fault is rendered issue by issue, not reduced to its status.
        DtrReading read = mapper.readPackage(rejected, List.of());
        assertThat(read.detail().responseKind()).isEqualTo(DtrResponseKind.OPERATION_OUTCOME);
        assertThat(read.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code().value()).isEqualTo("invalid");
            assertThat(issue.expression()).extracting(s -> s.value()).containsExactly("Parameters.parameter[1]");
        });
        assertThat(read.additionalFields()).isEmpty();
    }
}
