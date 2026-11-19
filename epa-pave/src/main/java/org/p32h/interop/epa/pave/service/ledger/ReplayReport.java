package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a replay found: which runs still give the same answer, which changed and how, and which
 * could not be sent again and why.
 *
 * @param startedAt       when the replay started
 * @param from            the first day asked for, or null
 * @param to              the last day asked for, or null
 * @param runnerAvailable false when this build has nothing that can run a case. Every run is then
 *                        reported as not replayable, and a note says so
 * @param configEpoch     the configuration epoch every run of this replay was held to, or null
 *                        when the tool could not say. A run that would have gone out under a later
 *                        configuration is reported as not configured, so one report never mixes
 *                        the answers of two environments
 * @param notes           what applies to the whole replay and not to one run: that nothing could
 *                        be run, that nothing was selected, that runs in the range were left out
 *                        of the selection and why, that writes were sent again. Empty when there
 *                        is nothing to say
 * @param selected        how many runs were selected. Always the number of results
 * @param summary         how many results there are of each status, by the status as it is
 *                        written. Every status is listed, with zero where there are none
 * @param results         one entry for each selected run, oldest first for a date range and in
 *                        the order given for run ids
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayReport(String startedAt, String from, String to, boolean runnerAvailable, Long configEpoch, List<String> notes,
                           int selected, Map<String, Integer> summary, List<ReplayEntry> results) {

    public ReplayReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
        results = results == null ? List.of() : List.copyOf(results);
        summary = summary == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    /** A report over the given results, with the count and the summary worked out from them so they cannot disagree. */
    static ReplayReport of(String startedAt, String from, String to, boolean runnerAvailable, Long configEpoch, List<String> notes,
                           List<ReplayEntry> results) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (ReplayStatus status : ReplayStatus.values()) {
            summary.put(status.wire(), 0);
        }
        for (ReplayEntry result : results) {
            summary.merge(result.status().wire(), 1, Integer::sum);
        }
        return new ReplayReport(startedAt, from, to, runnerAvailable, configEpoch, notes, results.size(), summary, results);
    }
}
