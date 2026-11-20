package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * The three DTR steps, each of them one run with its own ledger line.
 *
 * <ul>
 *   <li>{@link #runPackage} fetches the questionnaire package for an order</li>
 *   <li>{@link #runNextQuestion} is one step of the adaptive loop: it adds the tester's answers to
 *       the QuestionnaireResponse so far, sends it, and reads the questions the service now asks</li>
 *   <li>{@link #runResponse} puts the QuestionnaireResponse together inside the tool. It makes no
 *       call to the service at all</li>
 * </ul>
 *
 * <p>The two steps that make a call follow the order the CRD service fixed: validate the form, ask
 * the gateway where the call goes, build the body or take the tester's own, call the client, read
 * and word the answer, write the ledger record, return the result with the ledger's receipt.
 *
 * <p>Nothing is kept between steps. What a step needs from the one before it arrives in the
 * request: the questionnaire and the QuestionnaireResponse so far as JSON text, and the hand-off in
 * {@code carry}. Every line written for one questionnaire is linked to the package fetch it came
 * from, so the history can show the loop as one piece.
 */
@Service
public class DtrService {

    /**
     * A canonical url as it may be sent and written to the ledger: one unbroken absolute url or
     * urn, with a version after a bar when it has one.
     */
    private static final Pattern CANONICAL = Pattern.compile("(?i)(?:https?://|urn:)[^\\s\"{}<>\\\\]{1,300}");

    /** The name of a FHIR operation parameter: a short token, so nothing typed here can be mistaken for content. */
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");

    private final EpaProperties epa;
    private final OperationGateway gateway;
    private final OnyxDtrClient client;
    private final RunSupport support;
    private final DtrRequestBuilder builder;
    private final DtrResponseMapper mapper = new DtrResponseMapper();

    public DtrService(EpaProperties epa, OperationGateway gateway, OnyxDtrClient client, ProcedureCodeDirectory codeList,
                      RunSupport support) {
        this.epa = epa;
        this.gateway = gateway;
        this.client = client;
        this.support = support;
        this.builder = new DtrRequestBuilder(codeList);
    }

    /** Everything worked out from one response, before anything is written. */
    private record Interpreted(DtrReading reading, List<Finding> findings, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, ErrorView error, Timing timing, Map<String, String> correlation) {
    }

    // ---- the questionnaire package ----

    /**
     * Fetches the questionnaire package.
     *
     * @param unlock the session's production unlock, or null. The operation is a read, but a tester
     *               can point the run at any collection request, and the gateway judges that
     *               request for what it is. A suite run or a replay always passes null
     */
    public RunEnvelope<DtrDetail> runPackage(DtrPackageRequest request, RunOrigin origin, ProdUnlock unlock) {
        validatePackage(request);
        EpaOperation operation = EpaOperation.DTR_QUESTIONNAIRE_PACKAGE;
        boolean ownBody = RunSupport.ownBody(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin);
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        DtrRequestBuilder.Package sent;
        if (ownBody) {
            body = call.resolve(request.rawBody());
            sent = DtrRequestBuilder.readPackage(body.value());
            ownBodyNotes(body, requestNotes);
            if (!request.questionnaires().isEmpty() || Texts.present(request.coverageAssertionId())) {
                requestNotes.add("The questionnaires and the coverage assertion id on the form were not added to the body you supplied. "
                        + "Only what the body itself holds was sent.");
            }
            String draftNote = DtrRequestBuilder.namedDraftNote(request.carry());
            if (draftNote != null) {
                requestNotes.add(draftNote);
            }
        } else {
            sent = builder.buildPackage(request, call::resolve);
            body = ResolvedText.plain(sent.json());
            requestNotes.addAll(packageNotes(sent));
        }

        VendorExchange exchange = client.questionnairePackage(call, body, selection(request));

        // From here on the call has been made, so whatever happens next the run is recorded. The
        // steps that follow are linked to this run, so its id is settled now.
        String runId = RunStamps.newRunId();
        Map<String, String> known = correlation(DtrHandoff.first(request.carry(), NextStepView.CARRY_RUN_ID), sent.canonicals());
        // What the request itself establishes goes on the draft now, so that a line written for a
        // failure of this tool still says which codes were sent and which questionnaires were asked for.
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).runId(runId)
                .procedureCodes(sent.sentCodes()).correlation(known).unrecordedInputs(unrecordedInputs(request, typedFromAnEarlierAnswer(request)));
        Interpreted read;
        try {
            // Only the reading and wording of the response is inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one run.
            DtrReading reading = mapper.readPackage(exchange, sent.canonicals());
            List<String> questionnaires = reading.canonicals().isEmpty() ? sent.canonicals() : reading.canonicals();
            // A package fetch is where the questionnaire comes from, so the steps after it are linked to this run.
            DtrHandoff handoff = DtrHandoff.afterPackage(runId, sent.codes(), questionnaires, request.carry());
            read = interpret(operation, request, call, exchange, reading, handoff, correlation(known.get(RunRecord.CORR_RUN_ID), questionnaires),
                    ownBody ? null : DtrProfileCheck.finding(packageGaps(sent), true));
        } catch (RuntimeException e) {
            // The service was called and this tool then failed to read its answer. The run still
            // happened: it gets its line, and the tester still gets the request and the response.
            return support.toolError(operation, call, exchange, draft, request, e);
        }
        return finish(operation, request, call, exchange, draft, read, requestNotes, !ownBody);
    }

    /** What the package request would be, without sending it. Nothing is recorded. */
    public RequestPreview previewPackage(DtrPackageRequest request, ProdUnlock unlock) {
        validatePackage(request);
        EpaOperation operation = EpaOperation.DTR_QUESTIONNAIRE_PACKAGE;
        MemberIdForms shown = shownMemberId(request);
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, shown);
        }
        if (RunSupport.ownBody(request)) {
            return ownBodyPreview(operation, call, request.rawBody(), shown);
        }
        DtrRequestBuilder.Package built = builder.buildPackage(request, call::resolve);
        return RequestPreview.of(operation, call, built.json(), packageNotes(built), shown);
    }

    // ---- the adaptive loop ----

    /**
     * One step of the adaptive loop. The answers posted are added to the QuestionnaireResponse so
     * far, the response is sent, and what comes back is read: the questions now asked, the answer
     * set, the completion status and what changed since the response that was sent.
     *
     * @param unlock the session's production unlock, or null, for the same reason as a package fetch
     */
    public RunEnvelope<DtrDetail> runNextQuestion(DtrNextQuestionRequest request, RunOrigin origin, ProdUnlock unlock) {
        DtrAnswers.Posted posted = validateNextQuestion(request);
        EpaOperation operation = EpaOperation.DTR_NEXT_QUESTION;
        boolean ownBody = RunSupport.ownBody(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin);
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        JsonNode sent;
        List<DtrProfileCheck.Gap> gaps = List.of();
        if (ownBody) {
            body = call.resolve(request.rawBody());
            JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
            sent = parsed.ok() ? QuestionnaireJson.findResponse(parsed.node()) : null;
            ownBodyNotes(body, requestNotes);
            if (Texts.present(request.questionnaire()) || Texts.present(request.questionnaireResponse()) || !request.answers().isEmpty()) {
                requestNotes.add("The questionnaire, the response so far and the answers on the form were not added to the body you "
                        + "supplied. Only what the body itself holds was sent.");
            }
            if (Texts.present(request.wrapInParameter())) {
                requestNotes.add("The body you supplied was not put inside a Parameters resource. It was sent exactly as written.");
            }
        } else {
            DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(posted, filler(request, call::resolve), support.clock(),
                    request.wrapInParameter());
            body = ResolvedText.plain(built.json());
            requestNotes.addAll(loopNotes(posted, built, call));
            sent = built.response();
            gaps = built.gaps();
        }

        VendorExchange exchange = client.nextQuestion(call, body, selection(request));

        String runId = RunStamps.newRunId();
        String packageRun = DtrHandoff.first(request.carry(), NextStepView.CARRY_RUN_ID);
        String answering = first(OnyxDtrConventions.completes(sent));
        Map<String, String> known = correlation(packageRun, listOf(answering));
        // The questionnaire and the answers so far came from earlier steps and are not on the line,
        // so the line alone can never rebuild this request.
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).runId(runId)
                .procedureCodes(caseCodes(request)).correlation(known).unrecordedInputs(unrecordedInputs(request, true));
        Interpreted read;
        try {
            DtrReading reading = mapper.readNextQuestion(exchange, sent);
            // The questionnaire being completed as the response that came back names it, else as the one that was sent did.
            // Read through the mask: the name goes to the ledger line, which never holds a secret the request carried.
            String answered = Texts.orElse(first(OnyxDtrConventions.completes(
                    QuestionnaireJson.findResponse(exchange.withReturnedSecretsMasked().json()))), answering);
            DtrHandoff handoff = DtrHandoff.afterAnswering(runId, caseCodes(request), answered, request.carry());
            read = interpret(operation, request, call, exchange, reading, handoff, correlation(packageRun, listOf(answered)),
                    DtrProfileCheck.finding(gaps, true));
        } catch (RuntimeException e) {
            return support.toolError(operation, call, exchange, draft, request, e);
        }
        return finish(operation, request, call, exchange, draft, read, requestNotes, !ownBody);
    }

    /** What the next-question request would be, without sending it. Nothing is recorded. */
    public RequestPreview previewNextQuestion(DtrNextQuestionRequest request, ProdUnlock unlock) {
        DtrAnswers.Posted posted = validateNextQuestion(request);
        EpaOperation operation = EpaOperation.DTR_NEXT_QUESTION;
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, null);
        }
        if (RunSupport.ownBody(request)) {
            return ownBodyPreview(operation, call, request.rawBody(), null);
        }
        DtrRequestBuilder.LoopBody built = builder.buildNextQuestion(posted, filler(request, call::resolve), support.clock(),
                request.wrapInParameter());
        return RequestPreview.of(operation, call, built.json(), loopNotes(posted, built, call), null);
    }

    /**
     * What a tester should know about a next-question request this tool built: what the builder
     * left out or worked out, and whether the questionnaire is one the loop is meant for. A
     * questionnaire that is not marked adaptive is sent all the same, because what the service
     * makes of it is a test a tester is entitled to run.
     */
    private static List<String> loopNotes(DtrAnswers.Posted posted, DtrRequestBuilder.LoopBody built, PreparedCall call) {
        // What the request leaves out goes first, so it is read before anything that could explain a refusal another way.
        List<String> notes = new ArrayList<>(DtrProfileCheck.notes(built.gaps(), true));
        notes.addAll(built.notes());
        OnyxDtrConventions.Kind kind = OnyxDtrConventions.kind(posted.questionnaire());
        if (kind.kind() != QuestionnaireKind.ADAPTIVE) {
            notes.add("The questionnaire is not marked as adaptive. " + kind.basis() + " The next-question request is sent all the same.");
        }
        if (kind.nextQuestionBase() != null) {
            // The guide has the questionnaire say where next-question is called for it. A url is never taken from a response:
            // the gateway is the one source of where a call goes. So the two are compared, and the tester is told.
            String names = "The questionnaire names " + kind.nextQuestionBase() + " as the base address for next-question. ";
            if (!call.url().resolved()) {
                notes.add(names + "This tool sends to the address configured for the environment, and never takes one from a response.");
            } else if (OnyxDtrConventions.nextQuestionAddresses(kind.nextQuestionBase()).contains(call.url().value().replaceAll("/+$", ""))) {
                notes.add(names + "This request goes to an address the DTR guide derives from it.");
            } else {
                notes.add(names + "This request goes to the address configured for the environment, " + call.url().display()
                        + ", which is not one the DTR guide derives from it. This tool never takes an address from a response.");
            }
        }
        return notes;
    }

    // ---- the response, put together inside the tool ----

    /**
     * Puts the QuestionnaireResponse together from the answers given and says where it stands:
     * completed when every required question that is enabled has an answer, and otherwise in
     * progress with each such question named. For an adaptive questionnaire it reports the response
     * the loop last returned, which only the service can complete.
     *
     * <p>Nothing is sent, so there is no production unlock to pass and no prepared call: the
     * gateway is asked only which environment is active, so the line says where the tester was
     * working. The run is recorded like any other step.
     */
    public RunEnvelope<DtrDetail> runResponse(DtrResponseRequest request, RunOrigin origin) {
        DtrAnswers.Posted posted = validateResponse(request);
        // The line is stamped with the environment that is active now. A screen drawn under another
        // configuration would record this step against an environment its tester was not looking at.
        if (request.configEpoch() != null && request.configEpoch() != gateway.epoch()) {
            throw ConfigurationChangedException.sinceTheScreenWasDrawn();
        }
        EpaOperation operation = EpaOperation.DTR_RESPONSE;
        EnvironmentRef environment = gateway.activeEnvironment();

        // This step sends nothing and takes no run values, so a variable it reads comes from the active environment.
        DtrResponseAssembler.Assembled assembled = DtrResponseAssembler.assemble(posted,
                filler(request, template -> gateway.resolve(template, Map.of())), support.clock(), builder, mapper);
        DtrReading reading = assembled.reading();
        String runId = RunStamps.newRunId();
        String packageRun = DtrHandoff.first(request.carry(), NextStepView.CARRY_RUN_ID);
        String canonical = completing(posted, assembled.response());
        List<String> questionnaires = listOf(canonical);
        DtrHandoff handoff = DtrHandoff.afterAnswering(runId, caseCodes(request), canonical, request.carry());

        List<Finding> findings = new ArrayList<>(DtrFindings.check(reading));
        addIfPresent(findings, DtrProfileCheck.finding(assembled.gaps(), false));
        VerdictView verdict = DtrVerdicts.verdict(operation, reading, null);
        ExpectationView expectation = Expectations.assess(request.expected(), reading.outcome(), reading.asked(), reading.answers(),
                DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS);
        NextStepView nextStep = DtrVerdicts.nextStep(operation, reading, null, handoff);

        RunRecord record = support.fill(support.localDraft(operation, environment, origin), request).runId(runId)
                .procedureCodes(caseCodes(request))
                // The questionnaire and the answers are not on the line, so it can never be run again from the line alone.
                .unrecordedInputs(unrecordedInputs(request, true))
                .outcome(reading.outcome())
                .coverageCodes(reading.ledgerCodes())
                .result(expectation.result())
                .nextAction(nextStep.action())
                .correlation(correlation(packageRun, questionnaires))
                .findings(kinds(findings))
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<DtrDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.DTR)
                .operation(operation)
                .environment(environment)
                .profile(epa.mode())
                .verdict(verdict)
                .expectation(expectation)
                .nextStep(nextStep)
                .determination(determination(operation, reading))
                .detail(reading.detail())
                .findings(findings)
                .additionalFields(reading.additionalFields())
                .ledger(receipt)
                .build();
    }

    // ---- what the steps that make a call share ----

    /** @param requestFinding what the request this tool built left out, or null when it left out nothing or the tester wrote it */
    private Interpreted interpret(EpaOperation operation, RunRequest request, PreparedCall call, VendorExchange exchange, DtrReading reading,
                                  DtrHandoff handoff, Map<String, String> correlation, Finding requestFinding) {
        Timing timing = support.timing(exchange);
        List<Finding> findings = new ArrayList<>(DtrFindings.check(reading));
        addIfPresent(findings, requestFinding);
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));
        VerdictView verdict = DtrVerdicts.verdict(operation, reading, exchange);
        ExpectationView expectation = Expectations.assess(request.expected(), reading.outcome(), reading.asked(), reading.answers(),
                DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS);
        NextStepView nextStep = DtrVerdicts.nextStep(operation, reading, exchange, handoff);
        return new Interpreted(reading, findings, verdict, expectation, nextStep, error(reading, exchange), timing, correlation);
    }

    private RunEnvelope<DtrDetail> finish(EpaOperation operation, RunRequest request, PreparedCall call, VendorExchange exchange,
                                          RunRecord.Builder draft, Interpreted read, List<String> requestNotes, boolean bodyBuiltByTool) {
        RunRecord record = draft
                .outcome(read.reading().outcome())
                .coverageCodes(read.reading().ledgerCodes())
                .result(read.expectation().result())
                .nextAction(read.nextStep().action())
                .correlation(read.correlation())
                .findings(kinds(read.findings()))
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<DtrDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.DTR)
                .operation(operation)
                .environment(call.environment())
                .profile(epa.mode())
                .verdict(read.verdict())
                .expectation(read.expectation())
                .nextStep(read.nextStep())
                .determination(determination(operation, read.reading()))
                .detail(read.reading().detail().withRequest(requestNotes, bodyBuiltByTool))
                .findings(read.findings())
                .additionalFields(read.reading().additionalFields())
                // A next-question request names the member by a local reference and carries no member ID,
                // so its wire view does not show one as sent.
                .wire(exchange.wire(operation == EpaOperation.DTR_QUESTIONNAIRE_PACKAGE ? shownMemberId(request) : null))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /**
     * The error panel for a fault. A member or a provider the service does not recognise is a
     * finding about test data and is presented as an answer, even when it arrived with an error
     * status. A questionnaire that cannot be used arrived with a success status, so the shared
     * description of a transport fault has nothing to say about it, and it is described here.
     */
    private ErrorView error(DtrReading reading, VendorExchange exchange) {
        if (!reading.outcome().fault()) {
            return null;
        }
        ErrorView transport = support.faultView(exchange, reading.detail().issues());
        if (transport != null) {
            return transport;
        }
        return ErrorView.of(ErrorKind.MALFORMED_RESPONSE,
                "The service answered" + (exchange.httpStatus() == null ? "" : " with HTTP " + exchange.httpStatus())
                        + ", but what it sent cannot be used as a questionnaire.",
                "The problems are listed with this result and the body is shown in full. Report it as a defect in the service.")
                .withHttpStatus(exchange.httpStatus()).withIssues(reading.detail().issues());
    }

    /** @param origin where the run came from, so the gateway can word a refusal for a replay or a suite. Null for a preview */
    private static CallIntent intent(EpaOperation operation, RunRequest request, ProdUnlock unlock, RunOrigin origin) {
        return CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch()).withOrigin(origin);
    }

    private static MockSelection selection(RunRequest request) {
        return MockSelection.of(request.scenarioId(), RunSupport.memberId(request).sent(), RunSupport.npi(request));
    }

    private static MemberIdForms shownMemberId(RunRequest request) {
        MemberIdForms memberId = RunSupport.memberId(request);
        return memberId.typed().isEmpty() ? null : memberId;
    }

    /**
     * The tester's body is sent as written, valid or not: a malformed body is a negative case they
     * are entitled to send. They are told, so it cannot happen unnoticed.
     */
    private static void ownBodyNotes(ResolvedText body, List<String> notes) {
        JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
        if (body.resolved() && !parsed.ok()) {
            notes.add("The body you supplied is not valid JSON once its placeholders are filled in. It was sent exactly as written. "
                    + parsed.error());
        }
    }

    private static RequestPreview ownBodyPreview(EpaOperation operation, PreparedCall call, String rawBody, MemberIdForms shown) {
        ResolvedText resolved = call.resolve(rawBody);
        return RequestPreview.of(operation, call, resolved.resolved() ? resolved.display() : rawBody, resolved.resolved() ? List.of()
                : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + "."), shown);
    }

    // ---- validation: a bad form is refused before anything is sent or recorded ----

    private void validatePackage(DtrPackageRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        if (RunSupport.ownBody(request)) {
            // The tester's own body carries the member, the provider and the orders, so the form's
            // copies are optional. Whatever the form does hold is still checked.
            optionalCase(inputs, request);
        } else {
            inputs.member(request.member()).provider(request.provider());
            if (!request.procedureCodes().isEmpty()) {
                inputs.codes(request.procedureCodes());
            } else {
                // A step that follows CRD takes the codes CRD was asked about. The guide also lets a
                // package be asked for with no order at all: by canonical url, or by the context alone,
                // which is all there is to ask by after a PAS response.
                boolean context = Texts.present(request.coverageAssertionId())
                        || !DtrHandoff.values(request.carry(), NextStepView.CARRY_COVERAGE_ASSERTION_ID).isEmpty();
                inputs.require(!DtrRequestBuilder.codes(request).isEmpty() || !DtrRequestBuilder.canonicals(request).isEmpty() || context,
                        "procedureCodes", "Choose at least one procedure code, or give a questionnaire or a coverage assertion id to ask by.");
            }
        }
        for (int i = 0; i < request.questionnaires().size(); i++) {
            String canonical = request.questionnaires().get(i);
            inputs.require(Texts.blank(canonical) || CANONICAL.matcher(canonical.strip()).matches(), "questionnaires[" + i + "]",
                    "Name a questionnaire by its canonical url, with its version after a bar if it has one, for example "
                            + "https://example.org/Questionnaire/name|1.0. It must be one unbroken url.");
        }
        carriedRun(inputs, request);
        inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS)
                .scenario(request.scenarioId(), epa)
                .throwIfInvalid();
    }

    /** @return what was posted, read and checked. Null when the tester's own body is sent in its place */
    private DtrAnswers.Posted validateNextQuestion(DtrNextQuestionRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        DtrAnswers.Posted posted = null;
        if (!RunSupport.ownBody(request)) {
            posted = DtrAnswers.read(request.questionnaire(), request.questionnaireResponse(), request.answers());
            posted.errors().forEach(error -> inputs.require(false, error.field(), error.message()));
            authorNamed(inputs, posted, request);
        }
        inputs.require(Texts.blank(request.wrapInParameter()) || PARAMETER_NAME.matcher(request.wrapInParameter().strip()).matches(),
                "wrapInParameter", "Leave this empty to send the QuestionnaireResponse by itself. To send it inside a Parameters resource, "
                        + "give the name of the parameter to put it under: " + OnyxDtrConventions.WRAP_SDC + " as SDC defines it, or "
                        + OnyxDtrConventions.WRAP_DTR_BALLOT + " as DTR 2.2.0-ballot defines it.");
        optionalCase(inputs, request);
        carriedRun(inputs, request);
        inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS)
                .scenario(request.scenarioId(), epa)
                .throwIfInvalid();
        return posted;
    }

    private DtrAnswers.Posted validateResponse(DtrResponseRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        DtrAnswers.Posted posted = DtrAnswers.read(request.questionnaire(), request.questionnaireResponse(), request.answers());
        posted.errors().forEach(error -> inputs.require(false, error.field(), error.message()));
        if (posted.errors().isEmpty()) {
            List<org.p32h.interop.epa.pave.model.FieldError> problems = DtrResponseAssembler.problems(posted);
            problems.forEach(error -> inputs.require(false, error.field(), error.message()));
            if (problems.isEmpty()) {
                authorNamed(inputs, posted, request);
            }
        }
        optionalCase(inputs, request);
        carriedRun(inputs, request);
        inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), DtrOutcome.values(), DtrCodes.EXPECTATION_ELEMENTS)
                .throwIfInvalid();
        return posted;
    }

    /**
     * An answer this tool marks as given or changed by a person must name its author: invariant
     * dtrx-1 of the DTR 2.0.1 information-origin extension. The author this tool names is the
     * provider on the form, so without one the step is refused rather than sent with an extension
     * that does not conform.
     */
    private static void authorNamed(RunInputs inputs, DtrAnswers.Posted posted, RunRequest request) {
        if (posted.errors().isEmpty() && DtrRequestBuilder.marksAnAnswer(posted)) {
            inputs.require(request.provider() != null && Texts.present(request.provider().npi()), "provider.npi", AUTHOR_NEEDED);
        }
    }

    /** Why a step that adds an answer with no provider is refused. */
    static final String AUTHOR_NEEDED = "An answer given in this step is marked as given by a person, and DTR 2.0.1 then requires the "
            + "answer to name its author (invariant dtrx-1 of the information-origin extension). This tool names the provider on the form "
            + "as the author, so give the provider's NPI. An answer posted as a FHIR answer that already carries its own information-origin "
            + "extension is sent as written.";

    /** The notes of a package request this tool built: what it leaves out that DTR 2.0.1 requires first, then the builder's own. */
    private static List<String> packageNotes(DtrRequestBuilder.Package built) {
        List<String> notes = new ArrayList<>(DtrProfileCheck.notes(packageGaps(built), true));
        notes.addAll(built.notes());
        return notes;
    }

    private static List<DtrProfileCheck.Gap> packageGaps(DtrRequestBuilder.Package built) {
        JsonSafe.Parsed parsed = JsonSafe.parse(built.json());
        return parsed.ok() ? DtrProfileCheck.packageRequest(parsed.node()) : List.of();
    }

    /**
     * The member, the provider and the codes of a step that sends none of them as a resource. They
     * are taken so the run is recorded against the same case as the steps before it, and so the
     * response can say who answered and which case it is for. So each is optional, and
     * whatever is given is still checked.
     */
    private static void optionalCase(RunInputs inputs, RunRequest request) {
        if (request.member() != null && Texts.present(request.member().memberId())) {
            inputs.member(request.member());
        }
        if (request.provider() != null && Texts.present(request.provider().npi())) {
            inputs.provider(request.provider());
        }
        List<CodeInput> codes = request.procedureCodes() == null ? List.of() : request.procedureCodes();
        for (int i = 0; i < codes.size(); i++) {
            inputs.require(codes.get(i) != null && Texts.present(codes.get(i).code()), "procedureCodes[" + i + "].code",
                    "This procedure code is blank.");
        }
    }

    /**
     * What was handed on is checked before it is used. The run a step follows from is written to
     * the ledger as it stands, so it cannot be free text. A completed response is handed on again
     * for PAS to submit, so it has to be a QuestionnaireResponse.
     */
    private static void carriedRun(RunInputs inputs, RunRequest request) {
        String runId = DtrHandoff.first(request.carry(), NextStepView.CARRY_RUN_ID);
        inputs.require(runId == null || isRunId(runId), "carry." + NextStepView.CARRY_RUN_ID,
                "The run this step follows from must be a run id this tool returned.");
        List<String> responses = request.carry() == null ? null : request.carry().get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
        if (responses != null) {
            for (String response : responses) {
                JsonSafe.Parsed parsed = JsonSafe.parse(response);
                inputs.require(parsed.ok() && QuestionnaireJson.isType(parsed.node(), "QuestionnaireResponse"),
                        "carry." + NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, "A completed response handed on from an earlier step is not a "
                                + "QuestionnaireResponse. Start from the result of the step before this one.");
            }
        }
    }

    /** True when the text has the shape of a run id this tool mints. */
    static boolean isRunId(String text) {
        if (text == null || !text.startsWith("run-")) {
            return false;
        }
        try {
            return UUID.fromString(text.substring(4)).toString().equals(text.substring(4));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- the ledger line ----

    /**
     * The procedure codes of the case a step belongs to: those on the form, else those handed on.
     * A step of the loop sends neither. They are recorded so the line reads against the same case.
     */
    private static List<String> caseCodes(RunRequest request) {
        List<String> typed = RunSupport.codes(request);
        return typed.isEmpty() ? DtrHandoff.values(request.carry(), NextStepView.CARRY_PROCEDURE_CODES) : typed;
    }

    /**
     * Who is answering and for which case, for a step that answers a questionnaire. The orders are
     * the ones the package request for this case carries, chosen by the same rule: the orders CRD's
     * answer applied to when it handed any on that this tool builds, otherwise one for each code.
     */
    private static DtrRequestBuilder.Filler filler(RunRequest request, java.util.function.Function<String, ResolvedText> variables) {
        List<String> carried = DtrHandoff.values(request.carry(), NextStepView.CARRY_ORDER);
        List<String> every = new ArrayList<>();
        List<String> chosen = new ArrayList<>();
        for (int position = 1; position <= caseCodes(request).size(); position++) {
            String reference = CaseResources.serviceRequestRef(position);
            every.add(reference);
            if (carried.contains(reference) || carried.contains(CaseResources.serviceRequestId(position))) {
                chosen.add(reference);
            }
        }
        return new DtrRequestBuilder.Filler(request.member(), request.provider(), chosen.isEmpty() ? every : chosen,
                DtrResponseShape.read(variables, request.carry()));
    }

    /** True when the form names a questionnaire or a coverage assertion id, both of which an earlier answer supplied. */
    private static boolean typedFromAnEarlierAnswer(DtrPackageRequest request) {
        return request.questionnaires().stream().anyMatch(Texts::present) || Texts.present(request.coverageAssertionId());
    }

    /**
     * The inputs a line cannot reproduce. Beyond what every protocol lists, a DTR step is marked
     * as having used something an earlier step produced whenever it did, whether that arrived in
     * the hand-off or was typed on the form: a replay from the line would send a different request.
     */
    private static List<String> unrecordedInputs(RunRequest request, boolean usedAnEarlierStep) {
        List<String> inputs = new ArrayList<>(RunSupport.unrecordedInputs(request));
        if (usedAnEarlierStep && !inputs.contains(RunRecord.INPUT_PRIOR_STEP)) {
            inputs.add(RunRecord.INPUT_PRIOR_STEP);
        }
        return inputs;
    }

    /**
     * The identifiers that tie the lines of one questionnaire together: the package fetch a step
     * follows from, and the canonical url of each questionnaire it fetched or answered.
     */
    private static Map<String, String> correlation(String followsRunId, List<String> canonicals) {
        Map<String, String> correlation = new LinkedHashMap<>();
        if (Texts.present(followsRunId)) {
            correlation.put(RunRecord.CORR_RUN_ID, followsRunId);
        }
        if (!canonicals.isEmpty()) {
            // The addresses as they came. The record keeps them only when every one is an address,
            // and otherwise writes its own statement that something was there and was not recorded,
            // which replay refuses. A statement of this class's own in place of one address would
            // put text on the line where an address belongs.
            correlation.put(RunRecord.CORR_QUESTIONNAIRE, String.join(" ", canonicals));
        }
        return correlation;
    }

    /**
     * The canonical url of the questionnaire a response step is completing. A response from the
     * adaptive loop says which through the questionnaire inside it. A conventional form is the
     * questionnaire of the package itself, named by its own url, so that its line carries the same
     * name as the package fetch it came from.
     */
    private static String completing(DtrAnswers.Posted posted, JsonNode response) {
        String own = QuestionnaireJson.canonical(posted.questionnaire());
        boolean inside = QuestionnaireJson.containedQuestionnaire(response) != null;
        return inside || own == null ? first(OnyxDtrConventions.completes(response)) : own;
    }

    private static String first(List<String> canonicals) {
        return canonicals.isEmpty() ? null : canonicals.get(0);
    }

    private static List<String> listOf(String canonical) {
        return canonical == null ? List.of() : List.of(canonical);
    }

    private static List<String> kinds(List<Finding> findings) {
        return findings.stream().map(finding -> finding.kind().name()).distinct().toList();
    }

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }

    // ---- the determination grid ----

    /**
     * The determination grid: the coded facts of the step and what they mean. For a package, the
     * kind and the status of each questionnaire. For a step of the loop or a response, the
     * completion status and the counts. What would have answered and did not arrive is still
     * listed, as not returned, so a package with no questionnaire looks different at a glance from
     * one whose questionnaire asks nothing.
     */
    static List<DeterminationRow> determination(EpaOperation operation, DtrReading reading) {
        List<DeterminationRow> rows = new ArrayList<>();
        DtrDetail detail = reading.detail();
        FormStateView form = detail.form();
        if (form != null) {
            rows.add(coded(null, "Completion status", DtrCodes.RESPONSE_STATUS, form.status()));
            rows.add(counted(null, "Questions so far", DtrCodes.QUESTIONS, form.questionCount()));
            rows.add(counted(null, "Answered", DtrCodes.ANSWERED, form.answeredCount()));
            rows.add(counted(null, "Required and unanswered", DtrCodes.REQUIRED_UNANSWERED, form.requiredUnanswered().size()));
            return rows;
        }
        if (operation != EpaOperation.DTR_QUESTIONNAIRE_PACKAGE) {
            rows.add(DeterminationRow.notReturned(null, "Completion status", DtrCodes.RESPONSE_STATUS));
            return rows;
        }
        List<QuestionnaireView> questionnaires = detail.questionnaires();
        if (questionnaires.isEmpty() && detail.notReturned().isEmpty()) {
            rows.add(DeterminationRow.notReturned(null, "Questionnaire kind", DtrCodes.QUESTIONNAIRE_KIND));
            rows.add(DeterminationRow.notReturned(null, "Questionnaire status", DtrCodes.QUESTIONNAIRE_STATUS));
        }
        boolean named = questionnaires.size() + detail.notReturned().size() > 1;
        for (QuestionnaireView questionnaire : questionnaires) {
            String subject = named ? questionnaire.subject() : null;
            rows.add(new DeterminationRow(subject, "Questionnaire kind", questionnaire.kind().code(), questionnaire.kind().label(),
                    DtrCodes.QUESTIONNAIRE_KIND, true, Presence.VALUE));
            rows.add(coded(subject, "Questionnaire status", DtrCodes.QUESTIONNAIRE_STATUS, questionnaire.status()));
            rows.add(counted(subject, "Questions", DtrCodes.QUESTIONS, questionnaire.questionCount()));
        }
        // Each questionnaire that was asked for and is not in the package has its own rows, so it
        // cannot be read as covered by the ones that did come back.
        for (String canonical : detail.notReturned()) {
            rows.add(DeterminationRow.notReturned(canonical, "Questionnaire kind", DtrCodes.QUESTIONNAIRE_KIND));
            rows.add(DeterminationRow.notReturned(canonical, "Questionnaire status", DtrCodes.QUESTIONNAIRE_STATUS));
        }
        return rows;
    }

    private static DeterminationRow coded(String subject, String label, String element, DtrCode value) {
        if (value == null) {
            return DeterminationRow.notReturned(subject, label, element);
        }
        return new DeterminationRow(subject, label, value.code(), value.display(), element, value.recognised(), value.presence());
    }

    private static DeterminationRow counted(String subject, String label, String element, int count) {
        return new DeterminationRow(subject, label, String.valueOf(count), null, element, true, Presence.VALUE);
    }
}
