package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One CRD run, end to end.
 *
 * <p>The order is fixed and is the pattern the other protocols follow:
 * <ol>
 *   <li>validate the form: a bad form is refused before anything is sent or recorded</li>
 *   <li>ask the gateway where the call goes: a missing url or placeholder is refused before sending</li>
 *   <li>build the body, or take the tester's edited one</li>
 *   <li>call the service through the client, which never throws for a vendor fault</li>
 *   <li>read the response, check it, word the verdict, compare with the expectation</li>
 *   <li><b>write the ledger record</b></li>
 *   <li>return the result, carrying the ledger's receipt</li>
 * </ol>
 * The record is written before the result is returned, so a result on screen always has its line
 * in the ledger or says plainly that it does not.
 */
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
        this.conformance = new CrdConformanceChecker(codeList);
    }

    public RunEnvelope<CrdDetail> run(CrdRunRequest request, RunOrigin origin) {
        validate(request);
        String hook = CrdRequestBuilder.hook(request);
        EpaOperation operation = operation(hook);
        boolean ownBody = request.rawBody() != null;
        MemberIdForms memberId = MemberIds.forms(request.member() == null ? null : request.member().memberId());
        String npi = request.provider() == null ? null : Texts.trimToNull(request.provider().npi());

        PreparedCall call = gateway.prepare(CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()));

        ResolvedText body;
        List<String> requestNotes;
        if (ownBody) {
            body = call.resolve(request.rawBody());
            requestNotes = List.of();
        } else {
            CrdRequestBuilder.Built built = builder.build(request, UUID.randomUUID().toString(), call::resolve);
            body = ResolvedText.plain(built.json());
            requestNotes = built.notes();
        }

        VendorExchange exchange = client.hook(call, body, MockSelection.of(request.scenarioId(), memberId.sent(), npi));

        // From here on the call has been made, so whatever happens next the run is recorded.
        RunRecord.Builder draft = support.draft(operation, call, exchange, origin)
                .hook(hook)
                .procedureCodes(codes(request))
                .lineOfBusiness(request.member() == null ? null : Texts.trimToNull(request.member().lineOfBusiness()))
                .expected(Texts.trimToNull(request.expected()))
                .note(Texts.trimToNull(request.note()))
                .testerRef(Texts.trimToNull(request.testerRef()))
                .chainId(Texts.trimToNull(request.chainId()))
                .memberId(Texts.trimToNull(memberId.sent()))
                .npi(npi)
                .dateOfService(Texts.trimToNull(request.dateOfService()))
                .unrecordedInputs(unrecordedInputs(request));
        try {
            return interpret(request, operation, call, exchange, draft, requestNotes, !ownBody, memberId);
        } catch (RuntimeException e) {
            // The service was called and this tool then failed to read its answer. The run still
            // happened, so it gets its line before the failure is reported.
            support.record(draft.outcome(RunSupport.OUTCOME_TOOL_ERROR).result(TestResult.UNVERIFIED).build());
            throw e;
        }
    }

    private RunEnvelope<CrdDetail> interpret(CrdRunRequest request, EpaOperation operation, PreparedCall call, VendorExchange exchange,
                                             RunRecord.Builder draft, List<String> requestNotes, boolean builtByTool,
                                             MemberIdForms memberId) {
        CrdReading reading = mapper.read(exchange);
        CrdOutcome outcome = reading.outcome();
        Timing timing = support.timing(exchange);

        List<Finding> findings = new ArrayList<>(conformance.check(reading, exchange, request.procedureCodes(),
                request.member() == null ? null : request.member().lineOfBusiness(), IsoDates.parse(request.dateOfService())));
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));

        VerdictView verdict = CrdVerdicts.verdict(reading, exchange);
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, reading.codes(), CrdOutcome.values());
        NextStepView nextStep = CrdVerdicts.nextStep(reading);
        // The error panel is for a fault. A member the service does not recognise is a finding
        // about test data and is presented as an answer, even when it arrived with an error status.
        ErrorView error = outcome.fault() ? support.faultView(exchange, reading.detail().issues()) : null;

        Map<String, String> correlation = new LinkedHashMap<>();
        reading.detail().coverageInformation().stream().map(CoverageInformationView::satisfiedPaId).filter(Shown::hasValue)
                .findFirst().ifPresent(id -> correlation.put(RunRecord.CORR_AUTHORIZATION_REF, id.value()));
        reading.detail().coverageInformation().stream().flatMap(v -> v.questionnaires().stream()).filter(Shown::hasValue)
                .findFirst().ifPresent(q -> correlation.put(RunRecord.CORR_QUESTIONNAIRE, q.value()));

        RunRecord record = draft
                .outcome(outcome)
                .coverageCodes(reading.ledgerCodes())
                .delegatedVendor(reading.delegatedVendor())
                .result(expectation.result())
                .correlation(correlation)
                .findings(findings.stream().map(f -> f.kind().name()).distinct().toList())
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<CrdDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.CRD)
                .operation(operation)
                .environment(call.environment())
                .profile(epa.mode())
                .verdict(verdict)
                .expectation(expectation)
                .nextStep(nextStep)
                .determination(determination(reading))
                .detail(reading.detail().withRequest(requestNotes, builtByTool))
                .findings(findings)
                .additionalFields(reading.additionalFields())
                .wire(exchange.wire(memberId.typed().isEmpty() ? null : memberId))
                .error(error)
                .ledger(receipt)
                .timing(timing)
                .build();
    }

    /** What the request would be, without sending it. */
    public CrdPreview preview(CrdRunRequest request) {
        validate(request);
        EpaOperation operation = operation(CrdRequestBuilder.hook(request));
        PreparedCall call = gateway.prepare(CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()));
        MemberIdForms memberId = MemberIds.forms(request.member() == null ? null : request.member().memberId());
        String body;
        List<String> notes;
        if (request.rawBody() != null) {
            ResolvedText resolved = call.resolve(request.rawBody());
            body = resolved.resolved() ? resolved.display() : request.rawBody();
            notes = resolved.resolved() ? List.of()
                    : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + ".");
        } else {
            CrdRequestBuilder.Built built = builder.build(request, "(a new id is generated when the request is sent)", call::resolve);
            body = built.json();
            notes = built.notes();
        }
        return new CrdPreview(operation, call.method(), call.url(), call.environment(), call.requestName(), call.scripted(), body, notes,
                memberId.typed().isEmpty() ? null : memberId);
    }

    /** Calls CDS Hooks discovery and lists the services the environment offers. Not a test run, so it is not recorded. */
    public CrdDiscovery discover() {
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        VendorExchange exchange = client.discover(call);
        List<CrdDiscovery.Service> services = new ArrayList<>();
        List<AdditionalField> additional = List.of();
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
            additional = tracked.unconsumed();
        }
        return new CrdDiscovery(call.url(), services, additional, exchange.wire(null), support.faultView(exchange, List.of()));
    }

    private void validate(CrdRunRequest request) {
        RunInputs inputs = new RunInputs(support.clock());
        String hook = CrdRequestBuilder.hook(request);
        inputs.require(CrdRunRequest.ORDER_SIGN.equals(hook) || CrdRunRequest.ORDER_SELECT.equals(hook), "hook",
                "The hook must be order-sign or order-select.");
        if (request.rawBody() != null) {
            // The tester's own body carries the member, the provider and the codes, so the form's
            // copies are optional. Whatever the form does hold is still checked.
            inputs.rawBody(request.rawBody());
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
                .expectation(request.expected(), CrdOutcome.values())
                .scenario(request.scenarioId(), epa)
                .throwIfInvalid();
    }

    private static EpaOperation operation(String hook) {
        return CrdRunRequest.ORDER_SELECT.equals(hook) ? EpaOperation.CRD_ORDER_SELECT : EpaOperation.CRD_ORDER_SIGN;
    }

    private static List<String> codes(CrdRunRequest request) {
        return request.procedureCodes().stream().filter(Objects::nonNull).map(CodeInput::code).filter(Texts::present)
                .map(String::strip).toList();
    }

    /** The things this run sent that its ledger line will not hold, so replay can refuse it honestly. */
    private static List<String> unrecordedInputs(CrdRunRequest request) {
        List<String> inputs = new ArrayList<>();
        if (request.rawBody() != null) {
            inputs.add(RunRecord.INPUT_BODY);
        }
        if (!request.memberOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_MEMBER_DETAILS);
        }
        if (!request.providerOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_PROVIDER_DETAILS);
        }
        if (!request.variables().isEmpty()) {
            inputs.add(RunRecord.INPUT_RUN_VALUES);
        }
        if (request.request() != null) {
            inputs.add(RunRecord.INPUT_CHOSEN_REQUEST);
        }
        return inputs;
    }

    /**
     * The determination grid: the standard codes and what they mean. When the response carried no
     * coverage information the two elements that would have answered are still listed, as not
     * returned, so an empty answer looks different from a "no" at a glance.
     */
    static List<DeterminationRow> determination(CrdReading reading) {
        List<DeterminationRow> rows = new ArrayList<>();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        if (coverage.isEmpty()) {
            rows.add(DeterminationRow.notReturned("Covered", CrdCodes.COVERED));
            rows.add(DeterminationRow.notReturned("Prior authorization", CrdCodes.PA_NEEDED));
            return rows;
        }
        boolean several = coverage.size() > 1;
        for (CoverageInformationView view : coverage) {
            String prefix = several ? Texts.orElse(view.appliesTo().orNull(), view.foundAt()) + ": " : "";
            rows.add(row(prefix + "Covered", CrdCodes.COVERED, view.covered()));
            rows.add(row(prefix + "Prior authorization", CrdCodes.PA_NEEDED, view.paNeeded()));
            rows.add(row(prefix + "Documentation", CrdCodes.DOC_NEEDED, view.docNeeded()));
            view.docPurpose().forEach(value -> rows.add(row(prefix + "Documentation purpose", CrdCodes.DOC_PURPOSE, value)));
            view.infoNeeded().forEach(value -> rows.add(row(prefix + "Information needed", CrdCodes.INFO_NEEDED, value)));
            if (view.satisfiedPaId().presence() != Presence.ABSENT) {
                rows.add(new DeterminationRow(prefix + "Existing authorization", view.satisfiedPaId().orNull(), null,
                        CrdCodes.SATISFIED_PA_ID, true, view.satisfiedPaId().presence()));
            }
            if (Texts.present(view.delegatedVendor())) {
                rows.add(new DeterminationRow(prefix + "Delegated vendor", view.delegatedVendor(),
                        "Recognised from " + view.delegatedVendorBasis(), CrdCodes.DETAIL, true, Presence.VALUE));
            }
        }
        return rows;
    }

    private static DeterminationRow row(String label, String element, CodedValue value) {
        return new DeterminationRow(label, value.code(), value.display(), element, value.recognised(), value.presence());
    }

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }
}
