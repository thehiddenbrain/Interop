package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * The frontend is built in parts, each by someone who cannot change the files of the others, and
 * a part that calls a function another part renamed fails only when a tester presses the button.
 * These are the joins a build can check, over the text of the files:
 * <ul>
 *   <li>the page links its one module and its four stylesheets, each exists, and every
 *       stylesheet is linked</li>
 *   <li>every script is reached from the page through its imports, static or loaded when a tab
 *       opens, so none is left orphaned</li>
 *   <li>every name a script imports is exported by the file it names, and every view loaded when
 *       its tab opens exports mount</li>
 *   <li>the shared modules export what the plan's contracts promise the views (section 9.1)</li>
 *   <li>every member a script calls on an object another script exports (the frame's {@code app},
 *       a view's state) is one that object has</li>
 * </ul>
 * The last tests hand each rule a line that breaks it, so a rule that has stopped looking fails.
 */
class StaticJoinTest {

    private static final Pattern LINKED = Pattern.compile("(?:href|src)=\"((?:css|js)/[^\"]+)\"");
    private static final Pattern IMPORT = Pattern.compile("(?s)import\\s*(?:\\{([^}]*)}|(\\w+))\\s*from\\s*['\"]\\./([^'\"]+)['\"]");
    private static final Pattern IMPORT_ALL = Pattern.compile("import\\s*\\*\\s*as\\s*\\w+\\s*from\\s*['\"]\\./([^'\"]+)['\"]");
    private static final Pattern IMPORT_LATER = Pattern.compile("\\bimport\\s*\\(\\s*['\"]\\./([^'\"]+)['\"]\\s*\\)");
    private static final Pattern EXPORTED = Pattern.compile("export\\s+(?:async\\s+)?(?:function|const|let|class)\\s+(\\w+)");
    private static final Pattern EXPORT_DEFAULT = Pattern.compile("export\\s+default\\b");
    private static final Pattern SHARED_OBJECT = Pattern.compile("(?m)^export\\s+const\\s+(\\w+)\\s*=\\s*\\{");

    /** What each shared module promises the views (plan, section 9.1). A view builds against these names. */
    private static final Map<String, List<String>> CONTRACT = contract();

    private static Map<String, List<String>> contract() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("app.js", List.of("app"));
        out.put("api.js", List.of("call"));
        out.put("dom.js", List.of("el", "clear", "chip", "badge", "fold", "kv", "button", "field", "modal"));
        out.put("fault.js", List.of("errorPanel", "faultReport"));
        out.put("words.js", List.of("vocabulary"));
        out.put("session-runs.js", List.of("remember", "recall"));
        out.put("json-view.js", List.of("jsonView"));
        out.put("files.js", List.of("readText", "readBase64"));
        out.put("result.js", List.of("resultCard"));
        out.put("detail.js", List.of("responseDetail"));
        out.put("workbench.js", List.of("mount"));
        out.put("audit.js", List.of("mount"));
        out.put("configuration.js", List.of("mount"));
        return out;
    }

    /** The members of the frame's app that the views are promised. */
    private static final List<String> APP_MEMBERS = List.of("mode", "isMock", "epoch", "on", "go", "notice");

    @Test
    void thePageLinksItsModuleAndItsFourStylesheetsAndEveryStylesheetIsLinked() throws IOException {
        Set<String> linked = new TreeSet<>();
        Matcher matcher = LINKED.matcher(StaticSources.read("index.html"));
        while (matcher.find()) {
            linked.add(matcher.group(1));
        }
        assertThat(linked).containsExactlyInAnyOrder("js/app.js", "css/pave.css", "css/workbench.css", "css/audit.css", "css/config.css");
        for (String path : linked) {
            assertThat(StaticSources.ROOT.resolve(path)).as("linked from index.html").isRegularFile();
        }
        for (Source sheet : StaticSources.sources(".css")) {
            assertThat(linked).as("every stylesheet is linked from the page").contains(sheet.name());
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
            assertThat(StaticSources.JS.resolve(name)).as("imported script").isRegularFile();
            next.addAll(importedFiles(StaticSources.script(name)));
        }
        Set<String> present = new TreeSet<>();
        for (Source script : StaticSources.sources(".js")) {
            present.add(script.name().substring("js/".length()));
        }
        assertThat(reached).isEqualTo(present);
    }

    @Test
    void everyNameAScriptImportsIsExportedByTheFileItNames() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            missing.addAll(importsNotExported(script.name().substring("js/".length()), script.text()));
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyViewLoadedWhenItsTabOpensExportsMount() throws IOException {
        List<String> views = new ArrayList<>();
        Matcher later = IMPORT_LATER.matcher(StaticSources.script("app.js"));
        while (later.find()) {
            views.add(later.group(1));
        }
        assertThat(views).containsExactly("workbench.js", "audit.js", "configuration.js");
        for (String view : views) {
            assertThat(exported(StaticSources.script(view))).as(view).contains("mount");
        }
    }

    @Test
    void theSharedModulesExportWhatTheViewsArePromised() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CONTRACT.entrySet()) {
            Set<String> exported = exported(StaticSources.script(entry.getKey()));
            for (String name : entry.getValue()) {
                if (!exported.contains(name)) {
                    missing.add(entry.getKey() + " does not export " + name);
                }
            }
        }
        assertThat(missing).isEmpty();
        assertThat(sharedObjects().get("app.js:app")).as("members of app").containsAll(APP_MEMBERS);
    }

    @Test
    void everyMemberCalledOnAnObjectAnotherScriptExportsExists() throws IOException {
        Map<String, Set<String>> objects = sharedObjects();
        assertThat(objects).containsKey("app.js:app");
        List<String> missing = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            String name = script.name().substring("js/".length());
            String text = StaticSources.withoutComments(script.text());
            for (Map.Entry<String, Set<String>> object : objects.entrySet()) {
                String[] parts = object.getKey().split(":");
                if (importsName(text, parts[1], parts[0])) {
                    missing.addAll(calledButMissing(name, text, parts[1], object.getValue()));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /* ---------- each rule, given a line that breaks it ---------- */

    @Test
    void aCallToAMemberTheObjectDoesNotHaveIsCaught() {
        Set<String> members = members("export const app = {\n    mode() {\n        return 1;\n    },\n    on(name, fn) {\n"
                + "        const set = listeners[name];\n    },\n    go: show\n};\n", "app");
        assertThat(members).containsExactlyInAnyOrder("mode", "on", "go");
        assertThat(calledButMissing("audit.js", "app.mode(); app.refreshMode();", "app", members))
                .containsExactly("audit.js calls app.refreshMode");
        assertThat(importsName("import {el} from './dom.js';\nimport {app} from './app.js';", "app", "app.js")).isTrue();
        assertThat(importsName("import {application} from './app.js';", "app", "app.js")).isFalse();
    }

    @Test
    void anImportOfANameTheFileDoesNotExportIsCaught() throws IOException {
        assertThat(importsNotExported("made-up.js", "import {el, element} from './dom.js';"))
                .containsExactly("made-up.js imports element from dom.js");
        assertThat(importedFiles("const view = await import('./audit.js');\nimport {el} from \"./dom.js\";"))
                .containsExactlyInAnyOrder("audit.js", "dom.js");
    }

    /* ---------- reading ---------- */

    private static List<String> importedFiles(String text) {
        List<String> out = new ArrayList<>();
        for (Pattern pattern : List.of(IMPORT, IMPORT_ALL, IMPORT_LATER)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                out.add(matcher.group(pattern == IMPORT ? 3 : 1));
            }
        }
        return out;
    }

    private static Set<String> exported(String source) {
        Set<String> names = new TreeSet<>();
        Matcher export = EXPORTED.matcher(source);
        while (export.find()) {
            names.add(export.group(1));
        }
        return names;
    }

    private static List<String> importsNotExported(String name, String text) throws IOException {
        List<String> missing = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(text);
        while (matcher.find()) {
            String source = StaticSources.script(matcher.group(3));
            if (matcher.group(2) != null) {
                if (!EXPORT_DEFAULT.matcher(source).find()) {
                    missing.add(name + " imports a default from " + matcher.group(3));
                }
                continue;
            }
            Set<String> names = exported(source);
            for (String part : matcher.group(1).split(",")) {
                String wanted = part.trim().split("\\s+as\\s+")[0].trim();
                if (!wanted.isEmpty() && !names.contains(wanted)) {
                    missing.add(name + " imports " + wanted + " from " + matcher.group(3));
                }
            }
        }
        return missing;
    }

    /** Each object literal a script exports (export const name = {...}), by "file:name", with its members. */
    private static Map<String, Set<String>> sharedObjects() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Source script : StaticSources.sources(".js")) {
            Matcher object = SHARED_OBJECT.matcher(script.text());
            while (object.find()) {
                out.put(script.name().substring("js/".length()) + ":" + object.group(1), members(script.text(), object.group(1)));
            }
        }
        return out;
    }

    /**
     * The members of an exported object literal: each name that starts a line as a method
     * ("name(", "async name(") or a property ("name:", "name,") at the shallowest indentation a
     * member has between "export const name = {" and the "};" that closes it at the start of a line.
     */
    static Set<String> members(String text, String object) {
        String opening = "export const " + object + " = {";
        int start = text.indexOf(opening);
        assertThat(start).as(opening).isNotNegative();
        int end = text.indexOf("\n};", start);
        assertThat(end).as("end of " + opening).isPositive();
        Pattern member = Pattern.compile("(?m)^( +)(?:async\\s+)?(\\w+)\\s*(?:\\(|:|,$|$)");
        Matcher matcher = member.matcher(text.substring(start + opening.length(), end));
        List<String[]> found = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group(2);
            if (Set.of("return", "if", "for", "while", "switch", "catch", "function", "const", "let", "else", "try", "case", "default")
                    .contains(word)) {
                continue;
            }
            found.add(new String[] {matcher.group(1), word});
        }
        int shallowest = Integer.MAX_VALUE;
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

    private static boolean importsName(String text, String name, String file) {
        Matcher matcher = IMPORT.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1) != null && matcher.group(3).equals(file)) {
                for (String part : matcher.group(1).split(",")) {
                    String[] words = part.trim().split("\\s+as\\s+");
                    if (words[0].trim().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String> calledButMissing(String name, String text, String object, Set<String> members) {
        List<String> missing = new ArrayList<>();
        // Not the name of the file the object is imported from ("./app.js"), which is no call.
        Matcher matcher = Pattern.compile("(?<![\\w./])" + object + "\\.(\\w+)").matcher(text);
        while (matcher.find()) {
            if (!members.contains(matcher.group(1))) {
                missing.add(name + " calls " + object + "." + matcher.group(1));
            }
        }
        return missing.stream().distinct().toList();
    }
}
