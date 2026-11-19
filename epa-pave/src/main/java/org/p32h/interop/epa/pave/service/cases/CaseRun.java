package org.p32h.interop.epa.pave.service.cases;

import java.util.List;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.TestResult;

/**
 * What running a case produced: the result of each call that was made, in order.
 *
 * @param envelopes one result for a single operation, several for a chain. Never empty
 * @param stopped   for a chain that did not go all the way, why it stopped where it did. Null for
 *                  a single operation
 * @param overall   for a chain, the test result of the case as a whole. Null for a single
 *                  operation, whose result is the one its own call produced
 */
public record CaseRun(List<RunEnvelope<?>> envelopes, String stopped, TestResult overall) {

    public CaseRun {
        envelopes = envelopes == null ? List.of() : List.copyOf(envelopes);
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("A case that ran has at least one result.");
        }
    }

    public static CaseRun of(RunEnvelope<?> envelope) {
        return new CaseRun(List.of(envelope), null, null);
    }

    public RunEnvelope<?> last() {
        return envelopes.get(envelopes.size() - 1);
    }
}
