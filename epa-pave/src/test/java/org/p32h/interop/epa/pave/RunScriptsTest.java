package org.p32h.interop.epa.pave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run scripts build the jar before every start, and say which jar they start and when it was
 * built (R-31). They used to start whatever jar build/libs held, and every build calls itself
 * 1.0.0, so after a pull the old jar kept running and answered an upload with the internal error
 * that pull had fixed (files hunt, failure 3).
 *
 * <p>They also find a Java 21 or newer by themselves: PAVE_JAVA, JAVA_HOME, the PATH and, for run.cmd,
 * the Java STS brings, passing over an older Java instead of stopping at it, because the operator's
 * workstation has only Java 8 on the PATH and STS's own Java beside it (I5, R-31).
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
     * A stand-in java at folder/{@code home}/bin/java that answers -version as the Java {@code version}
     * and otherwise writes down that it ran, under the name {@code name}.
     */
    private Path java(String home, String version, String name) throws IOException {
        Path java = folder.resolve(home).resolve("bin/java");
        String line = version.startsWith("1.") ? "java version \\\"" + version + "\\\""
                : "openjdk version \\\"" + version + "\\\" 2024-10-15";
        script(java, "#!/usr/bin/env bash\n"
                + "if [ \"$1\" = \"-version\" ]; then echo \"" + line + "\" >&2; exit 0; fi\n"
                + "echo \"" + name + " $*\" >> '" + folder.resolve("calls.log") + "'\n");
        return java;
    }

    private Ran run(boolean buildFails, String... arguments) throws IOException, InterruptedException {
        java("jdk", "21.0.5", "java");
        return run(buildFails, environment -> environment.put("JAVA_HOME", folder.resolve("jdk").toString()), arguments);
    }

    /**
     * Copies run.sh into the folder beside a stand-in wrapper, which writes a jar stamped
     * 2026-01-05 15:04:05 unless it is told to fail, and writes down the JAVA_HOME it was given.
     * Then runs it with no PAVE_JAVA and no JAVA_HOME unless {@code environment} sets them.
     */
    private Ran run(boolean buildFails, Consumer<Map<String, String>> environment,
            String... arguments) throws IOException, InterruptedException {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "run.sh is a script for Linux and macOS");
        Files.copy(Path.of("run.sh"), folder.resolve("run.sh"));
        Path calls = folder.resolve("calls.log");
        script(folder.resolve("gradlew"), "#!/usr/bin/env bash\n"
                + "echo \"gradlew $*\" >> '" + calls + "'\n"
                + "echo \"JAVA_HOME=${JAVA_HOME:-}\" > '" + folder.resolve("gradle-java.log") + "'\n"
                + (buildFails ? "echo 'BUILD FAILED' >&2\nexit 1\n" : "")
                + "mkdir -p build/libs\n"
                + "printf 'new jar' > " + JAR + "\n"
                + "touch -t 202601051504.05 " + JAR + "\n");

        List<String> command = new ArrayList<>(List.of("bash", "run.sh"));
        command.addAll(List.of(arguments));
        Path output = folder.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().remove("PAVE_JAVA");
        builder.environment().remove("JAVA_HOME");
        environment.accept(builder.environment());
        // The stamp is printed in the zone of the machine; a fixed zone makes it the same everywhere.
        builder.environment().put("TZ", "UTC");
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("run.sh did not end within 60 seconds");
        }
        return new Ran(process.exitValue(), Files.readString(output), Files.exists(calls) ? Files.readAllLines(calls) : List.of());
    }

    /** What the stand-in wrapper was given as JAVA_HOME, empty when it had none. */
    private String gradleJavaHome() throws IOException {
        return Files.readString(folder.resolve("gradle-java.log")).strip().substring("JAVA_HOME=".length());
    }

    /** The PATH of this machine with the folder of a stand-in java in front, so that it is the java found there. */
    private static String pathWith(Path java) {
        return java.getParent() + File.pathSeparator + System.getenv("PATH");
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
        assertThat(lines).noneMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("if exist \"%jar%\" goto"));
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
            int at = line.toLowerCase(Locale.ROOT).indexOf("goto ");
            if (at >= 0 && !line.startsWith("rem")) {
                String label = line.substring(at + 5).strip().split(" ")[0];
                assertThat(lines).as(line).contains(":" + label);
            }
        }
    }

    @Test
    void runShTakesPaveJavaBeforeJavaHomeAndGivesGradleTheSameJava() throws Exception {
        java("jdk", "21.0.5", "java");
        Path paveJava = java("sts/jre", "25.0.1", "pave-java");

        Ran ran = run(false, environment -> {
            environment.put("JAVA_HOME", folder.resolve("jdk").toString());
            environment.put("PAVE_JAVA", paveJava.toString());
        }, "mock");

        assertThat(ran.exit()).isZero();
        assertThat(ran.calls()).containsExactly("gradlew --console=plain bootJar",
                "pave-java -jar " + JAR + " --spring.profiles.active=mock");
        assertThat(ran.output()).contains("Using Java 25.0.1 at " + paveJava + ", named by PAVE_JAVA.");
        assertThat(gradleJavaHome()).isEqualTo(folder.resolve("sts/jre").toString());
    }

    @Test
    void runShPassesOverAJavaHomeAtJava8AndUsesTheJava21OnThePath() throws Exception {
        Path old = java("java8", "1.8.0_402", "java8");
        Path onPath = java("path-jdk", "21.0.5", "path-java");

        Ran ran = run(false, environment -> {
            environment.put("JAVA_HOME", folder.resolve("java8").toString());
            environment.put("PATH", pathWith(onPath));
        }, "live");

        assertThat(ran.exit()).isZero();
        assertThat(ran.output()).contains("Not using " + old + ", named by JAVA_HOME: it is Java 1.8.0_402, and PAVE needs 21 or newer.")
                .contains("Using Java 21.0.5 at " + onPath + ", the java on the PATH.");
        assertThat(ran.calls()).containsExactly("gradlew --console=plain bootJar",
                "path-java -jar " + JAR + " --spring.profiles.active=live");
        // A JAVA_HOME left at Java 8 would make the wrapper build on Java 8.
        assertThat(gradleJavaHome()).isEmpty();
    }

    @Test
    void runShWithNoJava21AnywhereNamesPaveJavaAndRunsNothing() throws Exception {
        Path onPath = java("java8", "1.8.0_402", "java8");

        Ran ran = run(false, environment -> {
            environment.put("PAVE_JAVA", folder.resolve("no-such/bin/java").toString());
            environment.put("PATH", pathWith(onPath));
        });

        assertThat(ran.exit()).isEqualTo(1);
        assertThat(ran.calls()).isEmpty();
        assertThat(ran.output()).contains("Not using " + folder.resolve("no-such/bin/java") + ", named by PAVE_JAVA: there is no such program.")
                .contains("Not using " + onPath + ", the java on the PATH: it is Java 1.8.0_402, and PAVE needs 21 or newer.")
                .contains("run.sh: no Java 21 or newer was found, and PAVE needs one to build and to run.")
                .contains("PAVE_JAVA=/path/to/jdk-21/bin/java ./run.sh")
                .doesNotContain("Starting PAVE");
    }

    @Test
    void runCmdLooksForAJava21InThePlannedOrderSaysWhichItPickedAndBuildsWithIt() throws IOException {
        String text = new String(Files.readAllBytes(Path.of("run.cmd")), StandardCharsets.US_ASCII);
        assertThat(text).endsWith("\r\n");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
        List<String> lines = List.of(text.split("\r\n"));

        // The operator's workstation has Java 8 on the PATH and STS's own Java: PAVE_JAVA, then
        // JAVA_HOME, then the PATH, then STS's install folders, each tried for 21 or newer.
        int paveJava = lines.indexOf("call :trycandidate \"%PAVE_JAVA%\" \"named by PAVE_JAVA\"");
        int javaHome = lines.indexOf("call :trycandidate \"%JAVA_HOME%\\bin\\java.exe\" \"named by JAVA_HOME\"");
        int path = lines.indexOf("for %%P in (java.exe) do set \"PATHJAVA=%%~$PATH:P\"");
        int sts = lines.indexOf("for /d %%S in (\"%USERPROFILE%\\sts*\" \"%SystemDrive%\\sts*\" \"%ProgramFiles%\\sts*\" "
                + "\"%LOCALAPPDATA%\\Programs\\sts*\") do if not defined JAVA call :stsfolder \"%%~fS\"");
        int picked = lines.indexOf("echo Using Java %JVERSION% at %JAVA%, %SOURCE%.");
        int build = lines.indexOf("call gradlew.bat --console=plain bootJar");
        assertThat(paveJava).isPositive();
        assertThat(javaHome).isGreaterThan(paveJava);
        assertThat(lines.indexOf("call :trycandidate \"%PATHJAVA%\" \"the java on the PATH\"")).isGreaterThan(path);
        assertThat(path).isGreaterThan(javaHome);
        assertThat(sts).isGreaterThan(path);
        assertThat(picked).isGreaterThan(sts);
        assertThat(build).isGreaterThan(picked);
        assertThat(lines).contains("for /d %%J in (\"%~1\\plugins\\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*\") "
                + "do if not defined JAVA call :trycandidate \"%%~fJ\\jre\\bin\\java.exe\" \"the Java STS brings\"");
        // A candidate older than 21 is passed over with a line, never taken.
        assertThat(lines).contains("if %MAJORNUM% GEQ 21 goto candidateok",
                "if defined JVERSION echo Not using %CANDIDATE%, %CANDIDATESOURCE%: it is Java %JVERSION%, and PAVE needs 21 or newer.");
        // The old search took JAVA_HOME first, whatever its version.
        assertThat(lines).doesNotContain("if not defined JAVA_HOME goto javafrompath");

        // Gradle is given the same Java, inside the script's own setlocal so nothing leaks into the console.
        int setlocal = lines.indexOf("setlocal EnableExtensions DisableDelayedExpansion");
        int gradleJava = lines.indexOf("for %%D in (\"%JAVA%\\..\\..\") do set \"JAVA_HOME=%%~fD\"");
        assertThat(setlocal).isPositive().isLessThan(paveJava);
        assertThat(gradleJava).isGreaterThan(picked).isLessThan(build);
        assertThat(lines).noneMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("endlocal"));

        // When none is found, it names PAVE_JAVA and the one line that sets it, and stops before any build.
        int none = lines.indexOf("echo run.cmd: no Java 21 or newer was found, and PAVE needs one to build and to run. 1>&2");
        int setLine = lines.indexOf("echo set PAVE_JAVA=^<the folder STS is in^>\\plugins\\^<the justj folder^>\\jre\\bin\\java.exe 1>&2");
        assertThat(none).isGreaterThan(sts).isLessThan(picked);
        assertThat(setLine).isGreaterThan(none).isLessThan(picked);
        assertThat(lines.subList(setLine, picked)).contains("exit /b 1");

        // Every subroutine called is a label the file has, and the subroutines sit after the last
        // exit of the main path, where the main path cannot run into them.
        for (String line : lines) {
            int at = line.indexOf("call :");
            if (at >= 0 && !line.startsWith("rem")) {
                String label = line.substring(at + 6).strip().split(" ")[0];
                assertThat(lines).as(line).contains(":" + label);
                assertThat(lines.indexOf(":" + label)).as(line).isGreaterThan(lines.indexOf(":usage"));
            }
        }
    }
}
