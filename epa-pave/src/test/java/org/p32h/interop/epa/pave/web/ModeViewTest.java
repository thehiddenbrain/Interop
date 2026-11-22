package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * What {@code /api/mode} says about the running build (R-24, R-31): the stamp names the build by
 * its time, which is never guessed, and a rebuild on disk under a running copy is said.
 */
class ModeViewTest {

    private ClassesOnDisk.Watch before;

    @BeforeEach
    void knownSource() {
        before = ClassesOnDisk.install(ClassesOnDisk.Watch.unknown());
    }

    @AfterEach
    void putBack() {
        ClassesOnDisk.install(before);
    }

    private static JsonNode mode() throws Exception {
        return JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.of(Map.of("mode", "mock")), FakeGateway.unconfigured(),
                new MockEnvironment())).perform(get("/api/mode")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).node();
    }

    @Test
    void aJarIsNamedByItsTimeAndNeverChangesUnderPave(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("epa-pave-1.0.0.jar");
        Files.writeString(jar, "x");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-11-22T06:40:12Z")));
        ClassesOnDisk.install(ClassesOnDisk.Watch.ofJar(jar));

        JsonNode build = mode().get("build");
        assertThat(build.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(build.get("time").stringValue()).isEqualTo("2026-11-22T06:40:12Z");
        assertThat(build.get("changedOnDisk").booleanValue()).isFalse();
    }

    @Test
    void anUnknownBuildTimeIsLeftOutRatherThanGuessed() throws Exception {
        JsonNode build = mode().get("build");
        assertThat(build.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(build.has("time")).isFalse();
        assertThat(build.get("changedOnDisk").booleanValue()).isFalse();
    }

    @Test
    void aClassesFolderRebuiltWhilePaveRunsIsSaidAndTheStampKeepsTheBuildThatStarted(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Path type = classes.resolve("A.class");
        Files.writeString(type, "x");
        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T05:00:00Z")));
        MovableClock clock = new MovableClock(Instant.parse("2026-11-22T06:00:00Z"));
        ClassesOnDisk.install(ClassesOnDisk.Watch.ofFolder(classes, null, clock));

        assertThat(mode().get("build").get("changedOnDisk").booleanValue()).isFalse();

        Files.setLastModifiedTime(type, FileTime.from(Instant.parse("2026-11-22T06:00:05Z")));
        clock.advance(Duration.ofSeconds(11));
        JsonNode build = mode().get("build");
        assertThat(build.get("changedOnDisk").booleanValue()).isTrue();
        assertThat(build.get("time").stringValue()).isEqualTo("2026-11-22T05:00:00Z");
    }

    /** A clock the test moves, so the ten seconds between two looks at the folder pass without waiting. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
