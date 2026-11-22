package org.p32h.interop.epa.pave.service.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.testsupport.HandClock;

/**
 * A copy of PAVE started from a classes folder tells when the IDE rewrote that folder, or the
 * folder of the page's files, under it; a copy started from a jar never reports a change; and the
 * build time is the jar's, or the newest class file's at start (R-24, R-31).
 */
class ClassesOnDiskTest {

    private static final Instant START = Instant.parse("2026-11-22T06:00:00Z");

    private static Path file(Path root, String relative, Instant time) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.from(time));
        return file;
    }

    @Test
    void aClassFileNewerThanTheStartIsSeenAfterTheNextLook(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        file(classes, "org/p32h/interop/epa/pave/A.class", START.minusSeconds(120));
        Path newest = file(classes, "org/p32h/interop/epa/pave/web/B.class", START.minusSeconds(60));
        HandClock clock = new HandClock(START);
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofFolder(classes, null, clock);

        assertThat(watch.builtAt()).isEqualTo(START.minusSeconds(60));
        assertThat(watch.changed()).isFalse();

        // The IDE rebuilds one class a little later.
        Files.setLastModifiedTime(newest, FileTime.from(START.plusSeconds(5)));
        clock.advance(Duration.ofSeconds(3));
        // Looked at less than ten seconds ago: not looked at again.
        assertThat(watch.changed()).isFalse();
        clock.advance(ClassesOnDisk.LOOK_EVERY);
        assertThat(watch.changed()).isTrue();

        // Once true it stays true: only a new start puts memory and disk back in step.
        Files.setLastModifiedTime(newest, FileTime.from(START.minusSeconds(60)));
        clock.advance(ClassesOnDisk.LOOK_EVERY);
        assertThat(watch.changed()).isTrue();
        // The build time stays the one the start saw: the stamp names the build that started.
        assertThat(watch.builtAt()).isEqualTo(START.minusSeconds(60));
    }

    @Test
    void aPageFileNewerThanTheStartIsSeenToo(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        Path statics = dir.resolve("resources/static");
        file(classes, "org/p32h/interop/epa/pave/A.class", START.minusSeconds(120));
        Path script = file(statics, "js/app.js", START.minusSeconds(120));
        HandClock clock = new HandClock(START);
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofFolder(classes, statics, clock);
        assertThat(watch.changed()).isFalse();

        Files.setLastModifiedTime(script, FileTime.from(START.plusSeconds(1)));
        clock.advance(ClassesOnDisk.LOOK_EVERY);
        assertThat(watch.changed()).isTrue();
    }

    @Test
    void aClassFileStampedAheadOfThisClockAtStartIsNotTakenForARebuild(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        file(classes, "org/p32h/interop/epa/pave/A.class", START.plusSeconds(30));
        HandClock clock = new HandClock(START);
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofFolder(classes, null, clock);
        clock.advance(ClassesOnDisk.LOOK_EVERY);
        assertThat(watch.changed()).isFalse();
    }

    @Test
    void aClassesFolderRemovedUnderPaveIsAChange(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        Path only = file(classes, "A.class", START.minusSeconds(120));
        HandClock clock = new HandClock(START);
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofFolder(classes, null, clock);
        assertThat(watch.changed()).isFalse();

        Files.delete(only);
        Files.delete(classes);
        clock.advance(ClassesOnDisk.LOOK_EVERY);
        assertThat(watch.changed()).isTrue();
    }

    @Test
    void aJarNeverReportsAChangeAndItsTimeIsTheBuildTime(@TempDir Path dir) throws IOException {
        Path jar = file(dir, "epa-pave-1.0.0.jar", START.minusSeconds(3600));
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofJar(jar);
        assertThat(watch.builtAt()).isEqualTo(START.minusSeconds(3600));
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().plusSeconds(60)));
        assertThat(watch.changed()).isFalse();
        assertThat(watch.loadAll(getClass().getClassLoader())).isEqualTo(new ClassesOnDisk.Loaded(0, 0));
    }

    @Test
    void nothingKnownIsNeverChangedAndHasNoBuildTime() {
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.unknown();
        assertThat(watch.changed()).isFalse();
        assertThat(watch.builtAt()).isNull();
    }

    @Test
    void aFolderWithNoClassFileHasNoBuildTime(@TempDir Path dir) {
        assertThat(ClassesOnDisk.Watch.ofFolder(dir, null, new HandClock(START)).builtAt()).isNull();
    }

    @Test
    void everyClassOfTheFolderIsLoadedByNameAndAFileThatIsNotAClassIsLeftAlone(@TempDir Path dir) throws IOException {
        // The real class files of two classes of the tool, copied into a folder of their own.
        Path classes = dir.resolve("classes");
        for (Class<?> type : new Class<?>[] {FaultAdvice.class, FileGuard.class}) {
            String relative = type.getName().replace('.', '/') + ".class";
            Path target = classes.resolve(relative);
            Files.createDirectories(target.getParent());
            try (var in = type.getClassLoader().getResourceAsStream(relative)) {
                Files.copy(in, target);
            }
        }
        Files.writeString(classes.resolve("org/p32h/interop/epa/pave/service/error/notes.txt"), "not a class");
        Files.writeString(classes.resolve("org/p32h/interop/epa/pave/service/error/package-info.class"), "not loaded");
        // A class file of a class this loader does not know is counted, never thrown.
        Files.writeString(classes.resolve("org/p32h/interop/epa/pave/service/error/Gone.class"), "x");

        ClassesOnDisk.Loaded loaded = ClassesOnDisk.Watch.ofFolder(classes, null, new HandClock(START))
                .loadAll(getClass().getClassLoader());
        assertThat(loaded).isEqualTo(new ClassesOnDisk.Loaded(2, 1));
    }

    @Test
    void aClassNameIsItsPathUnderTheRoot(@TempDir Path dir) {
        assertThat(ClassesOnDisk.Watch.className(dir, dir.resolve("org/p32h/A$B.class"))).isEqualTo("org.p32h.A$B");
        assertThat(ClassesOnDisk.Watch.className(dir, dir.resolve("module-info.class"))).isNull();
        assertThat(ClassesOnDisk.Watch.className(dir, dir.resolve("org/p32h/A.java"))).isNull();
    }

    @Test
    void installReturnsTheWatchItReplaces() {
        ClassesOnDisk.Watch unknown = ClassesOnDisk.Watch.unknown();
        ClassesOnDisk.Watch before = ClassesOnDisk.install(unknown);
        try {
            assertThat(ClassesOnDisk.changedSinceStart()).isFalse();
            assertThat(ClassesOnDisk.builtAt()).isNull();
        } finally {
            assertThat(ClassesOnDisk.install(before)).isSameAs(unknown);
        }
    }

    @Test
    void aFailureToLinkLooksAtTheFolderNowEvenRightAfterAThrottledLook(@TempDir Path dir) throws IOException {
        // B3a.rejected hunts-1: a page polls every 4 s, so the throttled look is always fresh and a
        // rebuild made just before the failure was missed until the next look 10 s later.
        Path classes = dir.resolve("classes");
        Path rebuilt = file(classes, "org/p32h/interop/epa/pave/postman/A.class", START.minusSeconds(60));
        HandClock clock = new HandClock(START);
        ClassesOnDisk.Watch watch = ClassesOnDisk.Watch.ofFolder(classes, null, clock);
        ClassesOnDisk.Watch before = ClassesOnDisk.install(watch);
        try {
            assertThat(ClassesOnDisk.changedSinceStart()).isFalse();
            Files.setLastModifiedTime(rebuilt, FileTime.from(START.plusSeconds(1)));
            clock.advance(Duration.ofSeconds(1));
            // The page's poll still gets the throttled answer.
            assertThat(ClassesOnDisk.changedSinceStart()).isFalse();
            // A failure to link a class looks now and names the rebuild at once.
            assertThat(FaultAdvice.advice(new RuntimeException(new NoClassDefFoundError()))).isEqualTo(FaultAdvice.CLASSES_CHANGED);
            assertThat(ClassesOnDisk.changedSinceStart()).isTrue();
        } finally {
            ClassesOnDisk.install(before);
        }
    }

    @Test
    void aFailureToLinkWithNoRebuildStillGetsNoAdvice(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("classes");
        file(classes, "org/p32h/interop/epa/pave/postman/A.class", START.minusSeconds(60));
        ClassesOnDisk.Watch before = ClassesOnDisk.install(ClassesOnDisk.Watch.ofFolder(classes, null, new HandClock(START)));
        try {
            assertThat(FaultAdvice.advice(new NoClassDefFoundError())).isNull();
            assertThat(ClassesOnDisk.Watch.ofJar(dir.resolve("absent.jar")).changedNow()).isFalse();
        } finally {
            ClassesOnDisk.install(before);
        }
    }
}
