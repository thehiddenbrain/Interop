package org.p32h.interop.epa.pave.service.pas;

import java.util.List;
import java.util.Optional;
import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;

/**
 * The shared in-memory ledger with one difference: every query fails, while writes and lookups by
 * run id still work. The shared fake can refuse or fail a write and has no way to fail a query, and
 * a caller that asks the ledger a question must survive a ledger that cannot answer it.
 *
 * <p>It wraps the shared fake and holds no state of its own, so it is not a second ledger. The
 * exception message names a member on purpose: a caller that copied it into a result or a log
 * would be caught by the tests that look for that name.
 */
final class QueryFailingLedger implements RunLedger {

    static final String MESSAGE = "the ledger directory is gone for Placeholder-Surname";

    private final InMemoryLedger delegate;

    QueryFailingLedger(InMemoryLedger delegate) {
        this.delegate = delegate;
    }

    @Override
    public LedgerReceipt record(RunRecord draft) {
        return delegate.record(draft);
    }

    @Override
    public List<RunRecord> query(RunQuery query) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        return delegate.find(runId);
    }

    @Override
    public LedgerReceipt addNote(String runId, String note, String testerRef) {
        return delegate.addNote(runId, note, testerRef);
    }

    @Override
    public LedgerHealth health() {
        return delegate.health();
    }

    @Override
    public String tokenFor(String identifier) {
        return delegate.tokenFor(identifier);
    }

    @Override
    public boolean matches(String stored, String identifier) {
        return delegate.matches(stored, identifier);
    }
}
