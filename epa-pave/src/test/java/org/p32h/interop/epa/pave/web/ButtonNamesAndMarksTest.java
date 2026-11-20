package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A button names what it does, and a step nobody answered is drawn as that wherever a run is
 * drawn. Both are read from the source, because the build has no JavaScript runner: a button
 * named by a bare word that says nothing of what it acts on, and a list of runs that drops who
 * answered, are each a line a build can find.
 */
class ButtonNamesAndMarksTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    /** A button whose whole name is one of these says nothing of what it closes, dismisses or stops. */
    private static final Pattern BARE = Pattern.compile("button\\(\\s*'(Close|Dismiss|Cancel|OK|Ok|Submit|Done)'");

    @Test
    void noButtonIsNamedByABareWordThatDoesNotSayWhatItActsOn() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(JS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js")).sorted().toList()) {
                Matcher matcher = BARE.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(file.getFileName() + ": " + matcher.group());
                }
            }
        }
        assertThat(found).as("buttons that do not say what they act on").isEmpty();
    }

    @Test
    void theRuleFindsABareName() {
        assertThat(BARE.matcher("row.append(button('Dismiss', () => row.remove()));").find()).isTrue();
        assertThat(BARE.matcher("button('Dismiss this notice', () => row.remove())").find()).isFalse();
    }

    @Test
    void theListOfChainsDrawsTheLatestRunWithWhoAnsweredIt() throws IOException {
        // Without who answered, a step done inside this tool is drawn like an answer from the payer.
        assertThat(Files.readString(JS.resolve("tab-history.js"))).contains("latest.tone, latest.answeredBy)");
    }
}
