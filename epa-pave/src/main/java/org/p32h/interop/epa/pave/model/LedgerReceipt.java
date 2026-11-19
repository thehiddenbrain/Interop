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
 * @param record    the record after the tracking policy was applied, whether or not it reached
 *                  the file. It is given either way because a suite row and a replay see nothing
 *                  of a run except this record: if it were dropped when a write failed, a run that
 *                  happened would vanish from their results with no word as to why
 * @param message   why the record was not written, or null
 * @param runsToday how many runs today's ledger file holds after this write. Null when the ledger
 *                  cannot say, which is not the same as none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerReceipt(boolean recorded, RunRecord record, String message, Integer runsToday) {

    public LedgerReceipt {
        if (record == null) {
            throw new IllegalArgumentException("A receipt always carries the record, whether or not it was written.");
        }
        if (!recorded && Texts.blank(message)) {
            throw new IllegalArgumentException("A run that was not recorded must say why.");
        }
    }
}
