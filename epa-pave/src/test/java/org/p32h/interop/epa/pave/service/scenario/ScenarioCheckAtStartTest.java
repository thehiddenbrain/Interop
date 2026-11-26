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

    /**
     * The bundled scenarios copied into a directory of the tester's own, as a directory saved
     * before the sentinel mappings became data holds them: without crdSentinelMemberIds and
     * answersDtrSentinels. Plus a file that is not read.
     */
    private static Path directoryCopiedBeforeTheMappings(Path dir) throws IOException {
        Path scenarios = dir.resolve("scenarios");
        for (org.springframework.core.io.Resource resource : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:scenarios/*/*.json")) {
            String protocol = Path.of(resource.getURI().getPath()).getParent().getFileName().toString();
            Files.createDirectories(scenarios.resolve(protocol));
            tools.jackson.databind.node.ObjectNode file = (tools.jackson.databind.node.ObjectNode) org.p32h.interop.epa.pave.model.JsonSafe
                    .parse(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).node();
            file.remove("crdSentinelMemberIds");
            file.remove("answersDtrSentinels");
            Files.writeString(scenarios.resolve(protocol).resolve(resource.getFilename()), org.p32h.interop.epa.pave.model.JsonSafe.pretty(file));
        }
        Files.writeString(scenarios.resolve("crd/notes.txt"), "work in progress");
        return scenarios;
    }

    /** One GET over a socket to the running server, HTTP/1.0 so that the body is not cut into chunks. */
    private static tools.jackson.databind.JsonNode get(ConfigurableApplicationContext context, String path) throws IOException {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        try (java.net.Socket socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: localhost\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 200");
            return org.p32h.interop.epa.pave.model.JsonSafe.parse(response.substring(response.indexOf("\r\n\r\n") + 4)).node();
        }
    }

    private static java.util.List<String> notes(tools.jackson.databind.JsonNode list) {
        return org.p32h.interop.epa.pave.model.JsonSafe.elements(list, "notes").stream().map(tools.jackson.databind.JsonNode::asString).toList();
    }

    @Test
    void aDirectoryCopiedBeforeTheMappingsBecameDataStartsAndTheRunningToolSaysWhatItLostOnTheListsAndInTheLog(@TempDir Path dir,
            CapturedOutput output) throws IOException {
        // N-OLD-SCENARIO-DIR and N-LISTNOTES-UNTESTED, through the running application as a tester starts it.
        Path scenarios = directoryCopiedBeforeTheMappings(dir);
        String noAnswering = "No CRD scenario carries \"answersDtrSentinels\": true, so a DTR test member ID typed in CRD selects no CRD "
                + "scenario and is answered by the CRD default, crd-01-pa-required.json, and a journey with it does not reach DTR as the DTR "
                + "scenario's label says.";
        String lostCrdKey = ": its description speaks of a CRD test member ID that selects it, and the file lists none under "
                + "crdSentinelMemberIds, so no CRD test member ID selects it. A DTR or PAS scenario now names the CRD test member IDs it answers for "
                + "the same case under that key; a directory copied before the key existed needs it added.";
        try (ConfigurableApplicationContext context = start(dir, scenarios, "mock")) {
            assertThat(context.isRunning()).isTrue();
            java.util.List<String> crd = notes(get(context, "/api/crd/scenarios"));
            assertThat(crd).containsExactly(
                    "notes.txt: it is not read, because only files whose names end in .json are scenario files.",
                    "crd-01-pa-required.json: it is the default, because its number (1) is the lowest. A run that neither a chosen scenario "
                            + "nor a test member ID or NPI selects is answered from it.",
                    "crd-04-doc-dtr.json: its description speaks of DTR test member IDs, and the file does not carry "
                            + "\"answersDtrSentinels\": true, so it answers none of them. The CRD scenario that answers every DTR test member ID "
                            + "now says so with that key; a directory copied before the key existed needs it added.",
                    noAnswering);
            java.util.List<String> dtr = notes(get(context, "/api/dtr/scenarios"));
            assertThat(dtr).contains(noAnswering);
            for (String file : java.util.List.of("dtr-05-package-error-500.json", "dtr-09-member-not-found.json",
                    "dtr-10-provider-not-found.json", "dtr-11-bad-request-400.json", "dtr-12-timeout.json")) {
                assertThat(dtr).contains(file + lostCrdKey);
            }
            assertThat(dtr).hasSize(7);
            java.util.List<String> pas = notes(get(context, "/api/pas/scenarios"));
            for (String file : java.util.List.of("pas-09-invalid-bundle.json", "pas-15-member-not-found.json", "pas-16-timeout.json",
                    "pas-17-server-error-500.json", "pas-21-provider-not-found.json")) {
                assertThat(pas).contains(file + lostCrdKey);
            }
            assertThat(pas).hasSize(6);
            // The note is also on the line of the file it is about.
            assertThat(get(context, "/api/pas/scenarios").get("scenarios")).anySatisfy(line -> {
                assertThat(line.get("id").asString()).isEqualTo("pas-15-member-not-found");
                assertThat(notes(line)).containsExactly("pas-15-member-not-found.json" + lostCrdKey);
            });
        }
        // Said once at start, in the log, naming the files and quoting nothing of what they hold.
        assertThat(output.getAll()).contains("WARN").contains("Mock scenarios for crd: notes.txt: it is not read")
                .contains("pas-15-member-not-found.json" + lostCrdKey).contains(noAnswering).doesNotContain("work in progress");
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
