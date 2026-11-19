package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where member data, a question and an answer may go, and where they may not. The answers a tester
 * gives can be clinical: they are shown on screen and sent to the payer, and they go nowhere else.
 * Every DTR step is driven here with a member who has a name, a date of birth and an address, and
 * with answers of free text, and what was written to the ledger and to the log is then read back.
 */
class DtrPhiTest {

    private static final String STREET = "1 Placeholder Street";
    private static final String ANSWER = "Placeholder description of the symptom";
    private static final String SKILLED = "Placeholder description of the skilled need";

    /** Everything that must never be in a ledger line or a log line, whatever step wrote it. */
    private static final List<String> NEVER = List.of(
            // The member.
            DtrFixtures.SURNAME, "Given", DtrFixtures.BIRTH, "female", "SUB-PLACEHOLDER-1", STREET, "Sampletown",
            // What the payer asked, in its own words, and the names it gave its questions.
            "conservative therapy", "red-flag", "Sleepiness", "skilled need", "genetic counselling", "mri-1", "mri-2", "mri-3", "pac-1",
            "pac-4", "gen-1", "sleep-1",
            // What was answered: text the tester typed, and answers a package arrived with.
            ANSWER, SKILLED, "Medical genetics", "2026-01-10", "No (no)", "bathing",
            // Anything of a resource or a request body.
            "resourceType", "valueCoding", "valueString", "linkId", "\"item\"", "answerOption", "QuestionnaireResponse\"");

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
    private final OnyxDtrClient mockClient = new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP));

    private DtrService service(OnyxDtrClient client) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        return new DtrService(epa, gateway, client, new FakeCodeList(), support);
    }

    private static MemberDetail member(String memberId) {
        return DtrFixtures.member(memberId).toBuilder().address(new PostalAddress(STREET, null, "Sampletown", "MA", "00000")).build();
    }

    private static DtrPackageRequest packageRequest(String memberId) {
        return DtrFixtures.packageRequest(memberId).member(member(memberId)).build();
    }

    private static DtrNextQuestionRequest after(RunEnvelope<DtrDetail> previous, String memberId, DtrAnswerInput... answers) {
        DtrDetail detail = previous.detail();
        DtrNextQuestionRequest.Builder request = DtrNextQuestionRequest.builder().member(member(memberId)).provider(DtrFixtures.provider())
                .procedureCodes(List.of(CodeInput.of("72148"))).carry(previous.nextStep().carry()).answers(List.of(answers));
        return (detail.questionnaireResponses().isEmpty() ? request.questionnaire(detail.questionnaires().get(0).json())
                : request.questionnaireResponse(detail.questionnaireResponses().get(0).json())).build();
    }

    private static VendorExchange unreadable(EpaOperation operation) {
        ObjectNode exploding = new ObjectNode(JsonNodeFactory.instance) {
            @Override
            public JsonNode get(String propertyName) {
                throw new IllegalStateException("the reading exploded on " + DtrFixtures.SURNAME + " who answered " + ANSWER);
            }
        };
        return DtrFixtures.answered(operation, 200, "{\"resourceType\":\"Parameters\"}").withJson(exploding);
    }

    private static OnyxDtrClient stub(VendorExchange exchange) {
        return new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }
        };
    }

    /** Every kind of DTR step, each with member data and free-text answers in what it sends or receives. */
    private void runEveryKindOfStep() {
        DtrService service = service(mockClient);
        // The adaptive loop to completion, with an answer of free text, then the response step on what it returned.
        RunEnvelope<DtrDetail> fetched = service.runPackage(packageRequest("P32H-DTR-ADAPT"), RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> first = service.runNextQuestion(after(fetched, "P32H-DTR-ADAPT"), RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> second = service.runNextQuestion(after(first, "P32H-DTR-ADAPT", DtrAnswerInput.of("mri-1-therapy", "no")),
                RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> third = service.runNextQuestion(after(second, "P32H-DTR-ADAPT", DtrAnswerInput.of("mri-2-red-flag", "true")),
                RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> fourth = service.runNextQuestion(after(third, "P32H-DTR-ADAPT",
                DtrAnswerInput.of("mri-3-red-flag-detail", ANSWER)), RunOrigin.manual(), null);
        assertThat(fourth.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        service.runResponse(DtrResponseRequest.builder().member(member("P32H-DTR-ADAPT"))
                .questionnaireResponse(fourth.detail().questionnaireResponses().get(0).json()).carry(fourth.nextStep().carry()).build(),
                RunOrigin.manual());

        // A static questionnaire answered in the tool, complete and with a required question left out.
        RunEnvelope<DtrDetail> required = service.runPackage(packageRequest("P32H-DTR-REQUIRED"), RunOrigin.manual(), null);
        DtrResponseRequest.Builder response = DtrResponseRequest.builder().member(member("P32H-DTR-REQUIRED")).provider(DtrFixtures.provider())
                .questionnaire(required.detail().questionnaires().get(0).json()).carry(required.nextStep().carry());
        service.runResponse(response.answers(List.of(DtrAnswerInput.of("pac-1", "true"), DtrAnswerInput.of("pac-3", "true"))).build(),
                RunOrigin.manual());
        service.runResponse(response.answers(List.of(DtrAnswerInput.of("pac-1", "true"), DtrAnswerInput.of("pac-2", "bathing"),
                DtrAnswerInput.of("pac-3", "true"), DtrAnswerInput.of("pac-4", SKILLED))).build(), RunOrigin.manual());

        // A package that arrives with answers already in it, a package with nothing in it, and the faults.
        RunEnvelope<DtrDetail> prepopulated = service.runPackage(packageRequest("P32H-DTR-PREPOP"), RunOrigin.manual(), null);
        service.runResponse(DtrResponseRequest.builder().member(member("P32H-DTR-PREPOP"))
                .questionnaire(prepopulated.detail().questionnaires().get(0).json())
                .questionnaireResponse(prepopulated.detail().questionnaireResponses().get(0).json())
                .answers(List.of(DtrAnswerInput.of("gen-4", "no"))).build(), RunOrigin.manual());
        for (String sentinel : List.of("P32H-DOC-DTR", "P32H-DTR-EMPTY", "P32H-DTR-MALFORMED", "P32H-DTR-NOMEM", "P32H-DTR-ERR500",
                "P32H-DTR-BAD400", "P32H-DTR-TIMEOUT", "P32H-DTR-TWO")) {
            service.runPackage(packageRequest(sentinel), RunOrigin.manual(), null);
        }

        // A failure of this tool after each kind of call, whose exception message quotes the member and an answer.
        service(stub(unreadable(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE))).runPackage(packageRequest("S123456789"), RunOrigin.manual(), null);
        service(stub(unreadable(EpaOperation.DTR_NEXT_QUESTION))).runNextQuestion(after(first, "P32H-DTR-ADAPT",
                DtrAnswerInput.of("mri-1-therapy", "yes")), RunOrigin.manual(), null);

        // A form that is refused, whose answer is of the wrong type and is clinical text.
        assertThatThrownBy(() -> service.runNextQuestion(after(second, "P32H-DTR-ADAPT", DtrAnswerInput.of("mri-2-red-flag", ANSWER)),
                RunOrigin.manual(), null)).isInstanceOf(ValidationException.class);

        // A write the guardrail refuses, which is recorded too.
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment."));
        service.runNextQuestion(after(first, "P32H-DTR-ADAPT", DtrAnswerInput.of("mri-1-therapy", "yes")), RunOrigin.manual(), null);
    }

    @Test
    void theLedgerLineOfEveryStepHoldsNoMemberDataNoQuestionNoAnswerAndNoJson() {
        runEveryKindOfStep();
        assertThat(ledger.records()).hasSize(22);
        assertThat(ledger.records()).extracting(RunRecord::operation).contains(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE,
                EpaOperation.DTR_NEXT_QUESTION, EpaOperation.DTR_RESPONSE);
        assertThat(ledger.records()).extracting(RunRecord::outcome).contains("PACKAGE_LOADED", "NEXT_QUESTION", "RESPONSE_COMPLETED",
                "REQUIRED_UNANSWERED", "NO_QUESTIONNAIRE", "MALFORMED_QUESTIONNAIRE", "MEMBER_NOT_FOUND", "SERVICE_ERROR", "REJECTED", "TIMEOUT",
                "TOOL_ERROR", "BLOCKED");
        for (RunRecord record : ledger.records()) {
            String line = JsonSafe.mapper().writeValueAsString(record);
            for (String forbidden : NEVER) {
                assertThat(line).as(record.operation() + " " + record.outcome() + " must not hold [" + forbidden + "]").doesNotContain(forbidden);
            }
        }
        // What the lines do hold is codes and counts, which is what a history is read by.
        RunRecord completed = ledger.records().stream().filter(r -> r.operation() == EpaOperation.DTR_NEXT_QUESTION
                && r.outcome().equals("RESPONSE_COMPLETED")).findFirst().orElseThrow();
        assertThat(completed.coverageCodes()).containsExactly("response-status=completed", "questions=3", "answered=3", "required-unanswered=0");
        assertThat(completed.memberId()).isEqualTo("P32H-DTR-ADAPT");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void nothingAStepLogsHoldsAnIdentifierAnAnswerOrABody(CapturedOutput output) {
        runEveryKindOfStep();
        String logged = output.getAll();
        // The capture is real: the two failures of this tool were logged, by exception type, under a reference.
        assertThat(logged).contains("A run failed inside this tool after the service was called, reference")
                .contains("java.lang.IllegalStateException");
        for (String forbidden : NEVER) {
            assertThat(logged).as("the log must not hold [" + forbidden + "]").doesNotContain(forbidden);
        }
        // Nor an identifier: no member ID, sentinel or not, and no NPI.
        assertThat(logged).doesNotContain("P32H-DTR").doesNotContain("P32H-DOC").doesNotContain("S123456789").doesNotContain(DtrFixtures.NPI)
                .doesNotContain("exploded");
    }

    @Test
    void aResponseThatPutsTextWhereCodesBelongIsShownOnScreenAndKeptOutOfTheLedgerLine() {
        // A non-conformant package: a sentence in place of the status, and something that is no url where the url belongs.
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\","
                + "\"url\":\"see {\\\"patient\\\":\\\"Placeholder-Surname\\\"}\",\"status\":\"active for Placeholder-Surname born 1970-01-31\","
                + "\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]}}]}";
        RunEnvelope<DtrDetail> fetched = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, body)))
                .runPackage(DtrFixtures.packageRequest("S123456789").carry(Map.of(NextStepView.CARRY_PROCEDURE_CODES,
                        List.of("Placeholder-Surname has knee pain"))).procedureCodes(List.of()).build(), RunOrigin.manual(), null);
        // On screen the tester sees exactly what came back: that is the evidence.
        assertThat(fetched.detail().questionnaires().get(0).status().code()).isEqualTo("active for Placeholder-Surname born 1970-01-31");
        assertThat(fetched.detail().questionnaires().get(0).url().value()).contains("Placeholder-Surname");

        // The same for a step of the loop whose status is a sentence.
        String response = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":"
                + "\"Placeholder-Surname's questionnaire\",\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]}],\"questionnaire\":\"#q\","
                + "\"status\":\"completed for Placeholder-Surname born 1970-01-31\"}";
        RunEnvelope<DtrDetail> step = service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, response)))
                .runNextQuestion(DtrNextQuestionRequest.builder().questionnaire("{\"resourceType\":\"Questionnaire\",\"id\":\"q\"}").build(),
                        RunOrigin.manual(), null);
        assertThat(step.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_STATUS");
        assertThat(step.detail().form().status().code()).isEqualTo("completed for Placeholder-Surname born 1970-01-31");

        // The lines that are kept hold none of it, and still say that something was there.
        for (RunRecord record : ledger.records()) {
            String line = JsonSafe.mapper().writeValueAsString(record);
            assertThat(line).doesNotContain("Placeholder-Surname").doesNotContain("1970-01-31").doesNotContain("knee pain")
                    .doesNotContain("patient");
        }
        RunRecord packageLine = ledger.records().get(0);
        assertThat(packageLine.coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("questionnaire-status=(not a code, "));
        assertThat(packageLine.correlation().get(RunRecord.CORR_QUESTIONNAIRE)).startsWith("(not a url, ");
        assertThat(packageLine.procedureCodes()).singleElement().satisfies(code -> assertThat(code).startsWith("(not a code, "));
        RunRecord stepLine = ledger.records().get(1);
        assertThat(stepLine.coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("response-status=(not a code, "));
        assertThat(stepLine.correlation().get(RunRecord.CORR_QUESTIONNAIRE)).startsWith("(not a url, ");
        // The receipt on the result carries the same checked record, not the raw one.
        assertThat(JsonSafe.mapper().writeValueAsString(step.ledger())).doesNotContain("Placeholder-Surname");
    }

    @Test
    void noDtrTypeThatHoldsMemberDataAnAnswerOrAResourcePrintsIt() {
        DtrService service = service(mockClient);
        RunEnvelope<DtrDetail> fetched = service.runPackage(packageRequest("P32H-DTR-ADAPT"), RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> first = service.runNextQuestion(after(fetched, "P32H-DTR-ADAPT"), RunOrigin.manual(), null);
        DtrNextQuestionRequest stepRequest = after(first, "P32H-DTR-ADAPT", DtrAnswerInput.of("mri-1-therapy", "no"));
        RunEnvelope<DtrDetail> second = service.runNextQuestion(stepRequest, RunOrigin.manual(), null);
        DtrResponseRequest responseRequest = DtrResponseRequest.builder().member(member("P32H-DTR-ADAPT"))
                .questionnaireResponse(second.detail().questionnaireResponses().get(0).json())
                .answers(List.of(DtrAnswerInput.of("mri-2-red-flag", ANSWER))).build();
        DtrAnswers.Posted posted = DtrAnswers.read(stepRequest.questionnaire(), stepRequest.questionnaireResponse(), stepRequest.answers());
        DtrRequestBuilder builder = new DtrRequestBuilder(null);
        DtrRequestBuilder.LoopBody loopBody = builder.buildNextQuestion(posted, member("P32H-DTR-ADAPT"), DtrFixtures.CLOCK);
        DtrRequestBuilder.Package built = builder.buildPackage(packageRequest("P32H-DTR-ADAPT"), ResolvedText::plain);
        DtrHandoff handoff = DtrHandoff.afterAnswering(fetched.runId(), List.of("72148"), null, Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE,
                List.of("{\"answer\":\"" + ANSWER + "\"}")));

        Object[] printed = {packageRequest("P32H-DTR-ADAPT"), stepRequest, responseRequest, responseRequest.answers().get(0), posted, loopBody,
                built, handoff, second, second.detail(), second.detail().form(), second.detail().form().answers().get(0),
                second.detail().questionnaires().get(0), second.detail().questionnaireResponses().get(0), second.nextStep(), second.verdict(),
                new DtrReading(second.detail(), DtrOutcome.NEXT_QUESTION, second.additionalFields())};
        for (Object value : printed) {
            assertThat(value.toString()).as(value.getClass().getSimpleName())
                    .doesNotContain(DtrFixtures.SURNAME).doesNotContain(DtrFixtures.BIRTH).doesNotContain("P32H-DTR-ADAPT").doesNotContain(STREET)
                    .doesNotContain(ANSWER).doesNotContain("No (no)").doesNotContain("valueCoding").doesNotContain("resourceType");
        }
        // The request records say how much they hold, which is all a log needs.
        assertThat(stepRequest.toString()).isEqualTo("DtrNextQuestionRequest[answers=1]");
        assertThat(responseRequest.answers().get(0).toString()).isEqualTo("DtrAnswerInput[mri-2-red-flag, 1 value(s)]");
        assertThat(built.toString()).isEqualTo("Package[questionnaires=0, orders=1]");
    }
}
