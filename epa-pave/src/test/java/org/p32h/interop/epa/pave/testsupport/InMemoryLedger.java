package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A ledger that keeps records in a list, for tests of the services that write to one.
 *
 * <p>It applies no tracking policy: a draft is stored exactly as given, so a test can assert on
 * what a service handed to the ledger. The policy itself is tested against the real ledger.
 *
 * <p>A query applies every filter of {@link RunQuery}, as the real ledger does. A fake that
 * returned everything would let a caller that queries under the wrong key pass its tests.
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
            // As the real ledger does: the record is returned whether or not it was kept.
            return new LedgerReceipt(false, draft, refusal, records.size());
        }
        records.add(draft);
        return new LedgerReceipt(true, draft, null, records.size());
    }

    @Override
    public List<RunRecord> query(RunQuery query) {
        List<RunRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            RunRecord record = records.get(i);
            if (accepts(query, record)) {
                out.add(record);
            }
        }
        int limit = query == null || query.limit() == null ? 0 : query.limit();
        return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private boolean accepts(RunQuery query, RunRecord record) {
        if (query == null) {
            return true;
        }
        LocalDate day = record.timestamp() == null || record.timestamp().length() < 10 ? null
                : LocalDate.parse(record.timestamp().substring(0, 10));
        if (query.from() != null && (day == null || day.isBefore(query.from()))) {
            return false;
        }
        if (query.to() != null && (day == null || day.isAfter(query.to()))) {
            return false;
        }
        if (query.protocol() != null && query.protocol() != record.protocol()) {
            return false;
        }
        if (query.operation() != null && query.operation() != record.operation()) {
            return false;
        }
        if (query.result() != null && query.result() != record.result()) {
            return false;
        }
        if (query.outcome() != null && !query.outcome().equals(record.outcome())) {
            return false;
        }
        if (query.chainId() != null && !query.chainId().equals(record.chainId())) {
            return false;
        }
        if (query.suiteRunId() != null && !query.suiteRunId().equals(record.suiteRunId())) {
            return false;
        }
        if (query.correlationKey() != null) {
            String stored = record.correlation().get(query.correlationKey());
            return stored != null && matches(stored, query.correlationValue());
        }
        return true;
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
        return new LedgerHealth(true, "memory", true, "memory", records.size(), 0, null, refusal, null, refusal == null ? 0 : 1);
    }

    @Override
    public String tokenFor(String identifier) {
        return identifier;
    }

    @Override
    public boolean matches(String stored, String identifier) {
        return stored != null && identifier != null && MemberIds.noSpace(stored).equals(MemberIds.noSpace(identifier));
    }
}
