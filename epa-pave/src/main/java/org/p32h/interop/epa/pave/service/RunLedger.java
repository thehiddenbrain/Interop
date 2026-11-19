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
     * Every other correlation entry, including a run id, a claim id the tool minted, a
     * ClaimResponse id and a subscription id, is written as given.
     * The receipt holds the record as actually written.
     */
    LedgerReceipt record(RunRecord draft);

    /**
     * Runs matching the query, newest first, each with its latest note applied. A correlation
     * filter is given the raw identifier and matches a stored value in either form, as
     * {@link #matches} does.
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
     * salted token.
     */
    String tokenFor(String identifier);

    /**
     * True when a stored value is this identifier in either form the ledger can have written: the
     * identifier with whitespace removed, or its token. Lines are never rewritten, and a pended
     * authorization can be notified days after it was submitted, so a line written under one policy
     * must still match after the policy has changed. A correlation filter in {@link #query} matches
     * the same way.
     */
    boolean matches(String stored, String identifier);
}
