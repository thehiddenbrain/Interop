package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.config.TrailProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.testsupport.HandClock;
import tools.jackson.databind.node.ObjectNode;

/** The trail's store (R-21): its bounds, its files and what it does with a file it cannot read. */
class RunTrailTest {

    private static final Instant START = Instant.parse("2026-11-22T10:00:00Z");

    @TempDir
    Path directory;

    private final HandClock clock = new HandClock(START);

    private RunTrail trail(Map<String, String> settings, long maxBytes) {
        return new RunTrail(TrailProperties.of(settings), TrackingProperties.of(Map.of("directory", directory.toString())), clock, maxBytes);
    }

    private RunTrail trail() {
        return trail(Map.of(), RunTrail.MAX_BYTES);
    }

    private static String runId(int n) {
        return "run-" + new UUID(0, n);
    }

    private static ObjectNode entry(String runId, int padding) {
        ObjectNode entry = JsonSafe.mapper().createObjectNode();
        entry.put("runId", runId);
        entry.put("padding", "x".repeat(padding));
        return entry;
    }

    private List<String> names() throws Exception {
        try (Stream<Path> files = Files.list(directory.resolve("trail"))) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theFiveHundredAndFirstRunRemovesTheOldestAndNoOther() throws Exception {
        RunTrail trail = trail();
        for (int n = 1; n <= 501; n++) {
            trail.keep(runId(n), entry(runId(n), 10));
            clock.advance(Duration.ofMillis(1));
        }
        assertThat(trail.summary().count()).isEqualTo(500);
        assertThat(trail.find(runId(1)).kept()).isFalse();
        assertThat(trail.find(runId(1)).line()).isEqualTo("Not kept: the trail holds the last 500 runs.");
        assertThat(trail.find(runId(2)).kept()).isTrue();
        assertThat(trail.find(runId(501)).kept()).isTrue();
        assertThat(trail.find(runId(501)).entry().get("runId").asString()).isEqualTo(runId(501));
        // Named by the time it was kept, 13 digits, then the run id, so the order of the names is the order of the runs.
        assertThat(names().get(0)).isEqualTo(START.plusMillis(1).toEpochMilli() + "-" + runId(2) + ".json");
        assertThat(trail.find(runId(2)).keptAt()).isEqualTo(START.plusMillis(1));
    }

    @Test
    void maxEntriesIsTheSettingAndTheLineSaysIt() throws Exception {
        RunTrail trail = trail(Map.of("max-entries", "3"), RunTrail.MAX_BYTES);
        for (int n = 1; n <= 5; n++) {
            trail.keep(runId(n), entry(runId(n), 10));
            clock.advance(Duration.ofMillis(5));
        }
        assertThat(trail.summary().count()).isEqualTo(3);
        assertThat(trail.find(runId(2)).line()).isEqualTo("Not kept: the trail holds the last 3 runs.");
        assertThat(trail.find(runId(3)).kept()).isTrue();
    }

    @Test
    void theSizeBoundRemovesTheOldestUntilWhatIsLeftFits() throws Exception {
        RunTrail trail = trail(Map.of(), 5_000);
        for (int n = 1; n <= 6; n++) {
            trail.keep(runId(n), entry(runId(n), 1_000));
            clock.advance(Duration.ofMillis(1));
        }
        RunTrail.Summary summary = trail.summary();
        assertThat(summary.bytes()).isLessThanOrEqualTo(5_000);
        // Each file is a little over 1,000 bytes, so four fit and the two oldest went.
        assertThat(summary.count()).isEqualTo(4);
        assertThat(trail.find(runId(2)).kept()).isFalse();
        assertThat(trail.find(runId(3)).kept()).isTrue();
        assertThat(trail.find(runId(6)).kept()).isTrue();
    }

    @Test
    void theFolderAndEveryFileAreForTheirOwnerOnly() throws Exception {
        RunTrail trail = trail();
        trail.keep(runId(1), entry(runId(1), 10));
        Path folder = directory.resolve("trail");
        assertThat(trail.folder()).isEqualTo(folder.toAbsolutePath().normalize());
        if (OwnerOnly.supported()) {
            assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            assertThat(Files.getPosixFilePermissions(folder.resolve(names().get(0))))
                    .isEqualTo(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void aFileIsWrittenUnderATemporaryNameAndOneLeftByACrashGoesAtTheNextStart() throws Exception {
        RunTrail trail = trail();
        trail.keep(runId(1), entry(runId(1), 10));
        // Nothing but the entry is left once a keep returns.
        assertThat(names()).containsExactly(START.toEpochMilli() + "-" + runId(1) + ".json");

        // What a crash between writing and moving leaves: a temporary file, never read as an entry.
        String crashed = "." + START.plusMillis(9).toEpochMilli() + "-" + runId(2) + ".json.tmp";
        Files.writeString(directory.resolve("trail").resolve(crashed), "{\"runId\": \"" + runId(2) + "\", \"half");
        Files.writeString(directory.resolve("trail").resolve("notes.txt"), "the operator's own file");
        assertThat(trail.summary().count()).isEqualTo(1);
        assertThat(trail.find(runId(2)).kept()).isFalse();

        RunTrail restarted = trail();
        assertThat(names()).doesNotContain(crashed).contains("notes.txt");
        assertThat(restarted.find(runId(1)).kept()).isTrue();
        // Emptying removes entries only; a file the trail did not write is left alone.
        assertThat(restarted.empty()).isEqualTo(1);
        assertThat(names()).containsExactly("notes.txt");
    }

    @Test
    void aDamagedFileIsSkippedAndCountedAndNeverStopsTheTrail() throws Exception {
        RunTrail trail = trail();
        trail.keep(runId(1), entry(runId(1), 10));
        clock.advance(Duration.ofMillis(1));
        Path damaged = directory.resolve("trail").resolve(START.plusMillis(1).toEpochMilli() + "-" + runId(2) + ".json");
        Files.writeString(damaged, "{\"runId\": \"" + runId(2) + "\", \"envelope\": {");
        Path another = directory.resolve("trail").resolve(START.plusMillis(2).toEpochMilli() + "-" + runId(3) + ".json");
        Files.writeString(another, "{\"runId\": \"" + runId(4) + "\"}");

        RunTrail.Lookup unreadable = trail.find(runId(2));
        assertThat(unreadable.kept()).isFalse();
        assertThat(unreadable.line()).isEqualTo("Not kept: the trail's file for this run could not be read.");
        // A file whose entry names another run is not handed out as this run's.
        assertThat(trail.find(runId(3)).kept()).isFalse();
        assertThat(trail.summary().damaged()).isEqualTo(2);

        clock.advance(Duration.ofMillis(5));
        trail.keep(runId(5), entry(runId(5), 10));
        assertThat(trail.find(runId(1)).kept()).isTrue();
        assertThat(trail.find(runId(5)).kept()).isTrue();
        assertThat(trail.summary().count()).isEqualTo(4);
    }

    @Test
    void switchedOffItKeepsNothingAndSaysSoButCanStillBeEmptied() throws Exception {
        trail().keep(runId(1), entry(runId(1), 10));
        RunTrail off = trail(Map.of("enabled", "false"), RunTrail.MAX_BYTES);
        off.keep(runId(2), entry(runId(2), 10));
        assertThat(names()).hasSize(1);
        assertThat(off.find(runId(1)).kept()).isFalse();
        assertThat(off.find(runId(1)).line()).isEqualTo("The trail is switched off on this installation (trail.enabled).");
        assertThat(off.summary().enabled()).isFalse();
        assertThat(off.summary().count()).isEqualTo(1);
        assertThat(off.empty()).isEqualTo(1);
        assertThat(names()).isEmpty();
    }

    @Test
    void noFolderIsMadeUntilARunIsKept() {
        RunTrail trail = trail();
        assertThat(Files.exists(directory.resolve("trail"))).isFalse();
        assertThat(trail.summary().count()).isZero();
        assertThat(trail.summary().bytes()).isZero();
        assertThat(trail.find(runId(1)).kept()).isFalse();
    }

    @Test
    void onlyAnIdThisToolMintsIsARunId() throws Exception {
        assertThat(RunTrail.isRunId(runId(1))).isTrue();
        assertThat(RunTrail.isRunId("run-" + UUID.randomUUID())).isTrue();
        List<String> refused = new ArrayList<>(List.of("", "run-", "run-../../config-store/store.json", "chain-" + UUID.randomUUID(),
                "run-" + UUID.randomUUID().toString().toUpperCase(), "RUN-" + UUID.randomUUID(), "run-" + UUID.randomUUID() + "x"));
        refused.add(null);
        for (String id : refused) {
            assertThat(RunTrail.isRunId(id)).as(String.valueOf(id)).isFalse();
        }
        // Such an id is never written nor looked for.
        RunTrail trail = trail();
        trail.keep("run-../escape", entry("run-../escape", 10));
        assertThat(Files.exists(directory.resolve("trail"))).isFalse();
        assertThat(RunTrail.isEntryName("0000000000001-" + runId(1) + ".json")).isTrue();
        assertThat(RunTrail.isEntryName("000000000001-" + runId(1) + ".json")).isFalse();
    }
}
