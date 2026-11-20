package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The total time of a chain never reads as covering a run nobody timed. The server adds up the
 * runs that carry a time; the chain view says how many of the runs that is. The wording is run by
 * Node where Node is installed, as the code box refusal is.
 */
class ChainTotalFrontendTest {

    private static final Path CHAIN = Path.of("src/main/resources/static/js/chain.js");

    @Test
    void theTotalSaysHowManyRunsItCovers() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the wording cannot be run.");
        String source = Files.readString(CHAIN, StandardCharsets.UTF_8);
        String total = CaseCodesFrontendTest.body(source, "export function chainTotal(runCount, measured, took) {")
                .replaceFirst("^export ", "") + "\n}";
        String script = total + "\nconsole.log(JSON.stringify([chainTotal(4, 4, '500 ms'), chainTotal(4, 3, '500 ms'), "
                + "chainTotal(1, 1, '150 ms'), chainTotal(1, 0, '0 ms'), chainTotal(2, 0, '0 ms')]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[\"4 runs taking 500 ms in all\",\"4 runs, 3 of them measured, taking 500 ms in all\","
                + "\"1 run taking 150 ms in all\",\"1 run, not measured\",\"2 runs, none of them measured\"]");
    }

    @Test
    void theRunsCountedAsMeasuredAreThoseThatCarryATimeInEveryStageAndOutsideThem() throws IOException {
        String source = Files.readString(CHAIN, StandardCharsets.UTF_8);
        String counted = CaseCodesFrontendTest.body(source, "function measuredRuns(chain) {");
        assertThat(counted).contains("stage.runs").contains("chain.otherRuns").contains("typeof run.elapsedMillis === 'number'");
        assertThat(source).contains("chainTotal(chain.runCount, measuredRuns(chain), millis(chain.elapsedMillis))");
    }
}
