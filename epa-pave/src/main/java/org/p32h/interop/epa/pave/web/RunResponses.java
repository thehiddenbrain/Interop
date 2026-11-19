package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The status a run result is answered with, the same for every protocol.
 *
 * <p>A completed call answers 200 even when the service returned a fault, because the tester
 * needs to see the request that caused it and the fault is described in the body. The one result
 * that is not a completed call is a write the production guardrail refused: it answers 403, with
 * the full result as the body, so the browser can tell it apart without reading the body and the
 * tester still sees that the attempt was recorded.
 */
public final class RunResponses {

    private RunResponses() {
    }

    public static <D> ResponseEntity<RunEnvelope<D>> of(RunEnvelope<D> envelope) {
        return ResponseEntity.status(RunSupport.isBlocked(envelope) ? HttpStatus.FORBIDDEN : HttpStatus.OK).body(envelope);
    }
}
