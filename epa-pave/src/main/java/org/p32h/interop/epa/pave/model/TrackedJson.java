package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * A parsed response that remembers which of its fields the designed view has used.
 *
 * <p>The rule this serves: every field a service returns is reachable in the interface. A mapper
 * reads a response through {@link Node}. Each read that produces a view value marks that field as
 * consumed. {@link #unconsumed()} then walks the whole document and returns every leaf that was
 * never consumed, which becomes the "additional fields" view. The remainder is computed by
 * subtraction, so a field nobody anticipated shows up by default. There is no list of fields to
 * ignore, and so no list that can fall out of date.
 *
 * <p>Navigation ({@link Node#child}, {@link Node#children}) does not consume. Only reads that hand
 * a value to the view do, so a mapper cannot hide a field by walking past it.
 */
public final class TrackedJson {

    private final JsonNode root;
    private final Set<String> consumed = new HashSet<>();

    private TrackedJson(JsonNode root) {
        this.root = root;
    }

    public static TrackedJson of(JsonNode root) {
        return new TrackedJson(root);
    }

    public Node root() {
        return new Node(root, "", "");
    }

    /** Every leaf that no view read, in document order. A leaf is a scalar, a null or an empty container. */
    public List<AdditionalField> unconsumed() {
        List<AdditionalField> out = new ArrayList<>();
        if (!JsonSafe.missing(root)) {
            walk(root, "", "", out);
        }
        return out;
    }

    private void walk(JsonNode node, String pointer, String path, List<AdditionalField> out) {
        if (consumed.contains(pointer)) {
            return;
        }
        if (node.isObject() && !node.isEmpty()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                walk(property.getValue(), pointer + "/" + escape(property.getKey()),
                        path.isEmpty() ? displayName(property.getKey()) : path + "." + displayName(property.getKey()), out);
            }
            return;
        }
        if (node.isArray() && !node.isEmpty()) {
            int index = 0;
            for (JsonNode element : node) {
                walk(element, pointer + "/" + index, path + "[" + index + "]", out);
                index++;
            }
            return;
        }
        Presence presence = JsonSafe.presence(node);
        // An empty string, an empty array and an empty object are all EMPTY, and they are different
        // findings, so the JSON text is kept for them as well as for a value.
        String value = presence == Presence.VALUE || presence == Presence.EMPTY ? node.toString() : null;
        out.add(new AdditionalField(path.isEmpty() ? "(root)" : path, presence, value));
    }

    static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    static String displayName(String name) {
        // A name holding path punctuation is quoted so the displayed path stays unambiguous.
        boolean plain = !name.isEmpty() && name.chars().noneMatch(c -> c == '.' || c == '[' || c == ']' || c == '"' || Character.isWhitespace(c));
        return plain ? name : "\"" + name.replace("\"", "\\\"") + "\"";
    }

    /** The note every body that repeats a key carries, worded for a list that holds every value. */
    public static final String REPEATED_KEY_NOTE = "The body repeats a key inside one object. This tool reads the last value of each "
            + "repeated key, as JSON readers do. Every value of each repeated key is listed here, in the order the body carried them.";

    /** How the note the exchange carries for a repeated key begins. It says only the last value is shown, which is no longer so. */
    private static final String EXCHANGE_NOTE_START = "The body repeats a key inside one object.";

    /**
     * The entries a mapper puts first, with every value of each key the body repeats. The parsed
     * body keeps only the last value of a repeated key, so without these an earlier value would be
     * on the wire view alone. Each value is listed under the key's path with its place among them,
     * masked where it repeats a secret the request carried, as the wire view masks it.
     */
    public static List<AdditionalField> bodyFieldsWithRepeatedKeys(VendorExchange exchange) {
        List<RepeatedKeyRead> repeated = repeatedKeys(exchange);
        List<AdditionalField> out = new ArrayList<>();
        for (AdditionalField field : exchange.bodyFields()) {
            boolean exchangeNote = field.kind() == AdditionalField.Kind.NOTE && field.value() != null
                    && field.value().startsWith(EXCHANGE_NOTE_START);
            if (!(exchangeNote && !repeated.isEmpty())) {
                out.add(field);
            }
        }
        out.addAll(repeatedKeyFields(repeated));
        return out;
    }

    /**
     * The note and the entries for every value of each repeated key, for a reader that parses a
     * body itself, such as the notification receiver. Empty when no key repeats.
     */
    public static List<AdditionalField> repeatedKeyFields(JsonSafe.Parsed parsed) {
        if (parsed == null || !parsed.repeatedKeys()) {
            return List.of();
        }
        List<RepeatedKeyRead> read = parsed.repeated().stream().map(key -> new RepeatedKeyRead(key.path(), key.values())).toList();
        if (read.isEmpty()) {
            // The walk could not list the values; the note that a key repeats still stands.
            return List.of(AdditionalField.bodyNote(REPEATED_KEY_NOTE));
        }
        return repeatedKeyFields(read);
    }

    private static List<AdditionalField> repeatedKeyFields(List<RepeatedKeyRead> repeated) {
        List<AdditionalField> out = new ArrayList<>();
        if (repeated.isEmpty()) {
            return out;
        }
        out.add(AdditionalField.bodyNote(REPEATED_KEY_NOTE));
        for (RepeatedKeyRead key : repeated) {
            int count = key.values().size();
            for (int i = 0; i < count; i++) {
                String value = key.values().get(i);
                JsonSafe.Parsed one = JsonSafe.parse(value);
                Presence presence = one.ok() ? JsonSafe.presence(one.node()) : Presence.VALUE;
                out.add(new AdditionalField(key.path() + " (value " + (i + 1) + " of " + count + ")", presence,
                        presence == Presence.NULL ? null : value));
            }
        }
        return out;
    }

    /**
     * A key the body repeats, with each value as the screen may show it.
     *
     * @param values the JSON text of each value in the order sent, masked where it repeats a secret the request carried
     */
    public record RepeatedKeyRead(String path, List<String> values) {

        public RepeatedKeyRead {
            values = values == null ? List.of() : List.copyOf(values);
        }

        /** Prints the path and how many values, never a value: a value can be member data. */
        @Override
        public String toString() {
            return "RepeatedKeyRead[" + path + ", " + values.size() + " values]";
        }
    }

    /**
     * A finding for each repeated key a designed view read: one that is not among the fields
     * returned but not interpreted, so its last value is on screen as the answer. The finding gives
     * every value in the order sent and chooses none of them. It is filed with the rules of the
     * guide, because the rule broken is one of the format the guide is written in.
     *
     * @param unread the fields returned but not interpreted, as the mapper listed them
     */
    public static List<Finding> repeatedKeyFindings(VendorExchange exchange, List<AdditionalField> unread) {
        List<Finding> out = new ArrayList<>();
        for (RepeatedKeyRead key : repeatedKeys(exchange)) {
            boolean listedUnread = unread != null && unread.stream().filter(field -> field.kind() == AdditionalField.Kind.FIELD)
                    .map(AdditionalField::path)
                    .anyMatch(path -> path.equals(key.path()) || path.startsWith(key.path() + ".") || path.startsWith(key.path() + "["));
            if (listedUnread) {
                continue;
            }
            List<String> said = new ArrayList<>();
            for (int i = 0; i < key.values().size(); i++) {
                said.add((i == 0 ? "first " : i == key.values().size() - 1 ? "then " : "next ") + key.values().get(i));
            }
            out.add(new Finding(FindingKind.GUIDE_RULE_BROKEN,
                    key.path() + " is sent " + key.values().size() + " times in one object",
                    "The body carries " + key.path() + " " + key.values().size() + " times inside one object: " + String.join(", ", said)
                            + ". JSON (RFC 8259) says the names in an object should be unique, and FHIR JSON allows a property once. "
                            + "This tool reads the last value, as JSON readers do, and that is the value shown above. Which value the "
                            + "service meant cannot be told from the body. Every value is listed among the fields returned but not "
                            + "interpreted.",
                    null, null, null));
        }
        return out;
    }

    /** Every key the response body repeats, with its values masked as the wire view masks them. Empty when none repeats. */
    public static List<RepeatedKeyRead> repeatedKeys(VendorExchange exchange) {
        if (exchange == null || exchange.json() == null || Texts.blank(exchange.responseBody())) {
            return List.of();
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(exchange.responseBody());
        if (!parsed.repeatedKeys()) {
            return List.of();
        }
        SentSecrets secrets = exchange.request() == null ? null : exchange.request().secrets();
        List<RepeatedKeyRead> out = new ArrayList<>();
        for (JsonSafe.RepeatedKey key : parsed.repeated()) {
            List<String> values = new ArrayList<>();
            for (String value : key.values()) {
                values.add(secrets == null || secrets.searched() == 0 ? value : secrets.mask(value).text());
            }
            out.add(new RepeatedKeyRead(secrets == null || secrets.searched() == 0 ? key.path() : secrets.mask(key.path()).text(), values));
        }
        return out;
    }

    /**
     * What is worth saying about a body that is not JSON beyond that it is not, which the screen
     * says itself: where the parser stopped, and that a place is masked only when one is, so the
     * error panel's "exactly as received" and this never contradict each other.
     */
    public static String notJsonNote(VendorExchange received) {
        boolean masked = received.wireResponse() != null && received.wireResponse().maskedPlaces() > 0;
        String said = (masked ? "It is shown as received except that a place repeating a secret the request carried is masked. " : "")
                + Texts.orElse(received.parseError(), "");
        return said.strip().isEmpty() ? null : said.strip();
    }

    /** One position in the tracked document. */
    public final class Node {

        private final JsonNode node;
        private final String pointer;
        private final String path;

        private Node(JsonNode node, String pointer, String path) {
            this.node = node == null ? tools.jackson.databind.node.MissingNode.getInstance() : node;
            this.pointer = pointer;
            this.path = path;
        }

        /** The named child. Navigation only: nothing is consumed. */
        public Node child(String field) {
            return new Node(JsonSafe.child(node, field), pointer + "/" + escape(field),
                    path.isEmpty() ? displayName(field) : path + "." + displayName(field));
        }

        /** Walks object fields in order. Navigation only. */
        public Node at(String... path) {
            Node current = this;
            if (path != null) {
                for (String field : path) {
                    current = current.child(field);
                }
            }
            return current;
        }

        /**
         * The elements of this array. Navigation only: nothing is consumed, not even an empty array.
         * An empty array therefore appears in the additional fields unless the view that asked for
         * its elements states the emptiness itself and calls {@link #consume()}. That is what keeps
         * "links": [] apart from no links at all without a presence beside every list in every view.
         * Anything that is not an array yields no elements and stays unconsumed.
         */
        public List<Node> elements() {
            List<Node> out = new ArrayList<>();
            if (node.isArray()) {
                int index = 0;
                for (JsonNode element : node) {
                    out.add(new Node(element, pointer + "/" + index, path + "[" + index + "]"));
                    index++;
                }
            }
            return out;
        }

        public List<Node> children(String field) {
            return child(field).elements();
        }

        /** The properties of this object, in document order. Navigation only. */
        public List<Map.Entry<String, Node>> properties() {
            List<Map.Entry<String, Node>> out = new ArrayList<>();
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    out.add(Map.entry(property.getKey(), child(property.getKey())));
                }
            }
            return out;
        }

        /** Reads this node for the view and marks it, with everything beneath it, as consumed. */
        public Shown shown() {
            consume();
            return Shown.of(node);
        }

        public Shown shown(String field) {
            return child(field).shown();
        }

        /**
         * Reads this scalar as text, for a value used in logic or as a key. It is consumed only when
         * a value comes back. An object, an array, an explicit null or a missing node yields null and
         * is left unconsumed, so it still appears in the additional fields: a read that returns
         * nothing must not make the field disappear. A value that is put on screen goes through
         * {@link #shown()}, which keeps null and empty.
         */
        public String text() {
            String value = JsonSafe.text(node);
            if (value != null) {
                consume();
            }
            return value;
        }

        public String text(String field) {
            return child(field).text();
        }

        /** As {@link #text()}, with blank treated as no value. A blank string is left unconsumed. */
        public String presentText() {
            String value = JsonSafe.presentText(node);
            if (value != null) {
                consume();
            }
            return value;
        }

        public String presentText(String field) {
            return child(field).presentText();
        }

        /** An integer, consumed only when the node holds one. */
        public Integer integer(String field) {
            Node child = child(field);
            Integer value = JsonSafe.integer(child.node);
            if (value != null) {
                child.consume();
            }
            return value;
        }

        /** A boolean, consumed only when the node holds one. */
        public Boolean bool(String field) {
            Node child = child(field);
            Boolean value = JsonSafe.bool(child.node);
            if (value != null) {
                child.consume();
            }
            return value;
        }

        /**
         * Everything beneath this node that no view has read, handed to the caller and marked
         * consumed. For a view that shows "the rest of this resource" beside the parts it
         * understood, so those leaves are shown where they belong and do not crowd the list of
         * fields nobody anticipated.
         */
        public List<AdditionalField> remainder() {
            List<AdditionalField> out = new ArrayList<>();
            if (!node.isMissingNode()) {
                walk(node, pointer, path, out);
                consumed.add(pointer);
            }
            return out;
        }

        /**
         * Marks this node and everything beneath it as consumed and returns the raw node. For a view
         * that renders a whole subtree itself, such as a QuestionnaireResponse shown in full.
         */
        public JsonNode consumeSubtree() {
            consume();
            return node;
        }

        /** Marks this node, with everything beneath it, as consumed. A missing node is ignored. */
        public void consume() {
            if (!node.isMissingNode()) {
                consumed.add(pointer);
            }
        }

        /** The raw node for inspection. Looking does not consume. */
        public JsonNode raw() {
            return node;
        }

        public boolean exists() {
            return !node.isMissingNode();
        }

        public Presence presence() {
            return JsonSafe.presence(node);
        }

        public Presence presence(String field) {
            return child(field).presence();
        }

        /**
         * Whether each named child was absent, null, empty or held something, in the order named.
         * For a view that lists the entries of a repeating element: an empty list on screen cannot
         * say on its own whether the element was not returned, returned as null or returned empty,
         * and a screen must say which. Looking does not consume.
         */
        public Map<String, Presence> presences(String... fields) {
            Map<String, Presence> out = new LinkedHashMap<>();
            for (String field : fields) {
                out.put(field, presence(field));
            }
            return Collections.unmodifiableMap(out);
        }

        public boolean isObject() {
            return node.isObject();
        }

        public boolean isArray() {
            return node.isArray();
        }

        /** The JSON Pointer of this node within the document. */
        public String pointer() {
            return pointer;
        }

        /**
         * Where this node sits, written the way {@link AdditionalField#path()} writes it, for example
         * {@code data.member.dob}. A mapper that reports which field a value came from uses this, so
         * that name and the names in the additional fields read alike.
         */
        public String path() {
            return path.isEmpty() ? "(root)" : path;
        }
    }
}
