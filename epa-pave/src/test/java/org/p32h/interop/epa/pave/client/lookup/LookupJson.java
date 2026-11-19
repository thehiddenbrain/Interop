package org.p32h.interop.epa.pave.client.lookup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a response for a mapping test from the path a field is written at, so a test can say
 * "the value sits at name.given[0]" and get the JSON a service would send for that.
 */
final class LookupJson {

    private static final Pattern STEP = Pattern.compile("([^.\\[\\]]+)(?:\\[(\\d+)])?");

    private LookupJson() {
    }

    static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Puts a value at a path such as {@code address.line[1]}, creating what is on the way. Positions before a list index are filled. */
    static ObjectNode put(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length; i++) {
            Matcher matcher = STEP.matcher(parts[i]);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(path);
            }
            String name = matcher.group(1);
            boolean last = i == parts.length - 1;
            if (matcher.group(2) == null) {
                if (last) {
                    current.set(name, value);
                } else {
                    current = current.has(name) ? (ObjectNode) current.get(name) : current.putObject(name);
                }
                continue;
            }
            int index = Integer.parseInt(matcher.group(2));
            ArrayNode list = current.has(name) ? (ArrayNode) current.get(name) : current.putArray(name);
            while (list.size() <= index) {
                list.add("filler " + list.size());
            }
            if (!last) {
                throw new IllegalArgumentException("a list index is only supported at the end of a path: " + path);
            }
            list.set(index, value);
        }
        return root;
    }

    static ObjectNode put(ObjectNode root, String path, String value) {
        return put(root, path, JsonNodeFactory.instance.stringNode(value));
    }

    static ObjectNode put(ObjectNode root, String path, boolean value) {
        return put(root, path, JsonNodeFactory.instance.booleanNode(value));
    }
}
