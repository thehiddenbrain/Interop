package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** The only implementation of {@link PreparedCall}. Package-private, so only the gateway in this package mints one. */
final class IssuedCall implements PreparedCall {

    private final EpaOperation operation;
    private final String method;
    private final ResolvedUrl url;
    private final List<ResolvedHeader> headers;
    private final EnvironmentRef environment;
    private final String requestName;
    private final boolean scripted;
    private final LongSupplier epoch;
    private final Function<String, ResolvedText> resolver;
    private final String savedBody;

    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, long epoch, Function<String, ResolvedText> resolver) {
        this(operation, method, url, headers, environment, requestName, scripted, () -> epoch, resolver);
    }

    /**
     * @param epoch the epoch the call belongs to, asked each time. For a write that an unlock let
     *              through it stops answering the epoch it was prepared in once that unlock is no
     *              longer held, so a relock makes the call stale without moving the epoch that
     *              suites and screens are pinned to
     */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver) {
        this(operation, method, url, headers, environment, requestName, scripted, epoch, resolver, null);
    }

    /** @param savedBody the raw body saved with the collection request, as a template, or null */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver, String savedBody) {
        this.operation = operation;
        this.method = method;
        this.url = url;
        this.headers = headers == null ? List.of() : List.copyOf(headers);
        this.environment = environment == null ? EnvironmentRef.none() : environment;
        this.requestName = requestName;
        this.scripted = scripted;
        this.epoch = epoch;
        this.resolver = resolver;
        this.savedBody = savedBody;
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
        return epoch.getAsLong();
    }

    @Override
    public ResolvedText resolve(String template) {
        return template == null ? null : resolver.apply(template);
    }

    @Override
    public String savedBody() {
        return savedBody;
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
