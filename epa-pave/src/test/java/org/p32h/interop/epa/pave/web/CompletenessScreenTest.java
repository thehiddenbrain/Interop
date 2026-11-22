package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * Section 21 on the Workbench: where a view states a value, it says in words whether the service
 * did not return it, returned it as null or returned it empty. The server sends that as a presence;
 * these tests hold each script to reading the presence the server sends for it, so a renamed
 * property or a script that goes back to a yes-or-no flag fails the build. The lists of the
 * response detail (cards, items, issues) are read by detail.js and held by batch H's own test.
 */
class CompletenessScreenTest {

    /** One property a script reads: the script, the text of the read, the record, and the property. */
    private record Read(String script, String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("fault.js", "issue.expressionPresence", IssueView.class, "expressionPresence"),
            new Read("fault.js", "issue.locationPresence", IssueView.class, "locationPresence"),
            new Read("fault.js", "issue.detailsPresence", IssueView.class, "detailsPresence"),
            new Read("result.js", "row.sentWithNoValue", DeterminationRow.class, "sentWithNoValue"),
            new Read("result.js", "row.presence", DeterminationRow.class, "presence"),
            new Read("result.js", "field.presence", AdditionalField.class, "presence"),
            new Read("wb-lookup.js", "field.value", LookupField.class, "value"),
            new Read("wb-lookup.js", "field.masked", LookupField.class, "masked"),
            new Read("shown.js", "value.presence", Shown.class, "presence"));

    @Test
    void everyPresenceAScriptDrawsIsOnTheRecordTheServerSends() throws IOException {
        for (Read read : READS) {
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which " + read.script() + " reads")
                    .isTrue();
            assertThat(StaticSources.script(read.script())).as(read.script() + " reads " + read.text()).contains(read.text());
        }
    }

    /** Each presence that is not a value has its own words, and none is a blank. */
    @Test
    void absentNullAndEmptyAreThreeDifferentWords() throws IOException {
        String shown = StaticSources.script("shown.js");
        assertThat(shown).contains("ABSENT: 'not returned'").contains("NULL: 'returned as null'").contains("EMPTY: 'returned empty'");
        assertThat(StaticSources.script("result.js")).contains("chip(presenceWords(row.presence), {dashed: true})")
                .contains("chip('sent with no value', {dashed: true})");
    }

    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
