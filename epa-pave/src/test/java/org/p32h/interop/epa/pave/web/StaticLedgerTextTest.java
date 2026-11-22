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
 * Two rules of the screens that no server test can see.
 * <ul>
 *   <li>Every field whose text the ledger keeps as typed (the note and the tester reference of a
 *       run) carries the one hint, in the same words everywhere, that member data must not be typed
 *       into it. The server refuses only the member data of the request it goes with, so the rule is
 *       said at the field.</li>
 *   <li>The frame hands a change of the write access that comes without a change of the
 *       configuration to the views, so that a lock pressed in another browser is redrawn at once.</li>
 * </ul>
 * The last test hands the first rule a field that breaks it, so a rule that has stopped looking fails.
 */
class StaticLedgerTextTest {

    private static final Pattern LEDGER_FIELD = Pattern.compile("caseField\\(\\{name: '(note|testerRef)'[^}]*}\\)");

    @Test
    void everyNoteAndTesterReferenceKeptByTheLedgerCarriesTheHint() throws IOException {
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (Source script : StaticSources.sources(".js")) {
            Matcher matcher = LEDGER_FIELD.matcher(script.text());
            while (matcher.find()) {
                seen++;
                if (!matcher.group().contains("hint: LEDGER_TEXT_HINT")) {
                    missing.add(script.name() + ": " + matcher.group());
                }
            }
        }
        assertThat(missing).isEmpty();
        // The Workbench's Service card has both fields.
        assertThat(seen).isGreaterThanOrEqualTo(2);
        assertThat(StaticSources.script("wb-case.js"))
                .contains("export const LEDGER_TEXT_HINT = 'Kept in the ledger as typed. Never type member data here.';");
    }

    @Test
    void aChangeOfTheWriteAccessAloneIsHandedToTheViews() throws IOException {
        String app = StaticSources.script("app.js");
        assertThat(app).contains("const writesChanged = Boolean(before) && JSON.stringify(before.writeAccess) !== JSON.stringify(next.writeAccess);")
                .contains("if (writesChanged) {\n        emit('writes', next, before);");
    }

    @Test
    void aFieldWithoutTheHintIsFound() {
        Matcher matcher = LEDGER_FIELD.matcher("const note = caseField({name: 'note', label: 'Note'});");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).doesNotContain("hint: LEDGER_TEXT_HINT");
    }
}
