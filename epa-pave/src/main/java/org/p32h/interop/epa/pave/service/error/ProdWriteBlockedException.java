package org.p32h.interop.epa.pave.service.error;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedUrl;

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
    private final ResolvedUrl target;
    private final String method;
    private final String requestName;

    public ProdWriteBlockedException(EpaOperation operation, EnvironmentRef environment, String reason, String action) {
        this(operation, environment, reason, action, null, null);
    }

    /**
     * @param target where the request would have gone, so the blocked attempt can be shown and
     *               recorded as what it was. Null when no target could be worked out
     * @param method the method it would have been sent with, or null
     */
    public ProdWriteBlockedException(EpaOperation operation, EnvironmentRef environment, String reason, String action,
                                     ResolvedUrl target, String method) {
        this(operation, environment, reason, action, target, method, null);
    }

    /** @param requestName the collection request the target came from, or null */
    public ProdWriteBlockedException(EpaOperation operation, EnvironmentRef environment, String reason, String action,
                                     ResolvedUrl target, String method, String requestName) {
        super(reason);
        this.operation = operation;
        this.environment = environment;
        this.action = action;
        this.target = target;
        this.method = method;
        this.requestName = requestName;
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

    /** Where the request would have gone, or null. */
    public ResolvedUrl target() {
        return target;
    }

    /** The method it would have been sent with, or null. */
    public String method() {
        return method;
    }

    /** The collection request the target came from, or null. */
    public String requestName() {
        return requestName;
    }
}
