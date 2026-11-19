package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the ledger did with one record.
 *
 * <p>A run is written before its result is rendered, and the result carries this receipt. When
 * {@code recorded} is false the screen must say so plainly: a tester who believes they have a
 * record and does not is worse off than one who knows tracking is off.
 *
 * @param recorded  true only when the line was written, flushed and synced
 * @param written   the record as stored, after the tracking policy was applied. Null when not recorded
 * @param message   why the record was not written, or null
 * @param runsToday how many runs today's ledger file holds after this write
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerReceipt(boolean recorded, RunRecord written, String message, int runsToday) {
}
