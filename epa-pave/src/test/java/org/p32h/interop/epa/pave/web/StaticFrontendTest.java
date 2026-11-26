package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The frontend is plain files that a tester may edit and refresh. No compiler stands between an
 * edit and the browser, so the rules that keep those files safe are checked here, where a build
 * can check them:
 * <ul>
 *   <li>the page is served, with every script it loads, as something a browser will run, and every
 *       script parses as a module</li>
 *   <li>no request leaves the machine for an asset</li>
 *   <li>nothing a service returned is ever turned into markup</li>
 *   <li>nothing is written to browser storage except which tab is open</li>
 *   <li>no CSS tracking, and no inline script or style, which the content security policy would
 *       refuse at run time with nothing but a line in the browser console to show for it</li>
 * </ul>
 * Each rule is a function over the text of a file. The tests at the end hand every rule a line
 * that breaks it, so a rule that has stopped looking fails the build instead of passing it.
 */
@SpringBootTest
class StaticFrontendTest {

    /** The one key the frontend may use in browser storage. It holds which tab is open and nothing else. */
    private static final String INTERFACE_STATE_KEY = "pave.tab";

    // Assembled so this file does not contain the property name the house style gate forbids.
    private static final String TRACKING_PROPERTY = "letter" + "-" + "spacing";

    private static final String ADDRESS_ELSEWHERE = "\\s*(?:https?:)?//";

    /** Places where an address makes the browser fetch something. An address on another host in any of them is a request that leaves the machine. */
    private static final List<Pattern> LOADS_FROM_ELSEWHERE = List.of(
            Pattern.compile("(?i)\\b(?:src|href|srcset|action|poster)\\s*[=:]\\s*[\"'`]?" + ADDRESS_ELSEWHERE),
            Pattern.compile("(?i)\\burl\\(\\s*[\"']?" + ADDRESS_ELSEWHERE),
            Pattern.compile("(?i)@import\\s+(?:url\\(\\s*)?[\"']?" + ADDRESS_ELSEWHERE),
            Pattern.compile("(?i)\\bfetch\\s*\\(\\s*[\"'`]" + ADDRESS_ELSEWHERE),
            Pattern.compile("(?i)\\bfrom\\s+[\"']" + ADDRESS_ELSEWHERE),
            Pattern.compile("(?i)\\bimport\\s*\\(?\\s*[\"'`]" + ADDRESS_ELSEWHERE));

    /** Ways a script can turn a string into markup or into code. A vendor response is untrusted text, so none of them is used at all. */
    private static final List<Pattern> MARKUP_OR_CODE_FROM_A_STRING = List.of(
            Pattern.compile("\\b(?:inner|outer)HTML\\b"),
            Pattern.compile("\\binsertAdjacentHTML\\b"),
            Pattern.compile("\\bdocument\\s*\\.\\s*write(?:ln)?\\s*\\("),
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bnew\\s+Function\\s*\\("),
            Pattern.compile("\\bset(?:Timeout|Interval)\\s*\\(\\s*[\"'`]"),
            Pattern.compile("\\bcreateContextualFragment\\b"),
            Pattern.compile("\\bparseFromString\\s*\\("),
            Pattern.compile("\\bsetHTML(?:Unsafe)?\\s*\\("));

    /** Browser stores the frontend never touches. Member data is for the screen only. */
    private static final List<Pattern> FORBIDDEN_STORES = List.of(
            Pattern.compile("\\bsessionStorage\\b"),
            Pattern.compile("\\bindexedDB\\b"),
            Pattern.compile("\\bdocument\\s*\\.\\s*cookie\\b"),
            Pattern.compile("\\bcookieStore\\b"),
            Pattern.compile("\\bopenDatabase\\s*\\("),
            Pattern.compile("\\bcaches\\s*\\.\\s*(?:open|match|has|keys|delete)\\b"),
            Pattern.compile("\\bserviceWorker\\b"));

    private static final Pattern LOCAL_STORAGE = Pattern.compile("\\blocalStorage\\b");
    private static final Pattern LOCAL_STORAGE_BY_THE_ONE_KEY =
            Pattern.compile("\\blocalStorage\\s*\\.\\s*(getItem|setItem)\\s*\\(\\s*TAB_KEY\\s*[,)]");
    private static final Pattern KEY_DECLARATION = Pattern.compile("\\bTAB_KEY\\s*=\\s*['\"]([^'\"]*)['\"]");

    private static final Pattern SCRIPT_TAG = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>");
    private static final Pattern SRC = Pattern.compile("(?i)\\bsrc\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern MODULE_TYPE = Pattern.compile("(?i)\\btype\\s*=\\s*[\"']module[\"']");
    private static final Pattern HAS_SCHEME_OR_HOST = Pattern.compile("(?i)^\\s*(?:[a-z][a-z0-9+.-]*:|//)");
    private static final Pattern STYLESHEET = Pattern.compile("(?i)<link\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']*\\.css)[\"']");
    private static final Pattern LOCAL_IMPORT = Pattern.compile("\\b(?:from\\s+|import\\s*\\(\\s*)['\"](\\.[^'\"]*)['\"]");
    /** Every way a script can talk to a server. Only api.js does, so every answer is classified in one place. */
    private static final Pattern CALLER = Pattern.compile("\\b(?:fetch|XMLHttpRequest|EventSource|WebSocket|sendBeacon)\\s*\\(");
    private static final Pattern POLICY_META = Pattern.compile(
            "(?is)<meta\\s+http-equiv\\s*=\\s*[\"']Content-Security-Policy[\"']\\s+content\\s*=\\s*\"([^\"]*)\"");

    @Autowired
    WebApplicationContext context;

    @Autowired
    ResponseHeadersFilter headers;

    // ------------------------------------------------------------------ the page is served

    @Test
    void thePageAndEveryFileItLoadsAreServedAsSomethingABrowserWillRun() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(headers).build();

        // The address a tester types is the bare one, so that has to lead to the page.
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getForwardedUrl()).isEqualTo("index.html");

        MockHttpServletResponse page = mvc.perform(get("/index.html")).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentType()).startsWith("text/html");
        assertThat(page.getContentAsString()).contains("<title>PAVE");
        // Nothing this tool serves is kept by the browser, so an edited file shows on refresh (D-127), and the page
        // must carry the policy that keeps vendor text inert.
        assertThat(page.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(page.getHeader("Content-Security-Policy")).contains("script-src 'self'");

        List<Source> scripts = StaticSources.sources(".js");
        assertThat(scripts).as("the frontend has scripts").isNotEmpty();
        for (Source script : scripts) {
            MockHttpServletResponse served = mvc.perform(get("/" + script.name())).andReturn().getResponse();
            assertThat(served.getStatus()).as(script.name()).isEqualTo(200);
            // A browser refuses to run a module that is not served as JavaScript.
            assertThat(served.getContentType()).as(script.name()).containsAnyOf("text/javascript", "application/javascript");
        }
        for (Source sheet : StaticSources.sources(".css")) {
            MockHttpServletResponse served = mvc.perform(get("/" + sheet.name())).andReturn().getResponse();
            assertThat(served.getStatus()).as(sheet.name()).isEqualTo(200);
            assertThat(served.getContentType()).as(sheet.name()).startsWith("text/css");
        }
    }

    @Test
    void everyFileThePageAsksForExists() throws IOException {
        // A module that is missing is a blank page in the browser and nothing at all in a Java build.
        List<String> missing = new ArrayList<>();
        for (Source page : StaticSources.sources(".html")) {
            for (String asked : loadedBy(page)) {
                if (!Files.isRegularFile(StaticSources.ROOT.resolve(asked))) {
                    missing.add(page.name() + " asks for " + asked);
                }
            }
        }
        for (Source script : StaticSources.sources(".js")) {
            Matcher imports = LOCAL_IMPORT.matcher(script.text());
            while (imports.find()) {
                Path wanted = StaticSources.ROOT.resolve(script.name()).getParent().resolve(imports.group(1)).normalize();
                if (!Files.isRegularFile(wanted)) {
                    missing.add(script.name() + " imports " + imports.group(1));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /**
     * A module with a syntax error takes every module that imports it down with it: the page is
     * blank, and a Java build sees nothing. Node checks each one as the browser would read it, as
     * a module, where Node is installed.
     */
    @Test
    void everyScriptParsesAsAModule(@TempDir Path folder) throws IOException, InterruptedException {
        Path node = StaticSources.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the scripts were not parsed.");
        List<String> refused = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            Path copy = folder.resolve(script.name().replace('/', '_') + ".mjs");
            Files.writeString(copy, script.text(), StandardCharsets.UTF_8);
            Path output = folder.resolve("check.txt");
            Process check = new ProcessBuilder(node.toString(), "--check", copy.toString()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            assertThat(check.waitFor(60, TimeUnit.SECONDS)).as("node --check of " + script.name() + " finished").isTrue();
            if (check.exitValue() != 0) {
                refused.add(script.name() + ": " + Files.readString(output, StandardCharsets.UTF_8));
            }
        }
        assertThat(refused).isEmpty();
    }

    // ------------------------------------------------------------------ the rules over the files

    @Test
    void theTitleNamesTheToolSoTheBrowserTabCanBeToldApart() throws IOException {
        assertThat(titleProblems(new Source("index.html", StaticSources.read("index.html")))).isEmpty();
    }

    @Test
    void noStylesheetUsesTracking() throws IOException {
        List<Source> sheets = StaticSources.sources(".css");
        assertThat(sheets).as("the frontend has a stylesheet").isNotEmpty();
        assertThat(violations(sheets, StaticFrontendTest::trackingIn)).isEmpty();
    }

    @Test
    void noFileLoadsAnythingFromAnotherHost() throws IOException {
        // No CDN, no web font, no analytics. The tool runs on a laptop behind a corporate network.
        assertThat(violations(StaticSources.sources(".html", ".css", ".js", ".svg"), StaticFrontendTest::loadsFromElsewhereIn)).isEmpty();
    }

    @Test
    void noScriptTurnsAStringIntoMarkupOrIntoCode() throws IOException {
        assertThat(violations(StaticSources.sources(".js"), StaticFrontendTest::markupOrCodeFromAStringIn)).isEmpty();
    }

    @Test
    void theOnlyThingKeptInBrowserStorageIsWhichTabIsOpen() throws IOException {
        List<Source> scripts = StaticSources.sources(".js");
        assertThat(violations(scripts, StaticFrontendTest::storageIn)).isEmpty();
        // One key, declared once, and it is the one this test names.
        assertThat(storageKeys(scripts)).containsExactly(INTERFACE_STATE_KEY);
    }

    @Test
    void thePageLoadsOnlyModuleScriptsFromThisTool() throws IOException {
        for (Source page : StaticSources.sources(".html")) {
            assertThat(scriptProblems(page)).as(page.name()).isEmpty();
            assertThat(SCRIPT_TAG.matcher(page.text()).find()).as(page.name() + " loads a script").isTrue();
        }
    }

    @Test
    void thePageCarriesNoInlineScriptAndNoInlineStyle() throws IOException {
        // The content security policy refuses both, silently as far as a tester can see.
        assertThat(violations(StaticSources.sources(".html"), StaticFrontendTest::inlineCodeIn)).isEmpty();
        assertThat(violations(StaticSources.sources(".js"), StaticFrontendTest::styleAttributeFromScriptIn)).isEmpty();
    }

    @Test
    void thePageStatesTheSamePolicyTheServerSends() throws IOException {
        Matcher meta = POLICY_META.matcher(StaticSources.read("index.html"));
        assertThat(meta.find()).as("index.html carries a Content-Security-Policy meta tag").isTrue();
        Set<String> inPage = directives(meta.group(1));
        assertThat(inPage).contains("default-src 'self'", "script-src 'self'", "style-src 'self'", "connect-src 'self'",
                "img-src 'self' data:", "base-uri 'none'", "form-action 'self'");
        assertThat(meta.group(1)).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*");
        // The header may add what a meta tag cannot carry, and nothing else may differ.
        Set<String> fromServer = directives(ResponseHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(fromServer).containsAll(inPage);
        Set<String> onlyFromServer = new LinkedHashSet<>(fromServer);
        onlyFromServer.removeAll(inPage);
        assertThat(onlyFromServer).containsExactly("frame-ancestors 'none'");
    }

    @Test
    void onlyOneScriptTalksToTheServer() throws IOException {
        // Every answer is classified in one place, so no view reads a status code its own way.
        List<String> callers = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            if (CALLER.matcher(script.text()).find()) {
                callers.add(script.name());
            }
        }
        assertThat(callers).containsExactly("js/api.js");
    }

    @Test
    void everyFileIsPlainAscii() throws IOException {
        assertThat(violations(StaticSources.sources(".html", ".css", ".js", ".svg"), StaticFrontendTest::notAsciiIn)).isEmpty();
    }

    // ------------------------------------------------------------------ each rule fails when it is broken

    @Test
    void theTrackingRuleSeesTracking() {
        assertThat(trackingIn(new Source("css/x.css", "h1 { " + TRACKING_PROPERTY + ": 0.1em; }"))).hasSize(1);
        assertThat(trackingIn(new Source("css/x.css", "h1 { " + TRACKING_PROPERTY.toUpperCase() + " : 2px }"))).hasSize(1);
        assertThat(trackingIn(new Source("css/x.css", "h1 { word-spacing: 2px }"))).isEmpty();
    }

    @Test
    void theOtherHostRuleSeesEveryWayOfLoadingFromOne() {
        for (String line : List.of(
                "<script type=\"module\" src=\"https://cdn.example.org/lib.js\"></script>",
                "<link rel=\"stylesheet\" href=\"http://fonts.example.org/aptos.css\">",
                "<img src='//images.example.org/logo.png'>",
                "body { background: url(https://example.org/wash.png); }",
                "body { background: url( \"//example.org/wash.png\" ); }",
                "@import url(\"https://fonts.example.org/css\");",
                "@import 'https://fonts.example.org/css';",
                "const answer = await fetch('https://api.example.org/track');",
                "fetch(`https://api.example.org/${path}`)",
                "import {x} from 'https://cdn.example.org/x.js';",
                "const module = await import('//cdn.example.org/x.js');",
                "el('img', {src: 'https://example.org/pixel.gif'})",
                "<use href=\"https://example.org/sprite.svg#icon\"/>")) {
            assertThat(loadsFromElsewhereIn(new Source("x", line))).as(line).hasSize(1);
        }
        for (String line : List.of(
                "<script type=\"module\" src=\"js/app.js\"></script>",
                "<link rel=\"stylesheet\" href=\"css/pave.css\">",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\">",
                "const isWeb = /^https?:\\/\\//i.test(text);",
                "fetch(path, options)",
                "import * as api from './api.js';",
                "{id: 'audit', label: 'Audit', load: () => import('./audit.js')},",
                "return send('/api/mode');")) {
            assertThat(loadsFromElsewhereIn(new Source("x", line))).as(line).isEmpty();
        }
    }

    @Test
    void theMarkupRuleSeesEveryWayOfBuildingMarkupOrCodeFromAString() {
        for (String line : List.of(
                "node." + "inner" + "HTML = card.summary;",
                "node." + "inner" + "HTML += '<b>' + text + '</b>';",
                "node." + "outer" + "HTML = text;",
                "node.insertAdjacentHTML('beforeend', text);",
                "document.write(text);",
                "document.writeln(text);",
                "const value = eval(text);",
                "const run = new Function('a', text);",
                "setTimeout('poll()', 4000);",
                "range.createContextualFragment(text);",
                "new DOMParser().parseFromString(text, 'text/html');",
                "node.setHTMLUnsafe(text);")) {
            assertThat(markupOrCodeFromAStringIn(new Source("js/x.js", line))).as(line).hasSize(1);
        }
        for (String line : List.of(
                "node.textContent = card.summary;",
                "window.setTimeout(() => poll(false), 4000);",
                "const evaluated = interval(text);")) {
            assertThat(markupOrCodeFromAStringIn(new Source("js/x.js", line))).as(line).isEmpty();
        }
    }

    @Test
    void theStorageRuleSeesEveryStoreAndEveryOtherKey() {
        for (String line : List.of(
                "window.localStorage.setItem('recentMembers', memberId);",
                "localStorage.setItem(key, value);",
                "localStorage['pave.tab'] = tab;",
                "localStorage.memberId = id;",
                "localStorage.removeItem(TAB_KEY);",
                "localStorage.clear();",
                "window.sessionStorage.setItem(TAB_KEY, tab);",
                "const open = indexedDB.open('pave');",
                "document.cookie = 'member=' + id;",
                "await cookieStore.set('member', id);",
                "const cache = await caches.open('lookups');",
                "navigator.serviceWorker.register('worker.js');")) {
            assertThat(storageIn(new Source("js/x.js", line))).as(line).isNotEmpty();
        }
        for (String line : List.of(
                "return window.localStorage.getItem(TAB_KEY);",
                "window.localStorage.setItem(TAB_KEY, tabId);")) {
            assertThat(storageIn(new Source("js/x.js", line))).as(line).isEmpty();
        }
        // A second key is a second thing kept, whatever it is called in the script.
        assertThat(storageKeys(List.of(new Source("js/a.js", "const TAB_KEY = 'pave.tab';"),
                new Source("js/b.js", "const TAB_KEY = 'pave.member';")))).containsExactlyInAnyOrder("pave.tab", "pave.member");
    }

    @Test
    void theScriptRuleSeesAScriptThatIsNotAModuleFromThisTool() {
        for (String page : List.of(
                "<script src=\"js/app.js\"></script>",
                "<script type=\"text/javascript\" src=\"js/app.js\"></script>",
                "<script type=\"module\" src=\"https://cdn.example.org/app.js\"></script>",
                "<script type=\"module\" src=\"//cdn.example.org/app.js\"></script>",
                "<script type=\"module\">start();</script>",
                "<script>start();</script>")) {
            assertThat(scriptProblems(new Source("index.html", page))).as(page).isNotEmpty();
        }
        assertThat(scriptProblems(new Source("index.html", "<script type=\"module\" src=\"js/app.js\"></script>"))).isEmpty();
    }

    @Test
    void theInlineRuleSeesInlineStyleAndInlineHandlers() {
        for (String line : List.of(
                "<p style=\"color: red\">x</p>",
                "<style>p { color: red }</style>",
                "<button onclick=\"send()\">Send</button>",
                "<body onload='start()'>")) {
            assertThat(inlineCodeIn(new Source("index.html", line))).as(line).hasSize(1);
        }
        assertThat(inlineCodeIn(new Source("index.html", "<p class=\"stub-line\">One sentence.</p>"))).isEmpty();
        assertThat(styleAttributeFromScriptIn(new Source("js/x.js", "node.setAttribute('style', 'width: 40%');"))).hasSize(1);
        assertThat(styleAttributeFromScriptIn(new Source("js/x.js", "node.style.cssText = 'width: 40%';"))).hasSize(1);
        assertThat(styleAttributeFromScriptIn(new Source("js/x.js", "node.style.setProperty('width', '40%');"))).isEmpty();
    }

    @Test
    void theTitleRuleSeesATitleThatDoesNotNameTheTool() {
        assertThat(titleProblems(new Source("index.html", "<title>ePA</title>"))).hasSize(1);
        assertThat(titleProblems(new Source("index.html", "<head></head>"))).hasSize(1);
        assertThat(titleProblems(new Source("index.html", "<title>PAVE</title>"))).isEmpty();
    }

    @Test
    void theAsciiRuleSeesATypographicCharacter() {
        assertThat(notAsciiIn(new Source("js/x.js", "const text = 'caf" + (char) 0xE9 + "';"))).hasSize(1);
        assertThat(notAsciiIn(new Source("js/x.js", "const text = 'plain';\n\tconst more = 1;\r\n"))).isEmpty();
    }

    @Test
    void theOneCallerRuleSeesEveryOtherWayOfTalkingToAServer() {
        for (String line : List.of("const answer = await fetch(path);", "const request = new XMLHttpRequest();",
                "new EventSource('/api/runs');", "navigator.sendBeacon('/api/runs', data);", "new WebSocket(address);")) {
            assertThat(CALLER.matcher(line).find()).as(line).isTrue();
        }
        assertThat(CALLER.matcher("const answer = await call('GET', '/api/mode');").find()).isFalse();
        assertThat(CALLER.matcher("// Fetch questionnaire is the button of step 2, and a fetch of it is a call.").find()).isFalse();
    }

    // ------------------------------------------------------------------ the rules

    static List<String> trackingIn(Source source) {
        return matches(source, List.of(Pattern.compile("(?i)" + TRACKING_PROPERTY)), "sets tracking");
    }

    static List<String> loadsFromElsewhereIn(Source source) {
        return matches(source, LOADS_FROM_ELSEWHERE, "loads from another host");
    }

    static List<String> markupOrCodeFromAStringIn(Source source) {
        return matches(source, MARKUP_OR_CODE_FROM_A_STRING, "builds markup or code from a string");
    }

    /** Every use of browser storage that is not a read or a write of the one key, by the name it is declared under. */
    static List<String> storageIn(Source source) {
        List<String> found = new ArrayList<>(matches(source, FORBIDDEN_STORES, "uses a browser store this tool does not use"));
        String[] lines = source.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher any = LOCAL_STORAGE.matcher(lines[i]);
            int uses = 0;
            while (any.find()) {
                uses++;
            }
            Matcher allowed = LOCAL_STORAGE_BY_THE_ONE_KEY.matcher(lines[i]);
            int permitted = 0;
            while (allowed.find()) {
                permitted++;
            }
            if (uses > permitted) {
                found.add(source.name() + ":" + (i + 1) + ": uses local storage other than to read or write the one key");
            }
        }
        return found;
    }

    /** The values of every storage key the scripts declare. */
    static List<String> storageKeys(List<Source> scripts) {
        List<String> keys = new ArrayList<>();
        for (Source script : scripts) {
            Matcher declaration = KEY_DECLARATION.matcher(script.text());
            while (declaration.find()) {
                keys.add(declaration.group(1));
            }
        }
        return keys;
    }

    static List<String> scriptProblems(Source page) {
        List<String> problems = new ArrayList<>();
        Matcher tag = SCRIPT_TAG.matcher(page.text());
        while (tag.find()) {
            String attributes = tag.group(1);
            Matcher src = SRC.matcher(attributes);
            if (!tag.group(2).isBlank()) {
                problems.add(page.name() + ": a script is written into the page");
            }
            if (!src.find()) {
                problems.add(page.name() + ": a script has no src");
            } else if (HAS_SCHEME_OR_HOST.matcher(src.group(1)).find()) {
                problems.add(page.name() + ": the script " + src.group(1) + " is not loaded from this tool");
            }
            if (!MODULE_TYPE.matcher(attributes).find()) {
                problems.add(page.name() + ": a script is not type=\"module\"");
            }
        }
        return problems;
    }

    static List<String> inlineCodeIn(Source source) {
        return matches(source, List.of(Pattern.compile("(?i)\\sstyle\\s*="), Pattern.compile("(?i)<style\\b"),
                Pattern.compile("(?i)<[^>]*\\son[a-z]+\\s*=")), "carries inline style or an inline handler");
    }

    static List<String> styleAttributeFromScriptIn(Source source) {
        return matches(source, List.of(Pattern.compile("setAttribute\\(\\s*['\"]style['\"]"), Pattern.compile("\\.cssText\\b")),
                "writes a style attribute, which the content security policy refuses");
    }

    static List<String> titleProblems(Source page) {
        return Pattern.compile("(?is)<title>\\s*PAVE\\b").matcher(page.text()).find() ? List.of()
                : List.of(page.name() + ": the title does not start with PAVE");
    }

    static List<String> notAsciiIn(Source source) {
        return matches(source, List.of(Pattern.compile("[^\\t\\r\\x20-\\x7E]")), "holds a character outside plain ASCII");
    }

    // ------------------------------------------------------------------ reading the files

    private static List<String> matches(Source source, List<Pattern> patterns, String what) {
        List<String> found = new ArrayList<>();
        String[] lines = source.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(lines[i]).find()) {
                    found.add(source.name() + ":" + (i + 1) + ": " + what);
                    break;
                }
            }
        }
        return found;
    }

    private interface Rule {
        List<String> apply(Source source);
    }

    private static List<String> violations(List<Source> sources, Rule rule) {
        List<String> all = new ArrayList<>();
        for (Source source : sources) {
            all.addAll(rule.apply(source));
        }
        return all;
    }

    /** The local files a page asks the browser for: its scripts and its stylesheets. */
    private static List<String> loadedBy(Source page) {
        List<String> asked = new ArrayList<>();
        Matcher tag = SCRIPT_TAG.matcher(page.text());
        while (tag.find()) {
            Matcher src = SRC.matcher(tag.group(1));
            if (src.find() && !HAS_SCHEME_OR_HOST.matcher(src.group(1)).find()) {
                asked.add(src.group(1));
            }
        }
        Matcher sheet = STYLESHEET.matcher(page.text());
        while (sheet.find()) {
            if (!HAS_SCHEME_OR_HOST.matcher(sheet.group(1)).find()) {
                asked.add(sheet.group(1));
            }
        }
        return asked;
    }

    private static Set<String> directives(String policy) {
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(policy.split(";")).map(String::strip).filter(part -> !part.isEmpty()).forEach(out::add);
        return out;
    }
}
