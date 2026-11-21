package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Two rules of the screens that the audits asked for and that no server test can see.
 * <ul>
 *   <li>Every field whose text the ledger keeps as typed (the note and the tester reference of a
 *       run, on every form that sends one, and on the note editor of a run) carries the one hint,
 *       in the same words everywhere, that member data must not be typed into it. The server
 *       refuses only the member data of the request it goes with, so the rule is said at the field.</li>
 *   <li>The frame hands a change of the write access that comes without a change of the
 *       configuration to the tabs, so that a lock pressed in another browser is redrawn on every
 *       tab at once, and the tabs that state the write access listen for it.</li>
 * </ul>
 * The last test hands the first rule a field that breaks it, so a rule that has stopped looking fails.
 */
class StaticLedgerTextTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    /** The scripts with a form whose note or tester reference goes to the ledger. History's run filter is not one. */
    private static final List<String> FORMS = List.of("tab-crd.js", "tab-dtr.js", "tab-pas.js", "config-raw.js", "suites.js", "history-run.js");

    private static final Pattern LEDGER_FIELD = Pattern.compile("textField\\(\\{[^}]*field: '(note|testerRef)'[^}]*}\\)");

    @Test
    void everyNoteAndTesterReferenceKeptByTheLedgerCarriesTheHint() throws IOException {
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (String script : FORMS) {
            String text = Files.readString(JS.resolve(script));
            Matcher matcher = LEDGER_FIELD.matcher(text);
            while (matcher.find()) {
                seen++;
                if (!matcher.group().contains("hint: LEDGER_TEXT_HINT")) {
                    missing.add(script + ": " + matcher.group());
                }
            }
        }
        assertThat(missing).isEmpty();
        // CRD, DTR, PAS and the raw send have both fields, the suite form and the note editor one tester reference each.
        assertThat(seen).isGreaterThanOrEqualTo(10);
        assertThat(Files.readString(JS.resolve("history-run.js"))).contains("'-hint'}, LEDGER_TEXT_HINT)");
        assertThat(Files.readString(JS.resolve("forms.js"))).contains("export const LEDGER_TEXT_HINT = ");
    }

    @Test
    void aChangeOfTheWriteAccessAloneIsHandedToTheTabsThatStateIt() throws IOException {
        assertThat(Files.readString(JS.resolve("app.js"))).contains("entry.tab.onWriteAccessChange(answer.body, before)");
        for (String tab : List.of("tab-config.js", "tab-pas.js", "tab-history.js")) {
            assertThat(Files.readString(JS.resolve(tab))).as(tab).containsPattern("export default \\{[^}]*onWriteAccessChange");
        }
    }

    @Test
    void aFieldWithoutTheHintIsFound() {
        Matcher matcher = LEDGER_FIELD.matcher("const note = textField({label: 'Note', field: 'note'});");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).doesNotContain("hint: LEDGER_TEXT_HINT");
    }
}
