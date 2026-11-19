package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What every protocol service does the same way around a call: stamp the run, time it against the
 * threshold, fill the parts of the ledger record that do not depend on the protocol, write the
 * record, and describe a fault.
 *
 * <p>Keeping these here means CRD, DTR and PAS cannot drift apart on what a ledger line holds or
 * on how a timeout is worded.
 */
@Component
public class RunSupport {

    private final EpaProperties epa;
    private final TrackingProperties tracking;
    private final OnyxProperties onyx;
    private final ObjectProvider<RunLedger> ledger;
    private final Clock clock;

    public RunSupport(EpaProperties epa, TrackingProperties tracking, OnyxProperties onyx, ObjectProvider<RunLedger> ledger, Clock clock) {
        this.epa = epa;
        this.tracking = tracking;
        this.onyx = onyx;
        this.ledger = ledger;
        this.clock = clock;
    }

    public EpaProperties epa() {
        return epa;
    }

    public Clock clock() {
        return clock;
    }

    /** A completed call slower than this is flagged. The Onyx read timeout unless tracking sets its own. */
    public long slowThresholdMillis() {
        return tracking.slowThresholdMs() > 0 ? tracking.slowThresholdMs() : onyx.readTimeoutMs();
    }

    public Timing timing(VendorExchange exchange) {
        // Only a call that completed can be "slow". One that timed out has its own outcome.
        long threshold = slowThresholdMillis();
        boolean completed = exchange.httpStatus() != null;
        return new Timing(exchange.elapsedMillis(), threshold, completed && threshold > 0 && exchange.elapsedMillis() > threshold);
    }

    /**
     * A ledger draft with everything that does not depend on the protocol filled in: the run id,
     * the timestamp, the target, the profile, the environment, the scenario that answered, the
     * status, the timing and the failure type. The caller adds the outcome, the result and its inputs.
     */
    public RunRecord.Builder draft(EpaOperation operation, PreparedCall call, VendorExchange exchange, RunOrigin origin) {
        RunOrigin from = origin == null ? RunOrigin.manual() : origin;
        Timing timing = timing(exchange);
        return RunRecord.builder()
                .runId(RunStamps.newRunId())
                .timestamp(RunStamps.timestamp(clock))
                .protocol(operation.protocol())
                .operation(operation)
                .target(epa.target())
                .profile(epa.mode())
                .environment(call.environment())
                .targetSource(call.requestName())
                .scenarioId(exchange.scenarioId())
                .httpStatus(exchange.httpStatus())
                .elapsedMillis(exchange.elapsedMillis())
                .errorType(exchange.failureType())
                .slow(timing.slow())
                .suiteRunId(from.suiteRunId())
                .caseId(from.caseId())
                .replayOfRunId(from.replayOfRunId());
    }

    /**
     * A ledger draft for a line that records no outbound call: a notification that arrived, or a
     * step completed inside the tool such as a questionnaire response. It has no target, no status
     * and no timing, because nothing was sent.
     */
    public RunRecord.Builder localDraft(EpaOperation operation, EnvironmentRef environment, RunOrigin origin) {
        RunOrigin from = origin == null ? RunOrigin.manual() : origin;
        return RunRecord.builder()
                .runId(RunStamps.newRunId())
                .timestamp(RunStamps.timestamp(clock))
                .protocol(operation.protocol())
                .operation(operation)
                .target(epa.target())
                .profile(epa.mode())
                .environment(environment == null ? EnvironmentRef.none() : environment)
                .elapsedMillis(0)
                .suiteRunId(from.suiteRunId())
                .caseId(from.caseId())
                .replayOfRunId(from.replayOfRunId());
    }

    /**
     * A ledger draft for a write the production guardrail refused. Nothing was sent, and the
     * attempt is still recorded: a refused production write is something an owner wants to see.
     * A service does not use this directly: {@link #blocked} builds the draft, records it and
     * returns the result the tester sees.
     */
    public RunRecord.Builder blockedDraft(EpaOperation operation, ProdWriteBlockedException refusal, RunOrigin origin) {
        return localDraft(operation, refusal.environment(), origin)
                .targetSource(refusal.requestName())
                .outcome(RunOutcome.BLOCKED)
                .errorType(RunRecord.ERROR_BLOCKED)
                .result(TestResult.UNVERIFIED);
    }

    /**
     * Fills the parts of a ledger draft that come from the request, the same way for every
     * protocol: the codes, the line of business, the expectation, the note, the tester reference,
     * the chain, the member ID in the form that was sent, the NPI, the date of service and the
     * inputs the line cannot reproduce. The member's name, date of birth and address are in the
     * request and are not read here, because the record has nowhere to put them.
     */
    public RunRecord.Builder fill(RunRecord.Builder draft, RunRequest request) {
        return draft
                .procedureCodes(codes(request))
                .lineOfBusiness(request.member() == null ? null : Texts.trimToNull(request.member().lineOfBusiness()))
                .expected(Texts.trimToNull(request.expected()))
                .note(Texts.trimToNull(request.note()))
                .testerRef(Texts.trimToNull(request.testerRef()))
                .chainId(Texts.trimToNull(request.chainId()))
                .memberId(Texts.trimToNull(memberId(request).sent()))
                .npi(npi(request))
                .dateOfService(Texts.trimToNull(request.dateOfService()))
                .unrecordedInputs(unrecordedInputs(request));
    }

    /** The member ID of a request as typed and as sent. Both empty when the request names no member. */
    public static MemberIdForms memberId(RunRequest request) {
        return MemberIds.forms(request.member() == null ? null : request.member().memberId());
    }

    /** The NPI of a request, or null when it names no provider. */
    public static String npi(RunRequest request) {
        return request.provider() == null ? null : Texts.trimToNull(request.provider().npi());
    }

    /** The procedure codes of a request, without blanks. */
    public static List<String> codes(RunRequest request) {
        return request.procedureCodes() == null ? List.of()
                : request.procedureCodes().stream().filter(Objects::nonNull).map(CodeInput::code).filter(Texts::present)
                .map(String::strip).toList();
    }

    /** True when the tester supplied their own body, to be sent in place of one the tool would build. */
    public static boolean ownBody(RunRequest request) {
        return Texts.present(request.rawBody());
    }

    /** The things a run sent that its ledger line will not hold, so replay can refuse it honestly. */
    public static List<String> unrecordedInputs(RunRequest request) {
        List<String> inputs = new ArrayList<>();
        if (ownBody(request)) {
            inputs.add(RunRecord.INPUT_BODY);
        }
        if (request.memberOverrides() != null && !request.memberOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_MEMBER_DETAILS);
        }
        if (request.providerOverrides() != null && !request.providerOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_PROVIDER_DETAILS);
        }
        if (request.variables() != null && !request.variables().isEmpty()) {
            inputs.add(RunRecord.INPUT_RUN_VALUES);
        }
        if (request.request() != null) {
            inputs.add(RunRecord.INPUT_CHOSEN_REQUEST);
        }
        return inputs;
    }

    /**
     * The result of a write the production guardrail refused, built once here so that every
     * protocol answers it the same way. The rule: the service catches the refusal and returns this.
     * The attempt is recorded, the result carries the ledger receipt so the tester is told whether
     * it was, and the controller answers 403 with the result as the body. The verdict says nothing
     * was sent, the test result is unverified because there is nothing to compare, and the wire
     * view shows where the request would have gone, marked as not sent.
     *
     * @param <D> the protocol's detail type. A blocked result has no detail
     */
    public <D> RunEnvelope<D> blocked(EpaOperation operation, ProdWriteBlockedException refusal, RunRequest request,
                                      RunOrigin origin) {
        RunRecord record = fill(blockedDraft(operation, refusal, origin), request).build();
        LedgerReceipt receipt = record(record);
        ResolvedUrl target = refusal.target();
        MemberIdForms memberId = memberId(request);
        WireRequest attempted = new WireRequest(refusal.method(), target == null ? null : target.display(),
                target == null ? null : target.template(), List.of(), null, false,
                "Blocked by the production guardrail. Nothing left this machine.");
        return RunEnvelope.<D>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(operation.protocol())
                .operation(operation)
                .environment(refusal.environment())
                .profile(epa.mode())
                .verdict(VerdictView.of(RunOutcome.BLOCKED, "Nothing was sent. " + refusal.getMessage(), List.of()))
                .expectation(Expectations.notCompared(request.expected(), RunOutcome.BLOCKED,
                        "Nothing was sent, so there is nothing to compare with what was expected."))
                .nextStep(NextStepView.of(NextAction.UNLOCK_OR_SWITCH, Texts.orElse(refusal.action(),
                        "Unlock writes for this session, or switch to an environment that is not production."), refusal.getMessage()))
                .wire(new WireView(attempted, null, memberId.typed().isEmpty() ? null : memberId))
                .error(ErrorView.of(ErrorKind.BLOCKED, refusal.getMessage() + " Nothing was sent.", refusal.action()))
                .ledger(receipt)
                .build();
    }

    /** True when a result is the one {@link #blocked} builds, which a controller answers with 403. */
    public static boolean isBlocked(RunEnvelope<?> envelope) {
        return envelope != null && envelope.error() != null && envelope.error().kind() == ErrorKind.BLOCKED;
    }

    /**
     * The line that says which mock scenario answered and how it came to be the one, or null when
     * the answer came from a real service. Every protocol puts it first under the verdict. Without
     * it a default scenario's answer reads as an answer about the member and code on the form.
     */
    public static String scenarioLine(VendorExchange exchange) {
        if (exchange == null || Texts.blank(exchange.scenarioId())) {
            return null;
        }
        return "Mock scenario " + exchange.scenarioId() + " answered"
                + (Texts.present(exchange.scenarioNote()) ? ": " + exchange.scenarioNote() : "") + ".";
    }

    /**
     * Writes the record and returns what the ledger did with it. Never throws: a run whose record
     * could not be written still returns its result, and the receipt tells the screen to say so.
     */
    public LedgerReceipt record(RunRecord draft) {
        RunLedger target = ledger.getIfAvailable();
        if (target == null) {
            return new LedgerReceipt(false, null, "This run was NOT recorded: no ledger is available in this build.", 0);
        }
        try {
            return target.record(draft);
        } catch (RuntimeException e) {
            return new LedgerReceipt(false, null, "This run was NOT recorded: the ledger failed while writing it ("
                    + e.getClass().getSimpleName() + ").", 0);
        }
    }

    /** The finding for a run that used a collection request with a pre-request script, or null when it did not. */
    public Finding scriptFinding(PreparedCall call) {
        if (!call.scripted()) {
            return null;
        }
        return new Finding(FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN,
                "The collection request has a pre-request script that was not run",
                "This tool never runs Postman scripts. Any value the script would have computed, such as a token, a date or a "
                        + "generated id, was not computed. If the request failed for no visible reason, look here first.",
                call.requestName(), null, null);
    }

    /** The finding for a call that completed but took longer than the threshold, or null. */
    public Finding slowFinding(Timing timing) {
        if (timing == null || !timing.slow()) {
            return null;
        }
        return new Finding(FindingKind.SLOW_RESPONSE,
                "The response was slow",
                "The call completed in " + timing.elapsedMillis() + " ms, which is longer than the threshold of "
                        + timing.thresholdMillis() + " ms.", null, null, null);
    }

    /**
     * Describes a vendor fault for the error panel, the same way for every protocol. Null when the
     * call did not fail at the transport or HTTP level.
     *
     * @param issues the issues of an OperationOutcome the service returned, or empty
     */
    public ErrorView faultView(VendorExchange exchange, List<IssueView> issues) {
        OnyxFailureType failure = exchange.failureType();
        String status = exchange.httpStatus() == null ? "" : " with HTTP " + exchange.httpStatus();
        int code = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        ErrorView view = switch (failure) {
            case NONE -> null;
            // The same status can mean the body, the credentials or the address is at fault, and
            // the tester's next move differs for each, so the advice follows the status.
            case BAD_REQUEST -> ErrorView.of(ErrorKind.VENDOR_REJECTED, "The service did not accept the request as sent, answering"
                            + status + ".",
                    code == 401 || code == 403
                            ? "This status means the credentials were not accepted, or none were sent. Check the client id, the "
                            + "secret and the token url on the Config tab. The request that was sent is in the wire view."
                            : code == 404
                            ? "This status usually means the url or the service id is wrong. Check it on the Config tab. The request "
                            + "that was sent is in the wire view."
                            : "Read the issues it returned, correct the request and send again. The request that was sent is in the "
                            + "wire view.");
            case SERVER_ERROR -> ErrorView.of(ErrorKind.VENDOR_ERROR, "The service failed" + status + ".",
                    "This is a fault in the environment, not in the request. Note the time and the run id and report it.");
            case TIMEOUT -> ErrorView.of(ErrorKind.TIMEOUT,
                    "No response arrived within the read timeout" + (epa.mock() ? "" : " of " + onyx.readTimeoutMs() + " ms") + ".",
                    "The service may be slow or down. Send again, and if it repeats report the environment.");
            case UNAVAILABLE -> ErrorView.of(ErrorKind.UNREACHABLE,
                    Texts.orElse(exchange.failureDetail(), "The service could not be reached."),
                    "Check the url on the Config tab, the network and any proxy or trust store settings.");
            case MALFORMED_RESPONSE -> exchange.responseBody() == null
                    ? ErrorView.of(ErrorKind.MALFORMED_RESPONSE, "The service answered" + status + " with no body.",
                    "A success with nothing in it is not an answer. Report it as a defect in the service.")
                    : ErrorView.of(ErrorKind.MALFORMED_RESPONSE,
                    ("The service answered" + status + " with a body this tool could not read. "
                            + Texts.orElse(exchange.parseError(), "")).strip(),
                    "The body is shown exactly as received. Report it as a defect in the service.");
        };
        if (view == null) {
            return null;
        }
        return view.withHttpStatus(exchange.httpStatus()).withIssues(issues == null ? List.of() : issues);
    }
}
