package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.model.dtr.AnswerView;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireResponseView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DTR service driven through the real scenario files, the real mock client, the real request
 * builder and the real mapper, with a fake gateway, an in-memory ledger and an in-memory code list
 * around them.
 */
class DtrServiceTest {

    private static final String CHAIN = "chain-00000000-0000-4000-8000-000000000001";
    private static final String EARLIER_RUN = "run-00000000-0000-4000-8000-000000000009";
    private static final String SLEEP = "https://example.org/p32h-placeholder/Questionnaire/sleep-study-criteria";
    private static final String MRI = "https://example.org/p32h-placeholder/Questionnaire/lumbar-mri-adaptive|1.0.0";
    private static final String SCOPE_PACKAGE = "This step exercised the $questionnaire-package operation behind a provider-facing SMART "
            + "launch, not the launch itself, which this tool does not host.";
    private static final String SCOPE_NEXT = "This step exercised the next-question operation behind a provider-facing SMART launch, not "
            + "the launch itself, which this tool does not host.";
    private static final String SCOPE_LOCAL = "This step was completed inside this tool and exercised neither a DTR operation nor a "
            + "provider-facing SMART launch, which this tool does not host.";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
    private final OnyxDtrClient mockClient = new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP));

    private RunSupport support(String mode, Map<String, String> tracking) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", mode)), TrackingProperties.of(tracking), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
    }

    private DtrService service(OnyxDtrClient client, String mode, Map<String, String> tracking) {
        RunSupport support = support(mode, tracking);
        return new DtrService(support.epa(), gateway, client, codeList, support);
    }

    private DtrService service() {
        return service(mockClient, "mock", Map.of());
    }

    private RunEnvelope<DtrDetail> fetch(String memberId) {
        return service().runPackage(DtrFixtures.packageRequest(memberId).build(), RunOrigin.manual(), null);
    }

    /**
     * One step of the loop, posted the way the screen posts it: the hand-off of the result before
     * it, and the questionnaire from the package or the response the last step returned. Nothing
     * else is remembered between steps.
     */
    private static DtrNextQuestionRequest.Builder following(RunEnvelope<DtrDetail> previous, String memberId, DtrAnswerInput... answers) {
        DtrDetail detail = previous.detail();
        DtrNextQuestionRequest.Builder request = DtrNextQuestionRequest.builder().member(DtrFixtures.member(memberId))
                .provider(DtrFixtures.provider()).procedureCodes(List.of(CodeInput.of("72148"))).carry(previous.nextStep().carry())
                .chainId(previous.chainId()).answers(List.of(answers));
        return detail.questionnaireResponses().isEmpty() ? request.questionnaire(detail.questionnaires().get(0).json())
                : request.questionnaireResponse(detail.questionnaireResponses().get(0).json());
    }

    private RunEnvelope<DtrDetail> next(RunEnvelope<DtrDetail> previous, DtrAnswerInput... answers) {
        return service().runNextQuestion(following(previous, "P32H-DTR-ADAPT", answers).build(), RunOrigin.manual(), null);
    }

    /** A response step for the questionnaire a package result holds, posted the way the screen posts it. */
    private static DtrResponseRequest.Builder responding(RunEnvelope<DtrDetail> packageResult, String memberId, DtrAnswerInput... answers) {
        return DtrResponseRequest.builder().member(DtrFixtures.member(memberId)).provider(DtrFixtures.provider())
                .procedureCodes(List.of(CodeInput.of("95810"))).carry(packageResult.nextStep().carry()).chainId(packageResult.chainId())
                .questionnaire(packageResult.detail().questionnaires().get(0).json()).answers(List.of(answers));
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

    private static List<String> linkIds(List<QuestionRef> questions) {
        return questions.stream().map(QuestionRef::linkId).toList();
    }

    private static Map<String, List<String>> answerSet(FormStateView form) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (AnswerView answer : form.answers()) {
            out.put(answer.linkId(), answer.values());
        }
        return out;
    }

    // ---- the package: the result answers the four questions ----

    @Test
    void aPackageFetchReturnsTheVerdictTheKindTheTestResultTheNextStepAndTheLedgerReceipt() {
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR")
                .expected("PACKAGE_LOADED; questionnaire-kind=static").testerRef("QA-17").note("first fetch").chainId(CHAIN).build(),
                RunOrigin.manual(), null);

        // What happened, with the kind of questionnaire stated beside it and the reason it was called that.
        assertThat(result.verdict().headline()).isEqualTo("The questionnaire package was loaded with one questionnaire.");
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.verdict().tone()).isEqualTo(Tone.ACTION);
        assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario dtr-01-static-linear answered");
        assertThat(result.verdict().supporting()).contains("Sleep study criteria (placeholder): static, because it declares the standard "
                + "questionnaire profile http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-std-questionnaire. It holds 4 questions, "
                + "3 of them required.");
        assertThat(result.detail().questionnaires().get(0).kind()).isEqualTo(QuestionnaireKind.STATIC);
        assertThat(result.detail().kindRule()).startsWith("A questionnaire is adaptive when");
        // Nobody may take this for a SMART launch.
        assertThat(result.verdict().supporting()).last().isEqualTo(SCOPE_PACKAGE);
        // What did I expect, and did it match.
        assertThat(result.expectation().expected()).isEqualTo("PACKAGE_LOADED; questionnaire-kind=static");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // What do I do next.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(result.nextStep().headline()).isEqualTo("Next: answer the questionnaire and submit the response.");
        // And it is recorded, under the same run id the screen shows.
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(ledger.only().runId()).isEqualTo(result.runId());
        assertThat(result.protocol()).isEqualTo(Protocol.DTR);
        assertThat(result.operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(result.chainId()).isEqualTo(CHAIN);
        assertThat(result.profile()).isEqualTo("mock");
        assertThat(result.error()).isNull();
        assertThat(result.timing().slow()).isFalse();
        // The grid holds the coded facts under the names an expectation uses.
        assertThat(result.determination()).extracting(DeterminationRow::system, DeterminationRow::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("questionnaire-kind", "static"),
                        org.assertj.core.groups.Tuple.tuple("questionnaire-status", "active"),
                        org.assertj.core.groups.Tuple.tuple("questions", "4"));
    }

    @Test
    void theLedgerRecordOfAPackageFetchCarriesEveryFieldTheSpecificationListsAsAlways() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").expected("PACKAGE_LOADED")
                .testerRef("QA-17").note("package check").build(), RunOrigin.manual(), null);
        RunRecord record = ledger.only();

        assertThat(record.runId()).startsWith("run-").isEqualTo(result.runId());
        assertThat(record.timestamp()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(record.protocol()).isEqualTo(Protocol.DTR);
        assertThat(record.operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(record.target()).isEqualTo("onyx");
        assertThat(record.profile()).isEqualTo("mock");
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(record.scenarioId()).isEqualTo("dtr-01-static-linear");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(record.outcome()).isEqualTo("PACKAGE_LOADED");
        assertThat(record.coverageCodes()).containsExactly("questionnaires=1", "questionnaire-kind=static", "questionnaire-status=active",
                "questions=4");
        assertThat(record.delegatedVendor()).isNull();
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(record.errorType()).isEqualTo("NONE");
        assertThat(record.expected()).isEqualTo("PACKAGE_LOADED");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.note()).isEqualTo("package check");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.nextAction()).isEqualTo("SUBMIT_RESPONSE");
        assertThat(record.memberId()).isEqualTo("P32H-DOC-DTR");
        assertThat(record.npi()).isEqualTo("1234567893");
        assertThat(record.dateOfService()).isEqualTo("2026-03-20");
        // The questionnaire that was fetched ties this line to the steps that answer it.
        assertThat(record.correlation()).containsOnly(Map.entry(RunRecord.CORR_QUESTIONNAIRE, SLEEP + "|1.0.0"));
        // Started cold from the form, so the line is enough to run the same request again.
        assertThat(record.unrecordedInputs()).isEmpty();
    }

    @Test
    void eachKindOfQuestionnaireIsStatedAndDecidesTheNextStep() {
        RunEnvelope<DtrDetail> adaptive = fetch("P32H-DTR-ADAPT");
        assertThat(adaptive.detail().questionnaires().get(0).kind()).isEqualTo(QuestionnaireKind.ADAPTIVE);
        assertThat(adaptive.verdict().supporting()).anySatisfy(line -> assertThat(line).startsWith("Lumbar spine MRI criteria, adaptive "
                + "(placeholder): adaptive, because it carries the questionnaireAdaptive extension set to a url.")
                .endsWith("It holds no questions of its own: the service asks them one step at a time."));
        assertThat(adaptive.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(adaptive.nextStep().headline()).isEqualTo("Next: start the adaptive questionnaire with next-question.");

        RunEnvelope<DtrDetail> two = fetch("P32H-DTR-TWO");
        assertThat(two.verdict().headline()).isEqualTo("The questionnaire package was loaded with 2 questionnaires.");
        assertThat(two.nextStep().action()).isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(two.nextStep().headline()).isEqualTo("Next: answer each questionnaire and submit its response.");
        // Nothing is reduced to the first: both are listed, named, and carried on.
        assertThat(two.detail().questionnaires()).hasSize(2);
        assertThat(two.determination()).extracting(DeterminationRow::subject).containsOnly("Cardiology criteria (placeholder)",
                "Musculoskeletal criteria (placeholder)");
        assertThat(two.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(
                "https://example.org/p32h-placeholder/Questionnaire/cardiology-criteria|1.0.0",
                "https://example.org/p32h-placeholder/Questionnaire/musculoskeletal-criteria|1.0.0");
        assertThat(ledger.last().coverageCodes()).contains("questionnaires=2", "questionnaire[1]: questionnaire-status=active",
                "questionnaire[2]: questionnaire-status=draft");

        // A questionnaire with no marker and no items: the tool says it cannot tell, and does not pick.
        String unmarked = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\","
                + "\"url\":\"https://example.org/p32h-placeholder/Questionnaire/unmarked\",\"status\":\"active\"}}]}";
        RunEnvelope<DtrDetail> undetermined = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, unmarked)), "mock",
                Map.of()).runPackage(DtrFixtures.packageRequest("S123456789").expected("questionnaire-kind=undetermined").build(),
                RunOrigin.manual(), null);
        assertThat(undetermined.detail().questionnaires().get(0).kind()).isEqualTo(QuestionnaireKind.UNDETERMINED);
        assertThat(undetermined.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("undetermined, because it carries no "
                + "adaptive marker and no items"));
        assertThat(undetermined.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(undetermined.nextStep().reason()).isEqualTo("This tool cannot tell whether the questionnaire is adaptive or static, and "
                + "it does not pick one.");
        assertThat(undetermined.expectation().result()).isEqualTo(TestResult.PASS);
    }

    // ---- the distinction this tool exists for, on screen and in the ledger ----

    @Test
    void noQuestionnaireAndNothingRequiredGiveTwoDifferentScreensAndTwoDifferentLedgerRows() {
        RunEnvelope<DtrDetail> empty = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-EMPTY").expected("NOTHING_REQUIRED").build(),
                RunOrigin.manual(), null);
        RunEnvelope<DtrDetail> askedNothing = service().runNextQuestion(following(fetch("P32H-DTR-NOTHING"), "P32H-DTR-NOTHING")
                .expected("NOTHING_REQUIRED").build(), RunOrigin.manual(), null);

        // Two screens.
        assertThat(empty.verdict().headline()).isEqualTo("The service answered but returned no questionnaire.");
        assertThat(empty.verdict().supporting()).contains("The package holds 1 bundle and no questionnaire in it. The DTR guide does not "
                + "provide for that: a package bundle starts with its questionnaire.", "This tool reports what arrived. It does not turn an "
                + "empty package into an answer that no documentation is needed.");
        assertThat(empty.verdict().tone()).isEqualTo(Tone.NO_ANSWER);
        assertThat(empty.nextStep().headline()).isEqualTo("Next: read the response detail below, and check it against what CRD said about "
                + "documentation for this order.");
        assertThat(empty.nextStep().reason()).isEqualTo("The service returned no questionnaire. This tool does not take that as an answer "
                + "that no documentation is needed, so it cannot say what should happen next.");
        assertThat(askedNothing.verdict().headline()).isEqualTo("The response is complete and no question was asked.");
        assertThat(askedNothing.verdict().tone()).isEqualTo(Tone.CLEAR);

        // The grid of the empty package still lists what would have answered, as not returned.
        assertThat(empty.determination()).extracting(DeterminationRow::label, DeterminationRow::presence)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Questionnaire kind", Presence.ABSENT),
                        org.assertj.core.groups.Tuple.tuple("Questionnaire status", Presence.ABSENT));
        assertThat(askedNothing.determination()).filteredOn(row -> row.label().equals("Completion status")).singleElement()
                .satisfies(row -> assertThat(row.code()).isEqualTo("completed"));

        // One ends in a review, because nothing was said. The other carries a completed response on to PAS.
        assertThat(empty.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(empty.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(askedNothing.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(askedNothing.nextStep().carry()).containsKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);

        // The same expectation passes for one and fails for the other.
        assertThat(askedNothing.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(empty.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(empty.expectation().explanation()).isEqualTo("Expected \"The response is complete and no question was asked\" but the "
                + "outcome was \"The service returned no questionnaire\".");

        // Two ledger rows. Neither is a transport failure: the difference is in what the service said.
        RunRecord emptyRecord = ledger.records().get(0);
        RunRecord askedNothingRecord = ledger.last();
        assertThat(emptyRecord.outcome()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(askedNothingRecord.outcome()).isEqualTo("NOTHING_REQUIRED");
        assertThat(emptyRecord.coverageCodes()).isEmpty();
        assertThat(askedNothingRecord.coverageCodes()).containsExactly("response-status=completed", "questions=0", "answered=0",
                "required-unanswered=0");
        assertThat(emptyRecord.result()).isEqualTo(TestResult.FAIL);
        assertThat(askedNothingRecord.result()).isEqualTo(TestResult.PASS);
        assertThat(List.of(emptyRecord.errorType(), askedNothingRecord.errorType())).containsOnly("NONE");
        assertThat(List.of(emptyRecord.httpStatus(), askedNothingRecord.httpStatus())).containsOnly(200);
        assertThat(emptyRecord.nextAction()).isEqualTo("REVIEW");
        assertThat(askedNothingRecord.nextAction()).isEqualTo("START_PAS");
    }

    @Test
    void aPackageWithNoBundleInItIsTheAnswerTheGuideDefinesAndIsStillReportedAsAnEmptyPackage() {
        RunEnvelope<DtrDetail> none = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-NOBUNDLE").expected("NOTHING_REQUIRED").build(),
                RunOrigin.manual(), null);
        assertThat(none.verdict().outcomeCode()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(none.verdict().headline()).isEqualTo("The service answered but returned no questionnaire.");
        // The result says what the guide makes of this shape, and that the tool does not make it for the tester.
        assertThat(none.verdict().supporting()).contains("The DTR guide, from STU 2.1.0 on, has a service return no package bundle when no "
                + "questionnaire needs to be completed.", "This tool reports what arrived. It does not turn an empty package into an answer "
                + "that no documentation is needed.");
        assertThat(none.verdict().supporting()).noneMatch(line -> line.contains("a package bundle starts with its questionnaire"));
        assertThat(none.detail().bundles()).isEmpty();
        assertThat(none.detail().responseNote()).isEqualTo("The package arrived as a Parameters resource holding: operationOutcome "
                + "(OperationOutcome).");
        // What the service sent beside it is read into the designed view, and it is not a fault.
        assertThat(none.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.severity().value()).isEqualTo("information");
            assertThat(issue.detailsText().value()).isEqualTo("No questionnaire needs to be completed for this order.");
        });
        assertThat(none.error()).isNull();
        assertThat(none.additionalFields()).isEmpty();
        // It is never the answer that nothing is required: the expectation fails, and the journey stops for a person to look.
        assertThat(none.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(none.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(none.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(ledger.last().outcome()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(ledger.last().coverageCodes()).isEmpty();

        // A questionnaire that was asked for by name and did not come back is named, not left to be noticed.
        String wanted = "https://example.org/p32h-placeholder/Questionnaire/wanted";
        RunEnvelope<DtrDetail> asked = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-NOBUNDLE").questionnaires(List.of(wanted))
                .build(), RunOrigin.manual(), null);
        assertThat(asked.verdict().outcomeCode()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(asked.verdict().supporting()).contains("Asked for and not returned: " + wanted + ".");

        // An OperationOutcome by itself under a success status holds no questionnaire either. It is not the shape the guide
        // defines for that answer, so the result does not say it is.
        RunEnvelope<DtrDetail> bare = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200,
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"warning\",\"code\":\"processing\"}]}")), "mock", Map.of())
                .runPackage(DtrFixtures.packageRequest("S123456789").build(), RunOrigin.manual(), null);
        assertThat(bare.verdict().outcomeCode()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(bare.verdict().supporting()).noneMatch(line -> line.contains("The DTR guide"))
                .contains("This tool reports what arrived. It does not turn an empty package into an answer that no documentation is needed.");
    }

    @Test
    void aQuestionnaireThatWasAskedForAndNotReturnedIsNeverCoveredByTheOnesThatWere() {
        String other = "https://example.org/p32h-placeholder/Questionnaire/another-questionnaire";
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").questionnaires(List.of(SLEEP, other))
                .expected("questionnaire-kind=static").build(), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_INCOMPLETE");
        assertThat(result.verdict().headline()).isEqualTo("The package does not hold every questionnaire that was asked for.");
        assertThat(result.verdict().supporting()).contains("Asked for and not returned: " + other + ".");
        assertThat(result.detail().requested()).containsExactly(SLEEP, other);
        assertThat(result.detail().notReturned()).containsExactly(other);
        // The one that came back is static. That does not make the assertion hold for the one that did not.
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(result.expectation().explanation()).contains("no answer was returned for " + other);
        assertThat(result.determination()).filteredOn(row -> other.equals(row.subject())).extracting(DeterminationRow::presence)
                .containsExactly(Presence.ABSENT, Presence.ABSENT);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        // Typed on the form, so a replay from the line would not ask for the same questionnaires.
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
    }

    // ---- the chain: what CRD handed on, and a step started cold ----

    @Test
    void aPackageRequestIsBuiltFromWhatCrdHandedOnAndSaysWhichOfItWasUsed() {
        // The real CRD service on the real CRD scenario that asks for documentation, so the keys
        // one side writes are the keys the other side reads.
        RunSupport support = support("mock", Map.of());
        CrdService crd = new CrdService(support.epa(), gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(DtrFixtures.APP)), codeList,
                support);
        RunEnvelope<CrdDetail> crdResult = crd.run(CrdRunRequest.builder().member(DtrFixtures.member("P32H-DOC-DTR"))
                .provider(DtrFixtures.provider()).procedureCodes(List.of(CodeInput.of("95810"))).dateOfService("2026-03-20").chainId("new")
                .build(), RunOrigin.manual(), null);
        assertThat(crdResult.nextStep().action()).isEqualTo(NextAction.START_DTR);
        Map<String, List<String>> handedOn = crdResult.nextStep().carry();
        String assertionId = handedOn.get(NextStepView.CARRY_COVERAGE_ASSERTION_ID).get(0);

        // The DTR form names no code, no questionnaire and no assertion id: everything comes from CRD.
        RunEnvelope<DtrDetail> result = service().runPackage(DtrPackageRequest.builder().member(DtrFixtures.member("P32H-DOC-DTR"))
                .provider(DtrFixtures.provider()).dateOfService("2026-03-20").chainId(crdResult.chainId()).carry(handedOn).build(),
                RunOrigin.manual(), null);

        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.get("resourceType").asString()).isEqualTo("Parameters");
        Map<String, List<JsonNode>> parameters = new LinkedHashMap<>();
        sent.get("parameter").forEach(parameter -> parameters.computeIfAbsent(parameter.get("name").asString(), key -> new ArrayList<>())
                .add(parameter));
        // The carried canonical, the carried assertion id, and the order under the id CRD sent it with.
        assertThat(parameters.get("questionnaire")).singleElement()
                .satisfies(parameter -> assertThat(parameter.get("valueCanonical").asString()).isEqualTo(SLEEP));
        assertThat(parameters.get("context")).singleElement()
                .satisfies(parameter -> assertThat(parameter.get("valueString").asString()).isEqualTo(assertionId));
        assertThat(parameters.get("order")).singleElement().satisfies(parameter -> {
            assertThat(parameter.at("/resource/id").asString()).isEqualTo("service-request-1");
            assertThat(parameter.at("/resource/code/coding/0/code").asString()).isEqualTo("95810");
            assertThat(parameter.at("/resource/insurance/0/reference").asString()).isEqualTo("Coverage/coverage-1");
        });
        assertThat(parameters.get("coverage")).singleElement()
                .satisfies(parameter -> assertThat(parameter.at("/resource/id").asString()).isEqualTo("coverage-1"));

        // The result says which of what CRD handed on was used.
        assertThat(result.detail().requestNotes()).contains(
                "The procedure codes CRD was asked about were used, because the form names none: 95810.",
                "The order CRD's answer applied to was sent, under the id CRD sent it with.",
                "The request asks for the questionnaire CRD named: " + SLEEP + ".",
                "The coverage assertion id CRD handed on was sent as the context.");
        assertThat(result.detail().requestNotes()).anySatisfy(note -> assertThat(note).contains("SMART link").contains("hosts no SMART launch"));

        // The payer returned the questionnaire that was asked for, and the journey stays one chain.
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.detail().requested()).containsExactly(SLEEP);
        assertThat(result.detail().notReturned()).isEmpty();
        assertThat(result.chainId()).isEqualTo(crdResult.chainId());
        RunRecord record = ledger.last();
        assertThat(record.chainId()).isEqualTo(crdResult.chainId());
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        // What CRD said still reaches the step after this one, and the questionnaire and the run are this step's own.
        Map<String, List<String>> carry = result.nextStep().carry();
        assertThat(carry.get(NextStepView.CARRY_COVERAGE_ASSERTION_ID)).containsExactly(assertionId);
        assertThat(carry.get(NextStepView.CARRY_ORDER)).containsExactly("ServiceRequest/service-request-1");
        assertThat(carry.get(NextStepView.CARRY_PROCEDURE_CODES)).containsExactly("95810");
        assertThat(carry.get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(SLEEP + "|1.0.0");
        assertThat(carry.get(NextStepView.CARRY_RUN_ID)).containsExactly(result.runId());
    }

    @Test
    void aPackageRequestStartedColdIsBuiltFromTheFormAloneAndSaysWhatItHadToLeaveOut() {
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("S12345678   01").build(), RunOrigin.manual(), null);

        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        List<String> names = new ArrayList<>();
        sent.get("parameter").forEach(parameter -> names.add(parameter.get("name").asString()));
        // No questionnaire and no context: nothing was handed on and nothing was typed.
        assertThat(names).containsExactly("coverage", "order", "referenced", "referenced", "referenced");
        assertThat(result.detail().requestNotes()).contains(
                "The request names no questionnaire, so the service chooses from the coverage and the order.",
                "No context was sent: no coverage assertion id was handed on by CRD or given on the form.");
        // The member ID goes out without its spaces, and both forms are shown.
        assertThat(sent.at("/parameter/0/resource/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(result.wire().request().body()).doesNotContain("S12345678   01");
        assertThat(result.wire().memberId().typed()).isEqualTo("S12345678   01");
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.wire().memberId().changed()).isTrue();
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.detail().bodyBuiltByTool()).isTrue();
        assertThat(ledger.only().memberId()).isEqualTo("S1234567801");
        assertThat(ledger.only().unrecordedInputs()).isEmpty();
        assertThat(ledger.only().correlation()).doesNotContainKey(RunRecord.CORR_RUN_ID);
    }

    @Test
    void aValueTypedOnTheFormWinsOverTheSameThingHandedOn() {
        Map<String, List<String>> handedOn = Map.of(NextStepView.CARRY_QUESTIONNAIRE, List.of(SLEEP),
                NextStepView.CARRY_COVERAGE_ASSERTION_ID, List.of("assertion-from-crd"),
                NextStepView.CARRY_PROCEDURE_CODES, List.of("95811"));
        String typed = "https://example.org/p32h-placeholder/Questionnaire/typed-on-the-form";
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").questionnaires(List.of(typed))
                .coverageAssertionId("assertion-typed").carry(handedOn).build(), RunOrigin.manual(), null);
        String body = result.wire().request().body();
        assertThat(body).contains(typed).contains("assertion-typed").contains("95810")
                .doesNotContain("assertion-from-crd").doesNotContain("95811").doesNotContain(SLEEP);
        assertThat(result.detail().requestNotes()).contains("The request asks for the questionnaire given on the form: " + typed + ".",
                "The coverage assertion id given on the form was sent as the context.");
    }

    @Test
    void aQuestionnaireCanBeAskedForByItsCanonicalUrlAloneWithNoOrder() {
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").procedureCodes(List.of())
                .questionnaires(List.of(SLEEP)).build(), RunOrigin.manual(), null);
        assertThat(result.wire().request().body()).doesNotContain("ServiceRequest");
        assertThat(result.detail().requestNotes()).anySatisfy(note -> assertThat(note).startsWith("No order was sent"));
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(ledger.only().procedureCodes()).isEmpty();
    }

    // ---- not found, faults ----

    @Test
    void notFoundIsPresentedAsAFindingAboutTestDataAndAFaultAsAFindingAboutTheEnvironment() {
        RunEnvelope<DtrDetail> notFound = fetch("P32H-DTR-NOMEM");
        assertThat(notFound.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(notFound.verdict().headline()).isEqualTo("The service does not recognise this member.");
        assertThat(notFound.verdict().supporting()).contains("This is a finding about the test data, not about the environment.");
        assertThat(notFound.nextStep().action()).isEqualTo(NextAction.FIX_TEST_DATA);
        // No error panel: the service answered. Its issues are still shown in the detail.
        assertThat(notFound.error()).isNull();
        assertThat(notFound.detail().issues()).hasSize(1);
        assertThat(fetch("P32H-DTR-NOPROV").nextStep().headline()).isEqualTo("Next: check the NPI against the test data.");

        RunEnvelope<DtrDetail> serverError = fetch("P32H-DTR-ERR500");
        assertThat(serverError.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(serverError.verdict().headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(serverError.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.error().httpStatus()).isEqualTo(500);
        // The OperationOutcome is rendered issue by issue, in the error panel and in the detail.
        assertThat(serverError.error().issues()).singleElement().satisfies(issue -> assertThat(issue.code().value()).isEqualTo("exception"));
        assertThat(serverError.detail().responseKind()).isEqualTo(DtrResponseKind.OPERATION_OUTCOME);

        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("MEMBER_NOT_FOUND", "PROVIDER_NOT_FOUND", "SERVICE_ERROR");
        assertThat(ledger.last().errorType()).isEqualTo("SERVER_ERROR");
    }

    @Test
    void everyKindOfFaultIsDistinguishedInTheErrorPanelAndKeepsWhatArrived() {
        assertThat(fetch("P32H-DTR-BAD400").error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(fetch("P32H-DTR-ERR500").error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        RunEnvelope<DtrDetail> timeout = fetch("P32H-DTR-TIMEOUT");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.error().httpStatus()).isNull();
        assertThat(timeout.verdict().headline()).isEqualTo("The service did not answer in time.");
        assertThat(timeout.detail().responseKind()).isEqualTo(DtrResponseKind.NO_RESPONSE);
        DtrPackageRequest ordinary = DtrFixtures.packageRequest("S123456789").build();
        RunEnvelope<DtrDetail> down = service(stub(TestExchanges.unavailable(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)), "mock", Map.of())
                .runPackage(ordinary, RunOrigin.manual(), null);
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        RunEnvelope<DtrDetail> garbled = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200,
                "{\"resourceType\": \"Parameters\", \"parameter\": [")), "mock", Map.of()).runPackage(ordinary, RunOrigin.manual(), null);
        assertThat(garbled.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(garbled.verdict().headline()).isEqualTo("The service answered with a body that could not be read (HTTP 200).");
        // The raw bytes are kept for the screen even though they could not be parsed, and not only in the wire view.
        assertThat(garbled.wire().response().body()).isEqualTo("{\"resourceType\": \"Parameters\", \"parameter\": [");
        assertThat(garbled.wire().response().parsed()).isFalse();
        assertThat(garbled.additionalFields()).singleElement()
                .satisfies(field -> assertThat(field.value()).isEqualTo("{\"resourceType\": \"Parameters\", \"parameter\": ["));
        RunEnvelope<DtrDetail> noBody = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, null)), "mock", Map.of())
                .runPackage(ordinary, RunOrigin.manual(), null);
        assertThat(noBody.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(noBody.verdict().headline()).isEqualTo("The service answered with no body (HTTP 200).");
        assertThat(ledger.records()).extracting(RunRecord::errorType)
                .containsExactly("BAD_REQUEST", "SERVER_ERROR", "TIMEOUT", "UNAVAILABLE", "MALFORMED_RESPONSE", "MALFORMED_RESPONSE");
        assertThat(ledger.records()).extracting(RunRecord::nextAction)
                .containsExactly("FIX_REQUEST", "CHECK_ENVIRONMENT", "CHECK_ENVIRONMENT", "CHECK_ENVIRONMENT", "CHECK_ENVIRONMENT", "CHECK_ENVIRONMENT");
    }

    @Test
    void aPackageThatCannotBeUsedIsAFaultOfWhatWasSentAndIsShownInFull() {
        RunEnvelope<DtrDetail> result = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-MALFORMED").expected("PACKAGE_LOADED")
                .build(), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("MALFORMED_QUESTIONNAIRE");
        assertThat(result.verdict().headline()).isEqualTo("The service answered, but what it sent cannot be used as a questionnaire.");
        assertThat(result.verdict().supporting()).contains(
                "Malformed criteria (placeholder): item[0] has no linkId, so an answer cannot be tied to it.",
                "Malformed criteria (placeholder): The linkId \"dup-1\" is used by 2 items, so an answer cannot be tied to one of them.");
        // It arrived with a success status, so the error panel says what is wrong with it and not that the call failed.
        assertThat(result.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(result.error().title()).isEqualTo("The service answered with HTTP 200, but what it sent cannot be used as a questionnaire.");
        assertThat(result.error().httpStatus()).isEqualTo(200);
        // The questionnaire is still shown in full, with every item it holds.
        assertThat(result.detail().questionnaires().get(0).items()).hasSize(3);
        assertThat(result.detail().questionnaires().get(0).usable()).isFalse();
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(ledger.only().outcome()).isEqualTo("MALFORMED_QUESTIONNAIRE");
        assertThat(ledger.only().errorType()).isEqualTo("NONE");
    }

    @Test
    void aRequestThatWasNotAcceptedPointsAtTheLikelyCauseForItsStatus() {
        DtrPackageRequest request = DtrFixtures.packageRequest("S123456789").build();
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"security\"}]}";
        RunEnvelope<DtrDetail> unauthorised = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 401, outcome)), "mock",
                Map.of()).runPackage(request, RunOrigin.manual(), null);
        assertThat(unauthorised.nextStep().action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(unauthorised.nextStep().headline()).isEqualTo("Next: check the credentials on the Config tab.");
        RunEnvelope<DtrDetail> missing = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 404, "<html>Not Found</html>")),
                "mock", Map.of()).runPackage(request, RunOrigin.manual(), null);
        assertThat(missing.verdict().outcomeCode()).isEqualTo("REJECTED");
        assertThat(missing.nextStep().headline()).isEqualTo("Next: check the DTR url on the Config tab.");
        assertThat(fetch("P32H-DTR-BAD400").nextStep().action()).isEqualTo(NextAction.FIX_REQUEST);
    }

    // ---- validation: nothing sent, nothing recorded ----

    @Test
    void aPackageFormThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        AtomicInteger calls = new AtomicInteger();
        OnyxDtrClient counting = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, "{\"resourceType\":\"Parameters\"}");
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, "{\"resourceType\":\"QuestionnaireResponse\"}");
            }
        };
        DtrService service = service(counting, "mock", Map.of());

        assertInvalidPackage(service, DtrFixtures.packageRequest(" ").build(), "member.memberId");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").member(null).build(), "member.memberId");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").provider(ProviderDetail.builder().npi("1234567890").build()).build(),
                "provider.npi");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").provider(null).build(), "provider.npi");
        // Neither a code nor a questionnaire, on the form or handed on: there is nothing to ask about.
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").procedureCodes(List.of()).build(), "procedureCodes");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").procedureCodes(List.of(CodeInput.of(" "))).build(),
                "procedureCodes[0].code");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").dateOfService("03/20/2026").build(), "dateOfService");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").member(MemberDetail.builder().memberId("S123456789")
                .dateOfBirth("2026-03-11").build()).build(), "member.dateOfBirth");
        // An expectation this protocol cannot produce, and a misspelt element name.
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").expected("NO_AUTH_REQUIRED").build(), "expected");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").expected("questionnaire_kind=static").build(), "expected");
        // A questionnaire is named by a canonical url. Free text there would be sent, and written to the ledger.
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").questionnaires(List.of("the sleep study form")).build(),
                "questionnaires[0]");
        // So would a run id that is not one this tool minted, and a chain id of free text.
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789")
                .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of("Placeholder-Surname follow-up"))).build(), "carry.runId");
        assertInvalidPackage(service, DtrFixtures.packageRequest("S123456789").chainId("chain-1").build(), "chainId");

        assertThat(calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void everyProblemWithThePackageFormIsReportedAtOnce() {
        assertThatThrownBy(() -> service().runPackage(DtrPackageRequest.builder().dateOfService("tomorrow").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field)
                        .containsExactly("member.memberId", "provider.npi", "procedureCodes", "dateOfService"));
    }

    @Test
    void aRequestFieldTheToolDoesNotKnowIsRefusedAndNeverQuietlyDropped() {
        // "questionnaire" for "questionnaires": dropped silently, the request would ask for none.
        DtrPackageRequest misspelt = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-DOC-DTR\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"questionnaire\":[\"" + SLEEP + "\"]}",
                DtrPackageRequest.class);
        assertThat(misspelt.unrecognised()).containsOnlyKeys("questionnaire");
        assertThatThrownBy(() -> service().runPackage(misspelt, RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("questionnaire");
                    assertThat(error.message()).contains("does not know the field \"questionnaire\"");
                }));
        assertThat(ledger.records()).isEmpty();

        // The same request spelt correctly binds every field it posts, including the ones a chain uses.
        DtrPackageRequest correct = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"P32H-DOC-DTR\"},"
                + "\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],\"questionnaires\":[\"" + SLEEP + "\"],"
                + "\"coverageAssertionId\":\"assertion-1\",\"expected\":\"PACKAGE_LOADED\",\"carry\":{\"procedureCodes\":[\"95810\"]},"
                + "\"configEpoch\":0}", DtrPackageRequest.class);
        assertThat(correct.unrecognised()).isEmpty();
        assertThat(correct.questionnaires()).containsExactly(SLEEP);
        assertThat(correct.configEpoch()).isZero();
        assertThat(service().runPackage(correct, RunOrigin.manual(), null).expectation().result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void aScenarioCannotBeForcedOnLive() {
        assertInvalidPackage(service(mockClient, "live", Map.of()), DtrFixtures.packageRequest("S123456789").scenarioId("dtr-07-no-questionnaire")
                .build(), "scenarioId");
        // On mock the same request runs and answers from the chosen scenario, whatever the member.
        RunEnvelope<DtrDetail> forced = service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").scenarioId("dtr-07-no-questionnaire")
                .build(), RunOrigin.manual(), null);
        assertThat(forced.verdict().outcomeCode()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(ledger.only().scenarioId()).isEqualTo("dtr-07-no-questionnaire");
    }

    private void assertInvalidPackage(DtrService service, DtrPackageRequest request, String field) {
        assertThatThrownBy(() -> service.runPackage(request, RunOrigin.manual(), null))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    // ---- the gateway: the configuration the screen was drawn from, the guardrail, a missing target ----

    private static ProdWriteBlockedException blockedWrite() {
        return new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "PAS submit sample"), "POST", "PAS submit sample");
    }

    @Test
    void aRunIsRefusedWhenTheConfigurationChangedAfterItsScreenWasDrawn() {
        gateway.changeConfiguration();
        assertThatThrownBy(() -> service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").configEpoch(0L).build(), RunOrigin.manual(), null))
                .isInstanceOf(ConfigurationChangedException.class).hasMessageContaining("Reload");
        assertThat(ledger.records()).isEmpty();
        assertThat(service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").configEpoch(1L).build(), RunOrigin.manual(), null)
                .ledger().recorded()).isTrue();
        assertThat(gateway.lastIntent().expectedEpoch()).isEqualTo(1L);
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
    }

    @Test
    void aWriteTheGuardrailRefusesIsRecordedAndReturnedAsAResultThatSaysNothingWasSent() {
        // Both DTR operations are reads, but a tester can point a run at any collection request,
        // and the gateway judges that request for what it is.
        gateway.failing(blockedWrite());
        AtomicInteger calls = new AtomicInteger();
        OnyxDtrClient counting = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, "{}", true);
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return TestExchanges.of(EpaOperation.DTR_NEXT_QUESTION, 200, "{}", true);
            }
        };
        RunEnvelope<DtrDetail> result = service(counting, "live", Map.of()).runPackage(DtrFixtures.packageRequest("S12345678   01")
                .expected("PACKAGE_LOADED").testerRef("QA-17").build(), RunOrigin.manual(), null);

        assertThat(calls.get()).isZero();
        assertThat(RunSupport.isBlocked(result)).isTrue();
        assertThat(result.verdict().outcomeCode()).isEqualTo("BLOCKED");
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        RunRecord record = ledger.only();
        assertThat(record.operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(record.outcome()).isEqualTo("BLOCKED");
        assertThat(record.errorType()).isEqualTo(RunRecord.ERROR_BLOCKED);
        assertThat(record.testerRef()).isEqualTo("QA-17");

        // The preview of the same request shows where it would go and says it is blocked.
        RequestPreview preview = service(counting, "live", Map.of()).previewPackage(DtrFixtures.packageRequest("S123456789").build(), null);
        assertThat(preview.blocked()).startsWith("Writes to the production environment Onyx Production are blocked.");
        assertThat(preview.body()).isNull();
        assertThat(ledger.records()).hasSize(1);
    }

    @Test
    void theSessionsUnlockIsHandedToTheGatewayAndNothingElseDecidesWhetherItHolds() {
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").build(), RunOrigin.manual(), unlock);
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);
        service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").build(), RunOrigin.manual(), null);
        assertThat(gateway.lastIntent().unlock()).isNull();
    }

    @Test
    void aTargetTheGatewayRefusesIsNotSentAndNotRecorded() {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "There is no url for the DTR questionnaire package."));
        assertThatThrownBy(() -> fetch("P32H-DOC-DTR")).isInstanceOf(EndpointNotConfiguredException.class);
        assertThat(ledger.records()).isEmpty();
    }

    // ---- the ledger ----

    @Test
    void aLedgerThatCannotWriteIsReportedOnTheResultAndTheResultIsStillReturned() {
        ledger.refusing("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written.");
        RunEnvelope<DtrDetail> result = fetch("P32H-DOC-DTR");
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded");
        assertThat(result.ledger().record().runId()).isEqualTo(result.runId());
    }

    @Test
    void aRunFromASuiteOrAReplayCarriesItsOrigin() {
        service().runPackage(DtrFixtures.packageRequest("P32H-DOC-DTR").build(), new RunOrigin("suite-1", "case-7", "run-earlier"), null);
        assertThat(ledger.only().suiteRunId()).isEqualTo("suite-1");
        assertThat(ledger.only().caseId()).isEqualTo("case-7");
        assertThat(ledger.only().replayOfRunId()).isEqualTo("run-earlier");
    }

    /** A parsed body that fails the moment the mapper reads it, standing in for a defect in this tool's own reading. */
    private static VendorExchange unreadable(EpaOperation operation) {
        ObjectNode exploding = new ObjectNode(JsonNodeFactory.instance) {
            @Override
            public JsonNode get(String propertyName) {
                throw new IllegalStateException("the reading exploded on Placeholder-Surname");
            }
        };
        return DtrFixtures.answered(operation, 200, "{\"resourceType\":\"Parameters\",\"note\":\"as received\"}").withJson(exploding);
    }

    @Test
    void aFailureOfThisToolAfterTheCallStillLeavesALedgerLineAndShowsWhatWasSentAndReceived() {
        RunEnvelope<DtrDetail> result = service(stub(unreadable(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)), "mock", Map.of())
                .runPackage(DtrFixtures.packageRequest("S123456789").questionnaires(List.of(SLEEP)).expected("PACKAGE_LOADED")
                        .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of(EARLIER_RUN))).build(), RunOrigin.manual(), null);

        // The service was called, so the run happened and is recorded as a failure of the tool.
        RunRecord record = ledger.only();
        assertThat(record.outcome()).isEqualTo("TOOL_ERROR");
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.nextAction()).isEqualTo("REVIEW");
        assertThat(record.memberId()).isEqualTo("S123456789");
        // What the request itself established is on the line all the same.
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, SLEEP).containsEntry(RunRecord.CORR_RUN_ID, EARLIER_RUN);

        assertThat(RunSupport.isToolError(result)).isTrue();
        assertThat(result.runId()).isEqualTo(record.runId());
        assertThat(result.verdict().outcomeCode()).isEqualTo("TOOL_ERROR");
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.INTERNAL);
        assertThat(result.error().reference()).isNotBlank();
        // The exception's own message is never shown: it can quote a body.
        assertThat(result.error().title() + result.error().action() + result.verdict().headline()).doesNotContain("exploded")
                .doesNotContain(DtrFixtures.SURNAME);
        assertThat(result.wire().request()).isNotNull();
        assertThat(result.wire().response().body()).contains("as received");
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.detail()).isNull();
    }

    // ---- what was sent ----

    @Test
    void onMockTheWireViewSaysNothingWasSentAndWhichScenarioAnswered() {
        RunEnvelope<DtrDetail> result = fetch("P32H-DTR-PREPOP");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().note()).isEqualTo("Mock profile. Answered from scenario dtr-03-prepopulated, selected by the "
                + "sentinel member ID P32H-DTR-PREPOP. Nothing left this machine.");
        assertThat(result.wire().request().method()).isEqualTo("POST");
        assertThat(result.wire().request().headers()).extracting(h -> h.name() + ": " + h.value())
                .contains("Accept: application/fhir+json", "Content-Type: application/fhir+json");
        assertThat(result.wire().response().status()).isEqualTo(200);
        // An ordinary member gets the default scenario, and the note says it is the default.
        assertThat(fetch("S123456789").wire().request().note()).contains("dtr-01-static-linear").contains("the default");
    }

    @Test
    void theResolvedUrlAndTheCollectionRequestAreShownAndRecordedAndAScriptIsFlagged() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX).variable("baseUrl", "https://onyx.example.invalid/fhir")
                .url(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "{{baseUrl}}/Questionnaire/$questionnaire-package")
                .fromRequest("$questionnaire-package Happy path", true);
        RunEnvelope<DtrDetail> result = fetch("P32H-DOC-DTR");
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/fhir/Questionnaire/$questionnaire-package");
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{baseUrl}}/Questionnaire/$questionnaire-package");
        assertThat(result.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(ledger.only().targetSource()).isEqualTo("$questionnaire-package Happy path");
        assertThat(result.findings()).extracting(Finding::kind).contains(FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN);
        assertThat(ledger.only().findings()).contains("PRE_REQUEST_SCRIPT_NOT_RUN");
    }

    @Test
    void theTestersOwnPackageBodyIsSentAsWrittenAndWhatItAsksForIsReadFromIt() {
        String body = "{\"resourceType\":\"Parameters\",\"vendorSampleField\":true,\"parameter\":[{\"name\":\"coverage\",\"resource\":"
                + "{\"resourceType\":\"Coverage\",\"identifier\":[{\"value\":\"P32H-DOC-DTR\"}]}},{\"name\":\"order\",\"resource\":"
                + "{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\",\"code\":{\"coding\":[{\"code\":\"70553\"}]}}},"
                + "{\"name\":\"questionnaire\",\"valueCanonical\":\"" + SLEEP + "\"},"
                + "{\"name\":\"questionnaire\",\"valueCanonical\":\"https://example.org/p32h-placeholder/Questionnaire/not-in-the-package\"}]}";
        RunEnvelope<DtrDetail> result = service().runPackage(DtrPackageRequest.builder().rawBody(body)
                .procedureCodes(List.of(CodeInput.of("95810"))).questionnaires(List.of(SLEEP)).build(), RunOrigin.manual(), null);

        assertThat(result.wire().request().body()).isEqualTo(body);
        // The form held no member, so the sentinel typed into the body selected the scenario.
        assertThat(result.wire().request().note()).contains("found in the request body");
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        assertThat(result.detail().requestNotes()).containsExactly("The questionnaires and the coverage assertion id on the form were not "
                + "added to the body you supplied. Only what the body itself holds was sent.");
        // What the body asked for is what the response is checked against, so the missing questionnaire is not lost.
        assertThat(result.detail().requested()).hasSize(2);
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_INCOMPLETE");
        RunRecord record = ledger.only();
        assertThat(record.unrecordedInputs()).contains(RunRecord.INPUT_BODY);
        assertThat(record.memberId()).isNull();
        // The codes that went out are the ones in the body, not the ones left on the form.
        assertThat(record.procedureCodes()).containsExactly("70553");
        assertThat(result.nextStep().carry().get(NextStepView.CARRY_PROCEDURE_CODES)).containsExactly("70553");
    }

    @Test
    void aBodyThatIsNotJsonIsStillSentAsWrittenAndABodyWithAPlaceholderLeftInItIsRefused() {
        // Sending a malformed body is a negative case a tester is entitled to try.
        RunEnvelope<DtrDetail> result = service().runPackage(DtrPackageRequest.builder().rawBody("{\"resourceType\": \"Parameters\",").build(),
                RunOrigin.manual(), null);
        assertThat(result.wire().request().body()).isEqualTo("{\"resourceType\": \"Parameters\",");
        assertThat(result.detail().requestNotes()).singleElement().satisfies(note ->
                assertThat(note).contains("not valid JSON").contains("sent exactly as written"));
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY);

        assertThatThrownBy(() -> service().runPackage(DtrPackageRequest.builder()
                .rawBody("{\"resourceType\":\"Parameters\",\"id\":\"{{packageRequestId}}\"}").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> assertThat(e.variables()).containsExactly("packageRequestId"));
        assertThat(ledger.records()).hasSize(1);
        // Typed for this run, it resolves, and the ledger notes that a run value and a chosen request were used.
        service().runPackage(DtrPackageRequest.builder().rawBody("{\"resourceType\":\"Parameters\",\"id\":\"{{packageRequestId}}\"}")
                .variables(Map.of("packageRequestId", "p-1")).request(new RequestRef("col-1", "req-2")).build(), RunOrigin.manual(), null);
        assertThat(ledger.last().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES,
                RunRecord.INPUT_CHOSEN_REQUEST);
        assertThat(gateway.lastIntent().request()).isEqualTo(new RequestRef("col-1", "req-2"));
    }

    @Test
    void aPreviewShowsThePackageRequestWithoutSendingOrRecordingIt() {
        RequestPreview preview = service().previewPackage(DtrFixtures.packageRequest("S12345678   01").questionnaires(List.of(SLEEP)).build(), null);
        assertThat(preview.operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.url().configured()).isFalse();
        assertThat(preview.memberId().sent()).isEqualTo("S1234567801");
        JsonNode body = JsonSafe.parse(preview.body()).node();
        assertThat(body.get("resourceType").asString()).isEqualTo("Parameters");
        assertThat(preview.body()).contains(SLEEP);
        assertThat(preview.notes()).contains("The request asks for the questionnaire given on the form: " + SLEEP + ".");
        assertThat(preview.blocked()).isNull();
        assertThat(ledger.records()).isEmpty();
        assertThatThrownBy(() -> service().previewPackage(DtrFixtures.packageRequest(" ").build(), null)).isInstanceOf(ValidationException.class);
        // A body of the tester's own is shown resolved, or with what it still lacks.
        RequestPreview own = service().previewPackage(DtrPackageRequest.builder().rawBody("{\"id\":\"{{packageRequestId}}\"}").build(), null);
        assertThat(own.body()).isEqualTo("{\"id\":\"{{packageRequestId}}\"}");
        assertThat(own.notes()).containsExactly("The body still contains placeholders with no value: packageRequestId.");
    }

    @Test
    void aCodeOutsideItsValueSetIsShownAndRaisedAsAFindingThatDoesNotChangeTheOutcome() {
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\","
                + "\"url\":\"https://example.org/p32h-placeholder/Questionnaire/odd\",\"status\":\"published\",\"item\":[{\"linkId\":\"q1\","
                + "\"type\":\"slider\"},{\"linkId\":\"q2\",\"type\":\"string\",\"enableWhen\":[{\"question\":\"q1\",\"operator\":\"~\","
                + "\"answerInteger\":3}]}]}}]}";
        RunEnvelope<DtrDetail> result = service(stub(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, body)), "mock", Map.of())
                .runPackage(DtrFixtures.packageRequest("S123456789").build(), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.findings()).filteredOn(f -> f.kind() == FindingKind.CODE_OUTSIDE_VALUE_SET).extracting(Finding::serviceSays)
                .containsExactly("The service returned Questionnaire.status=published.", "The service returned item.type=slider.",
                        "The service returned item.enableWhen.operator=~.");
        assertThat(result.determination()).filteredOn(row -> row.label().equals("Questionnaire status")).singleElement().satisfies(row -> {
            assertThat(row.recognised()).isFalse();
            assertThat(row.display()).isEqualTo("Unrecognised code: published");
        });
        assertThat(ledger.only().findings()).containsExactly("CODE_OUTSIDE_VALUE_SET");
        assertThat(ledger.only().coverageCodes()).contains("questionnaire-status=published");
    }

    // ---- the adaptive loop, end to end on mock ----

    private RunEnvelope<DtrDetail> fetchAdaptive() {
        return service().runPackage(DtrFixtures.packageRequest("P32H-DTR-ADAPT").procedureCodes(List.of(CodeInput.of("72148"))).chainId("new")
                .build(), RunOrigin.manual(), null);
    }

    private static void assertStep(RunEnvelope<DtrDetail> step, String outcome, int questions, int answered, String status,
                                   List<String> asked, Map<String, List<String>> answers) {
        FormStateView form = step.detail().form();
        assertThat(step.verdict().outcomeCode()).isEqualTo(outcome);
        assertThat(form.questionCount()).as("question count").isEqualTo(questions);
        assertThat(form.answeredCount()).as("answered").isEqualTo(answered);
        assertThat(form.status().code()).as("completion status").isEqualTo(status);
        assertThat(linkIds(form.asked())).as("asked").isEqualTo(asked);
        assertThat(answerSet(form)).as("answer set").isEqualTo(answers);
        // The same three facts are in the words under the verdict, so the screen shows them at every step.
        assertThat(step.verdict().supporting()).contains("Question count so far: " + questions + ". Answered: " + answered
                + ". Completion status: " + status + ".");
        assertThat(step.verdict().supporting()).last().isEqualTo(SCOPE_NEXT);
    }

    @Test
    void theAdaptiveLoopRunsFromThePackageToCompletionWithTheCountTheAnswersAndTheStatusAtEveryStep() {
        RunEnvelope<DtrDetail> fetched = fetchAdaptive();
        assertThat(fetched.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(fetched.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(fetched.runId());

        // The first call is sent with no answers, and the service returns the first question.
        RunEnvelope<DtrDetail> first = next(fetched);
        assertStep(first, "NEXT_QUESTION", 1, 0, "in-progress", List.of("mri-1-therapy"), Map.of());
        assertThat(first.verdict().headline()).isEqualTo("The service returned the next question.");
        assertThat(first.verdict().supporting()).contains("Now asked: mri-1-therapy \"Has the member completed a course of conservative therapy?\".");
        assertThat(first.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(first.detail().form().complete()).isFalse();

        RunEnvelope<DtrDetail> second = next(first, DtrAnswerInput.of("mri-1-therapy", "no"));
        assertStep(second, "NEXT_QUESTION", 2, 1, "in-progress", List.of("mri-2-red-flag"), Map.of("mri-1-therapy", List.of("No (no)")));

        RunEnvelope<DtrDetail> third = next(second, DtrAnswerInput.of("mri-2-red-flag", "true"));
        assertStep(third, "NEXT_QUESTION", 3, 2, "in-progress", List.of("mri-3-red-flag-detail"),
                Map.of("mri-1-therapy", List.of("No (no)"), "mri-2-red-flag", List.of("true")));
        // While the response is in progress it is never handed on as something PAS could submit.
        assertThat(third.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);

        RunEnvelope<DtrDetail> fourth = service().runNextQuestion(following(third, "P32H-DTR-ADAPT",
                DtrAnswerInput.of("mri-3-red-flag-detail", "Placeholder description of the symptom")).expected("RESPONSE_COMPLETED; answered=3")
                .build(), RunOrigin.manual(), null);
        assertStep(fourth, "RESPONSE_COMPLETED", 3, 3, "completed", List.of(), Map.of("mri-1-therapy", List.of("No (no)"),
                "mri-2-red-flag", List.of("true"), "mri-3-red-flag-detail", List.of("Placeholder description of the symptom")));
        assertThat(fourth.verdict().headline()).isEqualTo("The questionnaire response is complete.");
        assertThat(fourth.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(fourth.detail().form().complete()).isTrue();
        assertThat(fourth.expectation().result()).isEqualTo(TestResult.PASS);

        // The completed response arrives in the hand-off, as compact JSON, with every answer the tester gave in it.
        assertThat(fourth.nextStep().action()).isEqualTo(NextAction.START_PAS);
        Map<String, List<String>> carry = fourth.nextStep().carry();
        assertThat(carry.get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).hasSize(1);
        String handedOn = carry.get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE).get(0);
        assertThat(handedOn).doesNotContain("\n");
        JsonNode completed = JsonSafe.parse(handedOn).node();
        assertThat(completed.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(completed.get("status").asString()).isEqualTo("completed");
        assertThat(completed.at("/item/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(completed.at("/item/0/answer/0/valueCoding/code").asString()).isEqualTo("no");
        assertThat(completed.at("/item/1/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(completed.at("/item/2/answer/0/valueString").asString()).isEqualTo("Placeholder description of the symptom");
        assertThat(completed.at("/contained/0/resourceType").asString()).isEqualTo("Questionnaire");
        assertThat(completed.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        // With it go the run the questionnaire came from, the questionnaire and the codes of the case.
        assertThat(carry.get(NextStepView.CARRY_RUN_ID)).containsExactly(fetched.runId());
        assertThat(carry.get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(MRI);
        assertThat(carry.get(NextStepView.CARRY_PROCEDURE_CODES)).containsExactly("72148");

        // Each step is a run with its own line, linked to the package fetch and naming the questionnaire.
        assertThat(ledger.records()).hasSize(5);
        List<RunRecord> steps = ledger.records().subList(1, 5);
        assertThat(steps).extracting(RunRecord::operation).containsOnly(EpaOperation.DTR_NEXT_QUESTION);
        assertThat(steps).extracting(RunRecord::runId).containsExactly(first.runId(), second.runId(), third.runId(), fourth.runId())
                .doesNotHaveDuplicates().doesNotContain(fetched.runId());
        assertThat(steps).allSatisfy(record -> {
            assertThat(record.correlation()).containsOnly(Map.entry(RunRecord.CORR_RUN_ID, fetched.runId()),
                    Map.entry(RunRecord.CORR_QUESTIONNAIRE, MRI));
            assertThat(record.chainId()).isEqualTo(fetched.chainId());
            assertThat(record.scenarioId()).isEqualTo("dtr-02-adaptive-branching");
            assertThat(record.procedureCodes()).containsExactly("72148");
            assertThat(record.memberId()).isEqualTo("P32H-DTR-ADAPT");
            // The questionnaire and the answers so far are not on the line, so it cannot be replayed from the line.
            assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
            assertThat(record.httpStatus()).isEqualTo(200);
        });
        assertThat(RunStamps.isChainId(fetched.chainId())).isTrue();
        assertThat(steps).extracting(RunRecord::outcome).containsExactly("NEXT_QUESTION", "NEXT_QUESTION", "NEXT_QUESTION", "RESPONSE_COMPLETED");
        assertThat(steps).extracting(RunRecord::nextAction)
                .containsExactly("ANSWER_NEXT_QUESTION", "ANSWER_NEXT_QUESTION", "ANSWER_NEXT_QUESTION", "START_PAS");
        assertThat(steps.get(1).coverageCodes()).containsExactly("response-status=in-progress", "questions=2", "answered=1",
                "required-unanswered=1");
        assertThat(steps.get(3).coverageCodes()).containsExactly("response-status=completed", "questions=3", "answered=3",
                "required-unanswered=0");
        assertThat(steps.get(3).result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void whatEachStepSendsIsTheResponseSoFarWithTheNewAnswerInIt() {
        RunEnvelope<DtrDetail> fetched = fetchAdaptive();
        // The package says which response it carries for the questionnaire, so the screen does not have to work it out.
        QuestionnaireResponseView carried = fetched.detail().questionnaireResponses().get(0);
        assertThat(fetched.detail().questionnaires().get(0).arrivedWith()).isEqualTo(carried.foundAt())
                .isEqualTo("parameter[0].resource.entry[1].resource");

        RunEnvelope<DtrDetail> first = next(fetched);
        // The first request is the response the package carried for the questionnaire, exactly as it arrived, with no answer.
        JsonNode firstSent = JsonSafe.parse(first.wire().request().body()).node();
        assertThat(firstSent).isEqualTo(JsonSafe.parse(carried.json()).node());
        assertThat(firstSent.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(firstSent.get("status").asString()).isEqualTo("in-progress");
        assertThat(firstSent.get("questionnaire").asString()).isEqualTo("#lumbar-mri-adaptive");
        // The questionnaire inside it has no url of its own. What it is derived from is what ties it to the one in the package.
        assertThat(firstSent.at("/contained/0").has("url")).isFalse();
        assertThat(firstSent.at("/contained/0/derivedFrom/0").asString()).isEqualTo(MRI);
        assertThat(firstSent.has("item")).isFalse();
        assertThat(first.wire().request().headers()).extracting(h -> h.name() + ": " + h.value()).contains("Content-Type: application/fhir+json");
        assertThat(first.wire().request().note()).contains("step \"first-question\"");
        assertThat(first.detail().bodyBuiltByTool()).isTrue();
        assertThat(first.detail().requestNotes()).noneMatch(note -> note.contains("derived from"));
        // The member ID is not in the request, and is not shown as sent.
        assertThat(first.wire().request().body()).doesNotContain("P32H-DTR-ADAPT");
        assertThat(first.wire().memberId()).isNull();

        RunEnvelope<DtrDetail> second = next(first, DtrAnswerInput.of("mri-1-therapy", "yes"));
        JsonNode secondSent = JsonSafe.parse(second.wire().request().body()).node();
        // The answer goes out as the coding of the option that was chosen, on the question it answers.
        assertThat(secondSent.at("/item/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(secondSent.at("/item/0/answer/0/valueCoding/code").asString()).isEqualTo("yes");
        assertThat(secondSent.at("/item/0/answer/0/valueCoding/display").asString()).isEqualTo("Yes");
        assertThat(second.wire().request().note()).contains("step \"therapy-weeks\"");
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.DTR_NEXT_QUESTION);
    }

    @Test
    void aCompleteResponseThatNamesNoQuestionnaireOfThePackageIsHandedOnButNotCountedAndTheResultSaysWhy() {
        RunEnvelope<DtrDetail> fetched = fetchAdaptive();
        String elsewhere = "https://example.org/p32h-placeholder/Questionnaire/another";
        String inside = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",%s"
                + "\"status\":\"active\"}],\"questionnaire\":\"#q\",\"status\":\"completed\"}";

        // The service completes a response whose questionnaire is derived from one the package does not hold.
        RunEnvelope<DtrDetail> other = service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200,
                inside.formatted("\"derivedFrom\":[\"" + elsewhere + "\"],"))), "mock", Map.of())
                .runNextQuestion(following(fetched, "P32H-DTR-ADAPT").build(), RunOrigin.manual(), null);
        assertThat(other.verdict().outcomeCode()).isEqualTo("NOTHING_REQUIRED");
        assertThat(other.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(other.nextStep().headline()).isEqualTo("Next: check which questionnaire this response answers before going on to PAS.");
        assertThat(other.nextStep().reason()).isEqualTo("This response is complete, but it says it answers " + elsewhere + ", which is not a "
                + "questionnaire of the package this step follows. The package holds: " + MRI + ". This tool does not count a response "
                + "towards a questionnaire it does not name, so it does not offer PAS.");
        // It is still handed on, and its line names what the response itself names.
        assertThat(other.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).hasSize(1);
        assertThat(ledger.last().nextAction()).isEqualTo("REVIEW");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, elsewhere)
                .containsEntry(RunRecord.CORR_RUN_ID, fetched.runId());

        // One whose questionnaire says neither what it is derived from nor what its own url is names nothing at all.
        RunEnvelope<DtrDetail> unnamed = service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, inside.formatted(""))),
                "mock", Map.of()).runNextQuestion(following(fetched, "P32H-DTR-ADAPT").build(), RunOrigin.manual(), null);
        assertThat(unnamed.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(unnamed.nextStep().reason()).startsWith("This response is complete, but it does not say which questionnaire it answers. "
                + "The package holds: " + MRI + ".");
        // The response that was sent named the questionnaire, so the line still does.
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, MRI);
    }

    @Test
    void whenThePackageCarriesNoResponseTheFirstStepPutsTheQuestionnaireInsideANewOne() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        assertThat(fetched.detail().questionnaireResponses()).isEmpty();
        assertThat(fetched.detail().questionnaires().get(0).arrivedWith()).isNull();

        RunEnvelope<DtrDetail> first = service().runNextQuestion(following(fetched, "P32H-DTR-LOOPERR").build(), RunOrigin.manual(), null);
        JsonNode firstSent = JsonSafe.parse(first.wire().request().body()).node();
        String canonical = "https://example.org/p32h-placeholder/Questionnaire/knee-arthroscopy-adaptive|1.0.0";
        assertThat(firstSent.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(firstSent.get("status").asString()).isEqualTo("in-progress");
        assertThat(firstSent.get("questionnaire").asString()).isEqualTo("#knee-arthroscopy-adaptive");
        // The questionnaire goes inside as the package returned it, and says which questionnaire is being completed.
        assertThat(firstSent.at("/contained/0/url").asString()).isEqualTo("https://example.org/p32h-placeholder/Questionnaire/knee-arthroscopy-adaptive");
        assertThat(firstSent.at("/contained/0/derivedFrom/0").asString()).isEqualTo(canonical);
        assertThat(first.detail().requestNotes()).anySatisfy(note -> assertThat(note).startsWith("The copy of the questionnaire inside the "
                + "QuestionnaireResponse was marked as derived from " + canonical));
        assertThat(firstSent.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(firstSent.has("item")).isFalse();
        // The member is named by the local id of the Patient resource. The member ID is not in the request, and is not shown as sent.
        assertThat(firstSent.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(first.wire().request().body()).doesNotContain("P32H-DTR-LOOPERR");
        assertThat(first.wire().memberId()).isNull();
        // Its line names the questionnaire of the package, like the line of the fetch it follows.
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, canonical)
                .containsEntry(RunRecord.CORR_RUN_ID, fetched.runId());
    }

    @Test
    void aQuestionThatAppearsAndOneThatDisappearsBecauseOfAnEarlierAnswerAreEachNamedInTheStepThatFollows() {
        RunEnvelope<DtrDetail> first = next(fetchAdaptive());
        RunEnvelope<DtrDetail> second = next(first, DtrAnswerInput.of("mri-1-therapy", "yes"));
        // Answering yes brings a follow-up, named by its linkId and its text.
        assertThat(second.detail().form().changes().appeared()).containsExactly(
                new QuestionRef("mri-2-weeks", "How many weeks of conservative therapy were completed?"));
        assertThat(second.detail().form().changes().disappeared()).isEmpty();
        assertThat(second.verdict().supporting()).contains("Since the previous step: 1 question appeared: mri-2-weeks \"How many weeks of "
                + "conservative therapy were completed?\".");

        RunEnvelope<DtrDetail> third = next(second, DtrAnswerInput.of("mri-2-weeks", "8"));
        assertThat(third.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(third.detail().form().changes().summary()).isEqualTo("Nothing changed since the previous step: the same questions are "
                + "asked and every answer that was sent came back.");

        // The first answer is changed. Its follow-up disappears, another appears, and the answer to the old follow-up is dropped.
        RunEnvelope<DtrDetail> fourth = next(third, DtrAnswerInput.of("mri-1-therapy", "no"));
        assertThat(fourth.verdict().outcomeCode()).isEqualTo("NEXT_QUESTION");
        assertThat(fourth.detail().form().changes().compared()).isTrue();
        assertThat(fourth.detail().form().changes().appeared()).containsExactly(
                new QuestionRef("mri-2-red-flag", "Is a red-flag symptom documented?"));
        assertThat(fourth.detail().form().changes().disappeared()).containsExactly(
                new QuestionRef("mri-2-weeks", "How many weeks of conservative therapy were completed?"));
        assertThat(fourth.detail().form().changes().answersDropped()).extracting(QuestionRef::linkId).containsExactly("mri-2-weeks");
        String summary = "Since the previous step: 1 question appeared: mri-2-red-flag \"Is a red-flag symptom documented?\". 1 question "
                + "disappeared: mri-2-weeks \"How many weeks of conservative therapy were completed?\". The answer that was sent to "
                + "mri-2-weeks \"How many weeks of conservative therapy were completed?\" is not in the response.";
        assertThat(fourth.detail().form().changes().summary()).isEqualTo(summary);
        assertThat(fourth.verdict().supporting()).contains(summary);
        assertThat(answerSet(fourth.detail().form())).isEqualTo(Map.of("mri-1-therapy", List.of("No (no)")));
        assertThat(linkIds(fourth.detail().form().asked())).containsExactly("mri-2-red-flag");

        // What was sent held both answers: in the loop the service decides which questions still apply.
        JsonNode sent = JsonSafe.parse(fourth.wire().request().body()).node();
        assertThat(sent.at("/item/0/answer/0/valueCoding/code").asString()).isEqualTo("no");
        assertThat(sent.at("/item/1/answer/0/valueInteger").asInt()).isEqualTo(8);
        // A response the service called complete, with an answer changed, is no longer complete, and is not handed to PAS.
        assertThat(sent.get("status").asString()).isEqualTo("in-progress");
        assertThat(fourth.detail().requestNotes()).contains("The response so far had the status completed. An answer was added or "
                + "changed, so it was sent as in-progress.");
        assertThat(third.nextStep().carry()).containsKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(fourth.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(fourth.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
    }

    @Test
    void aLoopStartedColdStillWorksAndLinksItsLaterStepsToItsFirst() {
        String questionnaire = fetchAdaptive().detail().questionnaires().get(0).json();
        // No member, no provider, no code and nothing handed on: only the questionnaire and a scenario.
        RunEnvelope<DtrDetail> first = service().runNextQuestion(DtrNextQuestionRequest.builder().questionnaire(questionnaire)
                .scenarioId("dtr-02-adaptive-branching").build(), RunOrigin.manual(), null);
        assertThat(first.verdict().outcomeCode()).isEqualTo("NEXT_QUESTION");
        assertThat(first.detail().requestNotes()).contains("The response names no subject, because the request names no member.");
        assertThat(first.wire().memberId()).isNull();
        RunRecord firstRecord = ledger.last();
        assertThat(firstRecord.memberId()).isNull();
        assertThat(firstRecord.procedureCodes()).isEmpty();
        assertThat(firstRecord.correlation()).containsOnly(Map.entry(RunRecord.CORR_QUESTIONNAIRE, MRI));
        // Nothing was handed on, and the line still cannot rebuild the request: the questionnaire it sent is not on it.
        assertThat(firstRecord.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        // It was handed no run, so it hands on its own, and the step after it is linked to that.
        assertThat(first.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(first.runId());

        RunEnvelope<DtrDetail> second = service().runNextQuestion(DtrNextQuestionRequest.builder()
                .questionnaireResponse(first.detail().questionnaireResponses().get(0).json()).answers(List.of(DtrAnswerInput.of("mri-1-therapy", "yes")))
                .scenarioId("dtr-02-adaptive-branching").carry(first.nextStep().carry()).build(), RunOrigin.manual(), null);
        assertThat(linkIds(second.detail().form().asked())).containsExactly("mri-2-weeks");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, first.runId());
        assertThat(second.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(first.runId());
    }

    private static final String ADAPTIVE_STUB = "{\"resourceType\":\"Questionnaire\",\"id\":\"stub\",\"url\":"
            + "\"https://example.org/p32h-placeholder/Questionnaire/stub\",\"extension\":[{\"url\":"
            + "\"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive\",\"valueBoolean\":true}]%s}";

    private static String stubQuestionnaire(String items) {
        return ADAPTIVE_STUB.formatted(items.isEmpty() ? "" : ",\"item\":[" + items + "]");
    }

    private static String stubResponse(String status, String items, String responseItems) {
        return "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[" + stubQuestionnaire(items) + "],\"questionnaire\":\"#stub\""
                + (status == null ? "" : ",\"status\":\"" + status + "\"") + (responseItems.isEmpty() ? "" : ",\"item\":[" + responseItems + "]") + "}";
    }

    private RunEnvelope<DtrDetail> stubbedStep(String responseBody, String expected) {
        return service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, responseBody)), "mock", Map.of())
                .runNextQuestion(DtrNextQuestionRequest.builder().member(DtrFixtures.member("S123456789")).questionnaire(stubQuestionnaire(""))
                        .expected(expected).carry(Map.of(NextStepView.CARRY_RUN_ID, List.of(EARLIER_RUN))).build(), RunOrigin.manual(), null);
    }

    @Test
    void aStatusOfCompletedNeverOutranksARequiredQuestionLeftUnanswered() {
        String items = "{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"string\",\"required\":true},"
                + "{\"linkId\":\"q2\",\"text\":\"Second\",\"type\":\"boolean\",\"required\":true}";
        RunEnvelope<DtrDetail> result = stubbedStep(stubResponse("completed", items,
                "{\"linkId\":\"q2\",\"answer\":[{\"valueBoolean\":true}]}"), "RESPONSE_COMPLETED");

        assertThat(result.verdict().outcomeCode()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(result.verdict().headline()).isEqualTo("A required question has no answer.");
        assertThat(result.verdict().supporting()).contains("Required and unanswered: q1 \"First\".",
                "The response carries the status completed all the same. This tool does not report it as complete.");
        assertThat(result.detail().form().complete()).isFalse();
        assertThat(result.detail().form().requiredUnanswered()).containsExactly(new QuestionRef("q1", "First"));
        // The tester expected a completed response. That is not what this is, so the test fails.
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        // And it is never handed to PAS as one.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(result.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(ledger.only().outcome()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(ledger.only().coverageCodes()).contains("response-status=completed", "required-unanswered=1");
    }

    @Test
    void aResponseThatAsksNothingMoreWithoutBeingCompleteAndAStatusTheToolDoesNotKnowAreEachSaidPlainly() {
        String answered = "{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"string\"}";
        RunEnvelope<DtrDetail> stalled = stubbedStep(stubResponse("in-progress", answered,
                "{\"linkId\":\"q1\",\"answer\":[{\"valueString\":\"given\"}]}"), null);
        assertThat(stalled.verdict().outcomeCode()).isEqualTo("NO_QUESTION_RETURNED");
        assertThat(stalled.verdict().supporting()).contains("This is not the same as a completed response. The service has not said the "
                + "questionnaire is finished.");
        assertThat(stalled.nextStep().action()).isEqualTo(NextAction.REVIEW);

        RunEnvelope<DtrDetail> odd = stubbedStep(stubResponse("finished", answered, ""), "response-status=completed");
        assertThat(odd.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_STATUS");
        assertThat(odd.verdict().supporting()).contains("Question count so far: 1. Answered: 0. Completion status: unrecognised code: finished.");
        assertThat(odd.determination()).filteredOn(row -> row.label().equals("Completion status")).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("finished");
            assertThat(row.recognised()).isFalse();
        });
        assertThat(odd.findings()).extracting(Finding::kind).contains(FindingKind.CODE_OUTSIDE_VALUE_SET);
        assertThat(odd.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(odd.nextStep().action()).isEqualTo(NextAction.REVIEW);

        // No status at all is not a status of in-progress and not one of completed.
        RunEnvelope<DtrDetail> none = stubbedStep(stubResponse(null, answered, ""), null);
        assertThat(none.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_STATUS");
        assertThat(none.verdict().supporting()).contains("Question count so far: 1. Answered: 0. Completion status: not stated.");
        assertThat(none.determination().get(0).presence()).isEqualTo(Presence.ABSENT);
    }

    @Test
    void aFaultInTheMiddleOfTheLoopIsShownInFullAndItsLineStaysLinkedToThePackage() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"exception\",\"diagnostics\":"
                + "\"The rules engine failed.\"}],\"vendorTraceId\":\"t-9\"}";
        RunEnvelope<DtrDetail> failed = service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 500, outcome)), "mock", Map.of())
                .runNextQuestion(DtrNextQuestionRequest.builder().member(DtrFixtures.member("S123456789")).questionnaire(stubQuestionnaire(""))
                        .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of(EARLIER_RUN))).build(), RunOrigin.manual(), null);
        assertThat(failed.verdict().headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(failed.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(failed.error().issues()).singleElement().satisfies(issue ->
                assertThat(issue.diagnostics().value()).isEqualTo("The rules engine failed."));
        assertThat(failed.detail().form()).isNull();
        assertThat(failed.additionalFields()).extracting(f -> f.path()).containsExactly("vendorTraceId");
        assertThat(failed.determination()).singleElement().satisfies(row -> assertThat(row.presence()).isEqualTo(Presence.ABSENT));
        assertThat(failed.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        // The line still says which questionnaire was being answered and which package it came from.
        assertThat(ledger.only().correlation()).containsOnly(Map.entry(RunRecord.CORR_RUN_ID, EARLIER_RUN),
                Map.entry(RunRecord.CORR_QUESTIONNAIRE, "https://example.org/p32h-placeholder/Questionnaire/stub"));
        assertThat(ledger.only().outcome()).isEqualTo("SERVICE_ERROR");
        assertThat(failed.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(EARLIER_RUN);

        RunEnvelope<DtrDetail> timedOut = service(stub(TestExchanges.timeout(EpaOperation.DTR_NEXT_QUESTION)), "mock", Map.of())
                .runNextQuestion(DtrNextQuestionRequest.builder().questionnaire(stubQuestionnaire("")).build(), RunOrigin.manual(), null);
        assertThat(timedOut.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timedOut.detail().responseKind()).isEqualTo(DtrResponseKind.NO_RESPONSE);

        RunEnvelope<DtrDetail> crashed = service(stub(unreadable(EpaOperation.DTR_NEXT_QUESTION)), "mock", Map.of())
                .runNextQuestion(DtrNextQuestionRequest.builder().questionnaire(stubQuestionnaire(""))
                        .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of(EARLIER_RUN))).build(), RunOrigin.manual(), null);
        assertThat(RunSupport.isToolError(crashed)).isTrue();
        assertThat(ledger.last().outcome()).isEqualTo("TOOL_ERROR");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, EARLIER_RUN);
        assertThat(ledger.last().unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
    }

    @Test
    void aStepOfTheLoopThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        String questionnaire = stubQuestionnaire("{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"integer\"},"
                + "{\"linkId\":\"g1\",\"type\":\"group\",\"item\":[{\"linkId\":\"q2\",\"type\":\"choice\",\"answerOption\":"
                + "[{\"valueCoding\":{\"code\":\"a\"}}]}]}");
        DtrService service = service();

        // Nothing to answer.
        assertInvalidStep(service, DtrNextQuestionRequest.builder().build(), "questionnaire");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire("{\"resourceType\": ").build(), "questionnaire");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire("{\"resourceType\":\"Patient\"}").build(), "questionnaire");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaireResponse("{\"resourceType\":\"Questionnaire\"}").build(),
                "questionnaireResponse");
        // An answer that cannot be tied to a question, or that the question does not take.
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("q9", "1")), "answers[0].linkId");
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("g1", "1")), "answers[0].linkId");
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("q1", "1", "2")), "answers[0].values");
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("q1", "1"), DtrAnswerInput.of("q1", "2")), "answers[1].linkId");
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("q1", "eight")), "answers[0].values[0]");
        assertInvalidStep(service, step(questionnaire, DtrAnswerInput.of("q2", "not-an-option")), "answers[0].values[0]");
        // The case fields are optional for this step, and whatever is given is still checked.
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire(questionnaire)
                .provider(ProviderDetail.builder().npi("1234567890").build()).build(), "provider.npi");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire(questionnaire)
                .member(MemberDetail.builder().memberId("S123456789").dateOfBirth("31-01-1970").build()).build(), "member.dateOfBirth");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire(questionnaire).procedureCodes(List.of(CodeInput.of(" "))).build(),
                "procedureCodes[0].code");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire(questionnaire).expected("PA_REQUIRED").build(), "expected");
        assertInvalidStep(service, DtrNextQuestionRequest.builder().questionnaire(questionnaire)
                .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of("run-1"))).build(), "carry.runId");
        assertInvalidStep(service(mockClient, "live", Map.of()), DtrNextQuestionRequest.builder().questionnaire(questionnaire)
                .scenarioId("dtr-02-adaptive-branching").build(), "scenarioId");

        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aMessageAboutAnAnswerNeverQuotesTheAnswer() {
        String questionnaire = stubQuestionnaire("{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"integer\"}");
        assertThatThrownBy(() -> service().runNextQuestion(step(questionnaire, DtrAnswerInput.of("q1", "a clinical remark typed by mistake")),
                RunOrigin.manual(), null)).isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.fieldErrors()).singleElement().satisfies(error ->
                            assertThat(error.message()).isEqualTo("This question takes a whole number."));
                    assertThat(e.getMessage()).doesNotContain("clinical remark");
                });
    }

    private static DtrNextQuestionRequest step(String questionnaire, DtrAnswerInput... answers) {
        return DtrNextQuestionRequest.builder().questionnaire(questionnaire).answers(List.of(answers)).build();
    }

    private void assertInvalidStep(DtrService service, DtrNextQuestionRequest request, String field) {
        assertThatThrownBy(() -> service.runNextQuestion(request, RunOrigin.manual(), null))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    @Test
    void aQuestionnaireThatIsNotAdaptiveCanStillBeSentToNextQuestionAndTheResultSaysSo() {
        RunEnvelope<DtrDetail> staticPackage = fetch("P32H-DOC-DTR");
        // On mock the static scenario defines no step of the loop, so the call is refused by name and not answered with something else.
        assertThatThrownBy(() -> service().runNextQuestion(following(staticPackage, "P32H-DOC-DTR").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .contains("dtr-01-static-linear").contains("\"next-question\""));
        assertThat(ledger.records()).hasSize(1);

        // Against a service that answers, it is sent, and the request notes say the questionnaire is not marked adaptive.
        String refused = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-supported\"}]}";
        RunEnvelope<DtrDetail> sent = service(stub(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 400, refused)), "mock", Map.of())
                .runNextQuestion(following(staticPackage, "P32H-DOC-DTR").build(), RunOrigin.manual(), null);
        assertThat(sent.verdict().outcomeCode()).isEqualTo("REJECTED");
        assertThat(sent.detail().requestNotes()).anySatisfy(note -> assertThat(note).startsWith("The questionnaire is not marked as "
                + "adaptive. Static, because it declares the standard questionnaire profile").endsWith("The next-question request is sent "
                + "all the same."));
        assertThat(service().previewNextQuestion(following(staticPackage, "P32H-DOC-DTR").build(), null).notes())
                .anySatisfy(note -> assertThat(note).startsWith("The questionnaire is not marked as adaptive."));
    }

    @Test
    void theTestersOwnNextQuestionBodyIsSentAsWrittenAndWhatChangedIsStillWorkedOutFromIt() {
        RunEnvelope<DtrDetail> first = next(fetchAdaptive());
        // The vendor's other shape: the response wrapped in a Parameters resource, with an answer typed in by hand.
        JsonNode response = JsonSafe.parse(first.detail().questionnaireResponses().get(0).json()).node();
        ((ObjectNode) response).putArray("item").addObject().put("linkId", "mri-1-therapy").putArray("answer").addObject()
                .putObject("valueCoding").put("code", "yes");
        String body = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"questionnaire-response\",\"resource\":" + response + "}]}";
        RunEnvelope<DtrDetail> result = service().runNextQuestion(DtrNextQuestionRequest.builder().rawBody(body)
                .scenarioId("dtr-02-adaptive-branching").answers(List.of(DtrAnswerInput.of("mri-1-therapy", "no")))
                .carry(first.nextStep().carry()).build(), RunOrigin.manual(), null);

        assertThat(result.wire().request().body()).isEqualTo(body);
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        assertThat(result.detail().requestNotes()).containsExactly("The questionnaire, the response so far and the answers on the form were "
                + "not added to the body you supplied. Only what the body itself holds was sent.");
        // The mock answered the answer in the body, not the one left on the form.
        assertThat(linkIds(result.detail().form().asked())).containsExactly("mri-2-weeks");
        assertThat(linkIds(result.detail().form().changes().appeared())).containsExactly("mri-2-weeks");
        RunRecord record = ledger.last();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_PRIOR_STEP);
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, MRI).containsEntry(RunRecord.CORR_RUN_ID,
                first.nextStep().carry().get(NextStepView.CARRY_RUN_ID).get(0));

        // A body that is not a QuestionnaireResponse is still sent. What changed cannot be said, and the result says that.
        RunEnvelope<DtrDetail> opaque = service().runNextQuestion(DtrNextQuestionRequest.builder().rawBody("{\"resourceType\":\"Basic\"}")
                .scenarioId("dtr-02-adaptive-branching").build(), RunOrigin.manual(), null);
        assertThat(opaque.detail().form().changes().compared()).isFalse();
        assertThat(opaque.detail().form().changes().summary()).startsWith("What was sent could not be read as a QuestionnaireResponse");
    }

    @Test
    void aPreviewShowsTheNextQuestionRequestWithoutSendingOrRecordingIt() {
        RunEnvelope<DtrDetail> fetched = fetchAdaptive();
        RequestPreview preview = service().previewNextQuestion(following(fetched, "P32H-DTR-ADAPT").build(), null);
        assertThat(preview.operation()).isEqualTo(EpaOperation.DTR_NEXT_QUESTION);
        assertThat(preview.method()).isEqualTo("POST");
        JsonNode body = JsonSafe.parse(preview.body()).node();
        assertThat(body.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(body.get("status").asString()).isEqualTo("in-progress");
        // The request names the member by a local reference and carries no member ID, so none is shown as sent.
        assertThat(preview.memberId()).isNull();
        assertThat(preview.body()).doesNotContain("P32H-DTR-ADAPT");
        assertThat(ledger.records()).hasSize(1);
        assertThatThrownBy(() -> service().previewNextQuestion(DtrNextQuestionRequest.builder().build(), null))
                .isInstanceOf(ValidationException.class);
    }

    // ---- the response, put together inside the tool ----

    @Test
    void aRequiredQuestionLeftUnansweredIsNeverReportedAsCompleteAndEachOneIsNamed() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        RunEnvelope<DtrDetail> fetched = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-REQUIRED").chainId("new").build(),
                RunOrigin.manual(), null);
        AtomicInteger calls = new AtomicInteger();
        OnyxDtrClient counting = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                throw new AssertionError("the response step makes no call");
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                throw new AssertionError("the response step makes no call");
            }
        };
        int preparedBefore = gateway.intents().size();

        // Two answers of yes each enable a required follow-up, and both follow-ups are left unanswered.
        RunEnvelope<DtrDetail> result = service(counting, "mock", Map.of()).runResponse(responding(fetched, "P32H-DTR-REQUIRED",
                DtrAnswerInput.of("pac-1", "true"), DtrAnswerInput.of("pac-3", "true")).expected("RESPONSE_COMPLETED").testerRef("QA-17").build(),
                RunOrigin.manual());

        assertThat(result.verdict().outcomeCode()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(result.verdict().headline()).isEqualTo("2 required questions have no answer.");
        assertThat(result.verdict().tone()).isEqualTo(Tone.ACTION);
        assertThat(result.detail().form().complete()).isFalse();
        assertThat(result.detail().form().requiredUnanswered()).containsExactly(
                new QuestionRef("pac-2", "Which activities does the member need help with?"), new QuestionRef("pac-4", "Describe the skilled need"));
        assertThat(result.verdict().supporting()).contains("Required and unanswered: pac-2 \"Which activities does the member need help "
                + "with?\"; pac-4 \"Describe the skilled need\".");
        assertThat(result.verdict().supporting()).contains("Question count so far: 5. Answered: 2. Completion status: in-progress.");
        // The response that was built says in-progress, and the tester who expected a completed one is told the test failed.
        assertThat(JsonSafe.parse(result.detail().questionnaireResponses().get(0).json()).node().get("status").asString()).isEqualTo("in-progress");
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(result.nextStep().headline()).isEqualTo("Next: answer the required questions named with this result and submit the "
                + "response again.");
        assertThat(result.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);

        // Nothing was sent, and the result says so in plain words in more than one place.
        assertThat(calls.get()).isZero();
        assertThat(gateway.intents()).hasSize(preparedBefore);
        assertThat(result.verdict().supporting().get(0)).isEqualTo("Nothing was sent to the service for this step. The response was put "
                + "together inside this tool.");
        assertThat(result.verdict().supporting()).last().isEqualTo(SCOPE_LOCAL);
        assertThat(result.detail().sent()).isFalse();
        assertThat(result.detail().responseKind()).isEqualTo(DtrResponseKind.NOT_SENT);
        assertThat(result.detail().responseNote()).isEqualTo("Nothing was sent to the service for this step.");
        assertThat(result.wire()).isNull();
        assertThat(result.timing()).isNull();
        assertThat(result.error()).isNull();
        assertThat(result.operation()).isEqualTo(EpaOperation.DTR_RESPONSE);
        assertThat(result.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(result.chainId()).isEqualTo(fetched.chainId());

        // It is a run like any other, with its own line, linked to the package fetch.
        RunRecord record = ledger.last();
        assertThat(ledger.records()).hasSize(2);
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.protocol()).isEqualTo(Protocol.DTR);
        assertThat(record.operation()).isEqualTo(EpaOperation.DTR_RESPONSE);
        assertThat(record.outcome()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(record.result()).isEqualTo(TestResult.FAIL);
        assertThat(record.coverageCodes()).containsExactly("response-status=in-progress", "questions=5", "answered=2", "required-unanswered=2");
        assertThat(record.httpStatus()).isNull();
        assertThat(record.elapsedMillis()).isZero();
        assertThat(record.errorType()).isEqualTo("NONE");
        assertThat(record.scenarioId()).isNull();
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(record.profile()).isEqualTo("mock");
        assertThat(record.memberId()).isEqualTo("P32H-DTR-REQUIRED");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.chainId()).isEqualTo(fetched.chainId());
        assertThat(record.nextAction()).isEqualTo("SUBMIT_RESPONSE");
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        assertThat(record.correlation()).containsOnly(Map.entry(RunRecord.CORR_RUN_ID, fetched.runId()),
                Map.entry(RunRecord.CORR_QUESTIONNAIRE, "https://example.org/p32h-placeholder/Questionnaire/post-acute-criteria|1.2.0"));
    }

    @Test
    void aStaticResponseIsCompleteWhenEveryRequiredQuestionThatIsEnabledIsAnsweredAndItIsWhatPasIsHanded() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-REQUIRED");
        RunEnvelope<DtrDetail> result = service().runResponse(responding(fetched, "P32H-DTR-REQUIRED", DtrAnswerInput.of("pac-1", "true"),
                DtrAnswerInput.of("pac-2", "bathing", "transferring"), DtrAnswerInput.of("pac-3", "true"),
                DtrAnswerInput.of("pac-4", "Placeholder description of the skilled need"), DtrAnswerInput.of("pac-5", "21"))
                .expected("RESPONSE_COMPLETED; required-unanswered=0").build(), RunOrigin.manual());

        assertThat(result.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(result.verdict().headline()).isEqualTo("The questionnaire response is complete.");
        assertThat(result.detail().form().complete()).isTrue();
        assertThat(result.detail().form().requiredUnanswered()).isEmpty();
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(result.nextStep().reason()).isEqualTo("Every required question that is enabled has an answer.");

        // The answers the tester entered are in the response PAS is handed, under the questions and groups they belong to.
        JsonNode completed = JsonSafe.parse(result.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE).get(0)).node();
        assertThat(completed.get("status").asString()).isEqualTo("completed");
        assertThat(completed.get("questionnaire").asString())
                .isEqualTo("https://example.org/p32h-placeholder/Questionnaire/post-acute-criteria|1.2.0");
        assertThat(completed.at("/item/0/linkId").asString()).isEqualTo("pac-g1");
        assertThat(completed.at("/item/0/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(completed.at("/item/0/item/1/answer/0/valueCoding/code").asString()).isEqualTo("bathing");
        assertThat(completed.at("/item/0/item/1/answer/1/valueCoding/code").asString()).isEqualTo("transferring");
        assertThat(completed.at("/item/1/item/1/answer/0/valueString").asString()).isEqualTo("Placeholder description of the skilled need");
        assertThat(completed.at("/item/2/answer/0/valueInteger").asInt()).isEqualTo(21);
        assertThat(completed.at("/subject/reference").asString()).isEqualTo("Patient/patient-1");
        assertThat(completed.get("authored").asString()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(result.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(fetched.runId());
        assertThat(ledger.last().nextAction()).isEqualTo("START_PAS");

        // Answers that disable the follow-ups leave nothing required unanswered, and an answer to a disabled question is left out and said to be.
        RunEnvelope<DtrDetail> disabled = service().runResponse(responding(fetched, "P32H-DTR-REQUIRED", DtrAnswerInput.of("pac-1", "false"),
                DtrAnswerInput.of("pac-2", "bathing"), DtrAnswerInput.of("pac-3", "false")).build(), RunOrigin.manual());
        assertThat(disabled.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(disabled.verdict().supporting()).contains("The answer given to pac-2 was left out of the response, because the answers "
                + "given disable that question.");
        assertThat(disabled.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE).get(0)).doesNotContain("bathing");
        // Every enableWhen condition is reported with whether it evaluated true.
        assertThat(disabled.detail().questionnaires().get(0).items().get(0).items().get(1).enableWhen()).singleElement().satisfies(condition -> {
            assertThat(condition.state()).isEqualTo(org.p32h.interop.epa.pave.model.dtr.ConditionState.FALSE);
            assertThat(condition.explanation()).isEqualTo("The condition does not hold.");
        });
    }

    @Test
    void anAdaptiveResponseIsCompletedByTheServiceAndTheResponseStepOnlyReportsIt() {
        RunEnvelope<DtrDetail> first = next(fetchAdaptive());
        RunEnvelope<DtrDetail> second = next(first, DtrAnswerInput.of("mri-1-therapy", "yes"));
        RunEnvelope<DtrDetail> third = next(second, DtrAnswerInput.of("mri-2-weeks", "8"));
        assertThat(third.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        String completedByTheService = third.detail().questionnaireResponses().get(0).json();

        // Once the loop has reached a completion status, the same endpoint completes the adaptive response.
        RunEnvelope<DtrDetail> completed = service().runResponse(DtrResponseRequest.builder().member(DtrFixtures.member("P32H-DTR-ADAPT"))
                .questionnaireResponse(completedByTheService).carry(third.nextStep().carry()).build(), RunOrigin.manual());
        assertThat(completed.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(completed.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(completed.nextStep().reason()).startsWith("The service reported the response as completed");
        // It is the response the service returned, unchanged, and the result says so.
        assertThat(completed.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).containsExactly(completedByTheService);
        assertThat(completed.verdict().supporting()).contains("This is the response the service last returned. This tool did not change it.");
        assertThat(ledger.last().operation()).isEqualTo(EpaOperation.DTR_RESPONSE);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, MRI);

        // A response the loop has not finished is not complete, whatever answers it holds, and the loop is where it continues.
        RunEnvelope<DtrDetail> unfinished = service().runResponse(DtrResponseRequest.builder()
                .questionnaireResponse(second.detail().questionnaireResponses().get(0).json()).carry(second.nextStep().carry())
                .expected("RESPONSE_COMPLETED").build(), RunOrigin.manual());
        assertThat(unfinished.verdict().outcomeCode()).isEqualTo("RESPONSE_NOT_COMPLETE");
        assertThat(unfinished.verdict().headline()).isEqualTo("The questionnaire response is not complete.");
        assertThat(unfinished.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(unfinished.nextStep().action()).isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(unfinished.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);

        // Answers for an adaptive questionnaire go through next-question. Posted here they are refused, not quietly merged.
        assertThatThrownBy(() -> service().runResponse(DtrResponseRequest.builder().questionnaireResponse(completedByTheService)
                .answers(List.of(DtrAnswerInput.of("mri-2-weeks", "9"))).build(), RunOrigin.manual()))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("answers");
                    assertThat(error.message()).contains("adaptive").contains("next-question");
                }));
    }

    @Test
    void aQuestionnaireWhoseKindCannotBeToldIsNeverCalledCompleteByThisTool() {
        String unmarked = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/unmarked\","
                + "\"status\":\"active\"}";
        RunEnvelope<DtrDetail> result = service().runResponse(DtrResponseRequest.builder().questionnaire(unmarked).build(), RunOrigin.manual());
        assertThat(result.verdict().outcomeCode()).isEqualTo("RESPONSE_NOT_COMPLETE");
        assertThat(result.verdict().supporting()).contains("This tool cannot tell whether this questionnaire is adaptive or static, so it "
                + "does not call its response complete.");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(result.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        // Started cold: it was handed no run, so it hands on its own.
        assertThat(result.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(result.runId());
        assertThat(ledger.only().correlation()).doesNotContainKey(RunRecord.CORR_RUN_ID);
        assertThat(ledger.only().memberId()).isNull();
        // The questionnaire and the answers are not on the line, whether or not anything was handed on.
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
    }

    @Test
    void aPackageThatArrivedWithAnswersIsCompletedFromThemAndTheyAreShownAsWhatArrived() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-PREPOP");
        assertThat(fetched.verdict().supporting()).anySatisfy(line -> assertThat(line).endsWith("It holds 5 questions, 5 of them required. "
                + "The package arrived with answers for 3 of them."));
        assertThat(fetched.detail().questionnaires().get(0).items().get(1).prepopulated()).extracting(shown -> shown.value()).containsExactly("true");
        // The condition on the third question is reported as true, because the answer that arrived enables it.
        assertThat(fetched.detail().questionnaires().get(0).items().get(2).enableWhen().get(0).state())
                .isEqualTo(org.p32h.interop.epa.pave.model.dtr.ConditionState.TRUE);

        // The response starts from the one the package arrived with, and the tester adds the one answer that is open.
        RunEnvelope<DtrDetail> result = service().runResponse(responding(fetched, "P32H-DTR-PREPOP", DtrAnswerInput.of("gen-4", "no"))
                .questionnaireResponse(fetched.detail().questionnaireResponses().get(0).json()).build(), RunOrigin.manual());
        assertThat(result.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        JsonNode completed = JsonSafe.parse(result.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE).get(0)).node();
        // What the payer put on the response is kept, and the answers it arrived with are still in it.
        assertThat(completed.get("id").asString()).isEqualTo("genetic-testing-prepopulated");
        assertThat(completed.at("/item/0/answer/0/valueString").asString()).isEqualTo("Medical genetics");
        assertThat(completed.at("/item/3/answer/0/valueCoding/code").asString()).isEqualTo("no");
        assertThat(answerSet(result.detail().form())).containsOnlyKeys("gen-1", "gen-2", "gen-3", "gen-4");
    }

    @Test
    void aResponseStepThatIsNotValidIsRefusedAndNothingIsRecorded() {
        String questionnaire = fetch("P32H-DOC-DTR").detail().questionnaires().get(0).json();
        ledger.records().clear();
        DtrService service = service();
        assertInvalidResponse(service, DtrResponseRequest.builder().build(), "questionnaire");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire)
                .answers(List.of(DtrAnswerInput.of("sleep-9", "1"))).build(), "answers[0].linkId");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire)
                .answers(List.of(DtrAnswerInput.of("sleep-2", "fourteen"))).build(), "answers[0].values[0]");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire)
                .answers(List.of(DtrAnswerInput.of("sleep-3", "not-a-symptom"))).build(), "answers[0].values[0]");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire).expected("pa-needed=no-auth").build(), "expected");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire)
                .carry(Map.of(NextStepView.CARRY_RUN_ID, List.of("the run before"))).build(), "carry.runId");
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire).chainId("my chain").build(), "chainId");
        // A response that says it answers another questionnaire is not merged into this one.
        assertInvalidResponse(service, DtrResponseRequest.builder().questionnaire(questionnaire).questionnaireResponse(
                "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://example.org/p32h-placeholder/Questionnaire/other\"}")
                .build(), "questionnaireResponse");
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).hasSize(1);
    }

    private void assertInvalidResponse(DtrService service, DtrResponseRequest request, String field) {
        assertThatThrownBy(() -> service.runResponse(request, RunOrigin.manual()))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    @Test
    void theResponseStepHasNoScenarioNoBodyOfItsOwnAndNoTargetAndSaysSoWhenOneIsPosted() {
        // Declaring these fields would mean accepting a value and then ignoring it.
        for (String field : List.of("\"scenarioId\":\"dtr-01-static-linear\"", "\"rawBody\":\"{}\"", "\"variables\":{\"a\":\"b\"}",
                "\"request\":{\"collectionId\":\"c\",\"requestId\":\"r\"}")) {
            DtrResponseRequest posted = JsonSafe.mapper().readValue("{\"questionnaire\":\"{}\"," + field + "}", DtrResponseRequest.class);
            String name = field.substring(1, field.indexOf('"', 1));
            assertThat(posted.unrecognised()).as(name).containsOnlyKeys(name);
            assertThatThrownBy(() -> service().runResponse(posted, RunOrigin.manual())).as(name)
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(name));
        }
        DtrResponseRequest plain = JsonSafe.mapper().readValue("{\"questionnaire\":\"{}\",\"answers\":[{\"linkId\":\"q1\",\"values\":[\"a\",true,3]}],"
                + "\"configEpoch\":2}", DtrResponseRequest.class);
        assertThat(plain.unrecognised()).isEmpty();
        assertThat(plain.answers().get(0).values()).hasSize(3);
        assertThat(plain.scenarioId()).isNull();
        assertThat(plain.rawBody()).isNull();
        assertThat(plain.request()).isNull();
        assertThat(plain.variables()).isEmpty();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theResponseStepIsRefusedWhenTheConfigurationChangedAfterItsScreenWasDrawn() {
        String questionnaire = fetch("P32H-DOC-DTR").detail().questionnaires().get(0).json();
        ledger.records().clear();
        // The line would be stamped with whatever environment is active now, which is not the one the tester was looking at.
        gateway.changeConfiguration();
        assertThatThrownBy(() -> service().runResponse(DtrResponseRequest.builder().questionnaire(questionnaire).configEpoch(0L).build(),
                RunOrigin.manual())).isInstanceOf(ConfigurationChangedException.class).hasMessageContaining("Reload");
        assertThat(ledger.records()).isEmpty();
        assertThat(service().runResponse(DtrResponseRequest.builder().questionnaire(questionnaire).configEpoch(1L).build(), RunOrigin.manual())
                .ledger().recorded()).isTrue();
        // With no epoch the check is not made, and a form that is not valid is told so before anything else.
        assertThat(service().runResponse(DtrResponseRequest.builder().questionnaire(questionnaire).build(), RunOrigin.manual())
                .ledger().recorded()).isTrue();
        assertThatThrownBy(() -> service().runResponse(DtrResponseRequest.builder().configEpoch(0L).build(), RunOrigin.manual()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aResponseStepWhoseLedgerCannotWriteStillReturnsItsResultAndSaysSo() {
        String questionnaire = fetch("P32H-DOC-DTR").detail().questionnaires().get(0).json();
        ledger.throwing(new IllegalStateException("disk full for Placeholder-Surname"));
        RunEnvelope<DtrDetail> result = service().runResponse(DtrResponseRequest.builder().member(DtrFixtures.member("S123456789"))
                .questionnaire(questionnaire).build(), new RunOrigin("suite-1", "case-7", null));
        assertThat(result.verdict().outcomeCode()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded").doesNotContain(DtrFixtures.SURNAME);
        assertThat(result.ledger().record().memberId()).isNull();
        assertThat(result.ledger().record().suiteRunId()).isEqualTo("suite-1");
    }

    // ---- a package with several questionnaires: the journey moves on only when each is answered ----

    @Test
    void pasIsOfferedOnlyWhenEveryQuestionnaireOfThePackageHasACompletedResponse() {
        String cardiology = "https://example.org/p32h-placeholder/Questionnaire/cardiology-criteria|1.0.0";
        String musculoskeletal = "https://example.org/p32h-placeholder/Questionnaire/musculoskeletal-criteria|1.0.0";
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-TWO");
        assertThat(fetched.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(cardiology, musculoskeletal);

        // The first questionnaire is answered in full. The response is complete, and the journey is not.
        RunEnvelope<DtrDetail> first = service().runResponse(responding(fetched, "P32H-DTR-TWO", DtrAnswerInput.of("card-1", "true"),
                DtrAnswerInput.of("card-2", "55")).build(), RunOrigin.manual());
        assertThat(first.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(first.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(first.nextStep().headline()).isEqualTo("Next: answer the other questionnaire of this package before going on to PAS: "
                + musculoskeletal + ".");
        assertThat(first.nextStep().reason()).isEqualTo("This response is complete. The package holds 2 questionnaires and one of them has no "
                + "completed response yet.");
        // What is complete is still handed on, with the whole list, so the next step can add to it.
        assertThat(first.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(cardiology, musculoskeletal);
        assertThat(first.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).hasSize(1);
        assertThat(ledger.last().nextAction()).isEqualTo("REVIEW");

        // The second questionnaire, started from the first result. Now both are complete, and both go to PAS.
        DtrResponseRequest.Builder second = DtrResponseRequest.builder().member(DtrFixtures.member("P32H-DTR-TWO"))
                .questionnaire(fetched.detail().questionnaires().get(1).json()).carry(first.nextStep().carry());
        RunEnvelope<DtrDetail> both = service().runResponse(second.answers(List.of(DtrAnswerInput.of("msk-1", "true"),
                DtrAnswerInput.of("msk-2", "4"))).build(), RunOrigin.manual());
        assertThat(both.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(both.nextStep().action()).isEqualTo(NextAction.START_PAS);
        List<String> handedOn = both.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(handedOn).hasSize(2);
        assertThat(JsonSafe.parse(handedOn.get(0)).node().get("questionnaire").asString()).isEqualTo(cardiology);
        assertThat(JsonSafe.parse(handedOn.get(1)).node().get("questionnaire").asString()).isEqualTo(musculoskeletal);
        assertThat(both.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(fetched.runId());

        // The second is answered again, and this time a required follow-up is left out. Its earlier
        // completed response is not handed on any more: the tester's current answers replace it.
        RunEnvelope<DtrDetail> reopened = service().runResponse(second.carry(both.nextStep().carry())
                .answers(List.of(DtrAnswerInput.of("msk-1", "true"), DtrAnswerInput.of("msk-2", "12"))).build(), RunOrigin.manual());
        assertThat(reopened.verdict().outcomeCode()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(reopened.nextStep().action()).isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(reopened.nextStep().carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).containsExactly(handedOn.get(0));

        // A new package fetch starts over: what was completed against an earlier fetch is not carried past it.
        RunEnvelope<DtrDetail> again = service().runPackage(DtrFixtures.packageRequest("P32H-DTR-TWO").carry(both.nextStep().carry()).build(),
                RunOrigin.manual(), null);
        assertThat(again.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        assertThat(again.nextStep().carry().get(NextStepView.CARRY_RUN_ID)).containsExactly(again.runId());
        // The fetch it followed from is on its line all the same.
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, fetched.runId());
    }

    @Test
    void somethingHandedOnAsACompletedResponseThatIsNotOneIsRefused() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DOC-DTR");
        Map<String, List<String>> carry = new LinkedHashMap<>(fetched.nextStep().carry());
        carry.put(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of("{\"resourceType\":\"Patient\",\"name\":\"" + DtrFixtures.SURNAME + "\"}"));
        assertInvalidResponse(service(), DtrResponseRequest.builder().questionnaire(fetched.detail().questionnaires().get(0).json())
                .carry(carry).build(), "carry.questionnaireResponse");
        assertInvalidStep(service(), DtrNextQuestionRequest.builder().questionnaire(stubQuestionnaire("")).carry(carry).build(),
                "carry.questionnaireResponse");
        assertThat(ledger.records()).hasSize(1);
    }

    @Test
    void aFaultPartWayThroughTheLoopOnMockLosesNothingAndTheStepCanBeSentAgainFromTheResultBeforeIt() {
        RunEnvelope<DtrDetail> fetched = fetch("P32H-DTR-LOOPERR");
        RunEnvelope<DtrDetail> first = service().runNextQuestion(following(fetched, "P32H-DTR-LOOPERR").build(), RunOrigin.manual(), null);
        assertThat(linkIds(first.detail().form().asked())).containsExactly("knee-1-imaging");

        RunEnvelope<DtrDetail> failed = service().runNextQuestion(following(first, "P32H-DTR-LOOPERR", DtrAnswerInput.of("knee-1-imaging", "true"))
                .expected("NEXT_QUESTION").build(), RunOrigin.manual(), null);
        assertThat(failed.verdict().outcomeCode()).isEqualTo("SERVICE_ERROR");
        assertThat(failed.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(failed.verdict().supporting().get(0)).startsWith("Mock scenario dtr-14-adaptive-loop-fault answered");
        assertThat(failed.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(failed.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(failed.error().issues()).hasSize(1);
        assertThat(failed.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        // What was sent is shown, with the answer in it, so nothing the tester typed is lost from view.
        assertThat(JsonSafe.parse(failed.wire().request().body()).node().at("/item/0/answer/0/valueBoolean").asBoolean()).isTrue();
        assertThat(failed.wire().request().note()).contains("step \"service-fails\"");
        assertThat(ledger.last().outcome()).isEqualTo("SERVICE_ERROR");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, fetched.runId());

        // Nothing is kept on the server, so the same step is sent again from the result before the fault.
        RunEnvelope<DtrDetail> again = service().runNextQuestion(following(first, "P32H-DTR-LOOPERR", DtrAnswerInput.of("knee-1-imaging", "true"))
                .build(), RunOrigin.manual(), null);
        assertThat(again.verdict().outcomeCode()).isEqualTo("SERVICE_ERROR");
        assertThat(again.runId()).isNotEqualTo(failed.runId());
        assertThat(ledger.records()).hasSize(4);
    }
}
