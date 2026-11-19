package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TrackedJsonTest {

    private static TrackedJson tracked(String json) {
        return TrackedJson.of(JsonSafe.parse(json).node());
    }

    private static List<String> paths(TrackedJson tracked) {
        return tracked.unconsumed().stream().map(AdditionalField::path).toList();
    }

    @Test
    void aFieldNobodyReadIsReportedAndAFieldSomebodyReadIsNot() {
        TrackedJson tracked = tracked("{\"summary\":\"Needs auth\",\"surprise\":\"new vendor field\"}");
        assertThat(tracked.root().shown("summary").value()).isEqualTo("Needs auth");
        assertThat(tracked.unconsumed()).containsExactly(new AdditionalField("surprise", Presence.VALUE, "\"new vendor field\""));
    }

    @Test
    void nothingReadMeansEverythingIsReported() {
        TrackedJson tracked = tracked("{\"a\":1,\"b\":{\"c\":[true,{\"d\":null}]},\"e\":\"\"}");
        assertThat(tracked.unconsumed()).containsExactly(
                new AdditionalField("a", Presence.VALUE, "1"),
                new AdditionalField("b.c[0]", Presence.VALUE, "true"),
                new AdditionalField("b.c[1].d", Presence.NULL, null),
                new AdditionalField("e", Presence.EMPTY, "\"\""));
    }

    @Test
    void navigationAloneDoesNotConsume() {
        TrackedJson tracked = tracked("{\"cards\":[{\"summary\":\"s\",\"extra\":1}]}");
        TrackedJson.Node card = tracked.root().children("cards").get(0);
        card.child("summary");
        card.raw();
        card.presence("summary");
        card.exists();
        assertThat(paths(tracked)).containsExactly("cards[0].summary", "cards[0].extra");
    }

    @Test
    void anUnknownFieldInsideAKnownArrayElementIsReported() {
        TrackedJson tracked = tracked("{\"cards\":[{\"summary\":\"one\",\"vendorNote\":\"x\"},{\"summary\":\"two\"}]}");
        for (TrackedJson.Node card : tracked.root().children("cards")) {
            card.shown("summary");
        }
        assertThat(paths(tracked)).containsExactly("cards[0].vendorNote");
    }

    @Test
    void readingAContainerConsumesEverythingBeneathIt() {
        TrackedJson tracked = tracked("{\"resource\":{\"id\":\"1\",\"nested\":{\"deep\":[1,2]}},\"other\":2}");
        JsonNode raw = tracked.root().child("resource").consumeSubtree();
        assertThat(raw.isObject()).isTrue();
        assertThat(paths(tracked)).containsExactly("other");
    }

    @Test
    void anEmptyArrayIsReportedUnlessTheViewStatesTheEmptinessItself() {
        // Asking for the elements of an empty array is navigation. If it consumed, "links": [] and
        // no links at all would give the same view and the same additional fields.
        TrackedJson tracked = tracked("{\"cards\":[],\"links\":[]}");
        assertThat(tracked.root().children("cards")).isEmpty();
        assertThat(tracked.root().children("links")).isEmpty();
        assertThat(tracked.unconsumed()).containsExactly(new AdditionalField("cards", Presence.EMPTY, "[]"),
                new AdditionalField("links", Presence.EMPTY, "[]"));
        // A view that says "the cards array was empty" itself marks it as stated.
        tracked.root().child("cards").consume();
        assertThat(paths(tracked)).containsExactly("links");
    }

    @Test
    void theThreeKindsOfEmptyKeepTheirJsonTextSoTheyCanBeToldApart() {
        TrackedJson tracked = tracked("{\"a\":\"\",\"b\":[],\"c\":{}}");
        assertThat(tracked.unconsumed()).containsExactly(new AdditionalField("a", Presence.EMPTY, "\"\""),
                new AdditionalField("b", Presence.EMPTY, "[]"), new AdditionalField("c", Presence.EMPTY, "{}"));
    }

    @Test
    void aFieldOfTheWrongShapeStaysVisible() {
        // The view expects an array of cards. A lone object yields no cards, so it must surface as additional.
        TrackedJson tracked = tracked("{\"cards\":{\"summary\":\"not an array\"}}");
        assertThat(tracked.root().children("cards")).isEmpty();
        assertThat(paths(tracked)).containsExactly("cards.summary");
    }

    @Test
    void nullAndEmptyAreReportedWithTheirOwnPresence() {
        TrackedJson tracked = tracked("{\"a\":null,\"b\":\"\",\"c\":{},\"d\":[]}");
        assertThat(tracked.unconsumed()).extracting(AdditionalField::presence)
                .containsExactly(Presence.NULL, Presence.EMPTY, Presence.EMPTY, Presence.EMPTY);
    }

    @Test
    void readingAnAbsentFieldYieldsAbsentAndConsumesNothing() {
        TrackedJson tracked = tracked("{\"present\":1}");
        assertThat(tracked.root().shown("absent")).isEqualTo(Shown.absent());
        assertThat(tracked.root().text("absent")).isNull();
        assertThat(paths(tracked)).containsExactly("present");
    }

    @Test
    void namesWithPathPunctuationAreQuotedAndStillTrackedExactly() {
        TrackedJson tracked = tracked("{\"a.b\":1,\"a\":{\"b\":2},\"x/y\":3,\"til~de\":4}");
        tracked.root().child("a").shown("b");
        tracked.root().shown("x/y");
        assertThat(paths(tracked)).containsExactly("\"a.b\"", "til~de");
        tracked.root().shown("til~de");
        tracked.root().shown("a.b");
        assertThat(tracked.unconsumed()).isEmpty();
    }

    @Test
    void consumingOneArrayElementLeavesItsSiblings() {
        TrackedJson tracked = tracked("{\"items\":[\"a\",\"b\",\"c\"]}");
        tracked.root().children("items").get(1).shown();
        assertThat(paths(tracked)).containsExactly("items[0]", "items[2]");
    }

    @Test
    void typedReadsConsume() {
        TrackedJson tracked = tracked("{\"count\":3,\"flag\":true,\"name\":\" padded \",\"left\":0}");
        assertThat(tracked.root().integer("count")).isEqualTo(3);
        assertThat(tracked.root().bool("flag")).isTrue();
        assertThat(tracked.root().presentText("name")).isEqualTo("padded");
        assertThat(paths(tracked)).containsExactly("left");
    }

    @Test
    void aTypedReadThatReturnsNothingLeavesTheFieldToBeReported() {
        // Each of these reads comes back null. If it consumed anyway, the field would be in the
        // wire view only: absent from the designed view and from the additional fields alike.
        TrackedJson wrongType = tracked("{\"summary\":{\"text\":\"Needs auth\",\"lang\":\"en\"},\"count\":\"three\",\"ratio\":1.5,"
                + "\"flag\":\"yes\",\"list\":[1]}");
        assertThat(wrongType.root().text("summary")).isNull();
        assertThat(wrongType.root().presentText("summary")).isNull();
        assertThat(wrongType.root().integer("count")).isNull();
        assertThat(wrongType.root().integer("ratio")).isNull();
        assertThat(wrongType.root().bool("flag")).isNull();
        assertThat(wrongType.root().text("list")).isNull();
        assertThat(paths(wrongType)).containsExactly("summary.text", "summary.lang", "count", "ratio", "flag", "list[0]");

        TrackedJson nulls = tracked("{\"a\":null,\"b\":null,\"c\":null,\"d\":null}");
        assertThat(nulls.root().text("a")).isNull();
        assertThat(nulls.root().presentText("b")).isNull();
        assertThat(nulls.root().integer("c")).isNull();
        assertThat(nulls.root().bool("d")).isNull();
        assertThat(nulls.unconsumed()).extracting(AdditionalField::presence).containsOnly(Presence.NULL).hasSize(4);

        // An empty string is a value to text() and no value to presentText(), which leaves it visible as EMPTY.
        TrackedJson empties = tracked("{\"a\":\"\",\"b\":\"  \"}");
        assertThat(empties.root().presentText("a")).isNull();
        assertThat(empties.root().presentText("b")).isNull();
        assertThat(paths(empties)).containsExactly("a", "b");
        assertThat(empties.root().text("a")).isEmpty();
        assertThat(paths(empties)).containsExactly("b");
    }

    @Test
    void theRestOfASubtreeCanBeHandedToAViewAndIsThenNotReportedAgain() {
        TrackedJson tracked = tracked("{\"resource\":{\"id\":\"1\",\"status\":\"draft\",\"subject\":{\"reference\":\"Patient/p\"}},\"other\":2}");
        TrackedJson.Node resource = tracked.root().child("resource");
        resource.shown("id");
        assertThat(resource.remainder()).containsExactly(new AdditionalField("resource.status", Presence.VALUE, "\"draft\""),
                new AdditionalField("resource.subject.reference", Presence.VALUE, "\"Patient/p\""));
        assertThat(paths(tracked)).containsExactly("other");
        assertThat(resource.remainder()).isEmpty();
        assertThat(tracked.root().child("absent").remainder()).isEmpty();
    }

    @Test
    void aScalarRootAndAMissingRootAreHandled() {
        TrackedJson scalar = tracked("\"just text\"");
        assertThat(scalar.unconsumed()).containsExactly(new AdditionalField("(root)", Presence.VALUE, "\"just text\""));
        assertThat(TrackedJson.of(null).unconsumed()).isEmpty();
        assertThat(TrackedJson.of(null).root().shown("x")).isEqualTo(Shown.absent());
    }

    @Test
    void aNodeReportsItsPathTheWayTheAdditionalFieldsWriteIt() {
        TrackedJson tracked = tracked("{\"data\":{\"member\":[{\"dob\":\"1970-01-01\",\"a.b\":1}]}}");
        TrackedJson.Node member = tracked.root().child("data").children("member").get(0);
        assertThat(member.child("dob").path()).isEqualTo("data.member[0].dob");
        assertThat(member.child("a.b").path()).isEqualTo("data.member[0].\"a.b\"");
        assertThat(tracked.root().path()).isEqualTo("(root)");
        assertThat(member.properties().get(0).getValue().path()).isEqualTo("data.member[0].dob");
        // The same spelling the walker uses for a field nobody read.
        assertThat(paths(tracked)).containsExactly("data.member[0].dob", "data.member[0].\"a.b\"");
    }

    @Test
    void propertiesWalksAnObjectInDocumentOrderWithoutConsuming() {
        TrackedJson tracked = tracked("{\"z\":1,\"a\":2}");
        assertThat(tracked.root().properties()).extracting(e -> e.getKey()).containsExactly("z", "a");
        assertThat(paths(tracked)).containsExactly("z", "a");
    }
}
