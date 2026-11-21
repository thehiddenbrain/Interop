package org.p32h.interop.epa.pave.service.pas;

import java.util.List;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;

/**
 * Finds the run that an identifier belongs to, among the runs that were sent.
 *
 * <p>A run the production guardrail refused has a ledger line too, and that line names the claim
 * or the subscription the run was aimed at. It sent nothing. An inquiry or a notification about a
 * claim is about the request the payer holds, so it must never be tied to a refused attempt that
 * happens to be the newest line for that claim.
 */
final class SentRuns {

    /**
     * How many of the newest matching runs are read before the whole ledger is asked. Refused
     * attempts in front of the run that was sent are rare and few, so one short look nearly always
     * settles it without reading every day's file.
     */
    private static final int FIRST_LOOK = 10;

    private SentRuns() {
    }

    /**
     * The newest run of one operation that carries an identifier and was not refused by the
     * guardrail, or null when there is none. The comparison with a stored value is the ledger's
     * own. Whatever the ledger throws is left to the caller, who knows what a ledger that cannot
     * be asked means for the question it had.
     */
    static RunRecord newest(RunLedger ledger, EpaOperation operation, String key, String value) {
        RunQuery.Builder query = RunQuery.builder().operation(operation).correlation(key, value);
        List<RunRecord> newestFirst = ledger.query(query.limit(FIRST_LOOK).build());
        RunRecord sent = firstSent(newestFirst);
        if (sent == null && newestFirst.size() >= FIRST_LOOK) {
            // Every one of the newest was refused. An older one that was sent may still be there.
            sent = firstSent(ledger.query(query.limit(null).build()));
        }
        return sent;
    }

    private static RunRecord firstSent(List<RunRecord> newestFirst) {
        for (RunRecord run : newestFirst) {
            if (!RunOutcome.BLOCKED.name().equals(run.outcome())) {
                return run;
            }
        }
        return null;
    }
}
