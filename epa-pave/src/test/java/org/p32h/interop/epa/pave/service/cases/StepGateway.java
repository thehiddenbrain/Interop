package org.p32h.interop.epa.pave.service.cases;

import java.util.EnumMap;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;

/**
 * A gateway that answers as the fake does, except for the operations it is told to refuse or fail
 * on, so one step of a chain can be refused, blocked or broken while the steps before it go out.
 */
public final class StepGateway implements OperationGateway {

    private final FakeGateway delegate;
    private final Map<EpaOperation, RuntimeException> failures = new EnumMap<>(EpaOperation.class);

    public StepGateway(FakeGateway delegate) {
        this.delegate = delegate;
    }

    /** Makes every later call of this operation throw, as a refusal or a defect would. */
    public StepGateway failing(EpaOperation operation, RuntimeException failure) {
        failures.put(operation, failure);
        return this;
    }

    @Override
    public PreparedCall prepare(CallIntent intent) {
        RuntimeException failure = failures.get(intent.operation());
        if (failure != null) {
            throw failure;
        }
        return delegate.prepare(intent);
    }

    @Override
    public ResolvedText resolve(String template, Map<String, String> runValues) {
        return delegate.resolve(template, runValues);
    }

    @Override
    public EnvironmentRef activeEnvironment() {
        return delegate.activeEnvironment();
    }

    @Override
    public WriteAccess writeAccess(ProdUnlock unlock) {
        return delegate.writeAccess(unlock);
    }

    @Override
    public long epoch() {
        return delegate.epoch();
    }
}
