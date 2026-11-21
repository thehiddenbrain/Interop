package org.p32h.interop.epa.pave.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.PaveApplication;

/**
 * No regular expression of this tool may make java.util.regex call itself once for every
 * character, label or segment of its input.
 *
 * <p>java.util.regex evaluates a greedy or lazy repetition of a group that holds an alternation or
 * a repetition of varying length by recursion, one call per iteration. A request thread has a
 * stack of about 1 MB, so a quoted text of some 1,300 characters in a Postman file threw
 * StackOverflowError on upload (D-275), and the collection, already stored, threw again on every
 * later read. A possessive repetition, or a repetition of a fixed width, is evaluated in a loop.
 *
 * <p>Every pattern the main code holds is found here: the static fields by reflection over every
 * class the main code compiles to, and the patterns written inline, by a scan of the sources that
 * fails when an inline pattern appears that {@link #INLINE} does not name. Each is then held to
 * two checks:
 *
 * <ul>
 *   <li>Its compiled form holds no repetition evaluated by recursion that may run more than
 *       {@link #MOST_RECURSIVE_ITERATIONS} times. This reads the node graph java.util.regex
 *       builds, so it needs the package opened to this test, which build.gradle does.</li>
 *   <li>It runs on long hostile texts in a thread whose stack is 256 KB, a quarter of a request
 *       thread's: a million of one character for each class of characters it names, a million of
 *       alternating quotes, backslashes, braces, dots, digits and white space, and texts of 20,000
 *       characters that start with each literal stretch of the pattern and repeat a letter, a
 *       digit, a space or a backslash with each character and stretch it names. No
 *       StackOverflowError, and each operation within {@link #BOUND_NANOS}.</li>
 * </ul>
 *
 * <p>Why the bound is five seconds: on the machine this was written on, the slowest operation of a
 * pattern that is fine took under 0.3 seconds on a million characters, so five seconds leaves
 * room for a loaded build machine. Catastrophic backtracking is exponential in the length of the
 * text: on 20,000 characters it would not end in any time at all, so five seconds and a minute
 * would catch the same patterns. A scan with find over a million characters that is quadratic,
 * a pattern that reads to the end of the text from every start, takes hours and is caught too.
 */
class RegexStackGuardTest {

    /** A tenth of a million: well inside what a 256 KB stack holds, even at a few hundred bytes a call. */
    static final int MOST_RECURSIVE_ITERATIONS = 100;

    static final long BOUND_NANOS = 5_000_000_000L;

    static final int STACK_BYTES = 256 * 1024;

    static final int MILLION = 1_000_000;

    static final int SHAPED = 20_000;

    /** How many literal stretches of a pattern its shaped texts start with, the empty start included. */
    static final int STRETCHES = 10;

    private static final Path SOURCES = Path.of("src/main/java");

    private static final String ROOT_PACKAGE = "org/p32h/interop/epa/pave/";

    /**
     * Static patterns that are only ever matched against a whole value with matches(), each read
     * at its every use. A find over a million characters that hold none of what they look for
     * reads to the end of the text from every start, which is quadratic and is not how they are
     * used, so these are run with matches only. Recursion shows in matches as it would in find:
     * both start at the first character. Every other pattern is run with both on every text.
     */
    static final Set<String> WHOLE_VALUE_ONLY = Set.of(
            "model/RunRecord.CODE_ENTRY", "model/RunRecord.NO_ANSWER_ENTRY", "model/RunRecord.NAMED_ENTRY",
            "model/RunRecord.SPACED_IDENTIFIER", "service/compare/ReferenceText.RELATIVE", "service/config/ProductionSigns.BARE_HOST",
            "service/pas/PasResources.URI_SHAPE", "service/run/CaseResources.URI_SHAPE", "service/suite/SuiteCases.PROCEDURE_CODE");

    /**
     * Every pattern written inline in the main code: the source file, the call, the first
     * argument as written (white space outside strings removed), and the patterns it can be.
     * Where the argument is built at run time from a value, the pattern given is what it becomes
     * for a value of the usual shape; what is quoted with Pattern.quote is a literal.
     */
    static final List<Inline> INLINE = List.of(
            inline("client/crd/OnyxCrdConventions.java", "replaceAll", "[^a-z0-9]"),
            inline("client/dtr/OnyxDtrConventions.java", "replaceAll", "/+$"),
            inline("client/dtr/OnyxDtrConventions.java", "replaceAll", "[^a-z0-9]"),
            inline("client/lookup/LookupOutcomes.java", "replaceAll", "[.\\s]+$"),
            inline("client/lookup/MemberResponseMapping.java", "replaceAll", "[^a-z0-9]"),
            inline("client/lookup/ProviderResponseMapping.java", "replaceAll", "[^a-z0-9]"),
            inline("client/lookup/Spelling.java", "split", "\\."),
            inline("client/pas/OnyxPasConventions.java", "split", "[?#]"),
            inline("client/pas/OnyxPasConventions.java", "split", "/"),
            inline("client/support/Exchanges.java", "Pattern.compile", "(?i)charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)"),
            inline("model/DelegatedVendors.java", "replaceAll", "[^a-z0-9]"),
            inline("model/EpaOperation.java", "replaceAll", "[\\s-]+"),
            inline("model/Expectations.java", "split", "[;\\r\\n]"),
            inline("model/Expectations.java", "replaceAll", "[^A-Z0-9]+"),
            built("model/RunRecord.java", "matches", "\"\\\\(\"+java.util.regex.Pattern.quote(what)+\", \\\\d{1,9} characters\\\\)\"",
                    "\\(" + Pattern.quote("not a code") + ", \\d{1,9} characters\\)"),
            inline("model/RunRecord.java", "replaceAll", "[^A-Za-z0-9]"),
            inline("model/RunRecord.java", "split", " "),
            built("model/RunRecord.java", "matches", "NOT_A_URL", "\\(not a url, \\d{1,9} characters\\)"),
            inline("model/RunRecord.java", "split", "/"),
            inline("model/RunRecord.java", "split", ";"),
            inline("postman/Ids.java", "replaceAll", "[^a-z0-9]+"),
            inline("postman/Ids.java", "replaceAll", "^-+|-+$"),
            inline("postman/Ids.java", "replaceAll", "-+$"),
            inline("postman/OperationDetector.java", "replaceAll", "[^a-z0-9]"),
            inline("postman/OperationDetector.java", "split", "/"),
            inline("postman/UrlNormal.java", "replaceFirst", "^0+(?=.)"),
            inline("postman/UrlNormal.java", "split", "/"),
            inline("postman/UrlNormal.java", "matches", "[0-9.]+"),
            inline("postman/UrlNormal.java", "split", "\\."),
            inline("postman/UrlNormal.java", "split", "[/\\\\;]"),
            inline("service/cases/CaseFaults.java", "matches", "[a-z]{1,20}"),
            inline("service/cases/ChainExpectation.java", "split", "[;\\r\\n]"),
            inline("service/cases/DtrCaseStep.java", "split", "\\s+"),
            inline("service/cases/OutcomeCatalogue.java", "split", "[;\\r\\n]"),
            inline("service/cases/OutcomeCatalogue.java", "replaceAll", "[^A-Z0-9]+"),
            inline("service/codelist/JsonCodeList.java", "replaceFirst", "^The body"),
            inline("service/compare/CompareRules.java", "split", "\\."),
            inline("service/config/ConfigStore.java", "replaceAll", "[\\p{Cntrl}]"),
            inline("service/config/ConfigStoreGateway.java", "split", "&"),
            inline("service/config/ConfigStoreGateway.java", "replaceAll", "/+$"),
            inline("service/config/ConfigStoreGateway.java", "matches", "[A-Z]{1,12}"),
            inline("service/config/MemberDataNames.java", "split", "(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+"),
            inline("service/config/ProductionDestinations.java", "split", "(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+"),
            inline("service/config/ProductionDestinations.java", "matches", "[A-Za-z][A-Za-z0-9+.-]*"),
            inline("service/crd/CrdRequestGlossary.java", "split", "\\."),
            inline("service/crd/CrdResponseMapper.java", "replaceAll", "[^a-z]"),
            built("service/dtr/DtrAnswers.java", "matches", "DATE", "\\d{4}(-\\d{2}(-\\d{2})?)?"),
            built("service/dtr/DtrAnswers.java", "matches", "DATE_TIME",
                    "\\d{4}(-\\d{2}(-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?)?)?)?"),
            inline("service/dtr/DtrAnswers.java", "matches", "\\d{2}:\\d{2}"),
            built("service/dtr/DtrAnswers.java", "matches", "TIME", "\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?"),
            inline("service/dtr/DtrProfileCheck.java", "split", " "),
            inline("service/dtr/DtrResponseShape.java", "split", ","),
            inline("service/dtr/DtrService.java", "replaceAll", "/+$"),
            built("service/dtr/EnableWhenEvaluator.java", "matches", "DATE", "\\d{4}(-\\d{2}(-\\d{2})?)?"),
            inline("service/ledger/JsonlRunLedger.java", "replaceAll", "[\\s_.\\-\\p{Pd}\\p{Cf}]"),
            inline("service/lookup/ProviderLookupService.java", "matches", "\\d{10}"),
            inline("service/pas/NotificationReceiver.java", "matches", "\\(not recorded, \\d+ characters\\)"),
            inline("service/pas/PasRequestBuilder.java", "matches", "[A-TV-Z][0-9][0-9AB](\\.?[0-9A-TV-Z]{1,4})?"),
            inline("service/pas/PasRequestBuilder.java", "replaceAll", "/+$"),
            inline("service/pas/PasResources.java", "replaceAll", "[^a-z0-9]"),
            inline("service/pas/PasResources.java", "matches", "\\d{5}"),
            inline("service/pas/PasResources.java", "matches", "\\d{4}[FTU]"),
            inline("service/pas/PasResources.java", "matches", "[A-V]\\d{4}"),
            inline("service/pas/SubscriptionRequestBuilder.java", "matches", "\\d{1,9}"),
            inline("service/pas/SubscriptionRequestBuilder.java", "split", "&"),
            inline("service/run/CaseResources.java", "matches", "\\d{5}"),
            inline("service/run/CaseResources.java", "matches", "\\d{4}[FTU]"),
            inline("service/run/CaseResources.java", "matches", "[A-V]\\d{4}"),
            inline("service/run/CaseResources.java", "replaceAll", "[^a-z0-9]"),
            inline("service/run/MemberInText.java", "Pattern.compile", "\\d+"),
            inline("service/run/MemberInText.java", "replaceAll", "[^A-Za-z0-9]"),
            inline("service/run/MemberInText.java", "split", "[\\s\\-]+"),
            built("service/run/MemberInText.java", "Pattern.compile",
                    "\"(?<![\\\\p{L}\\\\p{N}])\"+Pattern.quote(sought)+\"(?![\\\\p{L}\\\\p{N}])\"",
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote("S123456789") + "(?![\\p{L}\\p{N}])"),
            built("service/run/MemberInText.java", "Pattern.compile",
                    "\"(?<![\\\\p{L}\\\\p{N}])\"+Pattern.quote(one)+between+Pattern.quote(other)+\"(?![\\\\p{L}\\\\p{N}])\"",
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote("Given") + "[\\s,]+" + Pattern.quote("Family") + "(?![\\p{L}\\p{N}])"),
            built("service/run/MemberInText.java", "Pattern.compile", "\"(?<!\\\\d)\"+Pattern.quote(form)+\"(?!\\\\d)\"",
                    "(?<!\\d)" + Pattern.quote("2026-01-02") + "(?!\\d)"),
            inline("service/run/MemberInText.java", "replaceAll", "\\s+"),
            built("service/run/MemberInText.java", "Pattern.compile", "\"(?<![\\\\p{L}\\\\p{N}])\"+Pattern.quote(start)",
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote("S1234")),
            inline("service/run/RunInputs.java", "split", "[;\\r\\n]"),
            inline("service/scenario/ScenarioRepository.java", "replaceFirst", "^The body"),
            inline("service/suite/SuiteColumns.java", "replaceAll", "[^a-z0-9]"),
            inline("service/suite/SuiteService.java", "replaceAll", "\\p{Cntrl}"),
            inline("web/ApiExceptionHandler.java", "split", "/"));

    /** One inline call: where, which call, its first argument as written, and the patterns it can be. */
    record Inline(String file, String call, String argument, List<String> patterns) {
    }

    /** A call whose first argument is one string literal, which is the pattern. */
    static Inline inline(String file, String call, String regex) {
        return new Inline(file, call, "\"" + regex.replace("\\", "\\\\").replace("\"", "\\\"") + "\"", List.of(regex));
    }

    /** A call whose pattern is a constant or is built at run time. */
    static Inline built(String file, String call, String argument, String... patterns) {
        return new Inline(file, call, argument, List.of(patterns));
    }

    // ------------------------------------------------------------------------------------------
    // The guard
    // ------------------------------------------------------------------------------------------

    @Test
    void noPatternOfTheMainCodeIsEvaluatedByRecursionOrRunsAwayOnALongText() throws Exception {
        Map<String, Pattern> all = new LinkedHashMap<>(staticPatterns());
        for (Inline site : INLINE) {
            for (String regex : site.patterns()) {
                all.putIfAbsent(site.file().replace(".java", "") + " " + site.call() + " " + regex, Pattern.compile(regex));
            }
        }
        assertThat(all).as("patterns found").hasSizeGreaterThan(150);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Pattern> each : all.entrySet()) {
            for (String loop : recursiveRepetitions(each.getValue())) {
                problems.add(each.getKey() + " /" + each.getValue().pattern() + "/: " + loop);
            }
        }
        for (Map.Entry<String, Pattern> each : all.entrySet()) {
            boolean wholeValue = WHOLE_VALUE_ONLY.contains(each.getKey());
            for (String failure : hostileRuns(each.getValue(), wholeValue)) {
                problems.add(each.getKey() + " /" + each.getValue().pattern() + "/: " + failure);
            }
        }
        assertThat(problems).as("patterns evaluated by recursion, or slower than the bound on a long text").isEmpty();
    }

    /**
     * The guard has teeth: the three patterns that threw on upload before this guard existed, kept
     * here as they were written, each fail both checks.
     */
    @Test
    void theGuardFailsOnThePatternsThatThrewOnUpload() throws Exception {
        Map<String, String> old = Map.of(
                "LiteralCredentials.JSON_MEMBER", "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                "ProductionSigns.BARE_HOST", "[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d+)?(/.*)?",
                "PostmanParser.VERSION", "v\\d+(?:\\.\\d+)*");
        for (Map.Entry<String, String> each : old.entrySet()) {
            Pattern pattern = Pattern.compile(each.getValue());
            assertThat(recursiveRepetitions(pattern)).as(each.getKey()).isNotEmpty();
            // The shaped texts reach the repetition each of these recursed on; the runs of one
            // character would only show the quadratic find of BARE_HOST, five seconds at a time.
            List<Text> shaped = texts(pattern.pattern()).stream().filter(text -> text.length() == SHAPED).toList();
            assertThat(run(pattern, shaped, true)).as(each.getKey()).anySatisfy(failure -> assertThat(failure).contains("StackOverflowError"));
        }
    }

    /**
     * The bound has teeth too. java.util.regex remembers where an unbounded repetition of a group
     * has already failed, but not a bounded one, so this pattern passes the check of the compiled
     * form (it repeats at most 90 times) and backtracks exponentially: about 0.3 seconds on 30
     * characters, longer than anyone would wait on 60. It is stopped at the bound and reported.
     */
    @Test
    void aPatternThatBacktracksExponentiallyIsStoppedAtTheBoundAndReported() throws Exception {
        long start = System.nanoTime();
        Pattern exponential = Pattern.compile("(?:a|aa){1,90}b");
        assertThat(recursiveRepetitions(exponential)).isEmpty();
        List<String> failures = run(exponential, List.of(new Text("sixty of \"a\"", "", "a", 60)), false);
        assertThat(failures).containsExactly("matches on sixty of \"a\": over the bound of 5 seconds");
        assertThat(System.nanoTime() - start).isLessThan(3 * BOUND_NANOS);
    }

    // ------------------------------------------------------------------------------------------
    // Finding every pattern
    // ------------------------------------------------------------------------------------------

    /** Every Pattern a static field of a main class holds, directly or inside a collection, a map, an entry or an array. */
    static Map<String, Pattern> staticPatterns() throws IOException, URISyntaxException {
        Path classes = Path.of(PaveApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names;
        try (Stream<Path> files = Files.walk(classes)) {
            names = files.map(file -> classes.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".class")).map(name -> name.substring(0, name.length() - ".class".length())).sorted().toList();
        }
        assertThat(names).as("main classes under " + classes).hasSizeGreaterThan(300);
        Map<String, Pattern> found = new TreeMap<>();
        Map<String, Integer> perSource = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name.replace('/', '.'), false, RegexStackGuardTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                unreadable.add(name);
                continue;
            }
            String source = name.startsWith(ROOT_PACKAGE) ? name.substring(ROOT_PACKAGE.length()).replaceAll("\\$.*", "") : name;
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || field.getType() == String.class
                        || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException | LinkageError e) {
                    unreadable.add(name + "." + field.getName());
                    continue;
                }
                int before = found.size();
                collect(source + "." + field.getName(), value, found, Collections.newSetFromMap(new IdentityHashMap<>()));
                perSource.merge(source + ".java", found.size() - before, Integer::sum);
            }
        }
        assertThat(unreadable).as("classes or static fields this test could not read").isEmpty();

        // Each Pattern.compile that declares a static field is one pattern reflection must have
        // reached. Fewer means a pattern held where reflection cannot see it, which this test
        // would then not check.
        Map<String, Integer> declared = new TreeMap<>();
        for (SourceScan.Site site : SourceScan.all(SOURCES.resolve(ROOT_PACKAGE))) {
            if (site.declaresStaticField()) {
                declared.merge(site.file(), 1, Integer::sum);
            }
        }
        declared.forEach((file, count) -> assertThat(perSource.getOrDefault(file, 0))
                .as("static patterns reflection reached in " + file + ", against the Pattern.compile calls that declare a static field")
                .isGreaterThanOrEqualTo(count));
        return found;
    }

    private static void collect(String name, Object value, Map<String, Pattern> found, Set<Object> seen) {
        if (value == null || !seen.add(value)) {
            return;
        }
        if (value instanceof Pattern pattern) {
            found.put(name, pattern);
        } else if (value instanceof Map<?, ?> map) {
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collect(name + "[key " + i + "]", entry.getKey(), found, seen);
                collect(name + "[value " + i++ + "]", entry.getValue(), found, seen);
            }
        } else if (value instanceof Map.Entry<?, ?> entry) {
            collect(name + "[key]", entry.getKey(), found, seen);
            collect(name + "[value]", entry.getValue(), found, seen);
        } else if (value instanceof Collection<?> items) {
            int i = 0;
            for (Object item : items) {
                collect(name + "[" + i++ + "]", item, found, seen);
            }
        } else if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                collect(name + "[" + i + "]", Array.get(value, i), found, seen);
            }
        }
    }

    @Test
    void everyInlinePatternOfTheMainCodeIsNamedInTheList() throws IOException {
        Set<String> inSource = new TreeSet<>();
        for (SourceScan.Site site : SourceScan.all(SOURCES.resolve(ROOT_PACKAGE))) {
            if (!site.declaresStaticField()) {
                inSource.add(site.file() + " " + site.call() + " " + site.argument());
            }
        }
        Set<String> listed = new TreeSet<>();
        INLINE.forEach(site -> listed.add(site.file() + " " + site.call() + " " + site.argument()));
        Set<String> unlisted = new TreeSet<>(inSource);
        unlisted.removeAll(listed);
        Set<String> gone = new TreeSet<>(listed);
        gone.removeAll(inSource);
        assertThat(unlisted).as("inline patterns in the main code that INLINE does not name: add each, with the pattern it can be").isEmpty();
        assertThat(gone).as("entries of INLINE the main code no longer holds").isEmpty();
        assertThat(inSource).hasSizeGreaterThan(70);
    }

    /** The scan itself: what it counts as a pattern, and what it leaves out. */
    @Test
    void theSourceScanFindsEachKindOfCallAndSkipsCommentsStringsAndMatcherMethods() {
        String source = """
                class X {
                    private static final Pattern A = Pattern.compile("a+");
                    // text.replaceAll("in a comment", "")
                    /* text.split("in a block comment") */
                    String s = "text.matches(\\"in a string\\")";
                    void m(String text) {
                        text.replaceAll("[0-9]+", "");
                        text.replaceFirst( "^x" ,
                            "");
                        text.split(",", -1);
                        text.matches(DATE);
                        A.split(text);
                        A.matcher(text).replaceAll("");
                        matcher.matches();
                        policy.matches(one, other);
                        Pattern.compile("(?<!\\\\d)" + Pattern.quote(form) + "(?!\\\\d)").matcher(text).find();
                    }
                }
                """;
        assertThat(SourceScan.sites("X.java", source)).extracting(site -> site.call() + " " + site.argument() + " " + site.declaresStaticField())
                .containsExactly(
                        "Pattern.compile \"a+\" true",
                        "replaceAll \"[0-9]+\" false",
                        "replaceFirst \"^x\" false",
                        "split \",\" false",
                        "matches DATE false",
                        "Pattern.compile \"(?<!\\\\d)\"+Pattern.quote(form)+\"(?!\\\\d)\" false");
    }

    // ------------------------------------------------------------------------------------------
    // The compiled form
    // ------------------------------------------------------------------------------------------

    /**
     * Each repetition in the compiled form that java.util.regex evaluates by calling itself once per
     * iteration, and that may iterate more than {@link #MOST_RECURSIVE_ITERATIONS} times. Those are
     * the Loop and LazyLoop nodes: what a greedy or lazy repetition of a group that is not of one
     * fixed width compiles to. A possessive repetition and one of a fixed width compile to nodes
     * that loop.
     */
    static List<String> recursiveRepetitions(Pattern pattern) throws ReflectiveOperationException {
        Class<?> node = Class.forName("java.util.regex.Pattern$Node");
        Field matchRoot = Pattern.class.getDeclaredField("matchRoot");
        try {
            matchRoot.setAccessible(true);
        } catch (InaccessibleObjectException e) {
            throw new IllegalStateException("This test reads the compiled form of each pattern and needs java.util.regex opened to it. "
                    + "Run it through Gradle, whose test task passes --add-opens java.base/java.util.regex=ALL-UNNAMED, or add that "
                    + "argument to the launch.", e);
        }
        List<String> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> next = new ArrayDeque<>();
        next.push(matchRoot.get(pattern));
        while (!next.isEmpty()) {
            Object at = next.pop();
            if (!seen.add(at)) {
                continue;
            }
            String kind = at.getClass().getSimpleName();
            if (kind.equals("Loop") || kind.equals("LazyLoop")) {
                int most = intField(at, "cmax");
                if (most > MOST_RECURSIVE_ITERATIONS) {
                    found.add("a " + (kind.equals("Loop") ? "greedy" : "lazy") + " repetition of a group, evaluated by one call per iteration, "
                            + "up to " + (most == Integer.MAX_VALUE ? "any number of" : String.valueOf(most)) + " iterations");
                }
            }
            for (Class<?> type = at.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (node.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(at);
                        if (value != null) {
                            next.push(value);
                        }
                    } else if (field.getType().isArray() && node.isAssignableFrom(field.getType().getComponentType())) {
                        field.setAccessible(true);
                        Object values = field.get(at);
                        for (int i = 0; values != null && i < Array.getLength(values); i++) {
                            Object value = Array.get(values, i);
                            if (value != null) {
                                next.push(value);
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static int intField(Object at, String name) throws ReflectiveOperationException {
        for (Class<?> type = at.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(at);
            } catch (NoSuchFieldException e) {
                // declared higher up
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ------------------------------------------------------------------------------------------
    // The hostile texts
    // ------------------------------------------------------------------------------------------

    /** One of each class of characters: letters of both cases, a digit, white space, the characters JSON and urls are built of. */
    private static final String REPRESENTATIVES = "aA1 \n\"\\{}.\u00e9";

    /** A character outside the basic plane, two chars long: where a repetition of varying width would show. */
    private static final String SUPPLEMENTARY = new String(Character.toChars(0x1F600));

    /** Quotes, backslashes, braces, dots, digits and white space, alternated with one another. */
    private static final String ALTERNATED = "\"\\{}.1 ";

    /** A hostile text, built only when it is run so that a pattern's texts are never all held at once. */
    record Text(String label, String start, String unit, int length) {

        String text() {
            return start + unit.repeat(Math.max(1, (length - start.length()) / unit.length()));
        }
    }

    static List<Text> texts(String regex) {
        List<Text> out = new ArrayList<>();
        Set<String> singles = new LinkedHashSet<>();
        REPRESENTATIVES.chars().forEach(c -> singles.add(String.valueOf((char) c)));
        singles.add(SUPPLEMENTARY);
        regex.chars().filter(c -> c > ' ' && c < 127 && !Character.isLetterOrDigit(c)).forEach(c -> singles.add(String.valueOf((char) c)));
        for (String single : singles) {
            out.add(new Text("a million of " + shown(single), "", single, MILLION));
        }
        for (int i = 0; i < ALTERNATED.length(); i++) {
            for (int j = i + 1; j < ALTERNATED.length(); j++) {
                String pair = "" + ALTERNATED.charAt(i) + ALTERNATED.charAt(j);
                out.add(new Text("a million of " + shown(pair) + " alternated", "", pair, MILLION));
            }
        }
        // The first stretches are enough to reach every repetition of the patterns this tool holds;
        // the compiled form is checked for the rest.
        List<String> starts = literalStretches(regex).stream().limit(STRETCHES).toList();
        Set<String> pieces = new LinkedHashSet<>(singles);
        pieces.addAll(starts);
        pieces.remove("");
        for (String start : starts) {
            for (String lead : List.of("a", "A", "1", " ", "\\")) {
                for (String piece : pieces) {
                    if (!piece.equals(lead)) {
                        String unit = lead + piece;
                        out.add(new Text(shown(start) + " then " + shown(unit) + " repeated", start, unit, SHAPED));
                    }
                }
            }
        }
        return out;
    }

    /** The empty start, then each stretch of the pattern that stands for itself, such as P32H- or ", " or "(not a code, ". */
    static List<String> literalStretches(String regex) {
        Set<String> out = new LinkedHashSet<>();
        out.add("");
        StringBuilder stretch = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (inClass) {
                if (c == '\\') {
                    i++;
                } else if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '\\' && i + 1 < regex.length() && !Character.isLetterOrDigit(regex.charAt(i + 1))) {
                stretch.append(regex.charAt(++i));
                continue;
            }
            if (c != '\\' && "[](){}.*+?^$|".indexOf(c) < 0) {
                stretch.append(c);
                continue;
            }
            if (c == '\\') {
                i++;
            }
            inClass = c == '[';
            if (!stretch.isEmpty()) {
                out.add(stretch.toString());
                stretch.setLength(0);
            }
        }
        if (!stretch.isEmpty()) {
            out.add(stretch.toString());
        }
        return new ArrayList<>(out);
    }

    private static String shown(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.chars().forEach(c -> out.append(c < ' ' || c > '~' ? String.format("\\u%04x", c) : String.valueOf((char) c)));
        return out.append('"').toString();
    }

    static List<String> hostileRuns(Pattern pattern, boolean wholeValue) throws InterruptedException {
        return run(pattern, texts(pattern.pattern()), wholeValue);
    }

    /** Runs matches and find over each text in a thread of {@link #STACK_BYTES}, and says what overflowed or ran over the bound. */
    static List<String> run(Pattern pattern, List<Text> texts, boolean wholeValue) throws InterruptedException {
        List<String> failures = new ArrayList<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread runner = new Thread(null, () -> {
            try {
                for (Text text : texts) {
                    String built = text.text();
                    for (String operation : List.of("matches", "find")) {
                        if (operation.equals("find") && wholeValue) {
                            continue;
                        }
                        String failure = once(pattern, built, operation);
                        if (failure != null) {
                            failures.add(operation + " on " + text.label() + ": " + failure);
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                unexpected.set(e);
            }
        }, "regex-guard", STACK_BYTES);
        runner.start();
        runner.join();
        if (unexpected.get() != null) {
            throw new IllegalStateException(unexpected.get());
        }
        return failures;
    }

    private static String once(Pattern pattern, String text, String operation) {
        long start = System.nanoTime();
        try {
            Matcher matcher = pattern.matcher(new Bounded(text, start + BOUND_NANOS));
            if (operation.equals("matches")) {
                matcher.matches();
            } else {
                while (matcher.find()) {
                    // every match, as replaceAll and split read them
                }
            }
            return null;
        } catch (StackOverflowError e) {
            return "StackOverflowError";
        } catch (OverTheBound e) {
            return "over the bound of " + BOUND_NANOS / 1_000_000_000L + " seconds";
        }
    }

    private static final class OverTheBound extends RuntimeException {
        OverTheBound() {
            super(null, null, false, false);
        }
    }

    /** A text that stops a matcher once the bound has passed, so a pattern that would run for ever is reported and not waited for. */
    private static final class Bounded implements CharSequence {
        private final String text;
        private final long deadline;
        private int reads;

        Bounded(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
                throw new OverTheBound();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.substring(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    // ------------------------------------------------------------------------------------------
    // The source scan
    // ------------------------------------------------------------------------------------------

    /** Every call in the main sources that takes a regular expression, outside comments and strings. */
    static final class SourceScan {

        /**
         * @param file                the source file, under the root package
         * @param call                Pattern.compile, Pattern.matches, matches, replaceAll, replaceFirst or split
         * @param argument            the first argument as written, white space outside strings removed
         * @param declaresStaticField true for a Pattern.compile that initialises a static field, which reflection reads
         */
        record Site(String file, String call, String argument, boolean declaresStaticField) {
        }

        private static final List<String> CALLS = List.of("Pattern.compile(", "Pattern.matches(", ".matches(", ".replaceAll(",
                ".replaceFirst(", ".split(");

        private SourceScan() {
        }

        static List<Site> all(Path root) throws IOException {
            List<Site> out = new ArrayList<>();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(file -> file.toString().endsWith(".java")).sorted().toList();
            }
            assertThat(files).as("main sources under " + root.toAbsolutePath()).hasSizeGreaterThan(300);
            for (Path file : files) {
                out.addAll(sites(root.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8)));
            }
            return out;
        }

        static List<Site> sites(String file, String text) {
            String code = withoutComments(text);
            List<Site> out = new ArrayList<>();
            int i = 0;
            while (i < code.length()) {
                char c = code.charAt(i);
                if (code.startsWith("\"\"\"", i)) {
                    int end = code.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? code.length() : end + 3;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    i = endOfLiteral(code, i);
                    continue;
                }
                String call = null;
                for (String each : CALLS) {
                    if (code.startsWith(each, i) && (each.charAt(0) == '.' || i == 0 || code.charAt(i - 1) == '.'
                            || !Character.isJavaIdentifierPart(code.charAt(i - 1)))) {
                        call = each;
                        break;
                    }
                }
                if (call == null) {
                    i++;
                    continue;
                }
                int open = i + call.length() - 1;
                List<String> arguments = arguments(code, open);
                String receiver = receiver(code, i);
                boolean takesPattern = switch (call) {
                    // matches() of a Matcher has no argument, and a method of two is not String's.
                    case ".matches(" -> arguments.size() == 1 && !receiver.equals("Pattern");
                    // replaceAll and replaceFirst of a Matcher take the replacement only.
                    case ".replaceAll(", ".replaceFirst(" -> arguments.size() == 2;
                    // split of a constant is Pattern.split, whose pattern is the static field itself.
                    case ".split(" -> !receiver.matches("[A-Z][A-Z0-9_]*");
                    default -> true;
                };
                if (takesPattern && !arguments.isEmpty()) {
                    String name = call.startsWith("Pattern.") ? call.substring(0, call.length() - 1) : call.substring(1, call.length() - 1);
                    out.add(new Site(file, name, normal(arguments.get(0)), call.equals("Pattern.compile(") && declaresStatic(code, i)));
                }
                i = open + 1;
            }
            return out;
        }

        /** The source with every comment blanked out and every string kept. */
        static String withoutComments(String text) {
            StringBuilder out = new StringBuilder(text);
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (text.startsWith("//", i)) {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        out.setCharAt(i++, ' ');
                    }
                } else if (text.startsWith("/*", i)) {
                    int end = text.indexOf("*/", i + 2);
                    end = end < 0 ? text.length() : end + 2;
                    for (; i < end; i++) {
                        if (text.charAt(i) != '\n') {
                            out.setCharAt(i, ' ');
                        }
                    }
                } else if (text.startsWith("\"\"\"", i)) {
                    int end = text.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? text.length() : end + 3;
                } else if (c == '"' || c == '\'') {
                    i = endOfLiteral(text, i);
                } else {
                    i++;
                }
            }
            return out.toString();
        }

        private static int endOfLiteral(String text, int start) {
            char quote = text.charAt(start);
            int i = start + 1;
            while (i < text.length() && text.charAt(i) != quote) {
                i += text.charAt(i) == '\\' ? 2 : 1;
            }
            return i + 1;
        }

        private static String receiver(String code, int dot) {
            int end = dot;
            while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
                end--;
            }
            int start = end;
            while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
                start--;
            }
            return code.substring(start, end);
        }

        /** True when the statement the call stands in declares a static field, or assigns a constant, as a static block does. */
        private static boolean declaresStatic(String code, int at) {
            int start = at;
            int depth = 0;
            while (start > 0) {
                char c = code.charAt(start - 1);
                if (c == ')' || c == ']') {
                    depth++;
                } else if ((c == '(' || c == '[') && depth > 0) {
                    depth--;
                } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                    break;
                }
                start--;
            }
            String statement = code.substring(start, at).strip();
            return statement.matches("(?s)(?:(?:private|public|protected|final)\\s+)*static\\s.*")
                    || statement.matches("(?s)[A-Z][A-Z0-9_]*\\s*=\\s*(?:java\\.util\\.regex\\.)?");
        }

        private static List<String> arguments(String code, int open) {
            List<String> arguments = new ArrayList<>();
            int depth = 0;
            int start = open + 1;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '"' || c == '\'') {
                    i = endOfLiteral(code, i) - 1;
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) {
                        String last = code.substring(start, i).strip();
                        if (!last.isEmpty() || !arguments.isEmpty()) {
                            arguments.add(last);
                        }
                        return arguments;
                    }
                } else if (c == ',' && depth == 1) {
                    arguments.add(code.substring(start, i).strip());
                    start = i + 1;
                }
            }
            return arguments;
        }

        private static String normal(String argument) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < argument.length(); i++) {
                char c = argument.charAt(i);
                if (c == '"' || c == '\'') {
                    int end = endOfLiteral(argument, i);
                    out.append(argument, i, end);
                    i = end - 1;
                } else if (!Character.isWhitespace(c)) {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}
