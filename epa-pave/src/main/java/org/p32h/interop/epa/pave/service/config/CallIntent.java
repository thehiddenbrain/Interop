package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EpaOperation;
import java.util.Map;

/**
 * What a caller wants to send, before the gateway has decided where it goes or whether it may go.
 *
 * @param operation the operation
 * @param request   a specific collection request to use. It must be mapped to the operation. Null
 *                  for the default: the first request mapped to the operation, in collection order,
 *                  whose name does not contain "error"
 * @param runValues placeholder values typed on the form for this run, for {@code {{name}}}
 *                  placeholders. They win over every other layer
 * @param urlValues values for single-brace {@code {name}} tokens in a url template, such as the
 *                  {@code {id}} of a lookup url. The gateway percent-encodes each one, so a value
 *                  can never change the shape of the url
 * @param unlock    the session's production unlock, or null. A suite run or a replay always passes null
 * @param method    the HTTP method. Honoured only for an {@code UNMAPPED} request; for every other
 *                  operation the gateway uses the collection request's own method or the
 *                  operation's default, so a read cannot be turned into a write by naming a method
 *
 * <p>This is built by a controller or a service from validated inputs. It is never itself a
 * handler argument or a request body, so that nothing a browser posts can become an intent
 * directly.
 */
public record CallIntent(EpaOperation operation, RequestRef request, Map<String, String> runValues,
                         Map<String, String> urlValues, ProdUnlock unlock, String method) {

    public CallIntent {
        if (operation == null) {
            throw new IllegalArgumentException("An intent needs an operation.");
        }
        runValues = runValues == null ? Map.of() : Map.copyOf(runValues);
        urlValues = urlValues == null ? Map.of() : Map.copyOf(urlValues);
    }

    public static CallIntent of(EpaOperation operation) {
        return new CallIntent(operation, null, Map.of(), Map.of(), null, null);
    }

    public CallIntent withRequest(RequestRef newRequest) {
        return new CallIntent(operation, newRequest, runValues, urlValues, unlock, method);
    }

    public CallIntent withRunValues(Map<String, String> newRunValues) {
        return new CallIntent(operation, request, newRunValues, urlValues, unlock, method);
    }

    public CallIntent withUrlValues(Map<String, String> newUrlValues) {
        return new CallIntent(operation, request, runValues, newUrlValues, unlock, method);
    }

    /** Adds one value for a single-brace token, keeping any already set. */
    public CallIntent withUrlValue(String name, String value) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(urlValues);
        merged.put(name, value);
        return new CallIntent(operation, request, runValues, merged, unlock, method);
    }

    public CallIntent withUnlock(ProdUnlock newUnlock) {
        return new CallIntent(operation, request, runValues, urlValues, newUnlock, method);
    }

    public CallIntent withMethod(String newMethod) {
        return new CallIntent(operation, request, runValues, urlValues, unlock, newMethod);
    }

    /** Prints the names of the values and never the values, which can hold a member ID or a typed secret. */
    @Override
    public String toString() {
        return "CallIntent[" + operation + ", request=" + request + ", runValues=" + runValues.keySet() + ", urlValues="
                + urlValues.keySet() + "]";
    }
}
