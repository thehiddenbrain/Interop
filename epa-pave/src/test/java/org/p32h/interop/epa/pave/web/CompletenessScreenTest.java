package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.crd.CardView;
import org.p32h.interop.epa.pave.model.crd.SuggestionView;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.pas.AdjudicationView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasItemView;

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
            new Read("result.js", "issue.locationPresence", IssueView.class, "locationPresence"),
            new Read("result.js", "issue.detailsPresence", IssueView.class, "detailsPresence"),
            new Read("crd-detail.js", "card.source", CardView.class, "source"),
            new Read("crd-detail.js", "card.lists", CardView.class, "lists"),
            new Read("crd-detail.js", "suggestion.lists", SuggestionView.class, "lists"),
            new Read("pas-detail.js", "response.preAuthPeriod", ClaimResponseView.class, "preAuthPeriod"),
            new Read("pas-detail.js", "response.lists", ClaimResponseView.class, "lists"),
            new Read("pas-detail.js", "decision.authorizedPeriod", PasDecision.class, "authorizedPeriod"),
            new Read("pas-detail.js", "item.lists", PasItemView.class, "lists"),
            new Read("pas-detail.js", "adjudication.lists", AdjudicationView.class, "lists"),
            new Read("dtr-items.js", "item.lists", ItemView.class, "lists"),
            new Read("result.js", "row.sentWithNoValue", org.p32h.interop.epa.pave.model.DeterminationRow.class, "sentWithNoValue"));

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
