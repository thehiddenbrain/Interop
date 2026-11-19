package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.HashSet;
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
        String value = presence == Presence.VALUE ? node.toString() : null;
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
         * The elements of this array. An empty array is marked consumed, because the view that
         * asked for its elements is the view that reports it as empty. Anything that is not an
         * array yields no elements and stays unconsumed, so it appears in the additional fields.
         */
        public List<Node> elements() {
            List<Node> out = new ArrayList<>();
            if (node.isArray()) {
                if (node.isEmpty()) {
                    consumed.add(pointer);
                }
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

        /** Reads this scalar as text for the view and marks it consumed. Null when there is no scalar. */
        public String text() {
            consume();
            return JsonSafe.text(node);
        }

        public String text(String field) {
            return child(field).text();
        }

        /** As {@link #text()}, with blank treated as no value. */
        public String presentText() {
            consume();
            return JsonSafe.presentText(node);
        }

        public String presentText(String field) {
            return child(field).presentText();
        }

        public Integer integer(String field) {
            Node child = child(field);
            child.consume();
            return JsonSafe.integer(child.node);
        }

        public Boolean bool(String field) {
            Node child = child(field);
            child.consume();
            return JsonSafe.bool(child.node);
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
