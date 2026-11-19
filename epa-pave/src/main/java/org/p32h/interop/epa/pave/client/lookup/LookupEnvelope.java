package org.p32h.interop.epa.pave.client.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.TrackedJson;
import tools.jackson.databind.JsonNode;

/**
 * Finds the one record in a lookup response, whatever it is wrapped in, and keeps that question
 * apart from the question of what the record's fields are called.
 *
 * <p>A record may arrive bare, inside an object under one of a few wrapper names, as a list of one,
 * or as a combination of those. Nothing is consumed here: the wrapper's own fields, such as a
 * request id beside {@code data}, stay among the returned fields.
 *
 * <p>The rule is deliberately plain so that it can be narrowed when the contracts are final: a
 * wrapper name is followed when it holds an object, a list or null, and the first wrapper name in
 * the list wins. A record that itself carries an object under a wrapper name would be read from
 * that object. That would show as a lookup that recognised nothing, with every field listed, never
 * as a wrong member.
 */
final class LookupEnvelope {

    enum Kind {
        /** One record was found. */
        RECORD,
        /** The response holds no record: an empty object, an empty list, or a wrapper holding nothing. */
        EMPTY,
        /** A list of more than one record. A lookup by identifier is never answered by choosing. */
        SEVERAL,
        /** Not a record: a single value where an object was expected, or wrapping deeper than this tool follows. */
        NOT_A_RECORD
    }

    /**
     * @param scopes the record first, then the objects that wrap it, nearest first. Empty unless a record was found
     * @param count  how many records came back, when there were several
     */
    record Opened(Kind kind, List<TrackedJson.Node> scopes, int count) {

        TrackedJson.Node record() {
            return scopes.get(0);
        }
    }

    /** Far more wrapping than any service uses. It only stops a response nested without end from being walked forever. */
    private static final int MAX_STEPS = 8;

    private LookupEnvelope() {
    }

    /** @param wrapperNames the names a record may be wrapped in, in the order they are tried */
    static Opened open(TrackedJson.Node root, List<String> wrapperNames) {
        List<TrackedJson.Node> wrapping = new ArrayList<>();
        TrackedJson.Node node = root;
        for (int step = 0; step < MAX_STEPS; step++) {
            Presence presence = node.presence();
            if (presence == Presence.ABSENT || presence == Presence.NULL) {
                return new Opened(Kind.EMPTY, List.of(), 0);
            }
            if (node.isArray()) {
                List<TrackedJson.Node> elements = node.elements();
                if (elements.isEmpty()) {
                    return new Opened(Kind.EMPTY, List.of(), 0);
                }
                if (elements.size() > 1) {
                    return new Opened(Kind.SEVERAL, List.of(), elements.size());
                }
                node = elements.get(0);
                continue;
            }
            if (!node.isObject()) {
                return new Opened(Kind.NOT_A_RECORD, List.of(), 0);
            }
            if (presence == Presence.EMPTY) {
                return new Opened(Kind.EMPTY, List.of(), 0);
            }
            TrackedJson.Node inner = wrapped(node, wrapperNames);
            if (inner == null) {
                List<TrackedJson.Node> scopes = new ArrayList<>();
                scopes.add(node);
                scopes.addAll(wrapping);
                return new Opened(Kind.RECORD, scopes, 1);
            }
            wrapping.add(0, node);
            node = inner;
        }
        return new Opened(Kind.NOT_A_RECORD, List.of(), 0);
    }

    private static TrackedJson.Node wrapped(TrackedJson.Node object, List<String> wrapperNames) {
        for (String wrapper : wrapperNames) {
            String wanted = Spelling.normalise(wrapper);
            for (Map.Entry<String, TrackedJson.Node> property : object.properties()) {
                TrackedJson.Node child = property.getValue();
                // A single value under a wrapper name, such as "result": "ok", is a field and not a wrapper.
                boolean holdsARecordOrNothing = child.isObject() || child.isArray() || child.presence() == Presence.NULL;
                if (holdsARecordOrNothing && Spelling.normalise(property.getKey()).equals(wanted)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * True when anything beneath the node is a value, and not a null, a blank string or an empty
     * list or object. The identifier that was asked for does not count: a service that answers an
     * unknown identifier by handing it back with nothing else has returned no record.
     *
     * @param asked the identifier that was looked up
     */
    static boolean holdsAValue(JsonNode node, String asked) {
        if (JsonSafe.isObject(node)) {
            return JsonSafe.properties(node).stream().anyMatch(property -> holdsAValue(property.getValue(), asked));
        }
        if (JsonSafe.isArray(node)) {
            return JsonSafe.elements(node).stream().anyMatch(element -> holdsAValue(element, asked));
        }
        String text = JsonSafe.presentText(node);
        return text != null && !MemberIds.noSpace(text).equalsIgnoreCase(asked);
    }
}
