package com.thehiddenbrain.interop.patientaccess.history;

import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogTest {

    private static final Instant NOON = Instant.parse("2026-09-16T12:00:00Z");

    @TempDir
    Path dir;

    static RequestRecord record(String id, Instant at, String envId, String purpose, String correlation) {
        return new RequestRecord(id, at, envId, "env " + envId, purpose, correlation, "GET", "https://fhir.example.org/r4/" + id,
                Map.of("Accept", "application/fhir+json", "Authorization", "Bearer ***"), null, 200, Map.of("Content-Type", List.of("application/fhir+json")),
                "{\"resourceType\":\"Bundle\"}", false, 42, null, "Bundle searchset: 0 entries");
    }

    @Test
    void ringKeepsOnlyTheNewestEntries() {
        RequestLog log = new RequestLog(dir.resolve("history"), 3, false, new TestGraph.MutableClock(NOON));
        // the minimum ring size is 10 whatever the configuration says
        for (int i = 1; i <= 15; i++) {
            log.record(record("r" + i, NOON.plusSeconds(i), "e1", "search", null));
        }
        assertThat(log.size()).isEqualTo(10);
        assertThat(log.list(null, null, null, 100)).extracting(RequestRecord.Summary::id)
                .containsExactly("r15", "r14", "r13", "r12", "r11", "r10", "r9", "r8", "r7", "r6");
        assertThat(log.get("r1")).isEmpty();
        assertThat(log.get("r15")).isPresent();
        assertThat(log.list(null, null, null, 2)).extracting(RequestRecord.Summary::id).containsExactly("r15", "r14");
        assertThat(dir.resolve("history")).doesNotExist();
        log.clear();
        assertThat(log.size()).isZero();
    }

    @Test
    void filtersByEnvironmentPurposeAndCorrelation() {
        RequestLog log = new RequestLog(dir.resolve("history"), 100, false, new TestGraph.MutableClock(NOON));
        log.record(record("a", NOON, "e1", "search", "run-1"));
        log.record(record("b", NOON, "e2", "search", "run-1"));
        log.record(record("c", NOON, "e1", "auth", "run-2"));
        log.record(record("d", NOON, "e1", "read", null));

        assertThat(log.list("e1", null, null, 10)).extracting(RequestRecord.Summary::id).containsExactly("d", "c", "a");
        assertThat(log.list(null, "search", null, 10)).extracting(RequestRecord.Summary::id).containsExactly("b", "a");
        assertThat(log.list(null, null, "run-1", 10)).extracting(RequestRecord.Summary::id).containsExactly("b", "a");
        assertThat(log.list("e1", "search", "run-1", 10)).extracting(RequestRecord.Summary::id).containsExactly("a");
        assertThat(log.list("e3", null, null, 10)).isEmpty();
        assertThat(log.byCorrelation("run-2")).extracting(RequestRecord::id).containsExactly("c");
        RequestRecord.Summary s = log.list(null, null, null, 1).get(0);
        assertThat(s.environmentName()).isEqualTo("env e1");
        assertThat(s.summary()).isEqualTo("Bundle searchset: 0 entries");
        assertThat(s.durationMs()).isEqualTo(42);
    }

    @Test
    void persistsOneJsonlFilePerDayAndReloadsYesterdayAndToday() throws Exception {
        TestGraph.MutableClock clock = new TestGraph.MutableClock(NOON.minus(Duration.ofDays(2)));
        Path folder = dir.resolve("history");
        RequestLog log = new RequestLog(folder, 100, true, clock);
        log.record(record("old", clock.instant(), "e1", "search", null));
        clock.set(NOON.minus(Duration.ofDays(1)));
        log.record(record("y1", clock.instant(), "e1", "search", null));
        log.record(record("y2", clock.instant(), "e1", "read", null));
        clock.set(NOON);
        log.record(record("t1", clock.instant(), "e1", "auth", null));

        assertThat(folder.resolve("requests-2026-09-14.jsonl")).isRegularFile();
        assertThat(Files.readAllLines(folder.resolve("requests-2026-09-15.jsonl"))).hasSize(2).allSatisfy(l -> assertThat(l).startsWith("{\"id\":\"y"));
        assertThat(Files.readAllLines(folder.resolve("requests-2026-09-16.jsonl"))).singleElement().satisfies(l -> assertThat(l).contains("\"purpose\":\"auth\""));

        RequestLog reloaded = new RequestLog(folder, 100, true, new TestGraph.MutableClock(NOON.plus(Duration.ofHours(3))));
        assertThat(reloaded.size()).isEqualTo(3);
        assertThat(reloaded.list(null, null, null, 10)).extracting(RequestRecord.Summary::id).containsExactly("t1", "y2", "y1");
        RequestRecord t1 = reloaded.get("t1").orElseThrow();
        assertThat(t1.at()).isEqualTo(NOON);
        assertThat(t1.requestHeaders()).containsEntry("Authorization", "Bearer ***");
        assertThat(t1.responseHeaders()).containsEntry("Content-Type", List.of("application/fhir+json"));
        assertThat(t1.responseBody()).isEqualTo("{\"resourceType\":\"Bundle\"}");

        // a ring smaller than the files keeps the newest entries; unreadable lines are skipped
        Files.writeString(folder.resolve("requests-2026-09-16.jsonl"), "not json\n", java.nio.file.StandardOpenOption.APPEND);
        RequestLog small = new RequestLog(folder, 10, true, new TestGraph.MutableClock(NOON));
        assertThat(small.size()).isEqualTo(3);
        RequestLog notPersisting = new RequestLog(folder, 100, false, new TestGraph.MutableClock(NOON));
        assertThat(notPersisting.size()).isZero();
    }
}
