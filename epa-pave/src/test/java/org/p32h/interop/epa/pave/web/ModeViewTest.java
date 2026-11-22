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
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
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

    @Test
    void onMockTheAnswerCarriesNoSignInSoTheBarShowsNoChip() throws Exception {
        assertThat(mode().has("signIn")).isFalse();
    }

    /** The answer on live, with a token service that has asked nobody yet. */
    private static JsonNode liveMode(OnyxTokenService tokens) throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("onyxTokenService", tokens);
        return JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.of(Map.of("mode", "live")), FakeGateway.unconfigured(),
                new MockEnvironment(), beans.getBeanProvider(OnyxTokenService.class))).perform(get("/api/mode")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).node();
    }

    private static OnyxTokenService tokens(Map<String, String> onyx) {
        // No request reaches this client: the state is read without asking a token endpoint.
        return OnyxAccessTestFactory.tokens(OnyxProperties.of(onyx), FakeGateway.unconfigured(), RestClient.create(),
                Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void onLiveTheSignInIsServedAsStateShortAndDetail() throws Exception {
        JsonNode off = liveMode(tokens(Map.of())).get("signIn");
        assertThat(off.get("state").stringValue()).isEqualTo("NONE");
        assertThat(off.get("short").stringValue()).isEqualTo("No sign-in needed");
        assertThat(off.get("detail").stringValue()).isEqualTo("This environment states no sign-in, and onyx.auth in application.yml is "
                + "switched off, so calls go without a token.");
        assertThat(off.has("code")).isFalse();
        assertThat(off.has("shortWords")).isFalse();

        JsonNode first = liveMode(tokens(Map.of("auth.enabled", "true", "auth.token-url", "https://auth.example.invalid/token",
                "auth.client-id", "placeholder-client", "auth.client-secret", "placeholder-client-secret"))).get("signIn");
        assertThat(first.get("state").stringValue()).isEqualTo("FIRST_CALL");
        assertThat(first.get("short").stringValue()).isEqualTo("Signs in on first call");
        assertThat(first.toString()).doesNotContain("placeholder-client-secret");
    }

    @Test
    void aPastedTokenIsSaidAsSuchAndNeverShown() throws Exception {
        OnyxTokenService tokens = tokens(Map.of());
        tokens.paste("placeholder-pasted-token", "Placeholder Sandbox");

        JsonNode signIn = liveMode(tokens).get("signIn");

        assertThat(signIn.get("state").stringValue()).isEqualTo("PASTED");
        assertThat(signIn.get("short").stringValue()).isEqualTo("Using your pasted token");
        assertThat(signIn.toString()).doesNotContain("placeholder-pasted-token");
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
