package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.PaveApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * The application as a tester starts it, pointed at a damaged scenario directory. Calling the
 * check by hand cannot show that Spring runs it, so these start the real application and nothing
 * else.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScenarioCheckAtStartTest {

    /**
     * The test classes sit under the package the application scans, and some of them are
     * controllers or configurations of their own. A start that picked them up would not be the
     * application a tester runs, so only the classes of the application itself are scanned.
     */
    static final class OnlyTheApplication extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURI().toString().contains("/classes/java/test/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OnlyTheApplication;
        }

        @Override
        public int hashCode() {
            return OnlyTheApplication.class.hashCode();
        }
    }

    private static ConfigurableApplicationContext start(Path home, Path scenarios, String profile) {
        return new SpringApplicationBuilder(PaveApplication.class)
                .initializers(context -> context.getBeanFactory().registerSingleton("onlyTheApplication", new OnlyTheApplication()))
                .profiles(profile)
                .run("--server.port=0", "--app.scenario-dir=" + scenarios, "--tracking.directory=" + home.resolve("run-history"),
                        "--config-store.directory=" + home.resolve("config-store"));
    }

    /** A copy of the bundled PAS directory with one file cut short, as the verification damaged it, and the other protocols intact. */
    private static Path damagedSet(Path dir) throws IOException {
        Path scenarios = dir.resolve("scenarios");
        for (String protocol : ScenarioRepository.PROTOCOLS) {
            Files.createDirectories(scenarios.resolve(protocol));
            Files.writeString(scenarios.resolve(protocol + "/" + protocol + "-01.json"), "{\"id\":\"" + protocol + "-01\",\"number\":1,"
                    + "\"label\":\"One\",\"sentinelMemberId\":\"P32H-" + protocol.toUpperCase(java.util.Locale.ROOT) + "\",\"httpStatus\":200,"
                    + "\"response\":{}}");
        }
        Files.writeString(scenarios.resolve("pas/pas-02-pended.json"), "{\"id\": \"pas-02-pended\", \"number\": 2, \"label\"");
        return scenarios;
    }

    @Test
    void onTheMockProfileADamagedScenarioDirectoryStopsTheStartNamingTheSettingTheFileAndTheReason(@TempDir Path dir) throws IOException {
        Path scenarios = damagedSet(dir);
        assertThatThrownBy(() -> start(dir, scenarios, "mock")).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessage("The mock scenarios in " + scenarios + " (app.scenario-dir) cannot be used, so the tool does not start."
                        + " Mock scenarios for pas: pas-02-pended.json: The file is not valid JSON (line 1, column 45).");
    }

    @Test
    void onTheMockProfileAGoodScenarioDirectoryStarts(@TempDir Path dir) throws IOException {
        Path scenarios = damagedSet(dir);
        Files.delete(scenarios.resolve("pas/pas-02-pended.json"));
        try (ConfigurableApplicationContext context = start(dir, scenarios, "mock")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getBean(ScenarioRepository.class).list("pas")).extracting(Scenario::id).containsExactly("pas-01");
        }
    }

    @Test
    void onTheLiveProfileADamagedScenarioDirectoryIsOneWarningAndTheToolStarts(@TempDir Path dir, CapturedOutput output) throws IOException {
        Path scenarios = damagedSet(dir);
        try (ConfigurableApplicationContext context = start(dir, scenarios, "live")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("live");
        }
        assertThat(output.getAll()).contains("WARN").contains("The mock scenarios in " + scenarios + " (app.scenario-dir) cannot be used. "
                + "The live profile does not answer from them, so the tool starts; on the mock profile it would refuse to start until they "
                + "are put right.");
        // The problems name files of a set this profile does not read, and nothing of a file is written to the log.
        assertThat(output.getAll()).doesNotContain("pas-02-pended.json");
    }
}
