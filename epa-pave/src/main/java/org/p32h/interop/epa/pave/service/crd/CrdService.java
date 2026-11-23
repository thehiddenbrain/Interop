package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.MaskedReading;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.AsWritten;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import org.p32h.interop.epa.pave.service.run.PayerAdvice;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * One CRD run, end to end.
 *
 * <p>The order is fixed and is the pattern the other protocols follow:
 * <ol>
 *   <li>validate the form: a bad form is refused before anything is sent or recorded</li>
 *   <li>ask the gateway where the call goes: a missing url or placeholder is refused before
 *       sending. A write the production guardrail refuses is not an error to the tester: it is
 *       recorded and returned as a result that says nothing was sent</li>
 *   <li>build the body, or take the tester's own one</li>
 *   <li>call the service through the client, which never throws for a vendor fault</li>
 *   <li>read the response, check it, word the verdict, compare with the expectation</li>
 *   <li><b>write the ledger record</b></li>
 *   <li>return the result, carrying the ledger's receipt</li>
 * </ol>
 * The record is written before the result is returned, so a result on screen always has its line
 * in the ledger or says plainly that it does not.
 */
@Service
public class CrdService {

    private final EpaProperties epa;
    private final OperationGateway gateway;
    private final OnyxCrdClient client;
    private final RunSupport support;
    private final CrdRequestBuilder builder;
    private final CrdResponseMapper mapper = new CrdResponseMapper();
    private final CrdConformanceChecker conformance;

    public CrdService(EpaProperties epa, OperationGateway gateway, OnyxCrdClient client, ProcedureCodeDirectory codeList,
                      RunSupport support) {
        this.epa = epa;
        this.gateway = gateway;
        this.client = client;
        this.support = support;
        this.builder = new CrdRequestBuilder(codeList, support.clock());
        this.conformance = new CrdConformanceChecker(codeList, epa);
    }

    /**
     * @param unlock the session's production unlock, or null. A CRD hook is a read, but a tester can
     *               point the run at any collection request, and the gateway judges that request
     *               for what it is. A suite run or a replay always passes null
     */
    public RunEnvelope<CrdDetail> run(CrdRunRequest posted, RunOrigin origin, ProdUnlock unlock) {
        // A body as the collection writes it goes down the path a body of the tester's own takes (D-313).
        AsWritten written = AsWritten.take(posted, operation(CrdRequestBuilder.hook(posted)), gateway);
        CrdRunRequest request = written == null ? posted : posted.withRawBody(written.template());
        validate(request);
        String hook = CrdRequestBuilder.hook(request);
        EpaOperation operation = operation(hook);
        boolean ownBody = RunSupport.ownBody(request);
        MemberIdForms memberId = RunSupport.memberId(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin);
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        List<SentOrder> orders;
        List<CaseResources.Required> incomplete = List.of();
        boolean unreadableOnMock = false;
        if (ownBody) {
            body = call.resolve(request.rawBody());
            if (written != null) {
                written.requireFilled(call, body);
            }
            orders = SentOrder.fromBody(body.value());
            // The tester's body is sent as written, valid or not: a malformed body is a negative
            // case they are entitled to send. They are told, so it cannot happen unnoticed.
            JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
            if (body.resolved() && !parsed.ok()) {
                requestNotes.add("The body you supplied cannot be read as JSON once its placeholders are filled in. It was sent exactly "
                        + "as written. " + parsed.error());
                unreadableOnMock = epa.mock();
            }
        } else {
            CaseResources.Reads reads = CaseResources.reading(call);
            CrdRequestBuilder.Built built = builder.build(request, UUID.randomUUID().toString(), reads, shapes(call), call.memberIdRule(),
                    sampleLayout(call));
            body = reads.body(built.json());
            requestNotes.addAll(built.notes());
            requestNotes.addAll(reads.requestNotes());
            orders = built.orders();
            incomplete = built.incomplete();
        }
        if (written != null) {
            List<String> said = AsWritten.reworded(requestNotes);
            requestNotes.clear();
            requestNotes.addAll(written.notes(false));
            requestNotes.addAll(said);
        }

        String chosen = RunSupport.chosenRequestNote(request, call);
        if (chosen != null) {
            requestNotes.add(0, chosen);
        }
        // A mock scenario is chosen by the packed form, so a sentinel picks its scenario under every rule.
        VendorExchange exchange = client.hook(call, body, MockSelection.of(request.scenarioId(), memberId.sent(), RunSupport.npi(request)));
        MemberIdForms sentMemberId = CaseResources.memberIdForms(request.member(), call.memberIdRule());

        // From here on the call has been made, so whatever happens next the run is recorded. The id is
        // settled now because the next step hands it on, so that the step after this one is linked to it.
        String runId = RunStamps.newRunId();
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).runId(runId).hook(hook);
        if (ownBody) {
            // The codes that went out are the ones in the tester's body, not the ones left on the form.
            draft.procedureCodes(orders.stream().map(SentOrder::code).filter(Texts::present).distinct().toList());
        }
        // Nothing was sent: no answer to read, and an outcome rule would read the missing one as the service being down.
        if (RunSupport.notSignedIn(exchange)) {
            return support.notSignedIn(operation, call, exchange, draft, request);
        }
        Interpreted read;
        try {
            // Only the reading and wording of the response is inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one run.
            read = interpret(request, call, exchange, orders, incomplete, runId);
            if (unreadableOnMock) {
                read = read.withNextStep(UNREADABLE_BODY_ON_MOCK);
            }
        } catch (RuntimeException e) {
            // The service was called and this tool then failed to read its answer. The run still
            // happened: it gets its line, and the tester still gets the request and the response.
            return support.toolError(operation, call, exchange, draft, request, e);
        }

        RunRecord record = draft
                .outcome(read.reading().outcome())
                .coverageCodes(read.reading().ledgerCodes())
                .delegatedVendor(read.reading().delegatedVendor())
                .result(read.expectation().result())
                .nextAction(read.nextStep().action())
                .correlation(read.correlation())
                .findings(read.findings().stream().map(f -> f.kind().name()).distinct().toList())
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<CrdDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.CRD)
                .operation(operation)
                .environment(call.environment())
                .profile(epa.mode())
                .verdict(read.verdict())
                .expectation(read.expectation())
                .nextStep(read.nextStep())
                .determination(determination(read.reading()))
                .detail(read.reading().detail().withRequest(requestNotes, !ownBody))
                .findings(read.findings())
                .additionalFields(read.reading().additionalFields())
                .wire(exchange.wire(sentMemberId))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /** Everything worked out from one response, before anything is written. */
    private record Interpreted(CrdReading reading, List<Finding> findings, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, ErrorView error, Timing timing, Map<String, String> correlation) {

        Interpreted withNextStep(NextStepView next) {
            return new Interpreted(reading, findings, verdict, expectation, next, error, timing, correlation);
        }
    }

    /**
     * The next step of a run on the mock profile whose own body is not JSON. The scenario answered
     * all the same, chosen by a sentinel in the text or by default, so its answer says nothing about
     * that body, and a next step built from it would offer DTR or PAS on an answer no service gave.
     * Nothing is handed on. On live the service's own answer to the body decides the next step.
     */
    static final NextStepView UNREADABLE_BODY_ON_MOCK = new NextStepView(NextAction.REVIEW,
            "Next: correct the body and send it again. Nothing is handed on from this answer.",
            "On the mock profile a scenario answered, although the body you supplied cannot be read as JSON. That answer says nothing about "
                    + "the body, so no next step is offered from it. A service would read the body as it stands.", Map.of());

    private Interpreted interpret(CrdRunRequest request, PreparedCall call, VendorExchange exchange, List<SentOrder> orders,
                                  List<CaseResources.Required> incomplete, String runId) {
        CrdReading reading = mapper.read(exchange, orders);
        CrdOutcome outcome = reading.outcome();
        Timing timing = support.timing(exchange);

        List<Finding> findings = new ArrayList<>(conformance.check(reading, exchange,
                request.member() == null ? null : request.member().lineOfBusiness(), IsoDates.parse(request.dateOfService())));
        addIfPresent(findings, incompleteRequest(incomplete, exchange.httpStatus() != null));
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));

        ExpectationView expectation = Expectations.assess(request.expected(), outcome, reading.asked(), reading.answers(),
                CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
        // A refusal for want of the coverage's payer points at the values that give one (D-320).
        NextStepView nextStep = support.unsignedNextStep(followedFrom(PayerAdvice.over(CrdVerdicts.nextStep(reading, exchange, runId,
                environmentName(call), CrdVerdicts.statesFhirServer(call == null ? null : call.sampleBody()),
                call != null && CrdVerdicts.writesPrefetch(call.sampleName(), call.sampleBody())), exchange, call), runId), exchange);
        VerdictView verdict = CrdVerdicts.toned(CrdVerdicts.verdict(reading, exchange), nextStep);
        // A mask on a value this verdict is read by changes the verdict without changing its rules, so the verdict, the next step
        // and the findings say so where each is read.
        MaskedReading masked = MaskedReading.of(exchange, MaskedReading.Reading.CRD);
        if (masked != null) {
            findings.add(0, masked.finding());
            verdict = masked.on(verdict);
            nextStep = masked.on(nextStep);
        }
        // The error panel is for a fault. A member the service does not recognise is a finding
        // about test data and is presented as an answer, even when it arrived with an error status.
        // The call's own read timeout words a timeout: a save since the gateway was last asked may have changed the active one.
        ErrorView error = outcome.fault() ? support.faultView(exchange, reading.detail().issues(), call) : null;

        Map<String, String> correlation = new LinkedHashMap<>();
        reading.detail().coverageInformation().stream().map(CoverageInformationView::satisfiedPaId).filter(Shown::hasValue)
                .findFirst().ifPresent(id -> correlation.put(RunRecord.CORR_AUTHORIZATION_REF, id.value()));
        List<String> questionnaires = reading.detail().coverageInformation().stream().flatMap(v -> v.questionnaires().stream())
                .filter(Shown::hasValue).map(Shown::value).distinct().toList();
        if (!questionnaires.isEmpty()) {
            correlation.put(RunRecord.CORR_QUESTIONNAIRE, String.join(" ", questionnaires));
        }
        return new Interpreted(reading, findings, verdict, expectation, nextStep, error, timing, correlation);
    }

    /**
     * The next step with this run's id handed on when the step it offers is DTR or PAS, so that the
     * run made from it is linked to this one in the ledger, as a step of a suite chain is. Any
     * other next step starts nothing that could be linked.
     */
    static NextStepView followedFrom(NextStepView next, String runId) {
        if (Texts.blank(runId) || (next.action() != NextAction.START_PAS && next.action() != NextAction.START_DTR)) {
            return next;
        }
        Map<String, List<String>> carry = new LinkedHashMap<>(next.carry());
        carry.put(NextStepView.CARRY_RUN_ID, List.of(runId));
        return new NextStepView(next.action(), next.headline(), next.reason(), carry);
    }

    /** The name of the environment the call went to, for a next step that names where to change a setting of it. */
    private static String environmentName(PreparedCall call) {
        return call == null || call.environment() == null ? null : call.environment().name();
    }

    /**
     * The request shapes the call was prepared with: the environment's setting, or the evidence
     * of the collection request the call came from, or the default, each with its source.
     */
    static RequestShapes shapes(PreparedCall call) {
        RequestShapes shapes = call == null ? null : call.shapes();
        return shapes == null ? RequestShapes.DEFAULTS : shapes;
    }

    /**
     * The arrangement of the prefetch the collection's request the call comes from writes (D-309),
     * or null for a call with no saved body, such as one to a typed address or on mock.
     */
    static PrefetchLayout sampleLayout(PreparedCall call) {
        return call == null ? null : PrefetchLayout.ofCall(call.sampleName(), call.sampleBody());
    }

    /** What the request would be, without sending it. Nothing is recorded. */
    public RequestPreview preview(CrdRunRequest posted, ProdUnlock unlock) {
        AsWritten written = AsWritten.take(posted, operation(CrdRequestBuilder.hook(posted)), gateway);
        CrdRunRequest request = written == null ? posted : posted.withRawBody(written.template());
        validate(request);
        EpaOperation operation = operation(CrdRequestBuilder.hook(request));
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            // No call carries a rule: the active environment's is the one it would have gone by.
            return RequestPreview.blocked(operation, refusal, CaseResources.memberIdForms(request.member(), gateway.memberIdRule()));
        }
        MemberIdForms shown = CaseResources.memberIdForms(request.member(), call.memberIdRule());
        String body;
        List<String> notes;
        if (RunSupport.ownBody(request)) {
            ResolvedText resolved = call.resolve(request.rawBody());
            body = resolved.resolved() ? resolved.display() : request.rawBody();
            if (written != null) {
                notes = new ArrayList<>(written.notes(false));
                notes.addAll(written.previewLines(resolved));
            } else {
                notes = resolved.resolved() ? List.of()
                        : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + ".");
            }
        } else {
            // A real id, so a preview sent as the tester's own body is a valid request. The note says it is not the one a run sends.
            String hookInstance = UUID.randomUUID().toString();
            CaseResources.Reads reads = CaseResources.reading(call);
            CrdRequestBuilder.Built built = builder.build(request, hookInstance, reads, shapes(call), call.memberIdRule(),
                    sampleLayout(call));
            body = reads.shown(built.json());
            notes = new ArrayList<>(built.notes());
            notes.addAll(reads.requestNotes());
            notes.add("The hookInstance " + hookInstance + " was made for this preview. A new one is made each time the request is sent.");
        }
        return RequestPreview.of(operation, call, body, notes, shown);
    }

    /**
     * Compares the request a tester pasted, in {@code rawBody}, with the one this tool builds for
     * the same case, and explains each difference. The answer says where the two differ and never
     * which of them the service expects.
     *
     * <p>Nothing is sent and nothing is recorded. The gateway is asked only to fill in
     * placeholders and never to prepare a call, so a comparison works with no url configured and
     * can never be taken for a run. A pair that cannot be compared field by field is still an
     * answer and says why. Only a form that is not valid is refused.
     */
    public RequestComparison compare(CrdRunRequest request) {
        if (!RunSupport.ownBody(request)) {
            throw ValidationException.of("rawBody", "Paste the request to compare with the one this tool builds.");
        }
        if (CrdRequestComparer.tooLarge(request.rawBody())) {
            throw ValidationException.of("rawBody", "The pasted request is larger than 2 MB, which is more than this tool compares.");
        }
        // The same checks a run with its own body gets: whatever the form holds is checked, and the body is not.
        validate(request);
        java.util.function.Function<String, ResolvedText> variables = template -> gateway.resolve(template, request.variables());
        // This tool's side takes what the request a send would use states, as the send does (D-320).
        return new CrdRequestComparer(builder).compare(request, variables, CaseResources.reading(variables,
                gateway.requestValues(operation(CrdRequestBuilder.hook(request)), request.request(), request.variables())));
    }

    /** Calls CDS Hooks discovery and lists the services the environment offers. Not a test run, so it is not recorded. */
    public CrdDiscovery discover() {
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        // The services are read through the mask, as a run's response is, so a secret the service repeats is not shown here.
        VendorExchange received = client.discover(call);
        VendorExchange exchange = received.withReturnedSecretsMasked();
        List<CrdDiscovery.Service> services = new ArrayList<>();
        // Every value of a key the body repeats is listed, as on a run, so an earlier value is not on the wire view alone.
        List<AdditionalField> additional = new ArrayList<>(TrackedJson.bodyFieldsWithRepeatedKeys(exchange));
        if (exchange.json() != null) {
            TrackedJson tracked = TrackedJson.of(exchange.json());
            for (TrackedJson.Node service : tracked.root().children("services")) {
                if (!service.isObject()) {
                    continue;
                }
                List<CrdDiscovery.Prefetch> prefetch = new ArrayList<>();
                for (Map.Entry<String, TrackedJson.Node> entry : service.child("prefetch").properties()) {
                    prefetch.add(new CrdDiscovery.Prefetch(entry.getKey(), entry.getValue().shown()));
                }
                services.add(new CrdDiscovery.Service(service.shown("id"), service.shown("hook"), service.shown("title"),
                        service.shown("description"), prefetch));
            }
            additional.addAll(tracked.unconsumed());
        }
        // Which services are listed is read with the masks in place, so the list says so where a mask fell on a name it is read by.
        MaskedReading masked = MaskedReading.of(received, MaskedReading.Reading.DISCOVERY);
        return new CrdDiscovery(call.url(), services, additional, exchange.wire(null), support.faultView(exchange, List.of()),
                exchange.request() == null || exchange.request().sent() ? null : exchange.request().note(), masked == null ? null : masked.line());
    }

    /** @param origin where the run came from, so the gateway can word a refusal for a replay or a suite. Null for a preview */
    private static CallIntent intent(EpaOperation operation, CrdRunRequest request, ProdUnlock unlock, RunOrigin origin) {
        return CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch()).withOrigin(origin);
    }

    private void validate(CrdRunRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        String hook = CrdRequestBuilder.hook(request);
        inputs.require(CrdRunRequest.ORDER_SIGN.equals(hook) || CrdRunRequest.ORDER_SELECT.equals(hook), "hook", NOT_BUILT);
        if (RunSupport.ownBody(request)) {
            // The tester's own body carries the member, the provider and the codes, so the form's
            // copies are optional. Whatever the form does hold is still checked. The body itself is
            // not checked here: it may hold placeholders that only resolve into JSON later.
            if (request.member() != null && Texts.present(request.member().memberId())) {
                inputs.member(request.member());
            }
            if (request.provider() != null && (Texts.present(request.provider().npi()) || Texts.present(request.provider().vendorPractitionerId()))) {
                inputs.providerByNpiOrVendorId(request.provider());
            }
        } else {
            inputs.member(request.member()).providerByNpiOrVendorId(request.provider()).codes(request.procedureCodes());
        }
        for (int i = 0; i < request.diagnosisCodes().size(); i++) {
            CodeInput code = request.diagnosisCodes().get(i);
            inputs.require(code != null && Texts.present(code.code()), "diagnosisCodes[" + i + "].code", "This diagnosis code is blank.");
        }
        inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS)
                .scenario(request.scenarioId(), epa)
                .throwIfInvalid();
    }

    /**
     * What this tool does not build, said where a tester asks for it. CRD 2.0.1 lists six hooks
     * (cdsHookType) and profiles several kinds of order; this tool builds two hooks and one kind.
     */
    static final String NOT_BUILT = "This tool builds order-sign and order-select requests only. CRD 2.0.1 also lists the hooks "
            + "appointment-book, encounter-start, encounter-discharge and order-dispatch, and this tool builds none of them. Its orders "
            + "are ServiceRequests only: it does not build a DeviceRequest, MedicationRequest, NutritionOrder, CommunicationRequest, "
            + "Appointment or VisionPrescription.";

    /**
     * One finding for everything a CRD 2.0.1 profile requires that the built request went without,
     * or null when nothing is missing. It is about what was sent, so that a refusal by the service
     * is not read as a fault of the service.
     */
    static Finding incompleteRequest(List<CaseResources.Required> incomplete) {
        return incompleteRequest(incomplete, true);
    }

    /**
     * @param answered true when an answer arrived. With none (a timeout, a service that could not
     *                 be reached) nothing is known to have arrived, so the request is said to have
     *                 been built without the elements, never sent without them
     */
    static Finding incompleteRequest(List<CaseResources.Required> incomplete, boolean answered) {
        if (incomplete == null || incomplete.isEmpty()) {
            return null;
        }
        int count = incomplete.size();
        return new Finding(FindingKind.REQUEST_INCOMPLETE,
                (answered ? "The request was sent without " : "The request was built without ") + count + (count == 1 ? " element " : " elements ") + CaseResources.CRD_VERSION
                        + " requires",
                "Nobody supplied " + (count == 1 ? "it" : "them") + ", and this tool does not invent values. A service that validates "
                        + "against " + CaseResources.CRD_VERSION + " may refuse the request for this, which is then not a fault of the "
                        + "service. Left out: " + String.join("; ", incomplete.stream().map(CaseResources.Required::line).toList()) + ".",
                null, null, null);
    }

    private static EpaOperation operation(String hook) {
        return CrdRunRequest.ORDER_SELECT.equals(hook) ? EpaOperation.CRD_ORDER_SELECT : EpaOperation.CRD_ORDER_SIGN;
    }

    /**
     * The determination grid: the standard codes and what they mean. When the response carried no
     * coverage information the two elements that would have answered are still listed, as not
     * returned, so an empty answer looks different from a "no" at a glance. The same is done for
     * each code that was asked about and left unanswered.
     */
    static List<DeterminationRow> determination(CrdReading reading) {
        List<DeterminationRow> rows = new ArrayList<>();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        if (coverage.isEmpty()) {
            rows.add(DeterminationRow.notReturned(null, "Covered", CrdCodes.COVERED));
            rows.add(DeterminationRow.notReturned(null, "Prior authorization", CrdCodes.PA_NEEDED));
            return rows;
        }
        boolean named = reading.several();
        for (CoverageInformationView view : coverage) {
            // Two answers for one code are told apart by where each sat, so their rows do not merge.
            boolean twin = coverage.stream().filter(other -> other.subject().equals(view.subject())).count() > 1;
            String subject = named ? view.subject() + (twin ? " (" + view.foundAt() + ")" : "") : null;
            // A sub-extension sent with no value[x] is said to be that, as the coverage table says it, and not "empty".
            rows.add(row(subject, "Covered", CrdCodes.COVERED, view.covered(), view.sentWithNoValue(CrdCodes.COVERED)));
            rows.add(row(subject, "Prior authorization", CrdCodes.PA_NEEDED, view.paNeeded(), view.sentWithNoValue(CrdCodes.PA_NEEDED)));
            rows.add(row(subject, "Documentation", CrdCodes.DOC_NEEDED, view.docNeeded(), view.sentWithNoValue(CrdCodes.DOC_NEEDED)));
            for (int i = 0; i < view.docPurpose().size(); i++) {
                rows.add(row(subject, "Documentation purpose", CrdCodes.DOC_PURPOSE, view.docPurpose().get(i),
                        view.sentWithNoValue(CrdCodes.DOC_PURPOSE + "[" + i + "]")));
            }
            for (int i = 0; i < view.infoNeeded().size(); i++) {
                rows.add(row(subject, "Information needed", CrdCodes.INFO_NEEDED, view.infoNeeded().get(i),
                        view.sentWithNoValue(CrdCodes.INFO_NEEDED + "[" + i + "]")));
            }
            if (view.satisfiedPaId().presence() != Presence.ABSENT) {
                rows.add(new DeterminationRow(subject, "Identifier for the requirements already met", view.satisfiedPaId().orNull(), null,
                        CrdCodes.SATISFIED_PA_ID, true, view.satisfiedPaId().presence()));
            }
            if (Texts.present(view.delegatedVendor())) {
                rows.add(new DeterminationRow(subject, "Delegated vendor", view.delegatedVendor(),
                        "Recognised from " + view.delegatedVendorBasis(), CrdCodes.DETAIL, true, Presence.VALUE));
            }
        }
        for (String label : reading.unanswered()) {
            rows.add(DeterminationRow.notReturned(label, "Covered", CrdCodes.COVERED));
            rows.add(DeterminationRow.notReturned(label, "Prior authorization", CrdCodes.PA_NEEDED));
        }
        return rows;
    }

    private static DeterminationRow row(String subject, String label, String element, CodedValue value, boolean noValue) {
        return new DeterminationRow(subject, label, value.code(), value.display(), element, value.recognised(), value.presence(), noValue);
    }

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }
}
