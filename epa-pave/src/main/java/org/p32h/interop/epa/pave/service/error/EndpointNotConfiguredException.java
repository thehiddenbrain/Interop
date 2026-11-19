package org.p32h.interop.epa.pave.service.error;

import org.p32h.interop.epa.pave.model.EpaOperation;

/**
 * The tool has nowhere to send an operation: no mapped collection request and no fallback url.
 * Nothing was sent. Answered with HTTP 409, since the fix is to change configuration, not the form.
 */
public class EndpointNotConfiguredException extends RuntimeException {

    private final EpaOperation operation;

    public EndpointNotConfiguredException(EpaOperation operation, String message) {
        super(message);
        this.operation = operation;
    }

    public EpaOperation operation() {
        return operation;
    }
}
