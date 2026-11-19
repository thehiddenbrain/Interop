package org.p32h.interop.epa.pave.service.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.raw.RawDetail;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;

/**
 * Sends one collection request as written: its own method, url and headers, with placeholders
 * resolved and no protocol handling. For a request the tool could not map to an operation, and for
 * a tester who wants to send a mapped one exactly as the vendor wrote it.
 *
 * <p>It follows the same order as every other run, and the same guardrail. The gateway does not
 * know what an unmapped request does, so it judges it by its method and by what its name and url
 * look like: anything that could change state is a write, and a write to production is refused
 * unless the session unlocked it.
 *
 * <p>The tool does not interpret the answer. It reports what came back, shows every field of it,
 * and records the run. The one thing it reads is a FHIR OperationOutcome, issue by issue.
 */
public class RawService {

    /** The one thing about the answer a tester can assert besides the outcome: its HTTP status. */
    public static final String ELEMENT_HTTP_STATUS = "http-status";
    public static final List<String> EXPECTATION_ELEMENTS = List.of(ELEMENT_HTTP_STATUS);

    private final EpaProperties epa;
    private final OperationGateway gateway;
    private final RawRequestClient client;
    private final RunSupport support;

    public RawService(EpaProperties epa, OperationGateway gateway, RawRequestClient client, RunSupport support) {
        this.epa = epa;
        this.gateway = gateway;
        this.client = client;
        this.support = support;
    }

    public RunEnvelope<RawDetail> run(RawRunRequest request, RunOrigin origin, ProdUnlock unlock) {
        validate(request);
        PreparedCall call;
        try {
            call = gateway.prepare(intent(request, unlock));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(EpaOperation.UNMAPPED, refusal, request, origin);
        }
        ResolvedText body = RunSupport.ownBody(request) ? call.resolve(request.rawBody()) : null;

        VendorExchange exchange = client.send(call, body);

        // From here on the call has been made, so whatever happens next the run is recorded.
        RunRecord.Builder draft = support.fill(support.draft(EpaOperation.UNMAPPED, call, exchange, origin), request);
        Interpreted read;
        try {
            read = interpret(request, call, exchange);
        } catch (RuntimeException e) {
            return support.toolError(EpaOperation.UNMAPPED, call, exchange, draft, request, e);
        }

        RunRecord record = draft
                .outcome(read.outcome())
                .result(read.expectation().result())
                .nextAction(read.nextStep().action())
                .findings(read.findings().stream().map(f -> f.kind().name()).distinct().toList())
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<RawDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.RAW)
                .operation(EpaOperation.UNMAPPED)
                .environment(call.environment())
                .profile(epa.mode())
                .verdict(read.verdict())
                .expectation(read.expectation())
                .nextStep(read.nextStep())
                .detail(read.detail())
                .findings(read.findings())
                .additionalFields(read.additionalFields())
                .wire(exchange.wire(null))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /** Where the request would go and what its body resolves to, without sending it. Nothing is recorded. */
    public RequestPreview preview(RawRunRequest request, ProdUnlock unlock) {
        validate(request);
        PreparedCall call;
        try {
            call = gateway.prepare(intent(request, unlock));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(EpaOperation.UNMAPPED, refusal, null);
        }
        String body = null;
        List<String> notes = new ArrayList<>();
        if (RunSupport.ownBody(request)) {
            ResolvedText resolved = call.resolve(request.rawBody());
            body = resolved.resolved() ? resolved.display() : request.rawBody();
            if (!resolved.resolved()) {
                notes.add("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + ".");
            }
        } else {
            notes.add("No body will be sent, because none was supplied.");
        }
        return RequestPreview.of(EpaOperation.UNMAPPED, call, body, notes, null);
    }

    private record Interpreted(RawOutcome outcome, RawDetail detail, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, List<Finding> findings, List<AdditionalField> additionalFields,
                               ErrorView error, Timing timing) {
    }

    private Interpreted interpret(RawRunRequest request, PreparedCall call, VendorExchange exchange) {
        RawOutcome outcome = outcome(exchange);
        Timing timing = support.timing(exchange);

        // Nothing is read into a designed view, so every field the response carried is listed.
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
        List<IssueView> issues = List.of();
        String kind;
        if (exchange.httpStatus() == null) {
            kind = RawDetail.KIND_NO_RESPONSE;
        } else if (exchange.json() != null) {
            kind = RawDetail.KIND_JSON;
            TrackedJson tracked = TrackedJson.of(exchange.json());
            if (OperationOutcomes.is(exchange.json())) {
                tracked.root().text("resourceType");
                issues = OperationOutcomes.issues(tracked.root());
            }
            additional.addAll(tracked.unconsumed());
        } else {
            kind = exchange.responseBody() == null ? RawDetail.KIND_NO_BODY : RawDetail.KIND_NOT_JSON;
        }

        List<Finding> findings = new ArrayList<>();
        Finding script = support.scriptFinding(call);
        if (script != null) {
            findings.add(script);
        }
        Finding slow = support.slowFinding(timing);
        if (slow != null) {
            findings.add(slow);
        }

        List<String> supporting = new ArrayList<>();
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        supporting.add("This request was sent as written. The tool does not interpret its answer: everything that came back is listed below.");
        String status = exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
        VerdictView verdict = VerdictView.of(outcome, outcome.label() + status + ".", supporting);

        Map<String, List<String>> facts = exchange.httpStatus() == null ? Map.of()
                : Map.of(ELEMENT_HTTP_STATUS, List.of(String.valueOf(exchange.httpStatus())));
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, null,
                List.of(Expectations.Answer.of(null, facts)), RawOutcome.values(), EXPECTATION_ELEMENTS);

        NextStepView nextStep = outcome == RawOutcome.TIMEOUT || outcome == RawOutcome.UNREACHABLE || outcome == RawOutcome.SERVICE_ERROR
                || outcome == RawOutcome.MALFORMED_RESPONSE
                ? NextStepView.of(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                "No usable answer came back. This is a finding about the environment.")
                : outcome == RawOutcome.REJECTED
                ? NextStepView.of(NextAction.FIX_REQUEST, "Next: read the response below and correct the request, its url or its credentials.",
                "The service did not accept the request as it was sent.")
                : NextStepView.of(NextAction.REVIEW, "Next: read the response below.",
                "The tool does not know what this request is for, so it cannot say what should happen next.");

        ErrorView error = outcome.fault() ? support.faultView(exchange, issues) : null;
        RawDetail detail = new RawDetail(call.requestName(), kind, issues, List.of());
        return new Interpreted(outcome, detail, verdict, expectation, nextStep, findings, additional, error, timing);
    }

    /**
     * What came back, from the status and the transport alone. A success whose body is not JSON is
     * not a fault here: the tool has no reason to expect JSON from a request it does not know.
     */
    static RawOutcome outcome(VendorExchange exchange) {
        OnyxFailureType failure = exchange.failureType();
        boolean success = exchange.httpStatus() != null && exchange.httpStatus() >= 200 && exchange.httpStatus() < 300;
        return switch (failure) {
            case TIMEOUT -> RawOutcome.TIMEOUT;
            case UNAVAILABLE -> RawOutcome.UNREACHABLE;
            case SERVER_ERROR -> RawOutcome.SERVICE_ERROR;
            case BAD_REQUEST -> RawOutcome.REJECTED;
            // The shared classification calls a success with an unreadable body malformed. For a raw
            // request that is only true when the body did not arrive in full.
            case MALFORMED_RESPONSE -> success && Exchanges.INCOMPLETE_BODY.equals(exchange.parseError()) ? RawOutcome.MALFORMED_RESPONSE
                    : exchange.responseBody() == null ? RawOutcome.ANSWERED_NO_BODY : RawOutcome.ANSWERED_NOT_JSON;
            case NONE -> exchange.responseBody() == null ? RawOutcome.ANSWERED_NO_BODY : RawOutcome.ANSWERED;
        };
    }

    private static CallIntent intent(RawRunRequest request, ProdUnlock unlock) {
        return CallIntent.of(EpaOperation.UNMAPPED).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch());
    }

    private void validate(RawRunRequest request) {
        new RunInputs(support.clock()).common(request)
                .require(request.request() != null && org.p32h.interop.epa.pave.model.Texts.present(request.request().collectionId())
                                && org.p32h.interop.epa.pave.model.Texts.present(request.request().requestId()), "request",
                        "Choose the collection request to send.")
                .expectation(request.expected(), RawOutcome.values(), EXPECTATION_ELEMENTS)
                .throwIfInvalid();
    }
}
