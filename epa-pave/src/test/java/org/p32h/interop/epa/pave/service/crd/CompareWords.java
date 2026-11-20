package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;

/**
 * What every sentence of a comparison is held to, for the tests of the comparison.
 *
 * <p>A tester repeats these sentences to a vendor. So none of them may say what the service
 * expects or which request is right, since this tool does not know; none may quote a member's
 * name or date of birth, which belong in the value fields and nowhere else; and all of them
 * follow the house style the build enforces on the source tree, which a sentence put together at
 * run time would otherwise escape.
 */
final class CompareWords {

    /** Words that would turn a difference into a verdict. */
    private static final List<Pattern> VERDICTS = List.of(word("must"), word("requires"), word("should send"), word("is wrong"),
            word("is correct"));

    // The house style rules of build.gradle, put together from pieces as they are there, so that
    // this file does not hold the text it looks for.
    private static final List<Pattern> HOUSE_STYLE = List.of(
            Pattern.compile(range(0x2012, 0x2015)),
            Pattern.compile(range(0x2018, 0x201F)),
            Pattern.compile("(?i)\\b(?:" + String.join("|", "seam" + "less\\w*", "ro" + "bust\\w*", "lever" + "ag\\w*",
                    "del" + "v(?:e[sd]?|ing)\\b", "sle" + "ek\\w*", "cutting" + "[ -]" + "edge", "best" + "[ -]in[ -]" + "class") + ")"),
            Pattern.compile("(?i)" + "letter" + "-" + "spacing"),
            Pattern.compile("(?i)(?<![a-z0-9])" + "p" + "32" + "(?!h)"),
            Pattern.compile("(?i)" + "x" + "engine"));

    private CompareWords() {
    }

    /** A character class from two code points, so no character outside ASCII is written in this file. */
    private static String range(int from, int to) {
        return "[" + Character.toString(from) + "-" + Character.toString(to) + "]";
    }

    private static Pattern word(String words) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(words) + "\\b");
    }

    /** One sentence, or several: no verdict in it, and written in the house style. */
    static void assertSaysNothingAboutWhatIsRight(String text) {
        for (Pattern verdict : VERDICTS) {
            assertThat(verdict.matcher(text).find()).as("\"" + text + "\" says what a service expects or which request is right: " + verdict).isFalse();
        }
        for (Pattern rule : HOUSE_STYLE) {
            assertThat(rule.matcher(text).find()).as("\"" + text + "\" breaks the house style: " + rule).isFalse();
        }
        // Plain ASCII throughout, which is what the screen and a pasted message to a vendor both show unchanged.
        assertThat(text.chars().allMatch(c -> c >= 32 && c < 127)).as("\"" + text + "\" is plain ASCII").isTrue();
    }

    /** Every sentence a comparison says in its own words: the headline, the reason, the notes, and what it says of each value and difference. */
    static List<String> prose(RequestComparison comparison) {
        List<String> out = new ArrayList<>();
        out.add(comparison.headline());
        add(out, comparison.whyNot());
        add(out, comparison.caveat());
        add(out, comparison.maskedNote());
        out.addAll(comparison.notes());
        comparison.caseSource().forEach(value -> {
            add(out, value.label());
            add(out, value.note());
        });
        comparison.notFound().forEach(value -> {
            add(out, value.label());
            add(out, value.lookedIn());
            add(out, value.note());
        });
        comparison.differences().forEach(difference -> {
            add(out, difference.what());
            out.add(difference.explanation());
            out.add(difference.kindLabel());
        });
        comparison.counts().forEach(count -> {
            out.add(count.label());
            out.add(count.description());
        });
        return out;
    }

    private static void add(List<String> out, String text) {
        if (text != null) {
            out.add(text);
        }
    }

    /**
     * Holds a whole comparison to the rule, and checks that none of its sentences quotes one of
     * the given values. The values are the names, dates of birth and addresses of the fixture.
     */
    static void assertWords(RequestComparison comparison, String... memberData) {
        List<String> prose = prose(comparison);
        assertThat(prose).isNotEmpty();
        for (String sentence : prose) {
            assertSaysNothingAboutWhatIsRight(sentence);
            for (String value : memberData) {
                assertThat(sentence).as("no sentence quotes member data").doesNotContain(value);
            }
        }
    }
}
