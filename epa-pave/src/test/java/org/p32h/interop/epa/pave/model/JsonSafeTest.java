package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonSafeTest {

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    @Test
    void parseReportsBlankAndMalformedTextWithoutThrowing() {
        assertThat(JsonSafe.parse(null).ok()).isFalse();
        assertThat(JsonSafe.parse("").error()).isEqualTo("The body is empty.");
        assertThat(JsonSafe.parse("   ").ok()).isFalse();
        JsonSafe.Parsed truncated = JsonSafe.parse("{\"cards\": [");
        assertThat(truncated.ok()).isFalse();
        assertThat(truncated.error()).startsWith("The body is not valid JSON");
        assertThat(JsonSafe.parse("<html>nope</html>").ok()).isFalse();
        assertThat(JsonSafe.parse("{\"a\":1}").ok()).isTrue();
    }

    @Test
    void aLeadingByteOrderMarkIsSkipped() {
        // A response, a scenario file or a Postman export saved as UTF-8 with a byte order mark is still JSON.
        JsonSafe.Parsed parsed = JsonSafe.parse("\uFEFF{\"cards\":[]}");
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.node().has("cards")).isTrue();
        assertThat(JsonSafe.parse("\uFEFF").ok()).isFalse();
        assertThat(JsonSafe.parse("\uFEFF   ").error()).isEqualTo("The body is empty.");
    }

    @Test
    void aRepeatedKeyIsReportedAndDoesNotFailTheParse() {
        // The parser keeps the last value. The first exists only in the raw text, so the fact is recorded.
        JsonSafe.Parsed repeated = JsonSafe.parse("{\"indicator\":\"info\",\"indicator\":\"warning\"}");
        assertThat(repeated.ok()).isTrue();
        assertThat(repeated.repeatedKeys()).isTrue();
        assertThat(JsonSafe.text(repeated.node(), "indicator")).isEqualTo("warning");
        JsonSafe.Parsed nested = JsonSafe.parse("{\"a\":{\"b\":1,\"b\":2}}");
        assertThat(nested.repeatedKeys()).isTrue();
        // The same name in two different objects is not a repeat.
        assertThat(JsonSafe.parse("{\"a\":{\"id\":1},\"b\":{\"id\":2}}").repeatedKeys()).isFalse();
        assertThat(JsonSafe.parse("{\"cards\":[").repeatedKeys()).isFalse();
    }

    @Test
    void aDecimalIsShownExactlyAsItWasSent() {
        // Read as doubles these would come back as 1250.0, 0.1 and an exponent: numbers the service did not send.
        String body = "{\"total\":1250.00,\"rate\":0.10,\"count\":100.000,\"id\":123456789012345678901234567890.5,\"whole\":42}";
        JsonNode node = JsonSafe.parse(body).node();
        assertThat(Shown.of(node.get("total")).value()).isEqualTo("1250.00");
        assertThat(Shown.of(node.get("rate")).value()).isEqualTo("0.10");
        assertThat(Shown.of(node.get("count")).value()).isEqualTo("100.000");
        assertThat(Shown.of(node.get("id")).value()).isEqualTo("123456789012345678901234567890.5");
        assertThat(Shown.of(node.get("whole")).value()).isEqualTo("42");
        assertThat(TrackedJson.of(node).unconsumed()).extracting(AdditionalField::value)
                .containsExactly("1250.00", "0.10", "100.000", "123456789012345678901234567890.5", "42");
        assertThat(JsonSafe.pretty(node)).contains("1250.00").contains("0.10").contains("100.000").doesNotContain("E29");
    }

    @Test
    void aParsedDocumentDoesNotPrintItself() {
        JsonSafe.Parsed parsed = JsonSafe.parse("{\"lastName\":\"Placeholder-Surname\",\"access_token\":\"placeholder-token\"}");
        assertThat(parsed.toString()).isEqualTo("Parsed[ok]").doesNotContain("Placeholder-Surname").doesNotContain("placeholder-token");
        assertThat(JsonSafe.parse("{\"lastName\": ").toString()).startsWith("Parsed[The body is not valid JSON");
    }

    @Test
    void aParseErrorNeverQuotesTheSourceText() {
        JsonSafe.Parsed parsed = JsonSafe.parse("{\"lastName\": \"Placeholder-Surname\", oops}");
        assertThat(parsed.ok()).isFalse();
        assertThat(parsed.error()).doesNotContain("Placeholder-Surname").doesNotContain("oops");
    }

    @Test
    void everyReaderToleratesANullNode() {
        assertThatCode(() -> {
            JsonSafe.text(null);
            JsonSafe.text(null, "a");
            JsonSafe.presentText(null, "a");
            JsonSafe.integer(null, "a");
            JsonSafe.longValue(null, "a");
            JsonSafe.bool(null, "a");
            JsonSafe.elements(null, "a");
            JsonSafe.properties(null);
            JsonSafe.child(null, "a");
            JsonSafe.at(null, "a", "b");
            JsonSafe.presence(null);
            JsonSafe.compact(null);
        }).doesNotThrowAnyException();
        assertThat(JsonSafe.child(null, "a").isMissingNode()).isTrue();
        assertThat(JsonSafe.elements(null)).isEmpty();
        assertThat(JsonSafe.presence(null)).isEqualTo(Presence.ABSENT);
    }

    @Test
    void everyReaderToleratesANodeOfTheWrongType() {
        JsonNode node = json("{\"text\":\"abc\",\"number\":12,\"flag\":true,\"object\":{\"x\":1},\"array\":[1,2],\"nothing\":null}");
        // A strict Jackson 3 accessor throws on these. The safe readers answer null.
        assertThat(JsonSafe.integer(node, "text")).isNull();
        assertThat(JsonSafe.longValue(node, "object")).isNull();
        assertThat(JsonSafe.longValue(node, "flag")).isNull();
        assertThat(JsonSafe.bool(node, "number")).isNull();
        assertThat(JsonSafe.text(node, "object")).isNull();
        assertThat(JsonSafe.text(node, "array")).isNull();
        assertThat(JsonSafe.text(node, "nothing")).isNull();
        assertThat(JsonSafe.text(node, "absent")).isNull();
        assertThat(JsonSafe.elements(node, "object")).isEmpty();
        assertThat(JsonSafe.elements(node, "text")).isEmpty();
        assertThat(JsonSafe.properties(JsonSafe.child(node, "array"))).isEmpty();
        assertThat(JsonSafe.child(JsonSafe.child(node, "text"), "deeper").isMissingNode()).isTrue();
    }

    @Test
    void scalarsAreReadAcrossTheSpellingsHandEditedFilesUse() {
        JsonNode node = json("{\"a\":\"200\",\"b\":200,\"c\":\" 15 \",\"d\":\"TRUE\",\"e\":false,\"f\":1.5,\"g\":\"1.5\",\"h\":\"\"}");
        assertThat(JsonSafe.integer(node, "a")).isEqualTo(200);
        assertThat(JsonSafe.integer(node, "b")).isEqualTo(200);
        assertThat(JsonSafe.integer(node, "c")).isEqualTo(15);
        assertThat(JsonSafe.bool(node, "d")).isTrue();
        assertThat(JsonSafe.bool(node, "e")).isFalse();
        assertThat(JsonSafe.integer(node, "f")).isNull();
        assertThat(JsonSafe.integer(node, "g")).isNull();
        assertThat(JsonSafe.text(node, "b")).isEqualTo("200");
        assertThat(JsonSafe.text(node, "e")).isEqualTo("false");
        assertThat(JsonSafe.text(node, "h")).isEmpty();
        assertThat(JsonSafe.presentText(node, "h")).isNull();
        assertThat(JsonSafe.presentText(node, "c")).isEqualTo("15");
    }

    @Test
    void integerRejectsAValueThatDoesNotFit() {
        JsonNode node = json("{\"big\":99999999999,\"bigText\":\"99999999999\"}");
        assertThat(JsonSafe.integer(node, "big")).isNull();
        assertThat(JsonSafe.integer(node, "bigText")).isNull();
        assertThat(JsonSafe.longValue(node, "big")).isEqualTo(99999999999L);
    }

    @Test
    void presenceTellsAbsentNullEmptyAndValueApart() {
        JsonNode node = json("{\"n\":null,\"es\":\"\",\"ea\":[],\"eo\":{},\"s\":\"x\",\"zero\":0,\"f\":false,\"a\":[null],\"blank\":\" \"}");
        assertThat(JsonSafe.presence(JsonSafe.child(node, "missing"))).isEqualTo(Presence.ABSENT);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "n"))).isEqualTo(Presence.NULL);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "es"))).isEqualTo(Presence.EMPTY);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "ea"))).isEqualTo(Presence.EMPTY);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "eo"))).isEqualTo(Presence.EMPTY);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "s"))).isEqualTo(Presence.VALUE);
        // Zero and false are values. Treating them as empty would hide a real answer.
        assertThat(JsonSafe.presence(JsonSafe.child(node, "zero"))).isEqualTo(Presence.VALUE);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "f"))).isEqualTo(Presence.VALUE);
        assertThat(JsonSafe.presence(JsonSafe.child(node, "a"))).isEqualTo(Presence.VALUE);
        // A string of spaces was sent, so it is a value, not an empty one.
        assertThat(JsonSafe.presence(JsonSafe.child(node, "blank"))).isEqualTo(Presence.VALUE);
    }

    @Test
    void elementsDoesNotPromoteALoneObjectToAList() {
        JsonNode node = json("{\"cards\":{\"summary\":\"one\"}}");
        assertThat(JsonSafe.elements(node, "cards")).isEmpty();
    }
}
