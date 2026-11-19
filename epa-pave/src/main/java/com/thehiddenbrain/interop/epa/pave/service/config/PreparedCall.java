package com.thehiddenbrain.interop.epa.pave.service.config;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedHeader;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedUrl;
import java.util.List;

/**
 * A call that has been given a target and has passed the production guardrail.
 *
 * <p>Every client method that sends anything takes one of these, and the constructor is
 * package-private: only the gateway in this package can mint one, and it mints one only after
 * resolving the url against a single snapshot of the configuration and checking the guardrail
 * against that same snapshot. So there is no way to hand a client a production write that was not
 * authorised. The protection is in the type, not in a check somebody has to remember to call.
 */
public final class PreparedCall {

    private final EpaOperation operation;
    private final String method;
    private final ResolvedUrl url;
    private final List<ResolvedHeader> headers;
    private final EnvironmentRef environment;
    private final String requestName;
    private final boolean scripted;

    PreparedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers,
                 EnvironmentRef environment, String requestName, boolean scripted) {
        this.operation = operation;
        this.method = method;
        this.url = url;
        this.headers = headers == null ? List.of() : List.copyOf(headers);
        this.environment = environment == null ? EnvironmentRef.none() : environment;
        this.requestName = requestName;
        this.scripted = scripted;
    }

    public EpaOperation operation() {
        return operation;
    }

    public String method() {
        return method;
    }

    /** Where the call goes. On the mock profile this may be unconfigured, since nothing is sent. */
    public ResolvedUrl url() {
        return url;
    }

    /** Headers from the collection request, resolved. Empty when the target came from the fallback layer. */
    public List<ResolvedHeader> headers() {
        return headers;
    }

    /** The environment the url was resolved against and the guardrail was checked against. */
    public EnvironmentRef environment() {
        return environment;
    }

    /** The collection request this target came from, or null when it came from the fallback layer. */
    public String requestName() {
        return requestName;
    }

    /**
     * True when the collection request carries a pre-request script. The script is never run, so a
     * value Postman would have computed is not computed here, and the screen says so.
     */
    public boolean scripted() {
        return scripted;
    }

    @Override
    public String toString() {
        return "PreparedCall[" + operation + " " + method + " " + url + "]";
    }
}
