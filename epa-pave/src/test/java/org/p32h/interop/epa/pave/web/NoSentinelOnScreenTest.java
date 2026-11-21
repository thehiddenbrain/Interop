package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.ScenarioSummary;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;

/**
 * "sentinel" is the code's word for a test member ID or a test NPI. A tester reads "test member
 * ID" and "test NPI", so no sentence that can reach a screen says "sentinel": not the scenario
 * files' own texts, not the server's notes and refusals, not the static files. The keys of a
 * scenario file (sentinelMemberId, crdSentinelMemberIds, answersDtrSentinels) are names in the
 * record, not words, and stay; so do class names, comments and the code's identifiers.
 */
class NoSentinelOnScreenTest {

    /** The word on its own, not inside a camelCase key such as sentinelMemberId or answersDtrSentinels. */
    private static final Pattern WORD = Pattern.compile("(?i)(?<![A-Za-z])sentinels?(?![A-Za-z])");
    private static final Pattern JAVA_TEXT = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern SCRIPT_TEXT = Pattern.compile("'(?:[^'\\\\\\n]|\\\\.)*'|\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    @Test
    void noBundledScenarioSaysSentinelInAnythingTheScreenShows() {
        ScenarioRepository bundled = new ScenarioRepository(AppProperties.defaults());
        int read = 0;
        for (String protocol : List.of("crd", "dtr", "pas")) {
            ScenarioRepository.Loaded loaded = bundled.load(protocol);
            for (Scenario scenario : loaded.scenarios()) {
                ScenarioSummary summary = scenario.summary();
                List<String> texts = new ArrayList<>();
                texts.add(summary.label());
                texts.add(summary.description());
                texts.add(summary.defaultNote());
                texts.add(scenario.basis());
                texts.add(scenario.assumption());
                texts.addAll(summary.notes());
                for (String text : texts) {
                    if (text != null) {
                        read++;
                        assertThat(WORD.matcher(text).find()).as(scenario.source() + ": " + text).isFalse();
                    }
                }
            }
            for (String note : Stream.concat(loaded.notes().stream(), loaded.problems().stream()).toList()) {
                assertThat(WORD.matcher(note).find()).as(protocol + ": " + note).isFalse();
            }
        }
        assertThat(read).as("texts read").isGreaterThan(50);
    }

    @Test
    void theDefaultNoteSpeaksOfATestMemberIdOrNpi() {
        Scenario first = new ScenarioRepository(AppProperties.defaults()).load("pas").scenarios().get(0);
        assertThat(first.summary().defaultNote()).endsWith("A run that neither a chosen scenario nor a test member ID or NPI selects is answered from it.");
    }

    @Test
    void noServerSentenceSaysSentinel() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String code = line.strip();
                    // Comments are not screen text, and a pattern that still reads a file written in the old words is not either.
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.contains("Pattern.compile(")) {
                        continue;
                    }
                    Matcher text = JAVA_TEXT.matcher(line);
                    while (text.find()) {
                        if (WORD.matcher(text.group()).find()) {
                            found.add(file.getFileName() + ": " + code);
                        }
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void noScriptOrPageSaysSentinel() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/static"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js") || path.toString().endsWith(".html")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String code = line.strip();
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        continue;
                    }
                    Matcher text = SCRIPT_TEXT.matcher(line);
                    while (text.find()) {
                        // A class name is one token and never read; a sentence has a space in it.
                        if (text.group().contains(" ") && WORD.matcher(text.group()).find()) {
                            found.add(file.getFileName() + ": " + code);
                        }
                    }
                    if (file.toString().endsWith(".html") && WORD.matcher(line.replaceAll("<[^>]*>", "")).find()) {
                        found.add(file.getFileName() + ": " + code);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void theWordIsFoundWhereItIsWrittenAndNotInsideAKey() {
        assertThat(WORD.matcher("nor a sentinel selects").find()).isTrue();
        assertThat(WORD.matcher("Sentinels for DTR").find()).isTrue();
        assertThat(WORD.matcher("its crdSentinelMemberIds is").find()).isFalse();
        assertThat(WORD.matcher("\"answersDtrSentinels\": true").find()).isFalse();
    }
}
