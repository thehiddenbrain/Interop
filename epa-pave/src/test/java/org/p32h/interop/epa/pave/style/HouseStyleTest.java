package org.p32h.interop.epa.pave.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.style.HouseStyle.Rule;
import org.p32h.interop.epa.pave.style.HouseStyle.Scan;
import org.p32h.interop.epa.pave.style.HouseStyle.Violation;

/**
 * The house style gate of the build, held to what audit V5 found it let through.
 *
 * <p>Every planted line below is put together from pieces or escapes, for the reason the rules
 * are: this file is checked by the gate it tests, and must pass it.
 */
class HouseStyleTest {

    private static final String DASH = "dash other than a plain hyphen";
    private static final String QUOTE = "curly or typographic quote";
    private static final String ANGLE = "angle quotation mark";
    private static final String ELLIPSIS = "ellipsis character, write three full stops";
    private static final String INVISIBLE = "invisible or non-standard space character, write its escape";
    private static final String WORD = "banned filler word";
    private static final String TRACKING = "CSS tracking property";
    private static final String SHORT_NAME = "programme abbreviation without its trailing H";
    private static final String PRODUCT = "banned product name, say EDI";
    private static final String FORMAL_NAME = "formal name spelt otherwise than Point" + "32Health";

    /** Lines each rule must refuse. The audit's planted file is all here, and more. */
    private static final Map<String, List<String>> PLANTED = planted();

    private static Map<String, List<String>> planted() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(DASH, characters(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2043, 0x2053, 0x2212, 0x2E3A, 0x2E3B,
                0xFE31, 0xFE32, 0xFE58, 0xFE63, 0xFF0D));
        out.put(QUOTE, characters(0x00B4, 0x02B9, 0x02BA, 0x02BB, 0x02BC, 0x02BD, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C,
                0x201D, 0x201E, 0x201F, 0x2032, 0x2033, 0x2034, 0x2035, 0x2036, 0x2037, 0x275B, 0x275C, 0x275D, 0x275E,
                0x2E42, 0x301D, 0x301E, 0x301F, 0xFF02, 0xFF07));
        out.put(ANGLE, characters(0x00AB, 0x00BB, 0x2039, 0x203A));
        out.put(ELLIPSIS, characters(0x2026, 0x22EF, 0xFE19));
        out.put(INVISIBLE, characters(0x00A0, 0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x1680, 0x180E, 0x2000, 0x2003,
                0x200A, 0x200B, 0x200D, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202E, 0x202F, 0x205F, 0x2060, 0x2066,
                0x3000, 0x3164, 0xFEFF, 0xFFA0, 0xFFF9, 0xFFFB));
        out.put(WORD, List.of(
                "// control: " + "seam" + "less",
                "const is" + "Ro" + "bust = 1;",
                "const cutting" + "Edge = 1;",
                "best_in_" + "class",
                "cutting_" + "edge",
                "Cutting - " + "Edge",
                "Best In " + "Class",
                "best-in-" + "class",
                "a ro" + "bustness control",
                "seam-" + "less",
                "Seam" + "Less" + "Join",
                "lever" + "aged",
                "use" + "Lever" + "age()",
                "de" + "lves",
                "del" + "ving into",
                "my" + "Del" + "ve",
                "Sle" + "ek"));
        out.put(TRACKING, List.of(
                "  letter-" + "spacing: 0.1em;",
                "el.style.letter" + "Spacing = \"1px\";",
                "el.style.setProperty('letter-" + "spacing', '1px');",
                "letter_" + "spacing",
                "LETTER " + "SPACING"));
        out.put(SHORT_NAME, List.of(
                "map" + "P" + "32" + "Values",
                "the " + "P" + "32 programme",
                "p" + "32_member",
                "P" + "32" + "h",
                "p" + "32" + "H",
                "x" + "P" + "32"));
        out.put(PRODUCT, List.of(
                "X" + "Engine",
                "X-" + "Engine",
                "X " + "Engine",
                "x_" + "engine",
                "new " + "X" + "EngineClient()",
                "the X - " + "Engine"));
        out.put(FORMAL_NAME, List.of(
                "Point 32" + " Health",
                "Point32" + "health",
                "point32" + "Health",
                "Point-32-" + "Health",
                "POINT32" + "HEALTH"));
        return out;
    }

    /** Lines no rule may refuse: the plain forms, and words close to a banned one. */
    private static final List<String> CLEAN = List.of(
            "a plain - hyphen, \"straight\" quotes, 'single' quotes and three full stops...",
            "P32H, p32h in a package name, and Point" + "32Health",
            "a Seam test, the seam between two tasks, the best match, cutting off the end",
            "a lever arm, deliver and delivery, a sleeve",
            "letters spaced apart, word-spacing is not a rule here",
            "a regular expression in an index and an engine",
            "tab\tand carriage return\r");

    private static List<String> characters(int... codePoints) {
        return java.util.Arrays.stream(codePoints).mapToObj(code -> "before " + Character.toString(code) + " after").toList();
    }

    private static Set<String> labels(List<Violation> violations) {
        return violations.stream().map(Violation::label).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyRuleHasPlantedViolationsAndNoRuleIsUntested() {
        Set<String> ruleLabels = HouseStyle.rules().stream().map(Rule::label).collect(Collectors.toCollection(TreeSet::new));
        assertThat(ruleLabels).isEqualTo(new TreeSet<>(PLANTED.keySet()));
    }

    @Test
    void eachRuleRefusesEachPlantedLineWithItsOwnLabel() {
        PLANTED.forEach((label, lines) -> {
            for (String line : lines) {
                assertThat(labels(HouseStyle.checkText("src/main/resources/static/js/plant.js", line)))
                        .as("planted line %s", codePoints(line))
                        .contains(label);
            }
        });
    }

    @Test
    void theCleanLinesPassEveryRule() {
        for (String line : CLEAN) {
            assertThat(HouseStyle.checkText("src/main/resources/static/js/plain.js", line)).as(line).isEmpty();
        }
    }

    @Test
    void aPlantedFileFailsTheScanAtTheRightLineAndPassesOnceTheLineIsGone(@TempDir Path root) throws IOException {
        Path file = root.resolve("src/main/resources/static/js/plant.js");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "const fine = 1;\nconst is" + "Ro" + "bust = 1;\nconst alsoFine = 2;\n", StandardCharsets.UTF_8);

        Scan planted = HouseStyle.scan(root, List.of("src/main/resources/static/js/plant.js"), "test");
        assertThat(planted.violations()).containsExactly(new Violation("src/main/resources/static/js/plant.js", 2, WORD));
        assertThat(HouseStyle.report(planted)).contains("House style violations (1):", "plant.js:2: " + WORD);

        Files.writeString(file, "const fine = 1;\nconst alsoFine = 2;\n", StandardCharsets.UTF_8);
        Scan clean = HouseStyle.scan(root, List.of("src/main/resources/static/js/plant.js"), "test");
        assertThat(clean.violations()).isEmpty();
        assertThat(clean.filesRead()).isEqualTo(1);
        assertThat(HouseStyle.report(clean)).startsWith("No house style violations.");
    }

    @Test
    void theNameOfAFileIsCheckedAsWellAsItsLines(@TempDir Path root) throws IOException {
        String name = "src/main/resources/static/" + "P" + "32-plant-name.txt";
        Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name), "nothing wrong in here\n", StandardCharsets.UTF_8);

        Scan scan = HouseStyle.scan(root, List.of(name), "test");

        assertThat(scan.violations()).containsExactly(new Violation(name, 0, SHORT_NAME));
        assertThat(scan.violations().get(0).toString()).isEqualTo(name + ": file name: " + SHORT_NAME);
    }

    @Test
    void dotFilesAreReadWhenTheTreeIsWalked(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".gitignore"), "# planted " + Character.toString(0x2014) + " here\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".gitattributes"), "* text=auto " + "X" + "Engine\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("build/reports"));
        Files.writeString(root.resolve("build/reports/out.txt"), "X" + "Engine\n", StandardCharsets.UTF_8);

        List<String> walked = HouseStyle.walk(root);
        Scan scan = HouseStyle.scan(root, walked, "test");

        assertThat(walked).containsExactlyInAnyOrder(".gitignore", ".gitattributes");
        assertThat(scan.violations()).containsExactly(
                new Violation(".gitattributes", 1, PRODUCT),
                new Violation(".gitignore", 1, DASH));
    }

    @Test
    void theRealProjectListsItsDotFilesAndBreaksNoRule() {
        Path projectDir = Path.of(requiredProperty("pave.houseStyle.projectDir"));

        HouseStyle.Listing listing = HouseStyle.projectFiles(projectDir);
        Scan scan = HouseStyle.scanProject(projectDir);

        assertThat(listing.files()).contains(".gitignore", ".gitattributes", "build.gradle",
                "src/houseStyle/java/org/p32h/interop/epa/pave/style/HouseStyle.java");
        assertThat(listing.files()).noneMatch(path -> path.startsWith("build/") || path.startsWith(".gradle/"));
        assertThat(scan.violations()).as(HouseStyle.report(scan)).isEmpty();
        assertThat(scan.filesRead()).isGreaterThan(500);
    }

    @Test
    void binaryFilesAreSkippedAndBytesThatAreNotUtf8AreRefused(@TempDir Path root) throws IOException {
        Files.write(root.resolve("data.bin"), new byte[] {'X', 0, (byte) 0x97, 'x'});
        Files.write(root.resolve("notes.txt"), new byte[] {'a', ' ', (byte) 0x97, ' ', 'b', '\n'});

        Scan scan = HouseStyle.scan(root, List.of("data.bin", "notes.txt"), "test");

        assertThat(scan.binarySkipped()).isEqualTo(1);
        assertThat(scan.filesRead()).isEqualTo(1);
        assertThat(scan.violations()).containsExactly(new Violation("notes.txt", 0, "not readable as UTF-8 text"));
    }

    @Test
    void onlyTheGeneratedWrapperScriptMayHoldGuillemets() {
        String line = "# " + Character.toString(0x00AB) + "APP_NAME" + Character.toString(0x00BB);

        assertThat(HouseStyle.checkText("gradlew", line)).isEmpty();
        assertThat(labels(HouseStyle.checkText("scripts/run.sh", line))).containsExactly(ANGLE);
        // The exemption is for one rule only: a curly quote in the wrapper script is still refused.
        assertThat(labels(HouseStyle.checkText("gradlew", "# " + Character.toString(0x201C)))).containsExactly(QUOTE);
    }

    @Test
    void theBuildRunsThisGateAsPartOfCheck() {
        // build.gradle hands these over when the tests start. If the task is deleted, taken out of
        // check, or pointed at another class, this fails.
        assertThat(requiredProperty("pave.houseStyle.checkDependsOn").split(",")).contains("houseStyleCheck");
        assertThat(requiredProperty("pave.houseStyle.mainClass")).isEqualTo(HouseStyle.class.getName());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("system property %s, set by the test task in build.gradle", name).isNotBlank();
        return value;
    }

    private static String codePoints(String line) {
        return line.codePoints().mapToObj(code -> code < 127 ? Character.toString(code) : String.format("U+%04X", code))
                .collect(Collectors.joining());
    }
}
