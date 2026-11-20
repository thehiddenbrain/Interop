package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The frontend was built in parts, each by someone who could not change the files of the others,
 * and one part called a function another part had renamed: nothing failed until a tester pressed
 * the button. These are the joins a build can check, over the text of the files:
 * <ul>
 *   <li>every stylesheet and script the page links exists, and every stylesheet is linked</li>
 *   <li>every script is reached from the page through its imports, so none is left orphaned</li>
 *   <li>every name a script imports is exported by the file it imports it from</li>
 *   <li>every member a script calls on the frame's {@code app}, on the shared case
 *       ({@code caseContext}) or on a workbench ({@code bench}) is one that object has</li>
 * </ul>
 * The last tests hand each rule a line that breaks it, so a rule that has stopped looking fails.
 */
class StaticJoinTest {

    private static final Path ROOT = Path.of("src/main/resources/static");

    private static final Pattern LINKED = Pattern.compile("(?:href|src)=\"((?:css|js)/[^\"]+)\"");
    private static final Pattern IMPORT = Pattern.compile("(?s)import\\s*(?:\\{([^}]*)}|(\\w+))\\s*from\\s*'\\./([^']+)'");
    private static final Pattern IMPORT_ALL = Pattern.compile("import\\s*\\*\\s*as\\s*\\w+\\s*from\\s*'\\./([^']+)'");
    private static final Pattern EXPORTED = Pattern.compile("export\\s+(?:async\\s+)?(?:function|const|let|class)\\s+(\\w+)");
    private static final Pattern EXPORT_DEFAULT = Pattern.compile("export\\s+default\\b");

    @Test
    void everyStylesheetAndScriptThePageLinksExistsAndEveryStylesheetIsLinked() throws IOException {
        String page = read(ROOT.resolve("index.html"));
        Set<String> linked = new TreeSet<>();
        Matcher matcher = LINKED.matcher(page);
        while (matcher.find()) {
            linked.add(matcher.group(1));
        }
        assertThat(linked).contains("js/app.js", "css/pave.css");
        for (String path : linked) {
            assertThat(ROOT.resolve(path)).as("linked from index.html").isRegularFile();
        }
        for (Path sheet : files("css", ".css")) {
            assertThat(linked).as("every stylesheet is linked from the page").contains("css/" + sheet.getFileName());
        }
    }

    @Test
    void everyScriptIsReachedFromThePageAndNoneIsOrphaned() throws IOException {
        Set<String> reached = new TreeSet<>();
        Deque<String> next = new ArrayDeque<>(List.of("app.js"));
        while (!next.isEmpty()) {
            String name = next.pop();
            if (!reached.add(name)) {
                continue;
            }
            Path file = ROOT.resolve("js").resolve(name);
            assertThat(file).as("imported script").isRegularFile();
            next.addAll(importedFiles(read(file)));
        }
        Set<String> present = new TreeSet<>();
        for (Path script : files("js", ".js")) {
            present.add(script.getFileName().toString());
        }
        assertThat(reached).isEqualTo(present);
    }

    @Test
    void everyNameAScriptImportsIsExportedByTheFileItNames() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path script : files("js", ".js")) {
            missing.addAll(importsNotExported(script.getFileName().toString(), read(script)));
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyMemberCalledOnTheFrameTheCaseOrAWorkbenchExists() throws IOException {
        Set<String> app = members(read(ROOT.resolve("js/app.js")), "const app = {", "async function start");
        Set<String> caseContext = members(read(ROOT.resolve("js/case-context.js")), "export function createCaseContext", "export function bringIntoPane");
        Set<String> bench = members(read(ROOT.resolve("js/workbench.js")), "const bench = {", "return bench;");
        assertThat(caseContext).contains("summary", "request", "setMemberId");
        assertThat(bench).contains("call", "showResult", "track");
        List<String> missing = new ArrayList<>();
        for (Path script : files("js", ".js")) {
            String text = withoutComments(read(script));
            String name = script.getFileName().toString();
            missing.addAll(calledButMissing(name, text, "app", app));
            missing.addAll(calledButMissing(name, text, "caseContext", caseContext));
            missing.addAll(calledButMissing(name, text, "bench", bench));
        }
        assertThat(missing).isEmpty();
    }

    /* ---------- each rule, given a line that breaks it ---------- */

    @Test
    void aCallToAMemberTheCaseDoesNotHaveIsCaught() {
        Set<String> members = members("export function createCaseContext() {\n    return {\n        summary() {\n        },\n        setCodes(list) {\n"
                + "        }\n    };\n}\nexport function bringIntoPane() {}", "export function createCaseContext", "export function bringIntoPane");
        assertThat(members).containsExactlyInAnyOrder("summary", "setCodes");
        assertThat(calledButMissing("history-run.js", "app.caseContext.fillMember({}); app.caseContext.setCodes([]);", "caseContext", members))
                .containsExactly("history-run.js calls caseContext.fillMember");
    }

    @Test
    void anImportOfANameTheFileDoesNotExportIsCaught() throws IOException {
        assertThat(importsNotExported("made-up.js", "import {sameCase, usableHandover} from './handover.js';"))
                .containsExactly("made-up.js imports usableHandover from handover.js");
    }

    /* ---------- reading ---------- */

    private static List<String> importedFiles(String text) {
        List<String> out = new ArrayList<>();
        Matcher named = IMPORT.matcher(text);
        while (named.find()) {
            out.add(named.group(3));
        }
        Matcher all = IMPORT_ALL.matcher(text);
        while (all.find()) {
            out.add(all.group(1));
        }
        return out;
    }

    private static List<String> importsNotExported(String name, String text) throws IOException {
        List<String> missing = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(text);
        while (matcher.find()) {
            String source = read(ROOT.resolve("js").resolve(matcher.group(3)));
            if (matcher.group(2) != null) {
                if (!EXPORT_DEFAULT.matcher(source).find()) {
                    missing.add(name + " imports a default from " + matcher.group(3));
                }
                continue;
            }
            Set<String> exported = new TreeSet<>();
            Matcher export = EXPORTED.matcher(source);
            while (export.find()) {
                exported.add(export.group(1));
            }
            for (String part : matcher.group(1).split(",")) {
                String wanted = part.trim().split("\\s+as\\s+")[0].trim();
                if (!wanted.isEmpty() && !exported.contains(wanted)) {
                    missing.add(name + " imports " + wanted + " from " + matcher.group(3));
                }
            }
        }
        return missing;
    }

    /**
     * The members of an object literal written between two markers: each name that starts a line
     * as a method ("name(") or a property ("name:", "name,"). Nested objects are indented further
     * and the deepest indentation of a member line is not assumed, so a name is counted when it
     * starts a line at the smallest indentation seen for a member in that part of the file.
     */
    private static Set<String> members(String text, String from, String to) {
        int start = text.indexOf(from);
        int end = text.indexOf(to, start + from.length());
        assertThat(start).as("marker " + from).isNotNegative();
        assertThat(end).as("marker " + to).isPositive();
        Pattern member = Pattern.compile("(?m)^( +)(?:async\\s+)?(\\w+)\\s*(?:\\(|:|,$|$)");
        Matcher matcher = member.matcher(text.substring(start, end));
        int shallowest = Integer.MAX_VALUE;
        List<String[]> found = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group(2);
            if (Set.of("return", "if", "for", "while", "switch", "catch", "function", "const", "let", "else", "try").contains(word)) {
                continue;
            }
            found.add(new String[] {matcher.group(1), word});
        }
        // The members of the object are the names at the shallowest indentation a method or property has.
        for (String[] each : found) {
            shallowest = Math.min(shallowest, each[0].length());
        }
        Set<String> out = new TreeSet<>();
        for (String[] each : found) {
            if (each[0].length() == shallowest) {
                out.add(each[1]);
            }
        }
        return out;
    }

    private static List<String> calledButMissing(String name, String text, String object, Set<String> members) {
        List<String> missing = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b" + object + "\\.(\\w+)").matcher(text);
        while (matcher.find()) {
            // An object reaches its own members through "this" only; a line that defines the object is not a call.
            if (!members.contains(matcher.group(1))) {
                missing.add(name + " calls " + object + "." + matcher.group(1));
            }
        }
        return missing.stream().distinct().toList();
    }

    /** The text of a script without its comments, which name files ("app.js") and members in words. */
    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", " ");
    }

    private static List<Path> files(String folder, String suffix) throws IOException {
        try (Stream<Path> stream = Files.list(ROOT.resolve(folder))) {
            return stream.filter(path -> path.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
