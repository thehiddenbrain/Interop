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

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    private static String displayName(String name) {
        // A name holding path punctuation is quoted so the displayed path stays unambiguous.
        boolean plain = !name.isEmpty() && name.chars().noneMatch(c -> c == '.' || c == '[' || c == ']' || c == '"' || Character.isWhitespace(c));
        return plain ? name : "\"" + name.replace("\"", "\\\"") + "\"";
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
