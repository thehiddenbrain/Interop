package com.thehiddenbrain.interop.epa.pave.service.config;

import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import java.util.Map;

/**
 * What a caller wants to send, before the gateway has decided where it goes or whether it may go.
 *
 * @param operation the operation
 * @param request   a specific collection request to use, or null for the one mapped to the operation
 * @param runValues placeholder values typed on the form for this run. They win over every other layer
 * @param unlock    the session's production unlock, or null. A suite run always passes null
 * @param method    the HTTP method, or null for the method of the collection request or the
 *                  operation's default
 */
public record CallIntent(EpaOperation operation, RequestRef request, Map<String, String> runValues, ProdUnlock unlock,
                         String method) {

    public CallIntent {
        if (operation == null) {
            throw new IllegalArgumentException("An intent needs an operation.");
        }
        runValues = runValues == null ? Map.of() : Map.copyOf(runValues);
    }

    public static CallIntent of(EpaOperation operation) {
        return new CallIntent(operation, null, Map.of(), null, null);
    }

    public CallIntent withRequest(RequestRef newRequest) {
        return new CallIntent(operation, newRequest, runValues, unlock, method);
    }

    public CallIntent withRunValues(Map<String, String> newRunValues) {
        return new CallIntent(operation, request, newRunValues, unlock, method);
    }

    public CallIntent withUnlock(ProdUnlock newUnlock) {
        return new CallIntent(operation, request, runValues, newUnlock, method);
    }

    public CallIntent withMethod(String newMethod) {
        return new CallIntent(operation, request, runValues, unlock, newMethod);
    }
}
