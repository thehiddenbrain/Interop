package com.thehiddenbrain.interop.epa.pave.service.config;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentKind;
import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedHeader;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedUrl;
import java.time.Instant;
import java.util.List;

/**
 * Test-only factory for the two types whose constructors are package-private in production code.
 *
 * <p>It sits in the same package so that tests of clients and services can obtain a
 * {@link PreparedCall} or a {@link ProdUnlock} without going through a real gateway. Production
 * code cannot do this: outside the test source set nothing in this package but the gateway mints
 * either type.
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
        return new PreparedCall(operation, method, new ResolvedUrl(url, url, url, List.of(), List.of(), "test"), headers,
                environment, null, false);
    }

    /** A call whose url is given exactly, for cases such as an unresolved or unconfigured target. */
    public static PreparedCall with(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers,
                                    EnvironmentRef environment, String requestName, boolean scripted) {
        return new PreparedCall(operation, method, url, headers, environment, requestName, scripted);
    }

    /** A call on the mock profile with nothing configured, which is the out-of-the-box state. */
    public static PreparedCall unconfigured(EpaOperation operation) {
        return new PreparedCall(operation, operation.defaultMethod(), ResolvedUrl.notConfigured("not configured"), List.of(),
                EnvironmentRef.none(), null, false);
    }

    public static ProdUnlock unlock(String environmentName, long epoch) {
        return new ProdUnlock(environmentName, epoch, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
