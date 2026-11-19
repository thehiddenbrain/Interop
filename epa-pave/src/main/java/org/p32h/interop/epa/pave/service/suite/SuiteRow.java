package org.p32h.interop.epa.pave.service.suite;

import java.util.List;
import org.p32h.interop.epa.pave.model.CaseSpec;

/**
 * One row of a suite file, read.
 *
 * @param number   the position of the case in the file, counting the first as 1
 * @param caseId   the id the row gave, or one made from its position
 * @param what     what the row runs, in plain words. Null when the row does not say
 * @param write    true when running the row can create or change something on the far side
 * @param expected what the row declared it expects, as written, or null
 * @param spec     the case to run. Null when the row has a problem, because such a row is not run
 * @param problems what is wrong with the row. Empty when it can be run
 */
public record SuiteRow(int number, String caseId, String what, boolean chain, boolean write, String expected, CaseSpec spec,
                       List<String> problems) {

    public SuiteRow {
        problems = problems == null ? List.of() : List.copyOf(problems);
        if (!problems.isEmpty()) {
            spec = null;
        } else if (spec == null) {
            throw new IllegalArgumentException("A row with no problem has a case to run.");
        }
    }

    public boolean runnable() {
        return problems.isEmpty();
    }
}
