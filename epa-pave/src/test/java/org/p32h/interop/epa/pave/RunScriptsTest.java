package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run scripts build the jar before every start, and say which jar they start and when it was
 * built (R-31). They used to start whatever jar build/libs held, and every build calls itself
 * 1.0.0, so after a pull the old jar kept running and answered an upload with the internal error
 * that pull had fixed (files hunt, failure 3).
 *
 * <p>run.sh runs here for real, in a folder of its own, against stand-ins for the Gradle wrapper and
 * for Java that write down what they were asked, in order. run.cmd cannot run on this machine, so
 * it is read as text for the same steps.
 */
class RunScriptsTest {

    private static final String JAR = "build/libs/epa-pave-1.0.0.jar";

    @TempDir
    Path folder;

    private record Ran(int exit, String output, List<String> calls) {
    }

    private static void script(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertThat(file.toFile().setExecutable(true)).isTrue();
    }

    /**
     * Copies run.sh into the folder beside a stand-in wrapper, which writes a jar stamped
     * 2026-01-05 15:04:05 unless it is told to fail, and a stand-in Java 21. Then runs it.
     */
    private Ran run(boolean buildFails, String... arguments) throws IOException, InterruptedException {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "run.sh is a script for Linux and macOS");
        Files.copy(Path.of("run.sh"), folder.resolve("run.sh"));
        Path calls = folder.resolve("calls.log");
        script(folder.resolve("gradlew"), "#!/usr/bin/env bash\n"
                + "echo \"gradlew $*\" >> '" + calls + "'\n"
                + (buildFails ? "echo 'BUILD FAILED' >&2\nexit 1\n" : "")
                + "mkdir -p build/libs\n"
                + "printf 'new jar' > " + JAR + "\n"
                + "touch -t 202601051504.05 " + JAR + "\n");
        script(folder.resolve("jdk/bin/java"), "#!/usr/bin/env bash\n"
                + "if [ \"$1\" = \"-version\" ]; then echo 'openjdk version \"21.0.5\" 2024-10-15' >&2; exit 0; fi\n"
                + "echo \"java $*\" >> '" + calls + "'\n");

        List<String> command = new ArrayList<>(List.of("bash", "run.sh"));
        command.addAll(List.of(arguments));
        Path output = folder.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("JAVA_HOME", folder.resolve("jdk").toString());
        // The stamp is printed in the zone of the machine; a fixed zone makes it the same everywhere.
        builder.environment().put("TZ", "UTC");
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("run.sh did not end within 60 seconds");
        }
        return new Ran(process.exitValue(), Files.readString(output), Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    /** An older jar in build/libs, as a pull leaves it. */
    private void oldJar() throws IOException {
        Path jar = folder.resolve(JAR);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "old jar");
        assertThat(jar.toFile().setLastModified(1_000_000_000_000L)).isTrue();
    }

    @Test
    void runShBuildsBeforeEveryStartEvenWhenAJarIsThereAndSaysWhichJarItStarts() throws Exception {
        oldJar();

        Ran ran = run(false, "mock", "--server.port=8099");

        assertThat(ran.exit()).isZero();
        assertThat(ran.calls()).containsExactly("gradlew --console=plain bootJar",
                "java -jar " + JAR + " --spring.profiles.active=mock --server.port=8099");
        assertThat(Files.readString(folder.resolve(JAR))).isEqualTo("new jar");
        assertThat(ran.output()).contains("Building " + JAR + " with the Gradle wrapper.")
                .contains("Starting PAVE 1.0.0 from " + JAR + ", built 2026-01-05 15:04:05 UTC.")
                .contains("It answers on http://localhost:8099/ (mock profile). Stop it with Ctrl+C.");
    }

    @Test
    void runShBuildsTheFirstJarTooAndStartsTheLiveProfileWhenAsked() throws Exception {
        Ran ran = run(false, "live");

        assertThat(ran.exit()).isZero();
        assertThat(ran.calls()).containsExactly("gradlew --console=plain bootJar", "java -jar " + JAR + " --spring.profiles.active=live");
        assertThat(ran.output()).contains("It answers on http://localhost:8057/ (live profile).");
    }

    @Test
    void whenTheBuildFailsRunShStartsNothingRatherThanTheJarThatIsThere() throws Exception {
        oldJar();

        Ran ran = run(true);

        assertThat(ran.exit()).isEqualTo(1);
        assertThat(ran.calls()).containsExactly("gradlew --console=plain bootJar");
        assertThat(Files.readString(folder.resolve(JAR))).isEqualTo("old jar");
        assertThat(ran.output()).contains("run.sh: the build failed, so PAVE was not started: the jar in build/libs could be older "
                + "than the source.").doesNotContain("Starting PAVE");
    }

    @Test
    void runCmdBuildsBeforeEveryStartAndSaysWhichJarItStarts() throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of("run.cmd"));
        String text = new String(bytes, StandardCharsets.US_ASCII);
        // The Windows command interpreter can lose its place at a label in a file with bare line feeds.
        assertThat(text.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
        List<String> lines = List.of(text.split("\r\n"));

        int build = lines.indexOf("call gradlew.bat --console=plain bootJar");
        int start = lines.indexOf("\"%JAVA%\" -jar \"%JAR%\" \"--spring.profiles.active=%PROFILE%\" %*");
        assertThat(build).isPositive();
        assertThat(start).isGreaterThan(build);
        // The jar in build\libs no longer stands in for a build.
        assertThat(lines).noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("if exist \"%jar%\" goto"));
        // Nothing can jump in between the build and the start: the start is reached only by going through the build.
        assertThat(lines.subList(build, start)).noneMatch(line -> line.startsWith(":"));
        assertThat(lines.get(build - 1)).isEqualTo(":build");
        assertThat(lines.get(build + 1)).isEqualTo("if errorlevel 1 goto buildfailed");
        int failed = lines.indexOf(":buildfailed");
        assertThat(failed).isGreaterThan(start);
        assertThat(lines.subList(failed, lines.size())).contains("exit /b 1")
                .anyMatch(line -> line.startsWith("echo run.cmd: the build failed, so %NAME% was not started"));

        // It says which jar it starts and when that jar was written.
        assertThat(lines.subList(build, start)).contains("if exist \"%JAR%\" for %%F in (\"%JAR%\") do set \"BUILT=%%~tF\"",
                "echo Starting %NAME% %VERSION% from %JAR%, built %BUILT%.");

        // Every jump lands on a label the file has.
        for (String line : lines) {
            int at = line.toLowerCase(java.util.Locale.ROOT).indexOf("goto ");
            if (at >= 0 && !line.startsWith("rem")) {
                String label = line.substring(at + 5).strip().split(" ")[0];
                assertThat(lines).as(line).contains(":" + label);
            }
        }
    }
}
