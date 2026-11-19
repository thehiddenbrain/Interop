package com.thehiddenbrain.interop.epa.pave.service.error;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;

/**
 * The production guardrail refused a write. Nothing was sent. Answered with HTTP 403.
 *
 * <p>A PAS submit against production creates a real prior authorization request against a real
 * member, so a write to a production environment is refused unless writes were explicitly
 * unlocked for this session and the configuration floor permits it.
 */
public class ProdWriteBlockedException extends RuntimeException {

    private final EpaOperation operation;
    private final EnvironmentRef environment;
    private final String action;

    public ProdWriteBlockedException(EpaOperation operation, EnvironmentRef environment, String reason, String action) {
        super(reason);
        this.operation = operation;
        this.environment = environment;
        this.action = action;
    }

    public EpaOperation operation() {
        return operation;
    }

    public EnvironmentRef environment() {
        return environment;
    }

    /** What the tester can do about it. */
    public String action() {
        return action;
    }
}
