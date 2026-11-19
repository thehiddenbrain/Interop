package org.p32h.interop.epa.pave.service.suite;

import java.util.ArrayList;
import java.util.List;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.suite.SuiteTiming;

/**
 * The response times of a suite run, kept as numbers only. A sample holds how long one call took
 * and how it ended, and nothing about what was asked or for whom.
 */
public final class SuiteTimings {

    /** Upper edges of the buckets, in milliseconds. The last bucket has no upper edge. */
    private static final long[] EDGES = {250, 500, 1000, 2000, 5000, 10000};

    private final List<Long> answered = new ArrayList<>();
    private int timedOut;
    private int slow;

    /** Adds one ledger record. A record of something that was never sent adds nothing. */
    public void add(RunRecord record) {
        if (record == null || record.elapsedMillis() == null) {
            return;
        }
        if (OnyxFailureType.TIMEOUT.name().equals(record.errorType())) {
            timedOut++;
            return;
        }
        // No status means no answer arrived: blocked before sending, refused connection, or a step inside the tool.
        if (record.httpStatus() == null) {
            return;
        }
        answered.add(record.elapsedMillis());
        if (Boolean.TRUE.equals(record.slow())) {
            slow++;
        }
    }

    public SuiteTiming summarise(long thresholdMillis) {
        if (answered.isEmpty()) {
            return new SuiteTiming(0, timedOut, 0, thresholdMillis, null, null, null, null, null, null, List.of());
        }
        List<Long> sorted = answered.stream().sorted().toList();
        long total = 0;
        for (long value : sorted) {
            total += value;
        }
        List<SuiteTiming.Bucket> buckets = new ArrayList<>();
        long from = 0;
        for (long edge : EDGES) {
            long lower = from;
            buckets.add(new SuiteTiming.Bucket(label(lower, edge), lower, edge,
                    (int) sorted.stream().filter(v -> v >= lower && v < edge).count()));
            from = edge;
        }
        long last = from;
        buckets.add(new SuiteTiming.Bucket("over " + words(last), last, null, (int) sorted.stream().filter(v -> v >= last).count()));
        return new SuiteTiming(sorted.size(), timedOut, slow, thresholdMillis, sorted.get(0), percentile(sorted, 50),
                percentile(sorted, 90), percentile(sorted, 95), sorted.get(sorted.size() - 1), Math.round((double) total / sorted.size()),
                buckets);
    }

    /**
     * The nearest-rank percentile: the smallest sample with at least that share of the samples at
     * or below it. It is always a time that was measured, never a value between two.
     */
    static long percentile(List<Long> sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static String label(long from, long to) {
        return from == 0 ? "under " + words(to) : words(from) + " to " + words(to);
    }

    private static String words(long millis) {
        return millis < 1000 ? millis + " ms" : (millis / 1000) + " s";
    }
}
