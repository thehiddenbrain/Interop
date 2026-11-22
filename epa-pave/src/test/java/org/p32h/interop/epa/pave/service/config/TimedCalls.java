package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.SentSecrets;

/**
 * Test-only calls that carry the timeouts of an environment, as the gateway hands them once an
 * environment states its own. Beside {@link TestPreparedCalls} for the same reason: only this
 * package can mint a {@link PreparedCall}.
 */
public final class TimedCalls {

    private TimedCalls() {
    }

    /** A call at epoch 0 to a resolved url in the named sandbox environment, with these timeouts. */
    public static PreparedCall to(EpaOperation operation, String url, String environment, Timeouts timeouts) {
        return new IssuedCall(operation, operation.defaultMethod(), new ResolvedUrl(url, url, url, List.of(), List.of(), "test"), List.of(),
                new EnvironmentRef(environment, EnvironmentKind.SANDBOX), null, false, () -> 0L, ResolvedText::plain, null,
                SentSecrets::none, timeouts);
    }
}
