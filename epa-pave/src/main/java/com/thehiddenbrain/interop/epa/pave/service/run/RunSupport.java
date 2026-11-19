package com.thehiddenbrain.interop.epa.pave.service.run;

import com.thehiddenbrain.interop.epa.pave.config.EpaProperties;
import com.thehiddenbrain.interop.epa.pave.config.OnyxProperties;
import com.thehiddenbrain.interop.epa.pave.config.TrackingProperties;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.ErrorKind;
import com.thehiddenbrain.interop.epa.pave.model.ErrorView;
import com.thehiddenbrain.interop.epa.pave.model.Finding;
import com.thehiddenbrain.interop.epa.pave.model.FindingKind;
import com.thehiddenbrain.interop.epa.pave.model.IssueView;
import com.thehiddenbrain.interop.epa.pave.model.LedgerReceipt;
import com.thehiddenbrain.interop.epa.pave.model.OnyxFailureType;
import com.thehiddenbrain.interop.epa.pave.model.RunOrigin;
import com.thehiddenbrain.interop.epa.pave.model.RunRecord;
import com.thehiddenbrain.interop.epa.pave.model.TestResult;
import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.model.Timing;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.service.RunLedger;
import com.thehiddenbrain.interop.epa.pave.service.RunStamps;
import com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall;
import com.thehiddenbrain.interop.epa.pave.service.error.ProdWriteBlockedException;
import java.time.Clock;
import java.util.List;
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

    /** The outcome recorded when the call was made and this tool then failed to read the answer. */
    public static final String OUTCOME_TOOL_ERROR = "TOOL_ERROR";
    /** The outcome recorded when the production guardrail refused to send. */
    public static final String OUTCOME_BLOCKED = "BLOCKED";

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
     * A ledger draft for a write the production guardrail refused. Nothing was sent, and the
     * attempt is still recorded: a refused production write is something an owner wants to see.
     */
    public RunRecord.Builder blockedDraft(EpaOperation operation, ProdWriteBlockedException refusal, RunOrigin origin) {
        RunOrigin from = origin == null ? RunOrigin.manual() : origin;
        return RunRecord.builder()
                .runId(RunStamps.newRunId())
                .timestamp(RunStamps.timestamp(clock))
                .protocol(operation.protocol())
                .operation(operation)
                .target(epa.target())
                .profile(epa.mode())
                .environment(refusal.environment())
                .outcome(OUTCOME_BLOCKED)
                .errorType(RunRecord.ERROR_BLOCKED)
                .result(TestResult.UNVERIFIED)
                .elapsedMillis(0)
                .suiteRunId(from.suiteRunId())
                .caseId(from.caseId())
                .replayOfRunId(from.replayOfRunId());
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
        ErrorView view = switch (failure) {
            case NONE -> null;
            case BAD_REQUEST -> ErrorView.of(ErrorKind.VENDOR_REJECTED, "The service rejected the request" + status + ".",
                    "Read the issues it returned, correct the request and send again. The request that was sent is in the wire view.");
            case SERVER_ERROR -> ErrorView.of(ErrorKind.VENDOR_ERROR, "The service failed" + status + ".",
                    "This is a fault in the environment, not in the request. Note the time and the run id and report it.");
            case TIMEOUT -> ErrorView.of(ErrorKind.TIMEOUT,
                    "No response arrived within the read timeout" + (epa.mock() ? "" : " of " + onyx.readTimeoutMs() + " ms") + ".",
                    "The service may be slow or down. Send again, and if it repeats report the environment.");
            case UNAVAILABLE -> ErrorView.of(ErrorKind.UNREACHABLE,
                    Texts.orElse(exchange.failureDetail(), "The service could not be reached."),
                    "Check the url on the Config tab, the network and any proxy or trust store settings.");
            case MALFORMED_RESPONSE -> ErrorView.of(ErrorKind.MALFORMED_RESPONSE,
                    "The service answered" + status + " with a body this tool could not read. " + Texts.orElse(exchange.parseError(), ""),
                    "The body is shown exactly as received in the wire view. Report it as a defect in the service.");
        };
        if (view == null) {
            return null;
        }
        return view.withHttpStatus(exchange.httpStatus()).withIssues(issues == null ? List.of() : issues);
    }
}
