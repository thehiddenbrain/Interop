package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The single place a {@link JsonNode} is read.
 *
 * <p>Jackson 3 made the typed accessors strict: {@code longValue()} on a string throws where
 * Jackson 2 returned 0. Scenario files are edited by hand and vendor responses are not under this
 * tool's control, so every read here tolerates a null node, a missing node and a node of the wrong
 * type. None of these methods throws on bad input and none of them invents a value: when the data
 * is not there, the answer is null or an empty list.
 */
public final class JsonSafe {

    // Decimals are read exactly. Read as doubles, 1250.00 would be shown as 1250.0 and a long
    // identifier as an exponent, so the screen would show a number the service did not send.
    // A number is never read as an enum by its position, which would turn 1 into whichever
    // constant happens to be declared second.
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();
    private static final JsonMapper NO_REPEATED_KEYS = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();

    private JsonSafe() {
    }

    /**
     * The result of parsing text that may not be JSON at all. Exactly one of node and error is set.
     *
     * @param repeatedKeys true when an object in the text carries the same key more than once. The
     *                     node then holds only the last value of that key, so the caller should say so
     */
    public record Parsed(JsonNode node, String error, boolean repeatedKeys) {

        public boolean ok() {
            return node != null;
        }

        /** Prints whether it parsed, never the document: it can be a response body or a token response. */
        @Override
        public String toString() {
            return "Parsed[" + (ok() ? "ok" : error) + "]";
        }
    }

    /**
     * Parses text without throwing. Blank text is reported as an error, not as an empty document.
     * One leading byte order mark is skipped: a file or a response saved as UTF-8 with a mark is
     * valid JSON to the person who saved it, and rejecting it at line 1 column 1 explains nothing.
     */
    public static Parsed parse(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        if (Texts.blank(text)) {
            return new Parsed(null, "The body is empty.", false);
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                return new Parsed(null, "The body is empty.", false);
            }
            boolean repeated = false;
            try {
                NO_REPEATED_KEYS.readTree(text);
            } catch (JacksonException duplicate) {
                repeated = true;
            }
            return new Parsed(node, null, repeated);
        } catch (JacksonException e) {
            // The original message can quote the source text, which may be member data. Only the
            // position is kept.
            return new Parsed(null, "The body is not valid JSON" + position(e) + ".", false);
        }
    }

    private static String position(JacksonException e) {
        if (e.getLocation() == null || e.getLocation().getLineNr() < 0) {
            return "";
        }
        return " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
    }

    /** A plain mapper for tree work. Not the application mapper, so it carries no web configuration. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static boolean missing(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    public static boolean nullOrMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    public static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

    public static boolean isArray(JsonNode node) {
        return node != null && node.isArray();
    }

    /** The named child, or a missing node. Never null, and never throws on a non-object parent. */
    public static JsonNode child(JsonNode node, String field) {
        if (node == null || field == null || !node.isObject()) {
            return MissingNode.getInstance();
        }
        JsonNode child = node.get(field);
        return child == null ? MissingNode.getInstance() : child;
    }

    /** Walks object fields in order. Never null. */
    public static JsonNode at(JsonNode node, String... path) {
        JsonNode current = node == null ? MissingNode.getInstance() : node;
        if (path != null) {
            for (String field : path) {
                current = child(current, field);
            }
        }
        return current;
    }

    /**
     * The scalar as text: a string as is, a number or boolean as its JSON text. Null for a missing
     * node, an explicit null and any container. An empty string is returned as an empty string, so
     * callers that need "has a value" must use {@link Texts#present(String)}.
     */
    public static String text(JsonNode node) {
        if (nullOrMissing(node) || node.isContainer()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asString();
        }
        return null;
    }

    public static String text(JsonNode node, String field) {
        return text(child(node, field));
    }

    /** The stripped text when present, otherwise null. Blank counts as not present. */
    public static String presentText(JsonNode node) {
        return Texts.trimToNull(text(node));
    }

    public static String presentText(JsonNode node, String field) {
        return presentText(child(node, field));
    }

    /** An integer from a JSON number or a numeric string. Null when it is neither or does not fit. */
    public static Integer integer(JsonNode node) {
        Long value = longValue(node);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    public static Integer integer(JsonNode node, String field) {
        return integer(child(node, field));
    }

    /** A long from an integral JSON number or an integral numeric string. Null otherwise. */
    public static Long longValue(JsonNode node) {
        if (nullOrMissing(node)) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue(0L);
        }
        if (node.isString()) {
            String text = Texts.trimToNull(node.stringValue());
            if (text == null) {
                return null;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Long longValue(JsonNode node, String field) {
        return longValue(child(node, field));
    }

    /** A boolean from a JSON boolean or the strings true and false in any case. Null otherwise. */
    public static Boolean bool(JsonNode node) {
        if (nullOrMissing(node)) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue(false);
        }
        if (node.isString()) {
            String text = Texts.trimToNull(node.stringValue());
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    public static Boolean bool(JsonNode node, String field) {
        return bool(child(node, field));
    }

    /** The elements of an array. Empty for anything that is not an array, including a lone object. */
    public static List<JsonNode> elements(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (isArray(node)) {
            for (JsonNode element : node) {
                out.add(element);
            }
        }
        return out;
    }

    public static List<JsonNode> elements(JsonNode node, String field) {
        return elements(child(node, field));
    }

    /** The properties of an object in document order. Empty for anything that is not an object. */
    public static List<Map.Entry<String, JsonNode>> properties(JsonNode node) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        if (isObject(node)) {
            out.addAll(node.properties());
        }
        return out;
    }

    /** Classifies a node as absent, null, empty or carrying a value. */
    public static Presence presence(JsonNode node) {
        if (missing(node)) {
            return Presence.ABSENT;
        }
        if (node.isNull()) {
            return Presence.NULL;
        }
        if (node.isContainer()) {
            return node.isEmpty() ? Presence.EMPTY : Presence.VALUE;
        }
        if (node.isString() && node.stringValue().isEmpty()) {
            return Presence.EMPTY;
        }
        return Presence.VALUE;
    }

    /** Compact JSON text for a node, or null when there is no node. */
    public static String compact(JsonNode node) {
        return missing(node) ? null : node.toString();
    }

    /** Indented JSON text for a node, or null when there is no node. */
    public static String pretty(JsonNode node) {
        return missing(node) ? null : node.toPrettyString();
    }
}
