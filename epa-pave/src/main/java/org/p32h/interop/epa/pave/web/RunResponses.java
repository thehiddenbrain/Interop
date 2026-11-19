package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The status a run result is answered with, the same for every protocol.
 *
 * <p>A completed call answers 200 even when the service returned a fault, because the tester
 * needs to see the request that caused it and the fault is described in the body. Two results are
 * not a completed call, and each keeps the full result as its body so the tester still sees the
 * run id and whether the attempt was recorded:
 * <ul>
 *   <li>403 for a write the production guardrail refused. Nothing was sent</li>
 *   <li>500 for a run in which the service was called and this tool then failed to read the
 *       answer. The body carries the request that went out and the response as received</li>
 * </ul>
 * Every other 403 and 500 the tool answers comes from the exception handler and carries an
 * {@code ErrorView}: a refused write on an endpoint that records no run, such as the unlock
 * switch, and a failure before anything was sent. The browser tells the two bodies apart by the
 * presence of {@code runId}.
 */
public final class RunResponses {

    private RunResponses() {
    }

    public static <D> ResponseEntity<RunEnvelope<D>> of(RunEnvelope<D> envelope) {
        HttpStatus status = RunSupport.isBlocked(envelope) ? HttpStatus.FORBIDDEN
                : RunSupport.isToolError(envelope) ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
        return ResponseEntity.status(status).body(envelope);
    }
}
