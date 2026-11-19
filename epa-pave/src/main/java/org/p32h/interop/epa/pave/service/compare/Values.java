package org.p32h.interop.epa.pave.service.compare;

import java.text.NumberFormat;
import java.util.Locale;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Shown;
import tools.jackson.databind.JsonNode;

/** How a value of either document is handed to the screen, and what JSON type it has. */
final class Values {

    /** The longest text shown for one value. A whole resource can be megabytes, and a screen needs its start. */
    static final int LIMIT = 2000;

    /** The six JSON types, under the names the browser is given. */
    enum JsonType {
        OBJECT("object", "an object"),
        ARRAY("array", "a list"),
        STRING("string", "text (a JSON string)"),
        NUMBER("number", "a number"),
        BOOLEAN("boolean", "true or false (a JSON boolean)"),
        NULL("null", "an explicit null");

        private final String wire;
        private final String words;

        JsonType(String wire, String words) {
            this.wire = wire;
            this.words = words;
        }

        String wire() {
            return wire;
        }

        String words() {
            return words;
        }

        static JsonType of(JsonNode node) {
            if (node.isObject()) {
                return OBJECT;
            }
            if (node.isArray()) {
                return ARRAY;
            }
            if (node.isNumber()) {
                return NUMBER;
            }
            if (node.isBoolean()) {
                return BOOLEAN;
            }
            if (node.isNull()) {
                return NULL;
            }
            // A parsed document holds nothing else. Anything a caller built by hand is compared as its text.
            return STRING;
        }
    }

    private Values() {
    }

    /** The JSON type of a value under its wire name, or null when the document does not have the element. */
    static String type(JsonNode node) {
        return node == null || node.isMissingNode() ? null : JsonType.of(node).wire();
    }

    /**
     * A value as the screen shows it. A node that is not there is absent, which is a different
     * thing from an explicit null and from an empty text, list or object. A list or an object is
     * given as compact JSON.
     */
    static Shown shown(JsonNode node) {
        if (node == null) {
            return Shown.absent();
        }
        return switch (JsonSafe.presence(node)) {
            case ABSENT -> Shown.absent();
            case NULL -> Shown.explicitNull();
            case EMPTY -> Shown.empty();
            case VALUE -> Shown.of(cut(text(node)));
        };
    }

    /** The text of a scalar as JSON writes it without quotes, or the compact JSON of a list or an object. */
    static String text(JsonNode node) {
        if (node.isContainer()) {
            return node.toString();
        }
        return node.isString() ? node.stringValue() : node.asString();
    }

    /** Cuts long text and says so in the text itself, so a cut value is never taken for the whole one. */
    static String cut(String text) {
        if (text.length() <= LIMIT) {
            return text;
        }
        // Not in the middle of a character that takes two code units.
        int end = Character.isHighSurrogate(text.charAt(LIMIT - 1)) ? LIMIT - 1 : LIMIT;
        NumberFormat count = NumberFormat.getIntegerInstance(Locale.US);
        return text.substring(0, end) + " ... (cut here: the first " + count.format(end) + " of " + count.format(text.length())
                + " characters are shown)";
    }
}
