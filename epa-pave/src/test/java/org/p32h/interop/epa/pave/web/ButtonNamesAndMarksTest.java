package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * A button names what it does: "Close preview", "Close without saving", "Discard changes",
 * "Dismiss this notice", never a bare "Close" or "Cancel" that says nothing of what it closes,
 * dismisses or stops (plan R-25). Read from every script and the page, because the build has no
 * browser: a button named by a bare word is a line a build can find, however it is built.
 */
class ButtonNamesAndMarksTest {

    private static final String BARE_WORDS = "(?:Close|Dismiss|Cancel|OK|Ok|Submit|Done|Yes|No)";
    private static final String QUOTE = "['\"`]";

    /** The ways a script names a button: the helper's label, or the last child of an el('button', ...). */
    private static final List<Pattern> BARE = List.of(
            Pattern.compile("\\b(?:button|copyButton)\\(\\s*" + QUOTE + BARE_WORDS + QUOTE),
            Pattern.compile("\\bel\\(\\s*['\"]button['\"][^;]*?,\\s*" + QUOTE + BARE_WORDS + QUOTE + "\\s*\\)"),
            Pattern.compile("(?i)<button\\b[^>]*>\\s*" + BARE_WORDS + "\\s*</button>"));

    @Test
    void noButtonIsNamedByABareWordThatDoesNotSayWhatItActsOn() throws IOException {
        List<String> found = new ArrayList<>();
        List<Source> sources = StaticSources.sources(".js", ".html");
        assertThat(sources).as("the scripts and the page are read").hasSizeGreaterThan(5);
        for (Source source : sources) {
            found.addAll(bareIn(source));
        }
        assertThat(found).as("buttons that do not say what they act on").isEmpty();
    }

    @Test
    void theRuleFindsABareNameHoweverTheButtonIsBuilt() {
        for (String line : List.of(
                "row.append(button('Dismiss', () => row.remove()));",
                "const close = button(\"Close\", () => dialog.close(), {small: true});",
                "footer: dialog => [button(`Cancel`, () => dialog.close())]",
                "el('button', {type: 'button', on: {click: done}}, 'Done')",
                "<button type=\"button\">Close</button>")) {
            assertThat(bareIn(new Source("js/x.js", line))).as(line).hasSize(1);
        }
        for (String line : List.of(
                "button('Dismiss this notice', () => row.remove())",
                "button('Close preview', () => dialog.close(), {small: true})",
                "el('button', {type: 'button'}, 'Close without saving')",
                "<button type=\"button\">Discard changes</button>")) {
            assertThat(bareIn(new Source("js/x.js", line))).as(line).isEmpty();
        }
    }

    private static List<String> bareIn(Source source) {
        List<String> found = new ArrayList<>();
        for (Pattern pattern : BARE) {
            Matcher matcher = pattern.matcher(source.text());
            while (matcher.find()) {
                found.add(source.name() + ": " + matcher.group());
            }
        }
        return found;
    }
}
