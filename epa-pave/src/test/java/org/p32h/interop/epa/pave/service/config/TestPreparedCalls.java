package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Test-only factory for the two guarded types.
 *
 * <p>It sits in the same package so that tests of clients and services can obtain a
 * {@link PreparedCall} or a {@link ProdUnlock} without going through a real gateway. Production
 * code cannot do this: outside the test source set nothing in this package but the gateway mints
 * either type, and the implementations are not visible outside the package.
 *
 * <p>Every call built here carries epoch 0 and resolves text as plain text, unless a test says
 * otherwise.
 */
public final class TestPreparedCalls {

    private TestPreparedCalls() {
    }

    /** A call to a fully resolved url in a sandbox environment. */
    public static PreparedCall to(EpaOperation operation, String url) {
        return to(operation, operation.defaultMethod(), url, List.of(), new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX));
    }

    public static PreparedCall to(EpaOperation operation, String method, String url, List<ResolvedHeader> headers,
                                  EnvironmentRef environment) {
        return with(operation, method, new ResolvedUrl(url, url, url, List.of(), List.of(), "test"), headers, environment, null, false);
    }

    /** A call whose url is given exactly, for cases such as an unresolved or unconfigured target. */
    public static PreparedCall with(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers,
                                    EnvironmentRef environment, String requestName, boolean scripted) {
        return new IssuedCall(operation, method, url, headers, environment, requestName, scripted, 0L, ResolvedText::plain);
    }

    /** As {@link #with}, at a given configuration epoch and with a given resolver for text that belongs to the call. */
    public static PreparedCall at(long epoch, Function<String, ResolvedText> resolver, EpaOperation operation, String method,
                                  ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment, String requestName,
                                  boolean scripted) {
        return new IssuedCall(operation, method, url, headers, environment, requestName, scripted, epoch, resolver);
    }

    /** The same call, holding the given secrets beside what it carries, as a call the real gateway prepared holds those of its configuration. */
    public static PreparedCall holding(PreparedCall call, org.p32h.interop.epa.pave.model.SentSecrets held) {
        IssuedCall issued = (IssuedCall) call;
        return new IssuedCall(issued.operation(), issued.method(), issued.url(), issued.headers(), issued.environment(),
                issued.requestName(), issued.scripted(), issued::epoch, issued::resolve, issued.savedBody(), () -> held);
    }

    /** A call on the mock profile with nothing configured, which is the out-of-the-box state. */
    public static PreparedCall unconfigured(EpaOperation operation) {
        return with(operation, operation.defaultMethod(), ResolvedUrl.notConfigured("not configured"), List.of(),
                EnvironmentRef.none(), null, false);
    }

    /**
     * An unlock object for tests of code that only carries one around. A real gateway rejects it:
     * a gateway honours only the instances it minted itself and still holds, never an unlock that
     * merely has the right name and epoch.
     */
    public static ProdUnlock unlock(String environmentName, long epoch) {
        return new GrantedUnlock(environmentName, epoch, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
