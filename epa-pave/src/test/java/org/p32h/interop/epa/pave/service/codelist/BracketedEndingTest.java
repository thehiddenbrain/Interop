package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The loop that took the place of the pattern a header lost its bracketed ending by gives what
 * the pattern gave, and keeps going where the pattern gave out.
 */
class BracketedEndingTest {

    /** The pattern as it was written, with replaceFirst. */
    private static final Pattern OLD = Pattern.compile("(?:[(\\[][^()\\[\\]]*[)\\]])+$");

    @Test
    void theLoopGivesWhatThePatternGaveOnTheCasesAndOnRandomTexts() {
        List<String> cases = List.of("authrequired(y/n)", "authrequired[y/n](2026)", "code()", "pa(prior)required", "description(short",
                "notes)more", "(y/n)", "a(b]", "a[b)", "a(b)(c", "a)(b)", "x)(a)", "a(b)\n", "a(b)\r\n", "a(b)\r", "a(b)\u2028", "a(b)\n\n",
                "a(b)c(d)", "", ")", "]", "()", "a((b))", "a(b(c))", "a(b)x(c)");
        for (String text : cases) {
            assertThat(CodeListText.withoutBracketedEnding(text)).as(text).isEqualTo(OLD.matcher(text).replaceFirst(""));
        }
        List<String> pieces = List.of("(", ")", "[", "]", "a", "1", "/", "\n", "\r", "\u0085", "\u2028", " ", "()", "(a)", "[b]");
        Random random = new Random(20261121L);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = random.nextInt(10); n > 0; n--) {
                text.append(pieces.get(random.nextInt(pieces.size())));
            }
            String written = text.toString();
            assertThat(CodeListText.withoutBracketedEnding(written)).as(written).isEqualTo(OLD.matcher(written).replaceFirst(""));
        }
    }

    @Test
    void aHeaderOfAMillionBracketsIsReadInOnePassOnASmallStack() throws InterruptedException {
        String groups = "(a)".repeat(333_333);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread small = new Thread(null, () -> {
            try {
                result.set(new String[] {CodeListText.withoutBracketedEnding("header" + groups),
                        CodeListText.withoutBracketedEnding("header" + groups + "x")});
            } catch (Throwable e) {
                result.set(e);
            }
        }, "small-stack", 256 * 1024);
        small.start();
        small.join(10_000);
        assertThat(result.get()).isInstanceOf(String[].class);
        String[] both = (String[]) result.get();
        assertThat(both[0]).isEqualTo("header");
        assertThat(both[1]).hasSize("header".length() + groups.length() + 1);
    }
}
