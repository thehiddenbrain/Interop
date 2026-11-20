package org.p32h.interop.epa.pave.style;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The house style of build prompt section 23, as the build enforces it.
 *
 * <p>This class is the one place the rules are written. The {@code houseStyleCheck} task of
 * {@code build.gradle} runs {@link #main(String[])}, and the tests call the same methods on planted
 * files, so the gate and its test cannot drift apart. It lives in a source set of its own so the
 * gate needs only this file compiled, and so nothing of it reaches the application jar.
 *
 * <p>Every pattern is assembled from fragments and escapes, so this file holds none of the text it
 * forbids and passes its own check.
 *
 * <p>What it cannot see: commit messages and the pull request description, which section 23 also
 * covers, the names of people, and comments that only restate the code. Those are left to review.
 */
public final class HouseStyle {

    /** One rule. A path in {@code exemptPaths} is not held to it; see the rule that has one. */
    public record Rule(String label, Pattern pattern, Set<String> exemptPaths) {

        boolean appliesTo(String path) {
            return !exemptPaths.contains(path);
        }
    }

    /** A line number of 0 means the rule matched the file's path and not a line of it. */
    public record Violation(String path, int line, String label) {

        @Override
        public String toString() {
            return line == 0 ? path + ": file name: " + label : path + ":" + line + ": " + label;
        }
    }

    /** The files to check, relative to the project directory, and how they were found. */
    public record Listing(List<String> files, String listedBy) {
    }

    /** What the scan read, and how it found the files. */
    public record Scan(List<Violation> violations, int filesRead, int binarySkipped, String listedBy) {
    }

    // A separator between the parts of a compound: none at all (camelCase), an underscore, a
    // hyphen, a space, a dot. Up to three of them, so "X - name" is caught as well as "X-name".
    private static final String SEP = "[\\W_]{0,3}";

    private static final List<Rule> RULES = List.of(
            rule("dash other than a plain hyphen", "[" + codes(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2043, 0x2053,
                    0x2212, 0x2E3A, 0x2E3B, 0xFE31, 0xFE32, 0xFE58, 0xFE63, 0xFF0D) + "]"),
            rule("curly or typographic quote", "[" + codes(0x00B4, 0x02B9, 0x02BA, 0x02BB, 0x02BC, 0x02BD, 0x2018, 0x2019,
                    0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2032, 0x2033, 0x2034, 0x2035, 0x2036, 0x2037, 0x275B,
                    0x275C, 0x275D, 0x275E, 0x2E42, 0x301D, 0x301E, 0x301F, 0xFF02, 0xFF07) + "]"),
            // Gradle writes the wrapper script, guillemets included (D-013), and rewrites it on every
            // wrapper upgrade, so an edit there would not last. Everywhere else they are refused.
            new Rule("angle quotation mark", Pattern.compile("[" + codes(0x00AB, 0x00BB, 0x2039, 0x203A) + "]"), Set.of("gradlew")),
            rule("ellipsis character, write three full stops", "[" + codes(0x2026, 0x22EF, 0xFE19) + "]"),
            // These cannot be seen in an editor or a diff. Test data that needs one writes its escape.
            rule("invisible or non-standard space character, write its escape", "[" + codes(0x00A0, 0x00AD, 0x034F, 0x061C,
                    0x115F, 0x1160, 0x1680, 0x180E, 0x3000, 0x3164, 0xFEFF, 0xFFA0, 0xFFF9, 0xFFFA, 0xFFFB)
                    + range(0x2000, 0x200F) + range(0x2028, 0x202F) + range(0x205F, 0x206F) + "]"),
            // No word boundary in front of a word, so it is caught inside an identifier as well.
            rule("banned filler word", "(?i)(?:" + String.join("|",
                    "seam" + "[\\W_]?" + "less",
                    "ro" + "bust",
                    "lever" + "ag",
                    "del" + "v(?:e|ing)",
                    "sle" + "ek",
                    "cutting" + SEP + "edge",
                    "best" + SEP + "in" + SEP + "class") + ")"),
            // A stylesheet property, a script's style property or a setProperty call: all three spellings.
            rule("CSS tracking property", "(?i)" + "letter" + SEP + "spacing"),
            // The organisation's short name always carries its H, inside an identifier too, and the H
            // is written in the same case as the P.
            rule("programme abbreviation without its trailing H", "(?i:" + "p" + "32" + "(?!h))|" + "P" + "32" + "h|" + "p" + "32" + "H"),
            rule("banned product name, say EDI", "(?i)" + "x" + SEP + "engine"),
            // The formal name is one word with two capitals. Any other spelling of it is refused.
            rule("formal name spelt otherwise than Point" + "32Health",
                    "(?i:point" + SEP + "32" + SEP + "health)(?<!Point" + "32Health)"));

    private static final Set<String> BINARY_EXTENSIONS = Set.of("jar", "png", "jpg", "jpeg", "gif", "ico", "zip", "class",
            "woff", "woff2", "ttf", "otf", "pdf");

    private HouseStyle() {
    }

    private static Rule rule(String label, String regex) {
        return new Rule(label, Pattern.compile(regex), Set.of());
    }

    /** A character class body from code points, so no character outside ASCII is written in this file. */
    private static String codes(int... codePoints) {
        StringBuilder out = new StringBuilder();
        for (int codePoint : codePoints) {
            out.append(String.format(Locale.ROOT, "\\x{%04X}", codePoint));
        }
        return out.toString();
    }

    private static String range(int from, int to) {
        return codes(from) + "-" + codes(to);
    }

    public static List<Rule> rules() {
        return RULES;
    }

    /** Every rule a piece of text breaks, line by line, as if it were the file at {@code path}. */
    public static List<Violation> checkText(String path, String text) {
        List<Violation> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Rule rule : RULES) {
                if (rule.appliesTo(path) && rule.pattern().matcher(lines[i]).find()) {
                    out.add(new Violation(path, i + 1, rule.label()));
                }
            }
        }
        return out;
    }

    /** Every rule a file's path breaks. A name is shown in a listing and a stack trace as much as a line is. */
    public static List<Violation> checkName(String path) {
        List<Violation> out = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.appliesTo(path) && rule.pattern().matcher(path).find()) {
                out.add(new Violation(path, 0, rule.label()));
            }
        }
        return out;
    }

    /**
     * Checks the name and the content of each file, given as paths relative to {@code root} with
     * forward slashes. A file that is gone is skipped: git still lists a file deleted in the work
     * tree until the deletion is staged.
     */
    public static Scan scan(Path root, List<String> relativePaths, String listedBy) {
        List<Violation> violations = new ArrayList<>();
        int read = 0;
        int binary = 0;
        for (String relative : new TreeSet<>(relativePaths)) {
            violations.addAll(checkName(relative));
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + relative, e);
            }
            if (isBinary(relative, bytes)) {
                binary++;
                continue;
            }
            read++;
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
                violations.addAll(checkText(relative, text));
            } catch (CharacterCodingException e) {
                // Decoding with replacement would hide the very bytes this check is for.
                violations.add(new Violation(relative, 0, "not readable as UTF-8 text"));
            }
        }
        return new Scan(List.copyOf(violations), read, binary, listedBy);
    }

    // A known binary extension, or a NUL byte in the first 8000 bytes, which is how git itself
    // tells a binary file from a text one.
    private static boolean isBinary(String relative, byte[] bytes) {
        int dot = relative.lastIndexOf('.');
        if (dot >= 0 && BINARY_EXTENSIONS.contains(relative.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The files to check: every file git tracks under {@code root}, and every new file git would
     * track, so a file is caught before its first commit. Files git ignores (build output, IDE
     * metadata, the runtime data directories) are left out by the same rule that keeps them out
     * of a commit. Dot files such as {@code .gitignore} are included.
     *
     * <p>A copy of the source without git, a zip for example, is walked instead, leaving out the
     * same directories that {@code .gitignore} names.
     */
    public static Listing projectFiles(Path root) {
        List<String> listed = gitFiles(root);
        if (listed != null) {
            return new Listing(listed, "git");
        }
        return new Listing(walk(root), "a walk of the directory, git was not available");
    }

    /** The files of {@link #projectFiles(Path)} with their names and lines checked. */
    public static Scan scanProject(Path root) {
        Listing listing = projectFiles(root);
        return scan(root, listing.files(), listing.listedBy());
    }

    private static List<String> gitFiles(Path root) {
        try {
            Process process = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out;
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                out = buffer.toByteArray();
            }
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            List<String> files = new ArrayList<>();
            for (String name : new String(out, StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    files.add(name);
                }
            }
            return files;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static final Set<String> WALK_SKIPS = Set.of(".git", ".gradle", "build", "bin", "out", ".idea", ".settings",
            ".vscode", "config-store", "run-history");

    static List<String> walk(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path))
                    .filter(relative -> {
                        for (Path part : relative) {
                            if (WALK_SKIPS.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .map(relative -> relative.toString().replace('\\', '/'))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + root, e);
        }
    }

    /** The report the build writes, and the message it fails with. */
    public static String report(Scan scan) {
        StringBuilder out = new StringBuilder();
        if (scan.violations().isEmpty()) {
            out.append("No house style violations.\n");
        } else {
            out.append("House style violations (").append(scan.violations().size()).append("):\n");
            scan.violations().forEach(violation -> out.append("  ").append(violation).append('\n'));
        }
        out.append("Files read: ").append(scan.filesRead()).append(", binary files skipped: ").append(scan.binarySkipped())
                .append(", listed by ").append(scan.listedBy()).append(".\n");
        out.append("Not checked by the build: commit messages, the pull request description, names of people, "
                + "and comments that restate the code.\n");
        return out.toString();
    }

    /**
     * Arguments: the project directory and the report file. Exits with 1 when a rule is broken, so
     * the build fails; the report holds the list.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected two arguments: the project directory and the report file.");
        }
        Scan scan = scanProject(Path.of(args[0]));
        String report = report(scan);
        Path reportFile = Path.of(args[1]);
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        System.out.print(report);
        System.exit(scan.violations().isEmpty() ? 0 : 1);
    }
}
