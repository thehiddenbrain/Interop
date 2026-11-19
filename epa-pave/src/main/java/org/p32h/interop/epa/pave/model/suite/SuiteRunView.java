package org.p32h.interop.epa.pave.model.suite;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EnvironmentRef;

/**
 * A suite run as the screen shows it: where it stands, what it ran against, the count of each kind
 * of result, the response times, and one row per case.
 *
 * @param headline     one plain sentence saying where the run stands and what came of it
 * @param stopReason   why the run ended before every case was attempted. Null otherwise
 * @param epoch        the configuration the run started under and is pinned to
 * @param comparedWith the earlier suite run each row is compared with, or null
 * @param findingCounts how many cases raised each kind of finding, by the name of the kind. A
 *                     finding is something a person should look at, such as the code list and the
 *                     service disagreeing. It is not a failure of the case
 * @param notes        what a reader should know about the run as a whole
 * @param results      one row per case, in file order. Null in a list of runs, where only the
 *                     summary is given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuiteRunView(
        String suiteRunId,
        String name,
        String format,
        SuiteState state,
        String headline,
        String stopReason,
        String startedAt,
        String finishedAt,
        String mode,
        String target,
        EnvironmentRef environment,
        long epoch,
        String testerRef,
        String comparedWith,
        SuiteCounts counts,
        SuiteTiming timing,
        Map<String, Integer> findingCounts,
        List<String> notes,
        List<SuiteCaseResult> results) {

    public SuiteRunView {
        findingCounts = findingCounts == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(findingCounts));
        notes = notes == null ? List.of() : List.copyOf(notes);
        results = results == null ? null : List.copyOf(results);
    }

    /** The same run without its rows, for a list of runs. */
    public SuiteRunView summary() {
        return new SuiteRunView(suiteRunId, name, format, state, headline, stopReason, startedAt, finishedAt, mode, target,
                environment, epoch, testerRef, comparedWith, counts, timing, findingCounts, notes, null);
    }
}
