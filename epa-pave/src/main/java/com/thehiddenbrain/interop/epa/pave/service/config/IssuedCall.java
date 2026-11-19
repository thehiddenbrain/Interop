package com.thehiddenbrain.interop.epa.pave.service.config;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedHeader;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedUrl;
import java.util.List;
import java.util.function.Function;

/** The only implementation of {@link PreparedCall}. Package-private, so only the gateway in this package mints one. */
final class IssuedCall implements PreparedCall {

    private final EpaOperation operation;
    private final String method;
    private final ResolvedUrl url;
    private final List<ResolvedHeader> headers;
    private final EnvironmentRef environment;
    private final String requestName;
    private final boolean scripted;
    private final long epoch;
    private final Function<String, ResolvedText> resolver;

    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, long epoch, Function<String, ResolvedText> resolver) {
        this.operation = operation;
        this.method = method;
        this.url = url;
        this.headers = headers == null ? List.of() : List.copyOf(headers);
        this.environment = environment == null ? EnvironmentRef.none() : environment;
        this.requestName = requestName;
        this.scripted = scripted;
        this.epoch = epoch;
        this.resolver = resolver;
    }

    @Override
    public EpaOperation operation() {
        return operation;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public ResolvedUrl url() {
        return url;
    }

    @Override
    public List<ResolvedHeader> headers() {
        return headers;
    }

    @Override
    public EnvironmentRef environment() {
        return environment;
    }

    @Override
    public String requestName() {
        return requestName;
    }

    @Override
    public boolean scripted() {
        return scripted;
    }

    @Override
    public long epoch() {
        return epoch;
    }

    @Override
    public ResolvedText resolve(String template) {
        return template == null ? null : resolver.apply(template);
    }

    /**
     * Prints the url template, never the resolved url. For a lookup the resolved url holds the
     * member ID, so it must not reach a log through toString.
     */
    @Override
    public String toString() {
        return "PreparedCall[" + operation + " " + method + " " + (url == null ? "" : url.template()) + "]";
    }
}
