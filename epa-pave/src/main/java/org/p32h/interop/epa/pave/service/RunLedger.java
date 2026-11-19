package org.p32h.interop.epa.pave.service;

import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
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
     * writing, and rewrites exactly these parts of the draft and nothing else:
     * <ul>
     *   <li>{@code memberId}: whitespace is removed, then it is stored as is when recording member
     *       IDs is configured, and otherwise replaced by {@code memberToken} and
     *       {@code memberIdLast4}</li>
     *   <li>{@code dateOfService}: kept only when the member ID is kept</li>
     *   <li>{@code npi}: kept only when recording NPIs is configured</li>
     *   <li>the correlation entry {@link RunRecord#CORR_AUTHORIZATION_REF}: follows the member ID
     *       policy, since an authorization number identifies a member's request</li>
     * </ul>
     * The receipt holds the record as actually written.
     */
    LedgerReceipt record(RunRecord draft);

    /**
     * Runs matching the query, newest first, each with its latest note applied. A correlation
     * filter is given the raw identifier; the ledger applies its storage policy before comparing.
     */
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
     * The form the ledger writes for a member-linked identifier under the current policy: the
     * identifier with whitespace removed when recording member IDs is configured, otherwise its
     * salted token. To match an incoming identifier against a stored one, compare the stored value
     * with this and never with the raw identifier, so the match works under either policy.
     */
    String tokenFor(String identifier);
}
