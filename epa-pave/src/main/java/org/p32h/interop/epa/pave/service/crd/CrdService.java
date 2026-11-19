package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.IsoDates;
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
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
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
        this.builder = new CrdRequestBuilder(codeList);
        this.conformance = new CrdConformanceChecker(codeList, epa);
    }

    /**
     * @param unlock the session's production unlock, or null. A CRD hook is a read, but a tester can
     *               point the run at any collection request, and the gateway judges that request
     *               for what it is. A suite run or a replay always passes null
     */
    public RunEnvelope<CrdDetail> run(CrdRunRequest request, RunOrigin origin, ProdUnlock unlock) {
        validate(request);
        String hook = CrdRequestBuilder.hook(request);
        EpaOperation operation = operation(hook);
        boolean ownBody = RunSupport.ownBody(request);
        MemberIdForms memberId = RunSupport.memberId(request);

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin);
        }

        ResolvedText body;
        List<String> requestNotes = new ArrayList<>();
        List<SentOrder> orders;
        if (ownBody) {
            body = call.resolve(request.rawBody());
            orders = SentOrder.fromBody(body.value());
            // The tester's body is sent as written, valid or not: a malformed body is a negative
            // case they are entitled to send. They are told, so it cannot happen unnoticed.
            JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
            if (body.resolved() && !parsed.ok()) {
                requestNotes.add("The body you supplied is not valid JSON once its placeholders are filled in. It was sent exactly "
                        + "as written. " + parsed.error());
            }
        } else {
            CrdRequestBuilder.Built built = builder.build(request, UUID.randomUUID().toString(), call::resolve);
            body = ResolvedText.plain(built.json());
            requestNotes.addAll(built.notes());
            orders = built.orders();
        }

        VendorExchange exchange = client.hook(call, body, MockSelection.of(request.scenarioId(), memberId.sent(), RunSupport.npi(request)));

        // From here on the call has been made, so whatever happens next the run is recorded.
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).hook(hook);
        if (ownBody) {
            // The codes that went out are the ones in the tester's body, not the ones left on the form.
            draft.procedureCodes(orders.stream().map(SentOrder::code).filter(Texts::present).distinct().toList());
        }
        Interpreted read;
        try {
            // Only the reading and wording of the response is inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one run.
            read = interpret(request, call, exchange, orders);
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
                .wire(exchange.wire(memberId.typed().isEmpty() ? null : memberId))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /** Everything worked out from one response, before anything is written. */
    private record Interpreted(CrdReading reading, List<Finding> findings, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, ErrorView error, Timing timing, Map<String, String> correlation) {
    }

    private Interpreted interpret(CrdRunRequest request, PreparedCall call, VendorExchange exchange, List<SentOrder> orders) {
        CrdReading reading = mapper.read(exchange, orders);
        CrdOutcome outcome = reading.outcome();
        Timing timing = support.timing(exchange);

        List<Finding> findings = new ArrayList<>(conformance.check(reading, exchange,
                request.member() == null ? null : request.member().lineOfBusiness(), IsoDates.parse(request.dateOfService())));
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));

        VerdictView verdict = CrdVerdicts.verdict(reading, exchange);
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, reading.asked(), reading.answers(),
                CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);
        NextStepView nextStep = CrdVerdicts.nextStep(reading, exchange);
        // The error panel is for a fault. A member the service does not recognise is a finding
        // about test data and is presented as an answer, even when it arrived with an error status.
        ErrorView error = outcome.fault() ? support.faultView(exchange, reading.detail().issues()) : null;

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

    /** What the request would be, without sending it. Nothing is recorded. */
    public RequestPreview preview(CrdRunRequest request, ProdUnlock unlock) {
        validate(request);
        EpaOperation operation = operation(CrdRequestBuilder.hook(request));
        MemberIdForms memberId = RunSupport.memberId(request);
        MemberIdForms shown = memberId.typed().isEmpty() ? null : memberId;
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, unlock));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, shown);
        }
        String body;
        List<String> notes;
        if (RunSupport.ownBody(request)) {
            ResolvedText resolved = call.resolve(request.rawBody());
            body = resolved.resolved() ? resolved.display() : request.rawBody();
            notes = resolved.resolved() ? List.of()
                    : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + ".");
        } else {
            CrdRequestBuilder.Built built = builder.build(request, "(a new id is generated when the request is sent)", call::resolve);
            body = built.json();
            notes = built.notes();
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
        return new CrdRequestComparer(builder).compare(request, template -> gateway.resolve(template, request.variables()));
    }

    /** Calls CDS Hooks discovery and lists the services the environment offers. Not a test run, so it is not recorded. */
    public CrdDiscovery discover() {
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        VendorExchange exchange = client.discover(call);
        List<CrdDiscovery.Service> services = new ArrayList<>();
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
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
        return new CrdDiscovery(call.url(), services, additional, exchange.wire(null), support.faultView(exchange, List.of()),
                exchange.request() == null || exchange.request().sent() ? null : exchange.request().note());
    }

    private static CallIntent intent(EpaOperation operation, CrdRunRequest request, ProdUnlock unlock) {
        return CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch());
    }

    private void validate(CrdRunRequest request) {
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        String hook = CrdRequestBuilder.hook(request);
        inputs.require(CrdRunRequest.ORDER_SIGN.equals(hook) || CrdRunRequest.ORDER_SELECT.equals(hook), "hook",
                "The hook must be order-sign or order-select.");
        if (RunSupport.ownBody(request)) {
            // The tester's own body carries the member, the provider and the codes, so the form's
            // copies are optional. Whatever the form does hold is still checked. The body itself is
            // not checked here: it may hold placeholders that only resolve into JSON later.
            if (request.member() != null && Texts.present(request.member().memberId())) {
                inputs.member(request.member());
            }
            if (request.provider() != null && Texts.present(request.provider().npi())) {
                inputs.provider(request.provider());
            }
        } else {
            inputs.member(request.member()).provider(request.provider()).codes(request.procedureCodes());
        }
        inputs.date("dateOfService", request.dateOfService())
                .expectation(request.expected(), CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS)
                .scenario(request.scenarioId(), epa)
                .throwIfInvalid();
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
            rows.add(row(subject, "Covered", CrdCodes.COVERED, view.covered()));
            rows.add(row(subject, "Prior authorization", CrdCodes.PA_NEEDED, view.paNeeded()));
            rows.add(row(subject, "Documentation", CrdCodes.DOC_NEEDED, view.docNeeded()));
            view.docPurpose().forEach(value -> rows.add(row(subject, "Documentation purpose", CrdCodes.DOC_PURPOSE, value)));
            view.infoNeeded().forEach(value -> rows.add(row(subject, "Information needed", CrdCodes.INFO_NEEDED, value)));
            if (view.satisfiedPaId().presence() != Presence.ABSENT) {
                rows.add(new DeterminationRow(subject, "Existing authorization", view.satisfiedPaId().orNull(), null,
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

    private static DeterminationRow row(String subject, String label, String element, CodedValue value) {
        return new DeterminationRow(subject, label, value.code(), value.display(), element, value.recognised(), value.presence());
    }

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }
}
