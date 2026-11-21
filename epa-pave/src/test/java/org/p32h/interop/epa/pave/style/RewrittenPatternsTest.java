package org.p32h.interop.epa.pave.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each pattern rewritten so that java.util.regex no longer evaluates it by recursion gives the
 * same answer as before on every input: the old pattern is kept here as it was written, and both
 * are run on the cases of the existing tests and on a fixed-seed run of random texts built from
 * the pieces the pattern is about. Where a pattern is matched against a whole value, matches() and
 * its groups are compared; every match find() gives, with its groups, is compared as well.
 *
 * <p>LiteralCredentials no longer reads JSON members with a pattern; JsonMembersTest compares its
 * loop with the old pattern in the same way.
 *
 * <p>The long texts the old patterns threw on are not compared: there is no old answer to compare
 * with. {@link RegexStackGuardTest} runs the new patterns on those.
 */
class RewrittenPatternsTest {

    private static final int RANDOM_TEXTS = 100_000;

    private static final String OLD_SENTINEL = "P32H-[A-Z0-9]+(?:-[A-Z0-9]+)*";

    /** RunRecord's pieces as they were. Only SUBJECT and the canonical path changed; the rest is copied so the old patterns are whole. */
    private static final String TOKEN = "[A-Za-z0-9._\\-:/|+]{1,64}";
    private static final String NAME = "[A-Za-z0-9._-]{1,40}";
    private static final String ONE_SUBJECT = "(?:\\(not a code, \\d{1,9} characters\\)|[A-Za-z0-9._\\-/\\[\\]]{1,80})";
    private static final String OLD_SUBJECT = ONE_SUBJECT + "(?:, " + ONE_SUBJECT + ")*";
    private static final String OLD_CANONICAL = "(?i:https?://[A-Za-z0-9.\\-]{1,253}(?::\\d{1,5})?(?:/[A-Za-z0-9\\-._~:]*)*"
            + "|urn:[A-Za-z0-9][A-Za-z0-9\\-]{0,31}:[A-Za-z0-9\\-._:/]{1,400})(?:\\|[A-Za-z0-9.\\-_+]{1,64})?";
    private static final String NOT_A_URL = "\\(not a url, \\d{1,9} characters\\)";

    record Rewrite(String owner, String field, String old, int flags, List<String> pieces, List<String> cases) {

        @Override
        public String toString() {
            return owner.substring(owner.lastIndexOf('.') + 1) + "." + field;
        }
    }

    static Stream<Rewrite> rewrites() {
        List<String> hostPieces = List.of("a", "Z", "9", "-", ".", ":", "/", "8443", "prod", " ", "\n", "_", "x");
        List<String> hostCases = List.of("https://api.prod.example.invalid", "api.Production.example.invalid/fhir", "www.prod-payer.example.invalid",
                "https://{{prodHost}}/fhir", "product catalogue", "not prod", "api.example.invalid:8443/fhir", "localhost", "a.b", "a.b:",
                "a.b:80", "a.b:80/", "a.b:x/", "a..b", ".a.b", "a.b.", "a.b/c d", "a.b:8a", "a-b.c-d:1/x\ny");
        List<String> versionPieces = List.of("v", "1", "2", "0", ".", "x", "/", " ");
        List<String> versionCases = List.of("https://schema.getpostman.com/json/collection/v2.0.0/collection.json",
                "https://schema.getpostman.com/json/collection/v1.0.0/collection.json", "v2", "v2.", "v.2", "vv1.2.3");
        List<String> sentinelPieces = List.of("P32H-", "P32H", "-", "A", "Z", "0", "9", "a", " ", "--");
        List<String> sentinelCases = List.of("P32H-CRD-01", "P32H-NOMEM", "P32H-", "P32H--A", "P32H-A-", "P32H-a", "S123456789");
        List<String> subjectPieces = List.of("a", "1", ",", ", ", " ", "item ", "item 12", "12345", ":", "x");
        List<String> subjectCases = List.of("99213", "99213, 97110", "item 1", "item 1, 99213", "item 12345", "a,b", "a, ,b", ", a", "a, ",
                "a " + "b");
        List<String> entryPieces = List.of("a", "1", ".", ", ", ",", ": ", ":", "=", "(not a code, 12 characters)", "(no answer)", " ", "[0]",
                "/", "|", "x".repeat(81));
        List<String> entryCases = List.of("99213: coverage-info.covered=covered", "99213, 97110: pa-needed=auth-needed",
                "(not a code, 12 characters): covered=yes", "99213: (no answer)", "covered=covered", "covered=(not a code, 3 characters)",
                "99213: note=free text, with: punctuation", "a, b: c=d", "a, b=c: d=e", "a".repeat(81) + ": b=c");
        List<String> canonicalPieces = List.of("https://", "http://", "HTTPS://", "urn:", "a", "1", ".", "-", "/", ":", "|", "~", " ",
                "(not a url, 5 characters)", "?", "x".repeat(70));
        List<String> canonicalCases = List.of("http://example.org/fhir/Questionnaire/home-oxygen",
                "http://example.org/fhir/Questionnaire/home-oxygen|1.0.0 urn:oid:2.16.840.1.113883", "(not a url, 12 characters)",
                "https://example.org:8443/a/b/c https://example.org", "urn:uuid:not-a-uuid", "http://example.org/a?b", "http://example.org/a b");
        return Stream.of(
                new Rewrite("org.p32h.interop.epa.pave.service.config.ProductionSigns", "BARE_HOST",
                        "[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d+)?(/.*)?", 0, hostPieces, hostCases),
                new Rewrite("org.p32h.interop.epa.pave.postman.PostmanParser", "VERSION", "v\\d+(?:\\.\\d+)*", 0, versionPieces, versionCases),
                new Rewrite("org.p32h.interop.epa.pave.service.scenario.ScenarioRepository", "SENTINEL", OLD_SENTINEL, 0, sentinelPieces,
                        sentinelCases),
                new Rewrite("org.p32h.interop.epa.pave.service.ledger.TrackingPolicy", "SENTINEL", OLD_SENTINEL, 0, sentinelPieces,
                        sentinelCases),
                new Rewrite("org.p32h.interop.epa.pave.client.lookup.LookupFixtures", "SENTINEL", OLD_SENTINEL, 0, sentinelPieces,
                        sentinelCases),
                new Rewrite("org.p32h.interop.epa.pave.service.run.RunInputs", "SUBJECT",
                        "(?:item \\d{1,4}|[^\\s,]+)(?:, ?(?:item \\d{1,4}|[^\\s,]+))*", 0, subjectPieces, subjectCases),
                new Rewrite("org.p32h.interop.epa.pave.model.RunRecord", "CODE_ENTRY",
                        "(?:(" + OLD_SUBJECT + "): )?(" + NAME + ")=(" + TOKEN + ")", 0, entryPieces, entryCases),
                new Rewrite("org.p32h.interop.epa.pave.model.RunRecord", "NO_ANSWER_ENTRY",
                        "(" + OLD_SUBJECT + "): \\(no answer\\)", 0, entryPieces, entryCases),
                new Rewrite("org.p32h.interop.epa.pave.model.RunRecord", "NAMED_ENTRY",
                        "(?:(" + OLD_SUBJECT + "): )?(" + NAME + ")=(.*)", Pattern.DOTALL, entryPieces, entryCases),
                new Rewrite("org.p32h.interop.epa.pave.model.RunRecord", "QUESTIONNAIRES",
                        "(?:" + OLD_CANONICAL + "|" + NOT_A_URL + ")(?: (?:" + OLD_CANONICAL + "|" + NOT_A_URL + ")){0,19}", 0, canonicalPieces,
                        canonicalCases));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rewrites")
    void theRewrittenPatternAnswersAsTheOldOneDid(Rewrite rewrite) throws ReflectiveOperationException {
        Pattern now = current(rewrite);
        Pattern old = Pattern.compile(rewrite.old(), rewrite.flags());
        assertThat(now.pattern()).as("the pattern was rewritten").isNotEqualTo(old.pattern());
        assertThat(now.flags()).isEqualTo(old.flags());

        for (String text : rewrite.cases()) {
            assertThat(answer(now, text)).as(rewrite + " on a case of length " + text.length()).isEqualTo(answer(old, text));
        }
        Random random = new Random(20261121L);
        List<String> pieces = rewrite.pieces();
        for (int i = 0; i < RANDOM_TEXTS; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = random.nextInt(12); n > 0; n--) {
                text.append(pieces.get(random.nextInt(pieces.size())));
            }
            String written = text.toString();
            assertThat(answer(now, written)).as(rewrite + " on \"" + written + "\"").isEqualTo(answer(old, written));
        }
    }

    /** Whether the whole text matches and with which groups, then every match find gives and its groups. */
    private static String answer(Pattern pattern, String text) {
        StringBuilder out = new StringBuilder();
        Matcher whole = pattern.matcher(text);
        out.append(whole.matches() ? "matches" + groups(whole) : "no").append(" | ");
        Matcher each = pattern.matcher(text);
        while (each.find()) {
            out.append(each.start()).append('-').append(each.end()).append(groups(each)).append(';');
        }
        return out.toString();
    }

    private static String groups(Matcher matcher) {
        List<String> spans = new ArrayList<>();
        for (int g = 1; g <= matcher.groupCount(); g++) {
            spans.add(matcher.start(g) + "," + matcher.end(g));
        }
        return spans.toString();
    }

    private static Pattern current(Rewrite rewrite) throws ReflectiveOperationException {
        Field field = Class.forName(rewrite.owner()).getDeclaredField(rewrite.field());
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }
}
