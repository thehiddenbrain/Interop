package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.pas.OnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.CarriedMasks;
import org.p32h.interop.epa.pave.model.MaskedReading;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * One PAS submission or one inquiry, end to end, in the order every protocol follows: validate the
 * form, ask the gateway where the call goes and whether it may go, build the body, call the
 * service, read the answer, write the ledger record, return the result.
 *
 * <p>A submission is a write. On a production environment the gateway refuses it unless the
 * session holds an unlock, and that refusal is returned as a result that says nothing was sent.
 * The unlock comes from the session and is handed to the gateway untouched: nothing in a posted
 * request can stand in for it. An inquiry is a read and is allowed on production.
 *
 * <p>The claim of every submission carries an identifier, and that identifier is written to the
 * ledger line. It is what an inquiry asks about, and what a notification is matched by.
 */
@Service
public class PasService {

    /** What a claim id may look like. It is written to the ledger as it stands, so it is one token and never free text. */
    private static final Pattern CLAIM_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final String NEW_ID = "(a new id is generated when the request is sent)";

    private final OperationGateway gateway;
    private final OnyxPasClient client;
    private final RunSupport support;
    private final ObjectProvider<RunLedger> ledger;
    private final PasRequestBuilder builder;
    private final PasResponseMapper mapper = new PasResponseMapper();

    /**
     * @param onyx   read for the attachment mode, which is checked at startup
     * @param ledger asked which run submitted a claim, so an inquiry started from a typed claim id
     *               is still linked to its submission. A run is recorded through {@code support}
     */
    public PasService(OnyxProperties onyx, OperationGateway gateway, OnyxPasClient client, ProcedureCodeDirectory codeList,
                      RunSupport support, ObjectProvider<RunLedger> ledger) {
        this.gateway = gateway;
        this.client = client;
        this.support = support;
        this.ledger = ledger;
        this.builder = new PasRequestBuilder(codeList, onyx.pas().attachmentMode(), support.clock());
    }

    /**
     * Submits one prior authorization request.
     *
     * @param unlock the session's production unlock, or null. A suite run or a replay always passes null
     */
    public RunEnvelope<PasDetail> runSubmit(PasSubmitRequest request, RunOrigin origin, ProdUnlock unlock) {
        validateSubmit(request);
        EpaOperation operation = EpaOperation.PAS_SUBMIT;
        boolean ownBody = RunSupport.ownBody(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin, aboutRefused(request, first(request, NextStepView.CARRY_RUN_ID)));
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        SentClaim sent;
        String attachmentMode = null;
        List<String> leftOut = List.of();
        if (ownBody) {
            body = call.resolve(request.rawBody());
            sent = SentClaim.fromBody(body.value());
            ownBodyNotes(body, sent, requestNotes);
            if (Texts.present(request.claimId()) || !request.diagnosisCodes().isEmpty() || !request.attachments().isEmpty()
                    || first(request, NextStepView.CARRY_QUESTIONNAIRE_RESPONSE) != null) {
                requestNotes.add("The claim id, the diagnosis codes, the attachments and the questionnaire answers on the form were not "
                        + "added to the body you supplied. Only what the body itself holds was sent.");
            }
        } else {
            CaseResources.Reads reads = CaseResources.reading(call::resolve);
            PasRequestBuilder.Built built = builder.submit(request, claimId(request, true), claimId(request, false) == null,
                    "urn:uuid:" + UUID.randomUUID(), reads, call.memberIdRule());
            body = reads.body(built.json());
            requestNotes.addAll(built.notes());
            sent = built.sent();
            attachmentMode = built.attachmentMode();
            leftOut = built.leftOut();
        }

        VendorExchange exchange = client.submit(call, body, MockSelection.of(request.scenarioId(), mockKey(request), RunSupport.npi(request)));

        // From here on the call has been made, so whatever happens next the run is recorded. A later
        // step is linked to this run, so its id is settled now.
        String runId = RunStamps.newRunId();
        String follows = first(request, NextStepView.CARRY_RUN_ID);
        return finish(operation, request, origin, call, exchange, sent, requestNotes, leftOut, attachmentMode, ownBody, runId, follows,
                runId);
    }

    /**
     * Asks for the status of a submitted request. A read: it is allowed on production. The unlock is
     * passed all the same, because a tester can point the run at any collection request and the
     * gateway judges that request for what it is.
     */
    public RunEnvelope<PasDetail> runInquire(PasInquireRequest request, RunOrigin origin, ProdUnlock unlock) {
        validateInquire(request);
        EpaOperation operation = EpaOperation.PAS_INQUIRE;
        boolean ownBody = RunSupport.ownBody(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            // Linked as an inquiry that was sent would be: to the run the carry names, otherwise to the submission of that claim.
            return support.blocked(operation, refusal, request, origin, aboutRefused(request,
                    Texts.firstPresent(first(request, NextStepView.CARRY_RUN_ID), submissionOf(namedClaimId(request)))));
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        SentClaim sent;
        List<String> leftOut = List.of();
        if (ownBody) {
            body = call.resolve(request.rawBody());
            sent = SentClaim.fromBody(body.value());
            ownBodyNotes(body, sent, requestNotes);
        } else {
            String claimId = claimId(request, false);
            Codes codes = inquiryCodes(request, claimId);
            CaseResources.Reads reads = CaseResources.reading(call::resolve);
            PasRequestBuilder.Built built = builder.inquire(request, claimId, codes.codes(), codes.from(), "urn:uuid:" + UUID.randomUUID(),
                    reads, call.memberIdRule());
            body = reads.body(built.json());
            requestNotes.addAll(built.notes());
            sent = built.sent();
            leftOut = built.leftOut();
        }

        VendorExchange exchange = client.inquire(call, body, MockSelection.of(request.scenarioId(), mockKey(request), RunSupport.npi(request)));

        // The submission this inquiry follows from: the one the carry names, otherwise the run the
        // ledger holds for the claim id that was asked about.
        String submission = Texts.firstPresent(first(request, NextStepView.CARRY_RUN_ID), submissionOf(sent.claimId()));
        return finish(operation, request, origin, call, exchange, sent, requestNotes, leftOut, null, ownBody, RunStamps.newRunId(), submission,
                submission);
    }

    /**
     * Everything after the call, the same for a submission and an inquiry: read the answer, word
     * it, compare it with the expectation, write the ledger line, return the result.
     *
     * @param correlatedRunId the earlier run this one follows from, for the ledger line, or null
     * @param followsRunId    the run a later step should be linked to, handed on in the next step
     */
    private RunEnvelope<PasDetail> finish(EpaOperation operation, RunRequest request, RunOrigin origin, PreparedCall call,
                                          VendorExchange exchange, SentClaim sent, List<String> requestNotes, List<String> leftOut,
                                          String attachmentMode,
                                          boolean ownBody, String runId, String correlatedRunId, String followsRunId) {
        MemberIdForms memberId = MemberIds.forms(typedMemberId(request), call.memberIdRule());
        Map<String, String> known = new LinkedHashMap<>();
        if (Texts.present(sent.claimId())) {
            known.put(RunRecord.CORR_CLAIM_ID, sent.claimId());
        }
        if (Texts.present(correlatedRunId)) {
            known.put(RunRecord.CORR_RUN_ID, correlatedRunId);
        }
        // What the request itself establishes goes on the draft now, so that a line written for a
        // failure of this tool still says which claim was sent.
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).runId(runId)
                .attachmentMode(attachmentMode).correlation(known);
        if (ownBody) {
            // The codes that went out are the ones in the tester's body, not the ones left on the form.
            draft.procedureCodes(sent.codes());
        }
        // Nothing was sent: no answer to read, and an outcome rule would read the missing one as the service being down.
        if (RunSupport.notSignedIn(exchange)) {
            return support.notSignedIn(operation, call, exchange, draft, request);
        }
        Interpreted read;
        try {
            // Only the reading and wording of the response is inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one run.
            read = interpret(request, operation, call, exchange, sent, known, followsRunId, leftOut, runId);
        } catch (RuntimeException e) {
            // The service was called and this tool then failed to read its answer. The run still
            // happened: it gets its line, and the tester still gets the request and the response.
            return support.toolError(operation, call, exchange, draft, request, e);
        }

        RunRecord record = draft
                .outcome(read.reading().outcome())
                .coverageCodes(read.reading().ledgerCodes())
                .result(read.expectation().result())
                .nextAction(read.nextStep().action())
                .correlation(read.correlation())
                .findings(read.findings().stream().map(f -> f.kind().name()).distinct().toList())
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<PasDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.PAS)
                .operation(operation)
                .environment(call.environment())
                .profile(support.epa().mode())
                .verdict(read.verdict())
                .expectation(read.expectation())
                .nextStep(read.nextStep())
                .determination(PasVerdicts.determination(read.reading()))
                .detail(read.reading().detail().withRequest(sent.claimId(), attachmentMode, requestNotes, !ownBody))
                .findings(read.findings())
                .additionalFields(read.reading().additionalFields())
                .wire(exchange.wire(memberId.typed().isEmpty() ? null : memberId))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /** Everything worked out from one response, before anything is written. */
    private record Interpreted(PasReading reading, List<Finding> findings, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, ErrorView error, Timing timing, Map<String, String> correlation) {

        /** The correlation holds the authorization number as returned, which is guarded as a member id is. Only the outcome is printed. */
        @Override
        public String toString() {
            return "Interpreted[" + reading.outcome() + "]";
        }
    }

    private Interpreted interpret(RunRequest request, EpaOperation operation, PreparedCall call, VendorExchange exchange, SentClaim sent,
                                  Map<String, String> known, String followsRunId, List<String> leftOut, String runId) {
        PasReading reading = mapper.read(exchange, sent, operation == EpaOperation.PAS_INQUIRE);
        PasOutcome outcome = reading.outcome();
        Timing timing = support.timing(exchange);

        List<Finding> findings = new ArrayList<>();
        if (!leftOut.isEmpty()) {
            findings.add(PasFindings.requestIncomplete(leftOut));
        }
        findings.addAll(PasFindings.check(reading));
        // A key the body repeats is said with every value, as on CRD; the values are already among the returned fields.
        findings.addAll(TrackedJson.repeatedKeyFindings(exchange, reading.additionalFields()));
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));

        VerdictView verdict = PasVerdicts.verdict(reading, exchange, operation, known.get(RunRecord.CORR_CLAIM_ID),
                known.get(RunRecord.CORR_RUN_ID));
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, reading.asked(), reading.answers(),
                PasOutcome.values(), PasCodes.EXPECTATION_ELEMENTS);
        NextStepView nextStep = support.unsignedNextStep(PasVerdicts.nextStep(reading, exchange, operation, followsRunId, runId), exchange);
        // A mask on a value this verdict is read by changes the verdict without changing its rules, so the verdict, the next step
        // and the findings say so where each is read.
        MaskedReading masked = MaskedReading.of(exchange, MaskedReading.Reading.PAS);
        if (masked != null) {
            findings.add(0, masked.finding());
            verdict = masked.on(verdict);
            nextStep = masked.on(nextStep);
        }
        // The error panel is for a fault. A member the service does not recognise is a finding
        // about test data and is presented as an answer, even when it arrived with an error status.
        // A timeout is worded from the read timeout this call ran under, which the environment may set.
        ErrorView error = outcome.fault() ? support.faultView(exchange, reading.detail().issues(), call) : null;

        Map<String, String> correlation = new LinkedHashMap<>(known);
        if (Texts.present(reading.claimResponseId())) {
            correlation.put(RunRecord.CORR_CLAIM_RESPONSE_ID, reading.claimResponseId());
        }
        if (Texts.present(reading.authorizationRef())) {
            correlation.put(RunRecord.CORR_AUTHORIZATION_REF, reading.authorizationRef());
        }
        return new Interpreted(reading, findings, verdict, expectation, nextStep, error, timing, correlation);
    }

    /** What the submission would be, without sending it. Nothing is recorded. */
    public RequestPreview previewSubmit(PasSubmitRequest request, ProdUnlock unlock) {
        validateSubmit(request);
        EpaOperation operation = EpaOperation.PAS_SUBMIT;
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, shown(request, gateway.memberIdRule()));
        }
        MemberIdForms shown = shown(request, call.memberIdRule());
        if (RunSupport.ownBody(request)) {
            return ownBodyPreview(operation, call, request, shown);
        }
        CaseResources.Reads reads = CaseResources.reading(call::resolve);
        PasRequestBuilder.Built built = builder.submit(request, Texts.orElse(claimId(request, false), NEW_ID), claimId(request, false) == null,
                NEW_ID, reads, call.memberIdRule());
        return RequestPreview.of(operation, call, reads.shown(built.json()), PasNotes.asPreview(built.notes()), shown)
                .withToComplete(built.toComplete());
    }

    /** What the inquiry would be, without sending it. Nothing is recorded. */
    public RequestPreview previewInquire(PasInquireRequest request, ProdUnlock unlock) {
        validateInquire(request);
        EpaOperation operation = EpaOperation.PAS_INQUIRE;
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, shown(request, gateway.memberIdRule()));
        }
        MemberIdForms shown = shown(request, call.memberIdRule());
        if (RunSupport.ownBody(request)) {
            return ownBodyPreview(operation, call, request, shown);
        }
        String claimId = claimId(request, false);
        Codes codes = inquiryCodes(request, claimId);
        CaseResources.Reads reads = CaseResources.reading(call::resolve);
        PasRequestBuilder.Built built = builder.inquire(request, claimId, codes.codes(), codes.from(), NEW_ID, reads, call.memberIdRule());
        return RequestPreview.of(operation, call, reads.shown(built.json()), PasNotes.asPreview(built.notes()), shown);
    }

    private static RequestPreview ownBodyPreview(EpaOperation operation, PreparedCall call, RunRequest request, MemberIdForms shown) {
        ResolvedText resolved = call.resolve(request.rawBody());
        String body = resolved.resolved() ? resolved.display() : request.rawBody();
        List<String> notes = resolved.resolved() ? List.of()
                : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + ".");
        return RequestPreview.of(operation, call, body, notes, shown);
    }

    /** The member ID as typed and as the environment's rule sends it, or null when none was typed. */
    private static MemberIdForms shown(RunRequest request, MemberIdRule rule) {
        MemberIdForms memberId = MemberIds.forms(typedMemberId(request), rule);
        return memberId.typed().isEmpty() ? null : memberId;
    }

    private static String typedMemberId(RunRequest request) {
        return request.member() == null ? null : request.member().memberId();
    }

    /**
     * The member ID the mock answers by: always the packed form, whatever the environment's rule,
     * because the mock's members are keyed by one form and a rule changes only what goes on the wire.
     */
    private static String mockKey(RunRequest request) {
        return MemberIds.noSpace(typedMemberId(request));
    }

    /** @param origin where the run came from, so the gateway can word a refusal for a replay or a suite. Null for a preview */
    private static CallIntent intent(EpaOperation operation, RunRequest request, ProdUnlock unlock, RunOrigin origin) {
        return CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch()).withOrigin(origin);
    }

    /**
     * The identifier the claim carries: the one typed on the form, else the one an earlier step
     * handed on, else, for a submission that is being sent, one minted here.
     *
     * @param mint true to mint one when neither is given. False returns null in that case
     */
    private static String claimId(RunRequest request, boolean mint) {
        String typed = request instanceof PasSubmitRequest submit ? submit.claimId()
                : request instanceof PasInquireRequest inquire ? inquire.claimId() : null;
        String given = Texts.firstPresent(typed, first(request, NextStepView.CARRY_CLAIM_ID));
        return given != null || !mint ? given : "claim-" + UUID.randomUUID();
    }

    /**
     * What a run the guardrail refused was about, for its ledger line: the claim the request names
     * and the run it follows from. Nothing was sent, so nothing is resolved and no claim id is
     * minted: a line that named an id this tool made up for a request that never went would point
     * at a claim that does not exist.
     *
     * @param followsRunId the earlier run the refused one follows from, or null
     */
    private static Map<String, String> aboutRefused(RunRequest request, String followsRunId) {
        Map<String, String> about = new LinkedHashMap<>();
        String claimId = namedClaimId(request);
        if (claimId != null) {
            about.put(RunRecord.CORR_CLAIM_ID, claimId);
        }
        if (Texts.present(followsRunId)) {
            about.put(RunRecord.CORR_RUN_ID, followsRunId);
        }
        return about;
    }

    /**
     * The claim id a request names before anything is resolved or built: the one on the form or
     * carried from an earlier step, or, for a body of the tester's own, the one written in that
     * body. One that is still a placeholder, or is not the single word a claim id is, names no
     * claim. Null when the request names none.
     */
    private static String namedClaimId(RunRequest request) {
        if (!RunSupport.ownBody(request)) {
            return claimId(request, false);
        }
        String written = Texts.trimToNull(SentClaim.fromBody(request.rawBody()).claimId());
        return written != null && CLAIM_ID.matcher(written).matches() ? written : null;
    }

    /** The first present value an earlier step handed on under a key, or null. */
    private static String first(RunRequest request, String key) {
        List<String> values = request.carry() == null ? null : request.carry().get(key);
        return values == null ? null : values.stream().filter(Texts::present).map(String::strip).findFirst().orElse(null);
    }

    /**
     * The run that submitted a claim, as the ledger holds it, or null. Looked up through the
     * ledger's own correlation filter, never by comparing stored values here. A submission the
     * guardrail refused names its claim too and sent nothing, so it is never the one. A ledger
     * that is not there, or that fails, leaves the inquiry unlinked. It never costs the tester
     * their result.
     */
    private String submissionOf(String claimId) {
        RunRecord submitted = submissionRecord(claimId);
        return submitted == null ? null : submitted.runId();
    }

    /** The ledger line of the newest submission of a claim, or null, on the same terms as {@link #submissionOf}. */
    private RunRecord submissionRecord(String claimId) {
        if (Texts.blank(claimId)) {
            return null;
        }
        try {
            RunLedger target = ledger == null ? null : ledger.getIfAvailable();
            return target == null ? null : SentRuns.newest(target, EpaOperation.PAS_SUBMIT, RunRecord.CORR_CLAIM_ID, claimId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The codes an inquiry lists as items, and where they came from, in a few words for the request note. */
    private record Codes(List<CodeInput> codes, String from) {
    }

    /**
     * The codes of the submission an inquiry asks about, in the order it sent them: the ones on the
     * form, else the ones the submission handed on, else the ones on the ledger line of the newest
     * submission of that claim identifier. PAS 2.2.1 does not require an item on an inquiry, but each
     * decision of the answer can be tied to a code only when the inquiry lists them.
     */
    private Codes inquiryCodes(PasInquireRequest request, String claimId) {
        if (!request.procedureCodes().isEmpty()) {
            return new Codes(request.procedureCodes(), "given on the form");
        }
        List<String> handedOn = request.carry() == null ? null : request.carry().get(NextStepView.CARRY_PROCEDURE_CODES);
        List<CodeInput> carried = handedOn == null ? List.of()
                : handedOn.stream().filter(Texts::present).map(String::strip).map(CodeInput::of).toList();
        if (!carried.isEmpty()) {
            return new Codes(carried, "handed on from the submission");
        }
        RunRecord submitted = submissionRecord(claimId);
        if (submitted != null && !submitted.procedureCodes().isEmpty()) {
            return new Codes(submitted.procedureCodes().stream().map(CodeInput::of).toList(),
                    "found on the ledger line of the submission " + submitted.runId());
        }
        return new Codes(List.of(), null);
    }

    /** What a tester should know about a body of their own: that it is not JSON, or that it names no claim. */
    private static void ownBodyNotes(ResolvedText body, SentClaim sent, List<String> notes) {
        // The tester's body is sent as written, valid or not: a malformed body is a negative case
        // they are entitled to send. They are told, so it cannot happen unnoticed.
        JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
        if (body.resolved() && !parsed.ok()) {
            notes.add("The body you supplied cannot be read as JSON once its placeholders are filled in. It was sent exactly as written. "
                    + parsed.error());
        } else if (body.resolved() && Texts.blank(sent.claimId())) {
            notes.add("The body you supplied carries no claim identifier this tool could read, so this run has no claim id. An inquiry or "
                    + "a notification cannot be matched to it by claim id.");
        }
    }

    private void validateSubmit(PasSubmitRequest request) {
        RunInputs inputs = shared(request);
        claimIdShape(inputs, "claimId", request.claimId());
        if (!RunSupport.ownBody(request)) {
            // An NPI, or the vendor's own practitioner id with the NPI then optional (R-40).
            inputs.member(request.member()).providerByNpiOrVendorId(request.provider()).codes(request.procedureCodes());
            // The card's fields go into the built request only, so a body of the tester's own is not refused for them.
            PasCardChecks.check(request, inputs);
        }
        for (int i = 0; i < request.diagnosisCodes().size(); i++) {
            CodeInput code = request.diagnosisCodes().get(i);
            inputs.require(code != null && Texts.present(code.code()), "diagnosisCodes[" + i + "].code", "This diagnosis code is blank.");
        }
        PasAttachmentChecks.check(request.attachments(), inputs);
        String problem = PasRequestBuilder.questionnaireProblem(request.carry());
        inputs.require(problem == null, "carry." + NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, problem);
        inputs.throwIfInvalid();
    }

    private void validateInquire(PasInquireRequest request) {
        RunInputs inputs = shared(request);
        claimIdShape(inputs, "claimId", request.claimId());
        if (!RunSupport.ownBody(request)) {
            inputs.require(claimId(request, false) != null, "claimId",
                    "Enter the claim id of the submission to ask about, or start the inquiry from a submission.");
            inputs.member(request.member()).providerByNpiOrVendorId(request.provider());
            // The codes are optional for an inquiry. When they are given, none may be blank.
            for (int i = 0; i < request.procedureCodes().size(); i++) {
                CodeInput code = request.procedureCodes().get(i);
                inputs.require(code != null && Texts.present(code.code()), "procedureCodes[" + i + "].code", "This procedure code is blank.");
            }
        }
        inputs.throwIfInvalid();
    }

    /** The checks a submission and an inquiry share. */
    private RunInputs shared(RunRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        if (RunSupport.ownBody(request)) {
            // The tester's own body carries the member, the provider and the codes, so the form's
            // copies are optional. Whatever the form does hold is still checked.
            if (request.member() != null && Texts.present(request.member().memberId())) {
                inputs.member(request.member());
            }
            if (request.provider() != null && (Texts.present(request.provider().npi()) || Texts.present(request.provider().vendorPractitionerId()))) {
                inputs.providerByNpiOrVendorId(request.provider());
            }
        }
        claimIdShape(inputs, "carry." + NextStepView.CARRY_CLAIM_ID, first(request, NextStepView.CARRY_CLAIM_ID));
        String runId = first(request, NextStepView.CARRY_RUN_ID);
        inputs.require(runId == null || isRunId(runId), "carry." + NextStepView.CARRY_RUN_ID,
                "The run this step follows from must be a run id this tool returned.");
        return inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), PasOutcome.values(), PasCodes.EXPECTATION_ELEMENTS)
                .scenario(request.scenarioId(), support.epa());
    }

    private static void claimIdShape(RunInputs inputs, String field, String claimId) {
        String masks = CarriedMasks.in(claimId);
        // A mask handed on is refused with its reason by the checks every request shares; one typed into the field is refused here.
        if (masks != null) {
            if (!field.startsWith("carry.")) {
                inputs.require(false, field, CarriedMasks.typed("a claim id", masks));
            }
            return;
        }
        inputs.require(Texts.blank(claimId) || CLAIM_ID.matcher(claimId.strip()).matches(), field,
                "A claim id is one word of up to 64 letters, digits, dots, hyphens, underscores and colons. It is written to the "
                        + "ledger as it stands, so it cannot be free text.");
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

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }
}
