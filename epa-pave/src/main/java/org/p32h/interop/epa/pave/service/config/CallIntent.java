package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunOrigin;
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
 * @param expectedEpoch the configuration epoch the caller believes is current, or null to not
 *                  check. The browser sends the epoch its environment strip was drawn from, and a
 *                  suite pins the one it started under, so a run can never go to an environment
 *                  other than the one its screen showed
 * @param origin    the suite case or the replay the call is made for, or null for a call a tester
 *                  made on a screen. It changes nothing about whether the call may go: a suite and
 *                  a replay carry no unlock. It lets a refusal say so, instead of telling the
 *                  tester to unlock, which would not help
 * @param lookup    where a lookup saved on screen goes (R-11), or null for application.yml's lookup
 *                  and for every operation that is not a lookup. Set only by {@link #withLookup}
 *
 * <p>This is built by a controller or a service from validated inputs. It is never itself a
 * handler argument or a request body, so that nothing a browser posts can become an intent
 * directly.
 */
public record CallIntent(EpaOperation operation, RequestRef request, Map<String, String> runValues,
                         Map<String, String> urlValues, ProdUnlock unlock, String method, Long expectedEpoch, RunOrigin origin,
                         LookupTarget lookup) {

    public CallIntent {
        if (operation == null) {
            throw new IllegalArgumentException("An intent needs an operation.");
        }
        // A lookup target is a read whatever its method, so it may stand only for a lookup: on any
        // other operation it would be a way to send a write that the guardrail does not judge.
        if (lookup != null && operation != EpaOperation.MEMBER_LOOKUP && operation != EpaOperation.PROVIDER_LOOKUP) {
            throw new IllegalArgumentException("Only a member or provider lookup takes a lookup target.");
        }
        if (lookup != null && request != null) {
            throw new IllegalArgumentException("A lookup target and a collection request are two targets; a call has one.");
        }
        runValues = runValues == null ? Map.of() : Map.copyOf(runValues);
        urlValues = urlValues == null ? Map.of() : Map.copyOf(urlValues);
    }

    public CallIntent(EpaOperation operation, RequestRef request, Map<String, String> runValues, Map<String, String> urlValues,
                      ProdUnlock unlock, String method, Long expectedEpoch, RunOrigin origin) {
        this(operation, request, runValues, urlValues, unlock, method, expectedEpoch, origin, null);
    }

    public CallIntent(EpaOperation operation, RequestRef request, Map<String, String> runValues, Map<String, String> urlValues,
                      ProdUnlock unlock, String method, Long expectedEpoch) {
        this(operation, request, runValues, urlValues, unlock, method, expectedEpoch, null, null);
    }

    public static CallIntent of(EpaOperation operation) {
        return new CallIntent(operation, null, Map.of(), Map.of(), null, null, null, null, null);
    }

    public CallIntent withRequest(RequestRef newRequest) {
        return new CallIntent(operation, newRequest, runValues, urlValues, unlock, method, expectedEpoch, origin, lookup);
    }

    public CallIntent withRunValues(Map<String, String> newRunValues) {
        return new CallIntent(operation, request, newRunValues, urlValues, unlock, method, expectedEpoch, origin, lookup);
    }

    public CallIntent withUrlValues(Map<String, String> newUrlValues) {
        return new CallIntent(operation, request, runValues, newUrlValues, unlock, method, expectedEpoch, origin, lookup);
    }

    /** Adds one value for a single-brace token, keeping any already set. */
    public CallIntent withUrlValue(String name, String value) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(urlValues);
        merged.put(name, value);
        return new CallIntent(operation, request, runValues, merged, unlock, method, expectedEpoch, origin, lookup);
    }

    public CallIntent withUnlock(ProdUnlock newUnlock) {
        return new CallIntent(operation, request, runValues, urlValues, newUnlock, method, expectedEpoch, origin, lookup);
    }

    public CallIntent withMethod(String newMethod) {
        return new CallIntent(operation, request, runValues, urlValues, unlock, newMethod, expectedEpoch, origin, lookup);
    }

    /** The epoch the caller's view of the configuration was drawn from. Null means it is not checked. */
    public CallIntent withExpectedEpoch(Long newExpectedEpoch) {
        return new CallIntent(operation, request, runValues, urlValues, unlock, method, newExpectedEpoch, origin, lookup);
    }

    /** The suite case or replay this call is made for. A refusal is worded from it; nothing else is decided by it. */
    public CallIntent withOrigin(RunOrigin newOrigin) {
        return new CallIntent(operation, request, runValues, urlValues, unlock, method, expectedEpoch, newOrigin, lookup);
    }

    /**
     * Sends a member or provider lookup to the target saved on screen in place of application.yml's
     * (R-11). The gateway resolves it against the snapshot the call is prepared from, never counts
     * it a write for its method, and holds its secret header values.
     *
     * @throws IllegalArgumentException for any operation but the two lookups, or with a request named
     */
    public CallIntent withLookup(LookupTarget target) {
        return new CallIntent(operation, request, runValues, urlValues, unlock, method, expectedEpoch, origin, target);
    }

    /** Prints the names of the values and never the values, which can hold a member ID or a typed secret. */
    @Override
    public String toString() {
        return "CallIntent[" + operation + ", request=" + request + ", runValues=" + runValues.keySet() + ", urlValues="
                + urlValues.keySet() + (lookup == null ? "" : ", " + lookup) + "]";
    }
}
