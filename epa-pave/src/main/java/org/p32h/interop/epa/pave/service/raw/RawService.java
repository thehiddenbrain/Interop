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
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Reached;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
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
import org.springframework.stereotype.Service;

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
@Service
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
            call = prepare(intent(request, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(EpaOperation.UNMAPPED, refusal, request, origin);
        }
        // On live the gateway refuses such a url itself. On mock nothing is sent, but a run of a
        // url that was never complete would record an answer to a request nobody could send.
        List<String> missing = missingUrlParts(call);
        if (!missing.isEmpty()) {
            throw unfilled(missing);
        }
        Body body = body(request, call);

        // An unresolved placeholder in the body, supplied or saved, is refused here on both
        // profiles, before anything is sent or recorded.
        VendorExchange exchange = client.send(call, body.text());

        // From here on the call has been made, so whatever happens next the run is recorded.
        RunRecord.Builder draft = support.fill(support.draft(EpaOperation.UNMAPPED, call, exchange, origin), request);
        Interpreted read;
        try {
            read = interpret(request, call, exchange, body.source());
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
            call = prepare(intent(request, unlock, RunOrigin.manual()));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(EpaOperation.UNMAPPED, refusal, null);
        }
        Body chosen = body(request, call);
        String body = null;
        List<String> notes = new ArrayList<>();
        notes.add(bodySentence(chosen.source()));
        if (chosen.text() != null) {
            // Shown with every secret masked: the display form when it is complete, and otherwise
            // the text as written, in which a credential typed into it is already masked.
            body = chosen.text().resolved() ? chosen.text().display() : chosen.text().template();
            if (!chosen.text().resolved()) {
                notes.add("The body still contains placeholders with no value: " + String.join(", ", chosen.text().unresolved())
                        + ". Sending is refused until each has one.");
            }
        }
        List<String> missing = missingUrlParts(call);
        if (!missing.isEmpty()) {
            notes.add("The url still has " + (missing.size() == 1 ? "a part" : "parts") + " with no value: " + written(missing)
                    + ". Give " + (missing.size() == 1 ? "it" : "each") + " under urlValues: sending is refused until then.");
        }
        return RequestPreview.of(EpaOperation.UNMAPPED, call, body, notes, null).withBodySource(chosen.source());
    }

    /** The body that goes with a request, and which of the three it is. */
    private record Body(ResolvedText text, String source) {
    }

    /**
     * The body supplied for this run when there is one, and otherwise the body saved with the
     * collection request, resolved through the same layers as the url and the headers. The saved
     * body is a template in which a credential typed into it is a placeholder of its own, so the
     * wire view and the preview mask it as they mask a secret variable.
     */
    private static Body body(RawRunRequest request, PreparedCall call) {
        if (RunSupport.ownBody(request)) {
            return new Body(call.resolve(request.rawBody()), RawDetail.BODY_SUPPLIED);
        }
        String saved = call.savedBody();
        if (Texts.present(saved)) {
            return new Body(call.resolve(saved), RawDetail.BODY_SAVED);
        }
        return new Body(null, RawDetail.BODY_NONE);
    }

    /** Which body goes with the request, in words that are true on both profiles. */
    static String bodySentence(String source) {
        return switch (source) {
            case RawDetail.BODY_SUPPLIED -> "The body is the one supplied for this run, with its placeholders filled in.";
            case RawDetail.BODY_SAVED -> "The body is the one saved with the collection request, with its placeholders filled in, "
                    + "because none was supplied for this run.";
            default -> "There is no body: none was supplied for this run, and the collection request has none saved.";
        };
    }

    /**
     * Prepares the call. A url whose only missing values are single-brace parts is refused as a
     * form error that names each part under urlValues, where a caller supplies it: the gateway's own
     * refusal names it among the variables, where a raw send cannot supply it.
     */
    private PreparedCall prepare(CallIntent intent) {
        try {
            return gateway.prepare(intent);
        } catch (UnresolvedVariablesException e) {
            if (!e.urlTokens().isEmpty() && e.urlTokens().containsAll(e.variables())) {
                throw unfilled(e.variables());
            }
            throw e;
        }
    }

    /**
     * The single-brace parts of the url that are still waiting for a value. A part written
     * {@code {name}} or {@code :name} is not a placeholder an environment can define: only the
     * caller can fill it, through urlValues.
     */
    private static List<String> missingUrlParts(PreparedCall call) {
        ResolvedUrl url = call.url();
        if (url == null || url.template() == null) {
            return List.of();
        }
        return url.unresolved().stream().filter(name -> !url.template().contains("{{" + name + "}}")).toList();
    }

    private static ValidationException unfilled(List<String> parts) {
        List<FieldError> fields = parts.stream()
                .map(part -> new FieldError("urlValues." + part, "No value for {" + part + "}. Give the value this part of the url "
                        + "is waiting for.")).toList();
        return new ValidationException("The request cannot be sent: the url still has " + (parts.size() == 1 ? "a part" : "parts")
                + " with no value, " + written(parts) + ". Nothing was sent.", fields);
    }

    private static String written(List<String> parts) {
        return String.join(", ", parts.stream().map(part -> "{" + part + "}").toList());
    }

    private record Interpreted(RawOutcome outcome, RawDetail detail, VerdictView verdict, ExpectationView expectation,
                               NextStepView nextStep, List<Finding> findings, List<AdditionalField> additionalFields,
                               ErrorView error, Timing timing) {
    }

    private Interpreted interpret(RawRunRequest request, PreparedCall call, VendorExchange exchange, String bodySource) {
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
        // Worded by what happened. On the mock profile nothing leaves this machine, so the line
        // never says the request was sent.
        supporting.add(epa.mock()
                ? "On the mock profile nothing was sent: a scenario answered in place of the service. The tool does not interpret "
                + "the answer: everything that came back is listed below."
                : "This request was sent as written. The tool does not interpret its answer: everything that came back is listed below.");
        String status = exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
        VerdictView verdict = VerdictView.of(outcome, outcome.label() + status + ".", supporting);

        Map<String, List<String>> facts = exchange.httpStatus() == null ? Map.of()
                : Map.of(ELEMENT_HTTP_STATUS, List.of(String.valueOf(exchange.httpStatus())));
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, null,
                List.of(Expectations.Answer.of(null, facts)), RawOutcome.values(), EXPECTATION_ELEMENTS);

        NextStepView nextStep = outcome == RawOutcome.TIMEOUT || outcome == RawOutcome.UNREACHABLE || outcome == RawOutcome.SERVICE_ERROR
                || outcome == RawOutcome.MALFORMED_RESPONSE
                ? noUsableAnswer(call, exchange)
                : outcome == RawOutcome.REJECTED
                ? NextStepView.of(NextAction.FIX_REQUEST, "Next: read the response below and correct the request, its url or its credentials.",
                "The service did not accept the request as it was sent.")
                : NextStepView.of(NextAction.REVIEW, "Next: read the response below.",
                "The tool does not know what this request is for, so it cannot say what should happen next.");

        ErrorView error = outcome.fault() ? support.faultView(exchange, issues) : null;
        RawDetail detail = new RawDetail(call.requestName(), kind, issues, List.of(bodySentence(bodySource)), bodySource);
        return new Interpreted(outcome, detail, verdict, expectation, nextStep, findings, additional, error, timing);
    }

    /**
     * The next step when no usable answer came back. A read is sent again. A request that may
     * change something and may have reached the service is checked on before it is sent again:
     * sending it twice could do twice what it does.
     */
    static NextStepView noUsableAnswer(PreparedCall call, VendorExchange exchange) {
        String method = Exchanges.method(call);
        boolean read = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
        if (read) {
            return NextStepView.of(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment.");
        }
        if (exchange.reached() == Reached.NO) {
            return NextStepView.of(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "No usable answer came back, and the request did not reach the service, so nothing was changed. It is safe to "
                            + "send the same request again. This is a finding about the environment.");
        }
        return NextStepView.of(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then find out whether the request took "
                        + "effect before sending it again.",
                "No usable answer came back, and the service may have received the " + method + " and acted on it. Sending it again "
                        + "without checking could do the same thing twice. This is a finding about the environment.");
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

    private static CallIntent intent(RawRunRequest request, ProdUnlock unlock, RunOrigin origin) {
        CallIntent intent = CallIntent.of(EpaOperation.UNMAPPED).withRequest(request.request()).withRunValues(request.variables())
                .withUnlock(unlock).withExpectedEpoch(request.configEpoch()).withOrigin(origin);
        for (Map.Entry<String, String> value : new java.util.TreeMap<>(request.urlValues()).entrySet()) {
            intent = intent.withUrlValue(value.getKey(), value.getValue());
        }
        return intent;
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
