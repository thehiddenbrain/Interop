package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.LedgerHealth;
import com.thehiddenbrain.interop.epa.pave.model.LedgerReceipt;
import com.thehiddenbrain.interop.epa.pave.model.RunQuery;
import com.thehiddenbrain.interop.epa.pave.model.RunRecord;
import java.util.List;
import java.util.Optional;

/**
 * The test run ledger: an append-only record of every run that survives a restart.
 *
 * <p>A protocol service calls {@link #record(RunRecord)} before it returns a result, and puts the
 * receipt in that result. No method here throws for a failed write; the receipt says what happened
 * so the screen can tell the tester.
 */
public interface RunLedger {

    /**
     * Appends one run.
     *
     * <p>The draft carries the real member ID. The ledger applies the tracking policy before
     * writing: unless recording member IDs is configured it stores a salted token and the last
     * four characters, and drops the member ID and the date of service. The receipt holds the
     * record as actually written.
     */
    LedgerReceipt record(RunRecord draft);

    /** Runs matching the query, newest first, each with its latest note applied. */
    List<RunRecord> query(RunQuery query);

    /** One run by id, with its latest note applied. */
    Optional<RunRecord> find(String runId);

    /**
     * Adds or amends the note on a run. The existing line is never rewritten; the note is appended
     * as its own line and applied when the run is read.
     */
    LedgerReceipt addNote(String runId, String note, String testerRef);

    /** Whether tracking is alive, and how many runs today's file holds. */
    LedgerHealth health();

    /**
     * The token the ledger stores in place of an identifier, for matching an incoming value against
     * a stored one without the ledger holding the value itself.
     */
    String tokenFor(String identifier);
}
