package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A ledger that keeps records in a list, for tests of the services that write to one.
 *
 * <p>It applies no tracking policy: a draft is stored exactly as given, so a test can assert on
 * what a service handed to the ledger. The policy itself is tested against the real ledger.
 */
public final class InMemoryLedger implements RunLedger {

    private final List<RunRecord> records = new ArrayList<>();
    private String refusal;
    private RuntimeException failure;

    /** Makes every later write answer "not recorded" with this message, as a full disk would. */
    public InMemoryLedger refusing(String message) {
        this.refusal = message;
        return this;
    }

    /** Makes every later write throw, which a real ledger must never do and a service must survive. */
    public InMemoryLedger throwing(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    public List<RunRecord> records() {
        return records;
    }

    public RunRecord only() {
        if (records.size() != 1) {
            throw new AssertionError("Expected exactly one ledger record but there are " + records.size());
        }
        return records.get(0);
    }

    public RunRecord last() {
        return records.get(records.size() - 1);
    }

    @Override
    public LedgerReceipt record(RunRecord draft) {
        if (failure != null) {
            throw failure;
        }
        if (refusal != null) {
            return new LedgerReceipt(false, null, refusal, records.size());
        }
        records.add(draft);
        return new LedgerReceipt(true, draft, null, records.size());
    }

    @Override
    public List<RunRecord> query(RunQuery query) {
        List<RunRecord> out = new ArrayList<>(records);
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        return records.stream().filter(r -> r.runId().equals(runId)).findFirst();
    }

    @Override
    public LedgerReceipt addNote(String runId, String note, String testerRef) {
        RunRecord record = find(runId).orElseThrow();
        RunRecord noted = record.toBuilder().note(note).build();
        records.set(records.indexOf(record), noted);
        return new LedgerReceipt(true, noted, null, records.size());
    }

    @Override
    public LedgerHealth health() {
        return new LedgerHealth(true, "memory", true, "memory", records.size(), 0, null, null);
    }

    @Override
    public String tokenFor(String identifier) {
        return identifier;
    }
}
