package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.suite.SuiteTiming;

class SuiteTimingsTest {

    private static RunRecord call(Integer status, Long elapsed, OnyxFailureType failure, boolean slow) {
        RunRecord.Builder record = RunRecord.builder().runId("run-1").timestamp("2026-03-10T10:00:00.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome("NO_AUTH_REQUIRED").result(TestResult.UNVERIFIED)
                .httpStatus(status).errorType(failure).slow(slow);
        return elapsed == null ? record.build() : record.elapsedMillis(elapsed).build();
    }

    private static SuiteTimings of(long... elapsed) {
        SuiteTimings timings = new SuiteTimings();
        for (long value : elapsed) {
            timings.add(call(200, value, OnyxFailureType.NONE, false));
        }
        return timings;
    }

    @Test
    void theFiguresDescribeTheCallsTheServiceAnswered() {
        SuiteTiming timing = of(100, 300, 200, 400, 1000).summarise(15000);
        assertThat(timing.answered()).isEqualTo(5);
        assertThat(timing.thresholdMillis()).isEqualTo(15000);
        assertThat(timing.minMillis()).isEqualTo(100);
        assertThat(timing.maxMillis()).isEqualTo(1000);
        assertThat(timing.meanMillis()).isEqualTo(400);
        assertThat(timing.medianMillis()).isEqualTo(300);
        assertThat(timing.p90Millis()).isEqualTo(1000);
        assertThat(timing.p95Millis()).isEqualTo(1000);
        assertThat(timing.slow()).isZero();
        assertThat(timing.timedOut()).isZero();
    }

    @Test
    void aPercentileIsAlwaysATimeThatWasMeasured() {
        List<Long> hundred = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(SuiteTimings.percentile(hundred, 50)).isEqualTo(50);
        assertThat(SuiteTimings.percentile(hundred, 90)).isEqualTo(90);
        assertThat(SuiteTimings.percentile(hundred, 95)).isEqualTo(95);
        assertThat(SuiteTimings.percentile(List.of(7L), 95)).isEqualTo(7);
        assertThat(SuiteTimings.percentile(List.of(10L, 20L), 50)).isEqualTo(10);
        assertThat(SuiteTimings.percentile(List.of(10L, 20L), 90)).isEqualTo(20);
        assertThat(of(1, 2, 3, 4).summarise(0).medianMillis()).isEqualTo(2);
    }

    @Test
    void everyAnsweredCallIsInExactlyOneBucket() {
        SuiteTiming timing = of(0, 249, 250, 499, 500, 999, 1000, 1999, 2000, 4999, 5000, 9999, 10000, 60000).summarise(15000);
        assertThat(timing.buckets()).extracting(SuiteTiming.Bucket::label).containsExactly("under 250 ms", "250 ms to 500 ms",
                "500 ms to 1 s", "1 s to 2 s", "2 s to 5 s", "5 s to 10 s", "over 10 s");
        assertThat(timing.buckets()).extracting(SuiteTiming.Bucket::count).containsExactly(2, 2, 2, 2, 2, 2, 2);
        assertThat(timing.buckets().stream().mapToInt(SuiteTiming.Bucket::count).sum()).isEqualTo(timing.answered());
        assertThat(timing.buckets().get(0).fromMillis()).isZero();
        assertThat(timing.buckets().get(0).toMillis()).isEqualTo(250);
        assertThat(timing.buckets().get(6).fromMillis()).isEqualTo(10000);
        assertThat(timing.buckets().get(6).toMillis()).isNull();
    }

    @Test
    void aCallThatGotNoAnswerHasNoResponseTimeToDescribe() {
        SuiteTimings timings = new SuiteTimings();
        timings.add(call(200, 120L, OnyxFailureType.NONE, false));
        // Slow, but answered: it is in the figures and counted as slow.
        timings.add(call(200, 16000L, OnyxFailureType.NONE, true));
        // An HTTP error is still an answer, and how long it took is a fact about the service.
        timings.add(call(500, 80L, OnyxFailureType.SERVER_ERROR, false));
        // No answer inside the read timeout. Its elapsed time is the timeout, not a response time.
        timings.add(call(null, 15000L, OnyxFailureType.TIMEOUT, false));
        // Refused connection, blocked before sending, a step inside the tool, a record with no time, and nothing at all.
        timings.add(call(null, 3L, OnyxFailureType.UNAVAILABLE, false));
        timings.add(call(null, 0L, null, false));
        timings.add(call(null, null, OnyxFailureType.NONE, false));
        timings.add(null);

        SuiteTiming timing = timings.summarise(15000);
        assertThat(timing.answered()).isEqualTo(3);
        assertThat(timing.timedOut()).isEqualTo(1);
        assertThat(timing.slow()).isEqualTo(1);
        assertThat(timing.minMillis()).isEqualTo(80);
        assertThat(timing.maxMillis()).isEqualTo(16000);
    }

    @Test
    void withNoAnsweredCallThereAreNoFiguresAndNoZeroesPretendingToBeOnes() {
        SuiteTimings timings = new SuiteTimings();
        timings.add(call(null, 15000L, OnyxFailureType.TIMEOUT, false));
        SuiteTiming timing = timings.summarise(15000);
        assertThat(timing.answered()).isZero();
        assertThat(timing.timedOut()).isEqualTo(1);
        assertThat(timing.minMillis()).isNull();
        assertThat(timing.medianMillis()).isNull();
        assertThat(timing.p90Millis()).isNull();
        assertThat(timing.p95Millis()).isNull();
        assertThat(timing.maxMillis()).isNull();
        assertThat(timing.meanMillis()).isNull();
        assertThat(timing.buckets()).isEmpty();
    }

    @Test
    void aPercentileIsTheNearestRankRoundedUpAndNeverTheRankBelow() {
        // Thirteen calls: the 95th percentile is rank 13 (12.35 rounded up), the slowest. Rounding to the nearest rank would give 12.
        SuiteTiming thirteen = of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130).summarise(15000);
        assertThat(thirteen.p95Millis()).isEqualTo(130);
        assertThat(thirteen.p90Millis()).isEqualTo(120);
        assertThat(thirteen.medianMillis()).isEqualTo(70);
        // Seven calls: the 90th percentile is rank 7 (6.3 rounded up). Rounding would give rank 6.
        SuiteTiming seven = of(1, 2, 3, 4, 5, 6, 7).summarise(15000);
        assertThat(seven.p90Millis()).isEqualTo(7);
        assertThat(seven.p95Millis()).isEqualTo(7);
        assertThat(seven.medianMillis()).isEqualTo(4);
    }

    @Test
    void aTimeoutThatStillCarriesAStatusIsCountedAsTimedOutAndNotAsAnswered() {
        SuiteTimings timings = new SuiteTimings();
        timings.add(call(504, 30000L, OnyxFailureType.TIMEOUT, true));
        timings.add(call(200, 100L, OnyxFailureType.NONE, false));
        SuiteTiming timing = timings.summarise(15000);
        assertThat(timing.timedOut()).isEqualTo(1);
        assertThat(timing.answered()).isEqualTo(1);
        assertThat(timing.maxMillis()).isEqualTo(100);
        assertThat(timing.slow()).isZero();
    }
}
