package org.p32h.interop.epa.pave.service.compare;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.p32h.interop.epa.pave.model.JsonSafe;
import tools.jackson.databind.JsonNode;

/**
 * Decides which element of one list is compared with which element of the other.
 *
 * <p>A list in FHIR is rarely ordered by meaning, so comparing by position alone would report two
 * codings in a different order as two changed codings. The rules, first that applies:
 * <ol>
 *   <li>the entries of a Bundle: by resource type and id where both sides carry such a resource,
 *       and what is left by position among the entries of the same resource type that are left.
 *       One entry of a type left on each side is therefore matched whatever its id</li>
 *   <li>objects that all carry a {@code url}, which is what extensions are: by url, then by
 *       position among equal urls</li>
 *   <li>objects of which at least one carries a {@code system}, which is what codings,
 *       identifiers and telecoms are: by system, then by position among equal systems. Those
 *       with no system are matched among themselves by position, and never with one that has a system</li>
 *   <li>objects that all say what they are in a {@code type} with a coded value, which is how
 *       the classes of a coverage tell a plan from a group: by the code of that type, then by
 *       position among equal codes. Without this a sender that lists the group before the plan
 *       would be reported as holding another plan</li>
 *   <li>scalars: by value, which the engine does itself because order then matters as a whole</li>
 *   <li>anything else: by position</li>
 * </ol>
 * An element with no partner is reported as being in one list only. It is never paired with
 * whatever else happens to be left over: that would compare two different things and report
 * every one of their values as changed.
 */
final class ArrayMatcher {

    enum Rule { BUNDLE_ENTRY, URL, SYSTEM, TYPE, SCALARS, POSITION }

    /**
     * One element of the tool's list with its partner, or one element that has none.
     *
     * @param toolIndex -1 when only the other list has the element
     * @param yourIndex -1 when only the tool's list has the element
     * @param label     what goes in the brackets of the path: what the element was matched on
     * @param keyed     false for an element matched on the absence of a url or a system
     */
    record Match(int toolIndex, int yourIndex, String label, boolean keyed) {

        boolean paired() {
            return toolIndex >= 0 && yourIndex >= 0;
        }
    }

    /** The matches in the order of the tool's list, followed by what only the other list has, in its order. */
    record Plan(Rule rule, List<Match> matches) {

        boolean unmatchedOnBothSides() {
            return matches.stream().anyMatch(m -> m.yourIndex() < 0) && matches.stream().anyMatch(m -> m.toolIndex() < 0);
        }
    }

    private ArrayMatcher() {
    }

    /**
     * @param bundleEntries true when both lists are the {@code entry} of a FHIR Bundle
     */
    static Plan plan(boolean bundleEntries, JsonNode tool, JsonNode yours) {
        boolean objects = all(tool, JsonNode::isObject) && all(yours, JsonNode::isObject);
        if (objects && bundleEntries) {
            return new Plan(Rule.BUNDLE_ENTRY, entries(tool, yours));
        }
        if (objects && all(tool, ArrayMatcher::hasUrl) && all(yours, ArrayMatcher::hasUrl)) {
            return new Plan(Rule.URL, keyed(tool, yours, node -> JsonSafe.text(node, "url"), "no url"));
        }
        if (objects && (any(tool, ArrayMatcher::hasSystem) || any(yours, ArrayMatcher::hasSystem))) {
            return new Plan(Rule.SYSTEM, keyed(tool, yours, node -> hasSystem(node) ? JsonSafe.text(node, "system") : null, "no system"));
        }
        if (objects && all(tool, ArrayMatcher::hasTypeCode) && all(yours, ArrayMatcher::hasTypeCode)) {
            return new Plan(Rule.TYPE, keyed(tool, yours, ArrayMatcher::typeCode, "no type"));
        }
        if (all(tool, ArrayMatcher::scalar) && all(yours, ArrayMatcher::scalar)) {
            return new Plan(Rule.SCALARS, List.of());
        }
        return new Plan(Rule.POSITION, keyed(tool, yours, node -> "", null));
    }

    private static boolean hasUrl(JsonNode node) {
        return JsonSafe.child(node, "url").isString();
    }

    private static boolean hasSystem(JsonNode node) {
        return JsonSafe.child(node, "system").isString();
    }

    /** The first code among the codings of an element's type, or null when its type carries none. */
    private static String typeCode(JsonNode node) {
        for (JsonNode coding : JsonSafe.elements(JsonSafe.child(node, "type"), "coding")) {
            if (JsonSafe.child(coding, "code").isString()) {
                return JsonSafe.text(coding, "code");
            }
        }
        return null;
    }

    private static boolean hasTypeCode(JsonNode node) {
        return typeCode(node) != null;
    }

    private static boolean scalar(JsonNode node) {
        return !node.isContainer();
    }

    private static boolean all(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode element : array) {
            if (!test.test(element)) {
                return false;
            }
        }
        return true;
    }

    private static boolean any(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode element : array) {
            if (test.test(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches by a key, then by position among elements with the same key.
     *
     * @param keyOf      the key of an element. Null is a key of its own: "has none"
     * @param noKeyLabel how an element with no key is labelled. Null means the list is matched by
     *                   position alone, and the label is then the position
     */
    private static List<Match> keyed(JsonNode tool, JsonNode yours, Function<JsonNode, String> keyOf, String noKeyLabel) {
        boolean byPosition = noKeyLabel == null;
        Map<String, Deque<Integer>> yourPositions = new HashMap<>();
        Map<String, Integer> toolCount = new HashMap<>();
        Map<String, Integer> yourCount = new HashMap<>();
        for (int j = 0; j < yours.size(); j++) {
            String key = keyOf.apply(yours.get(j));
            yourPositions.computeIfAbsent(key, k -> new ArrayDeque<>()).add(j);
            yourCount.merge(key, 1, Integer::sum);
        }
        for (JsonNode element : tool) {
            toolCount.merge(keyOf.apply(element), 1, Integer::sum);
        }
        List<Match> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean[] taken = new boolean[yours.size()];
        for (int i = 0; i < tool.size(); i++) {
            String key = keyOf.apply(tool.get(i));
            int ordinal = seen.merge(key, 1, Integer::sum);
            Deque<Integer> candidates = yourPositions.get(key);
            int partner = candidates == null || candidates.isEmpty() ? -1 : candidates.poll();
            if (partner >= 0) {
                taken[partner] = true;
            }
            boolean several = Math.max(toolCount.getOrDefault(key, 0), yourCount.getOrDefault(key, 0)) > 1;
            out.add(new Match(i, partner, byPosition ? String.valueOf(i) : label(key, noKeyLabel, ordinal, several), key != null));
        }
        // What is left on the other side, in its own order. Its ordinal continues after the tool's
        // elements with the same key, so no two elements of one list ever share a label.
        Map<String, Integer> extra = new HashMap<>();
        for (int j = 0; j < yours.size(); j++) {
            if (!taken[j]) {
                String key = keyOf.apply(yours.get(j));
                int ordinal = toolCount.getOrDefault(key, 0) + extra.merge(key, 1, Integer::sum);
                boolean several = Math.max(toolCount.getOrDefault(key, 0), yourCount.getOrDefault(key, 0)) > 1;
                out.add(new Match(-1, j, byPosition ? String.valueOf(j) : label(key, noKeyLabel, ordinal, several), key != null));
            }
        }
        return out;
    }

    private static String label(String key, String noKeyLabel, int ordinal, boolean several) {
        return (key == null ? noKeyLabel : key) + (several ? " #" + ordinal : "");
    }

    /** The resource type and id of what a Bundle entry holds. Both null when it holds no resource. */
    private record Held(String type, String id) {

        static Held of(JsonNode entry) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            String type = ResourceIndex.resourceType(resource);
            return new Held(type, type == null ? null : JsonSafe.presentText(JsonSafe.child(resource, "id")));
        }
    }

    private static List<Match> entries(JsonNode tool, JsonNode yours) {
        List<Held> toolHeld = new ArrayList<>();
        List<Held> yourHeld = new ArrayList<>();
        tool.forEach(entry -> toolHeld.add(Held.of(entry)));
        yours.forEach(entry -> yourHeld.add(Held.of(entry)));
        int[] partner = new int[toolHeld.size()];
        java.util.Arrays.fill(partner, -1);
        boolean[] taken = new boolean[yourHeld.size()];

        // First by type and id, where both sides carry such a resource.
        Map<Held, Deque<Integer>> yourByIdentity = new HashMap<>();
        for (int j = 0; j < yourHeld.size(); j++) {
            if (yourHeld.get(j).id() != null) {
                yourByIdentity.computeIfAbsent(yourHeld.get(j), k -> new ArrayDeque<>()).add(j);
            }
        }
        for (int i = 0; i < toolHeld.size(); i++) {
            Deque<Integer> same = toolHeld.get(i).id() == null ? null : yourByIdentity.get(toolHeld.get(i));
            if (same != null && !same.isEmpty()) {
                partner[i] = same.poll();
                taken[partner[i]] = true;
            }
        }
        // Then what is left, by position among the entries of the same resource type that are left.
        Map<String, Deque<Integer>> yourLeftByType = new HashMap<>();
        for (int j = 0; j < yourHeld.size(); j++) {
            if (!taken[j]) {
                yourLeftByType.computeIfAbsent(yourHeld.get(j).type(), k -> new ArrayDeque<>()).add(j);
            }
        }
        for (int i = 0; i < toolHeld.size(); i++) {
            Deque<Integer> left = partner[i] >= 0 ? null : yourLeftByType.get(toolHeld.get(i).type());
            if (left != null && !left.isEmpty()) {
                partner[i] = left.poll();
                taken[partner[i]] = true;
            }
        }

        Map<String, Integer> toolCount = new HashMap<>();
        Map<String, Integer> yourCount = new HashMap<>();
        toolHeld.forEach(held -> toolCount.merge(held.type(), 1, Integer::sum));
        yourHeld.forEach(held -> yourCount.merge(held.type(), 1, Integer::sum));
        List<Match> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < toolHeld.size(); i++) {
            String type = toolHeld.get(i).type();
            int ordinal = seen.merge(type, 1, Integer::sum);
            boolean several = Math.max(toolCount.getOrDefault(type, 0), yourCount.getOrDefault(type, 0)) > 1;
            out.add(new Match(i, partner[i], label(type, "no resource", ordinal, several), type != null));
        }
        Map<String, Integer> extra = new HashMap<>();
        for (int j = 0; j < yourHeld.size(); j++) {
            if (!taken[j]) {
                String type = yourHeld.get(j).type();
                int ordinal = toolCount.getOrDefault(type, 0) + extra.merge(type, 1, Integer::sum);
                boolean several = Math.max(toolCount.getOrDefault(type, 0), yourCount.getOrDefault(type, 0)) > 1;
                out.add(new Match(-1, j, label(type, "no resource", ordinal, several), type != null));
            }
        }
        return out;
    }
}
