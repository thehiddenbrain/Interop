package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.crd.CardView;
import org.p32h.interop.epa.pave.model.crd.SuggestionView;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * A card's source and its lists say whether each was not returned, returned as null or returned
 * empty. The values inside them cannot: a null source and an empty one both have no label.
 */
class CrdCardPresenceTest {

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private CardView card(String fields) {
        String body = "{\"cards\":[{\"summary\":\"A card\"" + (fields.isEmpty() ? "" : "," + fields) + "}]}";
        return mapper.read(TestExchanges.crd(200, body)).detail().cards().get(0);
    }

    @Test
    void theSourceIsSaidToBeNotReturnedNullEmptyOrThere() {
        assertThat(card("").source()).isEqualTo(Presence.ABSENT);
        assertThat(card("\"source\":null").source()).isEqualTo(Presence.NULL);
        assertThat(card("\"source\":{}").source()).isEqualTo(Presence.EMPTY);
        assertThat(card("\"source\":{\"label\":\"Payer\"}").source()).isEqualTo(Presence.VALUE);
        // The label alone reads the same for the first three, which is why the source has its own presence.
        assertThat(card("\"source\":null").sourceLabel().presence()).isEqualTo(Presence.ABSENT);
        assertThat(card("\"source\":{}").sourceLabel().presence()).isEqualTo(Presence.ABSENT);
    }

    @Test
    void eachListOfTheCardSaysWhatItWas() {
        assertThat(card("").lists()).containsExactlyInAnyOrderEntriesOf(Map.of("links", Presence.ABSENT, "suggestions", Presence.ABSENT,
                "overrideReasons", Presence.ABSENT));
        CardView nulls = card("\"links\":null,\"suggestions\":null,\"overrideReasons\":null");
        assertThat(nulls.lists()).containsEntry("links", Presence.NULL).containsEntry("suggestions", Presence.NULL)
                .containsEntry("overrideReasons", Presence.NULL);
        CardView empty = card("\"links\":[],\"suggestions\":[],\"overrideReasons\":[]");
        assertThat(empty.lists()).containsEntry("links", Presence.EMPTY).containsEntry("suggestions", Presence.EMPTY)
                .containsEntry("overrideReasons", Presence.EMPTY);
        assertThat(nulls.links()).isEmpty();
        assertThat(empty.links()).isEmpty();
    }

    @Test
    void aSuggestionSaysWhatItsActionsWere() {
        CardView card = card("\"suggestions\":[{\"label\":\"one\"},{\"label\":\"two\",\"actions\":null},{\"label\":\"three\",\"actions\":[]}]");
        assertThat(card.suggestions()).extracting(SuggestionView::lists).extracting(lists -> lists.get("actions"))
                .containsExactly(Presence.ABSENT, Presence.NULL, Presence.EMPTY);
        assertThat(card.lists()).containsEntry("suggestions", Presence.VALUE);
    }

    @Test
    void thePresencesAreOnTheWire() {
        tools.jackson.databind.JsonNode json = org.p32h.interop.epa.pave.model.JsonSafe.mapper().valueToTree(card("\"source\":null,\"links\":[]"));
        assertThat(json.get("source").asString()).isEqualTo("NULL");
        assertThat(json.at("/lists/links").asString()).isEqualTo("EMPTY");
        assertThat(json.at("/lists/suggestions").asString()).isEqualTo("ABSENT");
    }
}
