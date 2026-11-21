package org.p32h.interop.epa.pave.model.suite;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * How long the calls of a suite run took. It describes calls the service answered: a call that
 * timed out, or was never sent, has no response time to describe and is counted separately.
 *
 * @param answered        calls the service answered, which is what the figures below describe
 * @param timedOut        calls that got no answer within the read timeout
 * @param slow            answered calls that took longer than the threshold
 * @param thresholdMillis the threshold a call is judged against
 * @param minMillis       null, like every figure below, when no call was answered
 * @param buckets         the answered calls by how long they took, shortest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuiteTiming(int answered, int timedOut, int slow, long thresholdMillis, Long minMillis, Long medianMillis,
                          Long p90Millis, Long p95Millis, Long maxMillis, Long meanMillis, List<Bucket> buckets) {

    public SuiteTiming {
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }

    /**
     * @param label      the range in words, such as "250 to 500 ms"
     * @param fromMillis the lower edge, included
     * @param toMillis   the upper edge, not included. Null for the last bucket, which has none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Bucket(String label, long fromMillis, Long toMillis, int count) {
    }
}
