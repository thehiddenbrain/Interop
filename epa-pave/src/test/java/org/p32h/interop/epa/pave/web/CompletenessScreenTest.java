package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.IssueView;

/**
 * Section 21 on the designed views: where a view states a value or heads a list, it says in words
 * whether the service did not return it, returned it as null or returned it empty. The server
 * sends that as a presence; these tests hold each script to reading the presence the server sends
 * for it, so a renamed property or a script that goes back to a yes-or-no flag fails the build.
 */
class CompletenessScreenTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    /** One property a script reads: the script, the text of the read, the record, and the property. */
    private record Read(String script, String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("result.js", "issue.expressionPresence", IssueView.class, "expressionPresence"),
            new Read("result.js", "issue.locationPresence", IssueView.class, "locationPresence"));

    @Test
    void everyPresenceAScriptDrawsIsOnTheRecordTheServerSends() throws IOException {
        for (Read read : READS) {
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which " + read.script() + " reads")
                    .isTrue();
            assertThat(Files.readString(JS.resolve(read.script()))).as(read.script() + " reads " + read.text()).contains(read.text());
        }
    }

    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
