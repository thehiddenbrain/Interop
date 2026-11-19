package org.p32h.interop.epa.pave.service.compare;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.service.compare.Values.JsonType;
import tools.jackson.databind.JsonNode;

/**
 * Compares two JSON documents, the one this tool built and the one a tester supplied, and
 * explains each difference.
 *
 * <p>It knows JSON and the general shapes of FHIR: a resource states its type, a Bundle holds
 * entries, an id is a label, and a reference points at a resource by that label. It knows nothing
 * about any one kind of document. What an element is, and which part it plays, it asks of the
 * {@link Glossary}; which objects have free keys and which strings are references it is told by
 * the {@link CompareRules}.
 *
 * <p>The rules it applies:
 * <ul>
 *   <li>Objects are compared key by key. A key only one document has is one difference for
 *       everything beneath it, never one per leaf.</li>
 *   <li>Absent, null and empty are three different things, and each is worded as what it is.</li>
 *   <li>Two scalars are equal when they have the same JSON type and the same value. A string
 *       against a number is a different type even when the two read the same.</li>
 *   <li>The same number written with a different precision is reported as written differently:
 *       neither as equal nor as a different value.</li>
 *   <li>Lists are matched as {@link ArrayMatcher} describes.</li>
 *   <li>Two resources of different types are not compared beneath: they are one difference. The
 *       one exception is a resource against a Bundle that holds exactly one resource of that
 *       type, which is how the answer to a FHIR search carries it: the wrapping is one
 *       difference, and the two resources are then compared with each other.</li>
 *   <li>When two matched resources have different ids, that is one difference, a label. A
 *       reference that differs only because it follows such a pair is counted on that difference
 *       and not listed. A reference that differs for any other reason is listed.</li>
 * </ul>
 * Differences come back in the document order of the tool's document. What only the other
 * document has follows its neighbours.
 *
 * <p>The comparison never goes deeper than {@link #MAX_DEPTH} levels. Where it stops it says so
 * in a note, because a comparison that quietly skipped a subtree would report two documents as
 * closer than they are.
 */
public final class JsonComparer {

    /** The deepest level compared. The top of the document is level one. */
    public static final int MAX_DEPTH = 64;

    /**
     * What a comparison found.
     *
     * @param notes what a reader should know about the comparison as a whole
     */
    public record Result(List<Difference> differences, List<String> notes) {

        public Result {
            differences = List.copyOf(differences);
            notes = List.copyOf(notes);
        }

        /** Prints the counts and never a difference's values. */
        @Override
        public String toString() {
            return "Result[differences=" + differences.size() + ", notes=" + notes.size() + "]";
        }
    }

    /** How the group of a difference is decided. */
    private enum Grouping {
        /** By the role of the element: a difference in a value. */
        ROLE,
        /** A difference in shape, which is part of how a request is built whatever the element is. */
        STRUCTURE
    }

    private static final String BUNDLE = "Bundle";

    private final CompareRules rules;
    private final Glossary glossary;

    public JsonComparer(CompareRules rules, Glossary glossary) {
        this.rules = rules == null ? CompareRules.none() : rules;
        this.glossary = glossary == null ? Glossary.NONE : glossary;
    }

    /**
     * @param tool  the document this tool built
     * @param yours the document it is compared with
     */
    public Result compare(JsonNode tool, JsonNode yours) {
        if (tool == null || yours == null) {
            throw new IllegalArgumentException("A comparison needs two documents.");
        }
        Run run = new Run(tool, yours);
        run.walk(Place.root(), tool, yours, null, null, null);
        return run.finish();
    }

    /**
     * One place in the comparison: where it is written for people, where it is in each document,
     * and how the glossary knows it.
     */
    private record Place(String path, String toolPointer, String yourPointer, String document, String fhir, int depth) {

        static Place root() {
            return new Place("", "", "", "", null, 1);
        }

        Place key(String key, boolean freeKey) {
            return new Place(Paths.join(path, Paths.display(key)), Paths.member(toolPointer, key), Paths.member(yourPointer, key),
                    Paths.join(document, freeKey ? "*" : key), fhir == null ? null : fhir + "." + key, depth + 1);
        }

        /** Two members of a free-keyed object that hold the same resource type under different keys. */
        Place matchedByType(String resourceType, String toolKey, String yourKey) {
            return new Place(path + "[" + resourceType + "]", Paths.member(toolPointer, toolKey), Paths.member(yourPointer, yourKey),
                    Paths.join(document, "*"), null, depth + 1);
        }

        Place element(String label, int toolIndex, int yourIndex) {
            return new Place(path + "[" + label + "]", toolIndex < 0 ? null : Paths.element(toolPointer, toolIndex),
                    yourIndex < 0 ? null : Paths.element(yourPointer, yourIndex), document, fhir, depth + 1);
        }

        /** The same place, known to be a resource of this type: the glossary then sees the type alone. */
        Place resource(String resourceType) {
            return new Place(path, toolPointer, yourPointer, document, resourceType, depth);
        }

        /**
         * The same place, where one document holds its resource inside an entry of a Bundle. The
         * path stays the one of the bare resource, and the pointer on the wrapped side goes into
         * the entry, so a screen marks the resource itself in both texts.
         */
        Place insideEntry(boolean yoursIsWrapped, int entry) {
            String wrapped = Paths.member(Paths.element(Paths.member(yoursIsWrapped ? yourPointer : toolPointer, "entry"), entry), "resource");
            return new Place(path, yoursIsWrapped ? toolPointer : wrapped, yoursIsWrapped ? wrapped : yourPointer, document, fhir, depth);
        }

        Place onlyInTool() {
            return new Place(path, toolPointer, null, document, fhir, depth);
        }

        Place onlyInYours() {
            return new Place(path, null, yourPointer, document, fhir, depth);
        }

        ElementPath element() {
            return new ElementPath(document, fhir);
        }

        String shownPath() {
            return path.isEmpty() ? "(the whole request)" : path;
        }
    }

    /** What a slot in the list of differences is waiting for. */
    private enum Pending { NOTHING, PAIR_ID, REFERENCE, REFERENCE_LIST }

    /**
     * One position in the list of differences. A difference that depends on which resources turn
     * out to be matched, and those are known only once the whole document has been walked, keeps
     * its position here and is filled in at the end.
     */
    private static final class Slot {
        private final Pending pending;
        private final Place at;
        private final JsonNode tool;
        private final JsonNode yours;
        private final List<Difference> ready = new ArrayList<>();
        private String bareType;
        private boolean fullUrl;
        private JsonNode toolOwner;
        private JsonNode yourOwner;

        private Slot(Pending pending, Place at, JsonNode tool, JsonNode yours) {
            this.pending = pending;
            this.at = at;
            this.tool = tool;
            this.yours = yours;
        }
    }

    /** One reference that differs only because it follows a matched pair of resources. */
    private record Follower(Slot slot, Place at, JsonNode tool, JsonNode yours) {
    }

    /**
     * The references that follow each matched pair, by the tool's resource of the pair. Resources
     * are kept by identity, since two can be equal and distinct, and in the order they were first
     * followed, so the same two documents always give the same list of differences.
     */
    private static final class Followers {
        private final Map<JsonNode, List<Follower>> byResource = new IdentityHashMap<>();
        private final List<JsonNode> order = new ArrayList<>();

        private void add(JsonNode toolResource, Follower follower) {
            List<Follower> list = byResource.get(toolResource);
            if (list == null) {
                list = new ArrayList<>();
                byResource.put(toolResource, list);
                order.add(toolResource);
            }
            list.add(follower);
        }

        private List<Follower> of(JsonNode toolResource) {
            return byResource.getOrDefault(toolResource, List.of());
        }

        private List<JsonNode> resources() {
            return order;
        }
    }

    /** One comparison. Nothing here outlives the call, so the comparer itself holds no state. */
    private final class Run {

        private final ResourceIndex toolIndex;
        private final ResourceIndex yourIndex;
        private final List<Slot> slots = new ArrayList<>();
        /** The tool's resource to the resource it was compared with. By identity: two resources can be equal and distinct. */
        private final Map<JsonNode, JsonNode> pairs = new IdentityHashMap<>();
        private String depthFirstPath;
        private int depthPlaces;

        private Run(JsonNode tool, JsonNode yours) {
            this.toolIndex = ResourceIndex.of(tool, rules);
            this.yourIndex = ResourceIndex.of(yours, rules);
        }

        // ---- the walk ----

        /**
         * @param key        the key the two nodes sit under, or null for the top and for list elements
         * @param toolParent the object or list that holds the tool's node, or null at the top
         */
        private void walk(Place at, JsonNode tool, JsonNode yours, String key, JsonNode toolParent, JsonNode yourParent) {
            JsonType type = JsonType.of(tool);
            if (type != JsonType.of(yours)) {
                add(at, DifferenceKind.DIFFERENT_TYPE, tool, yours, Grouping.STRUCTURE, null, Wording.differentType(tool, yours), null);
                return;
            }
            switch (type) {
                case OBJECT -> objects(at, tool, yours);
                case ARRAY -> arrays(at, tool, yours, key, toolParent, yourParent);
                case STRING -> strings(at, tool, yours, key, toolParent, yourParent);
                case NUMBER -> numbers(at, tool, yours);
                case BOOLEAN -> {
                    if (tool.booleanValue() != yours.booleanValue()) {
                        add(at, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.ROLE, null, Wording.differentBoolean(tool.booleanValue()), null);
                    }
                }
                case NULL -> {
                    // Two explicit nulls are the same thing.
                }
            }
        }

        private void objects(Place here, JsonNode tool, JsonNode yours) {
            String toolType = ResourceIndex.resourceType(tool);
            String yourType = ResourceIndex.resourceType(yours);
            if (toolType != null && yourType != null && !toolType.equals(yourType)) {
                int inYours = BUNDLE.equals(yourType) ? onlyEntryOf(yours, toolType) : -1;
                int inTool = BUNDLE.equals(toolType) ? onlyEntryOf(tool, yourType) : -1;
                if (inYours >= 0 || inTool >= 0) {
                    boolean yoursIsWrapped = inYours >= 0;
                    add(here, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.STRUCTURE, null,
                            Wording.wrappedInBundle(yoursIsWrapped, yoursIsWrapped ? toolType : yourType), null);
                    walk(here.insideEntry(yoursIsWrapped, yoursIsWrapped ? inYours : inTool),
                            yoursIsWrapped ? tool : tool.get("entry").get(inTool).get("resource"),
                            yoursIsWrapped ? yours.get("entry").get(inYours).get("resource") : yours, null, null, null);
                    return;
                }
                add(here, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.STRUCTURE, null, Wording.differentResources(toolType, yourType), null);
                return;
            }
            if (toolType != null && yourType != null) {
                pairs.put(tool, yours);
            }
            String resourceType = toolType != null ? toolType : yourType;
            Place at = resourceType == null ? here : here.resource(resourceType);
            if (tool.isEmpty() && yours.isEmpty()) {
                return;
            }
            if (tool.isEmpty() || yours.isEmpty()) {
                add(at, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.STRUCTURE, null, Wording.emptiness(tool, yours), null);
                return;
            }
            if (at.depth() >= MAX_DEPTH) {
                depthReached(at);
                return;
            }
            if (rules.freeKeyedAt(at.document())) {
                freeKeyed(at, tool, yours);
                return;
            }
            for (Map.Entry<String, JsonNode> member : tool.properties()) {
                String key = member.getKey();
                JsonNode other = yours.get(key);
                if (other == null) {
                    only(true, at.key(key, false), member.getValue(), Grouping.STRUCTURE, Wording.onlyMember(true, member.getValue()));
                } else {
                    walk(at.key(key, false), member.getValue(), other, key, tool, yours);
                }
            }
            for (Map.Entry<String, JsonNode> member : yours.properties()) {
                if (tool.get(member.getKey()) == null) {
                    only(false, at.key(member.getKey(), false), member.getValue(), Grouping.STRUCTURE, Wording.onlyMember(false, member.getValue()));
                }
            }
        }

        /** The position of the only entry of a Bundle that holds a resource of this type, or -1 when there is none or more than one. */
        private int onlyEntryOf(JsonNode bundle, String resourceType) {
            int found = -1;
            JsonNode entries = bundle.get("entry");
            for (int i = 0; entries != null && entries.isArray() && i < entries.size(); i++) {
                if (resourceType.equals(ResourceIndex.resourceType(entries.get(i).get("resource")))) {
                    if (found >= 0) {
                        return -1;
                    }
                    found = i;
                }
            }
            return found;
        }

        /**
         * An object whose keys are free. Members are matched by key first. What is left on both
         * sides is matched by resource type when exactly one member of that type is left on each
         * side, and reported as the same content under a different key.
         */
        private void freeKeyed(Place at, JsonNode tool, JsonNode yours) {
            Map<String, List<String>> toolLeft = new LinkedHashMap<>();
            Map<String, List<String>> yourLeft = new HashMap<>();
            for (Map.Entry<String, JsonNode> member : tool.properties()) {
                String type = ResourceIndex.resourceType(member.getValue());
                if (yours.get(member.getKey()) == null && type != null) {
                    toolLeft.computeIfAbsent(type, k -> new ArrayList<>()).add(member.getKey());
                }
            }
            for (Map.Entry<String, JsonNode> member : yours.properties()) {
                String type = ResourceIndex.resourceType(member.getValue());
                if (tool.get(member.getKey()) == null && type != null) {
                    yourLeft.computeIfAbsent(type, k -> new ArrayList<>()).add(member.getKey());
                }
            }
            Map<String, String> renamed = new HashMap<>();
            Map<String, String> renamedFrom = new HashMap<>();
            for (Map.Entry<String, List<String>> left : toolLeft.entrySet()) {
                List<String> theirs = yourLeft.get(left.getKey());
                if (left.getValue().size() == 1 && theirs != null && theirs.size() == 1) {
                    renamed.put(left.getValue().get(0), theirs.get(0));
                    renamedFrom.put(theirs.get(0), left.getValue().get(0));
                }
            }
            for (Map.Entry<String, JsonNode> member : tool.properties()) {
                String key = member.getKey();
                JsonNode other = yours.get(key);
                if (other != null) {
                    walk(at.key(key, true), member.getValue(), other, key, tool, yours);
                } else if (renamed.containsKey(key)) {
                    String yourKey = renamed.get(key);
                    String type = ResourceIndex.resourceType(member.getValue());
                    Place matched = at.matchedByType(type, key, yourKey);
                    slots.add(ready(difference(matched.resource(type), DifferenceKind.DIFFERENT_KEY, Shown.of(key), Shown.of(yourKey), null,
                            null, Grouping.STRUCTURE, null, Wording.differentKey(type, key, yourKey), null)));
                    walk(matched, member.getValue(), yours.get(yourKey), key, tool, yours);
                } else {
                    only(true, at.key(key, true), member.getValue(), Grouping.STRUCTURE, Wording.onlyKey(true, key, member.getValue()));
                }
            }
            for (Map.Entry<String, JsonNode> member : yours.properties()) {
                String key = member.getKey();
                if (tool.get(key) == null && !renamedFrom.containsKey(key)) {
                    only(false, at.key(key, true), member.getValue(), Grouping.STRUCTURE, Wording.onlyKey(false, key, member.getValue()));
                }
            }
        }

        private void arrays(Place at, JsonNode tool, JsonNode yours, String key, JsonNode toolParent, JsonNode yourParent) {
            if (tool.isEmpty() && yours.isEmpty()) {
                return;
            }
            if (tool.isEmpty() || yours.isEmpty()) {
                add(at, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.STRUCTURE, null, Wording.emptiness(tool, yours), null);
                return;
            }
            if (at.depth() >= MAX_DEPTH) {
                depthReached(at);
                return;
            }
            if (rules.referenceAt(at.document()) && allStrings(tool) && allStrings(yours)) {
                Slot slot = new Slot(Pending.REFERENCE_LIST, at, tool, yours);
                slot.bareType = rules.bareTypeAt(at.document());
                slots.add(slot);
                return;
            }
            boolean bundle = "entry".equals(key) && BUNDLE.equals(ResourceIndex.resourceType(toolParent))
                    && BUNDLE.equals(ResourceIndex.resourceType(yourParent));
            ArrayMatcher.Plan plan = ArrayMatcher.plan(bundle, tool, yours);
            if (plan.rule() == ArrayMatcher.Rule.SCALARS) {
                scalars(at, tool, yours);
                return;
            }
            boolean extraOnBothSides = plan.unmatchedOnBothSides();
            for (ArrayMatcher.Match match : plan.matches()) {
                Place element = at.element(match.label(), match.toolIndex(), match.yourIndex());
                if (match.paired()) {
                    walk(element, tool.get(match.toolIndex()), yours.get(match.yourIndex()), null, tool, yours);
                    continue;
                }
                boolean inTool = match.toolIndex() >= 0;
                JsonNode value = inTool ? tool.get(match.toolIndex()) : yours.get(match.yourIndex());
                String sentence = switch (plan.rule()) {
                    case BUNDLE_ENTRY -> Wording.onlyEntry(inTool, value);
                    case URL -> Wording.onlyKeyed(inTool, "url", match.keyed(), extraOnBothSides);
                    case SYSTEM -> Wording.onlyKeyed(inTool, "system", match.keyed(), extraOnBothSides);
                    default -> Wording.onlyPosition(inTool);
                };
                only(inTool, element, value, Grouping.STRUCTURE, sentence);
            }
        }

        private boolean allStrings(JsonNode array) {
            for (JsonNode element : array) {
                if (!element.isString()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Two lists of scalars: equal when they are the same list. The same values in another
         * order is one difference. Otherwise each value one list has and the other lacks is one.
         */
        private void scalars(Place at, JsonNode tool, JsonNode yours) {
            Map<String, Deque<Integer>> yourPositions = new HashMap<>();
            for (int j = 0; j < yours.size(); j++) {
                yourPositions.computeIfAbsent(scalarKey(yours.get(j)), k -> new ArrayDeque<>()).add(j);
            }
            int[] partner = new int[tool.size()];
            Arrays.fill(partner, -1);
            boolean[] taken = new boolean[yours.size()];
            boolean inOrder = true;
            boolean complete = tool.size() == yours.size();
            for (int i = 0; i < tool.size(); i++) {
                Deque<Integer> same = yourPositions.get(scalarKey(tool.get(i)));
                if (same == null || same.isEmpty()) {
                    complete = false;
                    continue;
                }
                partner[i] = same.poll();
                taken[partner[i]] = true;
                inOrder &= partner[i] == i;
            }
            if (complete && !inOrder) {
                add(at, DifferenceKind.DIFFERENT_ORDER, tool, yours, Grouping.ROLE, null, Wording.differentOrder(), null);
            }
            for (int i = 0; i < tool.size(); i++) {
                if (partner[i] < 0) {
                    only(true, at.element(String.valueOf(i), i, -1), tool.get(i), Grouping.ROLE, Wording.onlyValue(true));
                } else if (!Values.text(tool.get(i)).equals(Values.text(yours.get(partner[i])))) {
                    // The key treats 1 and 1.0 as one number, so what is left here is how it was written.
                    add(at.element(String.valueOf(i), i, partner[i]), DifferenceKind.WRITTEN_DIFFERENTLY, tool.get(i), yours.get(partner[i]),
                            Grouping.ROLE, null, Wording.writtenDifferently(), null);
                }
            }
            for (int j = 0; j < yours.size(); j++) {
                if (!taken[j]) {
                    only(false, at.element(String.valueOf(j), -1, j), yours.get(j), Grouping.ROLE, Wording.onlyValue(false));
                }
            }
        }

        /** What makes two scalars the same value: the JSON type, and the number or the text. */
        private String scalarKey(JsonNode node) {
            JsonType type = JsonType.of(node);
            // stripTrailingZeros gives equal numbers one form without ever writing out a large exponent.
            String value = type == JsonType.NUMBER ? node.decimalValue().stripTrailingZeros().toString() : Values.text(node);
            return type.wire() + ":" + value;
        }

        private void strings(Place at, JsonNode tool, JsonNode yours, String key, JsonNode toolParent, JsonNode yourParent) {
            String toolText = Values.text(tool);
            String yourText = Values.text(yours);
            if (toolText.equals(yourText)) {
                return;
            }
            if (key != null) {
                if (key.equals("id") && pairs.get(toolParent) == yourParent) {
                    Slot slot = new Slot(Pending.PAIR_ID, at, tool, yours);
                    slot.toolOwner = toolParent;
                    slots.add(slot);
                    return;
                }
                boolean fullUrl = key.equals("fullUrl") && at.fhir() != null;
                if (fullUrl || (key.equals("reference") && at.fhir() != null) || rules.referenceAt(at.document())) {
                    Slot slot = new Slot(Pending.REFERENCE, at, tool, yours);
                    slot.bareType = rules.bareTypeAt(at.document());
                    slot.fullUrl = fullUrl;
                    // A full url is the address of the resource in its own entry, so that is what it points at.
                    slot.toolOwner = fullUrl ? toolParent.get("resource") : null;
                    slot.yourOwner = fullUrl ? yourParent.get("resource") : null;
                    slots.add(slot);
                    return;
                }
            }
            add(at, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.ROLE, null, Wording.differentText(toolText, yourText), null);
        }

        private void numbers(Place at, JsonNode tool, JsonNode yours) {
            BigDecimal toolNumber = tool.decimalValue();
            BigDecimal yourNumber = yours.decimalValue();
            if (toolNumber.compareTo(yourNumber) != 0) {
                add(at, DifferenceKind.DIFFERENT_VALUE, tool, yours, Grouping.ROLE, null, Wording.differentNumber(), null);
            } else if (!Values.text(tool).equals(Values.text(yours))) {
                add(at, DifferenceKind.WRITTEN_DIFFERENTLY, tool, yours, Grouping.ROLE, null, Wording.writtenDifferently(), null);
            }
        }

        private void depthReached(Place at) {
            if (depthFirstPath == null) {
                depthFirstPath = at.shownPath();
            }
            depthPlaces++;
        }

        // ---- differences ----

        private void only(boolean inTool, Place at, JsonNode value, Grouping grouping, String sentence) {
            String resourceType = ResourceIndex.resourceType(value);
            Place place = inTool ? at.onlyInTool() : at.onlyInYours();
            add(resourceType == null ? place : place.resource(resourceType), inTool ? DifferenceKind.ONLY_IN_TOOL : DifferenceKind.ONLY_IN_YOURS,
                    inTool ? value : null, inTool ? null : value, grouping, null, sentence, null);
        }

        private void add(Place at, DifferenceKind kind, JsonNode tool, JsonNode yours, Grouping grouping, DifferenceGroup forced,
                         String explanation, Integer following) {
            slots.add(ready(difference(at, kind, tool, yours, grouping, forced, explanation, following)));
        }

        private Slot ready(Difference difference) {
            Slot slot = new Slot(Pending.NOTHING, null, null, null);
            slot.ready.add(difference);
            return slot;
        }

        private Difference difference(Place at, DifferenceKind kind, JsonNode tool, JsonNode yours, Grouping grouping,
                                      DifferenceGroup forced, String explanation, Integer following) {
            return difference(at, kind, Values.shown(tool), Values.shown(yours), Values.type(tool), Values.type(yours), grouping, forced,
                    explanation, following);
        }

        /**
         * @param forced the group, when the engine knows it from the shape of FHIR whatever the
         *               glossary says: an id of a matched resource is a label, and a reference
         *               that points elsewhere is part of how the request is built
         */
        private Difference difference(Place at, DifferenceKind kind, Shown toolValue, Shown yourValue, String toolType, String yourType,
                                      Grouping grouping, DifferenceGroup forced, String explanation, Integer following) {
            ElementPath element = at.element();
            Glossary.Entry entry = glossary.describe(element);
            DifferenceGroup group;
            if (forced != null) {
                group = forced;
            } else if (entry == null) {
                group = DifferenceGroup.NOT_CLASSIFIED;
            } else if (grouping == Grouping.STRUCTURE) {
                group = DifferenceGroup.BUILD;
            } else {
                group = entry.role() == null ? DifferenceGroup.NOT_CLASSIFIED : entry.role().group();
            }
            String text = explanation;
            String remark = glossary.remark(element, kind);
            if (remark != null && !remark.isBlank()) {
                text += " " + remark.strip();
            }
            if (group == DifferenceGroup.NOT_CLASSIFIED) {
                text += Wording.notClassified(entry != null);
            }
            return new Difference(at.shownPath(), at.toolPointer(), at.yourPointer(), kind, kind.label(), group, toolValue, yourValue,
                    toolType, yourType, entry == null ? null : entry.what(), text, following);
        }

        // ---- labels and references, once every pair is known ----

        private Result finish() {
            Followers followers = new Followers();
            Map<JsonNode, Slot> idSlots = new IdentityHashMap<>();
            for (Slot slot : slots) {
                switch (slot.pending) {
                    case PAIR_ID -> idSlots.put(slot.toolOwner, slot);
                    case REFERENCE -> reference(slot, followers);
                    case REFERENCE_LIST -> referenceList(slot, followers);
                    case NOTHING -> {
                        // Already a difference.
                    }
                }
            }
            for (Map.Entry<JsonNode, Slot> id : idSlots.entrySet()) {
                Slot slot = id.getValue();
                int following = followers.of(id.getKey()).size();
                slot.ready.add(difference(slot.at, DifferenceKind.DIFFERENT_VALUE, slot.tool, slot.yours, Grouping.ROLE, DifferenceGroup.LABELS,
                        Wording.differentIds(ResourceIndex.resourceType(id.getKey()), following), following));
            }
            // A pair whose ids agree can still be labelled differently, by the full urls of its
            // entries. There is then no id difference to carry the count, so the first reference
            // that follows the pair is listed and carries it.
            for (JsonNode resource : followers.resources()) {
                if (!idSlots.containsKey(resource)) {
                    Follower first = followers.of(resource).get(0);
                    int others = followers.of(resource).size() - 1;
                    first.slot().ready.add(difference(first.at(), DifferenceKind.DIFFERENT_VALUE, first.tool(), first.yours(), Grouping.ROLE,
                            DifferenceGroup.LABELS, Wording.differentLabels(ResourceIndex.resourceType(resource), others), others));
                }
            }
            List<Difference> differences = new ArrayList<>();
            slots.forEach(slot -> differences.addAll(slot.ready));
            List<String> notes = new ArrayList<>();
            if (depthFirstPath != null) {
                notes.add(Wording.depthReached(MAX_DEPTH, depthFirstPath, depthPlaces));
            }
            return new Result(differences, notes);
        }

        /** The tool's resource among these whose partner is among those, or null when no such pair exists. */
        private JsonNode pairedTool(List<ResourceIndex.Resource> toolTargets, List<ResourceIndex.Resource> yourTargets) {
            for (ResourceIndex.Resource toolTarget : toolTargets) {
                JsonNode partner = pairs.get(toolTarget.node());
                if (partner != null && yourTargets.stream().anyMatch(target -> target.node() == partner)) {
                    return toolTarget.node();
                }
            }
            return null;
        }

        private List<ResourceIndex.Resource> own(JsonNode resource, ResourceIndex index) {
            return resource == null ? List.of() : index.resources().stream().filter(r -> r.node() == resource).toList();
        }

        private void reference(Slot slot, Followers followers) {
            ReferenceText toolText = ReferenceText.parse(Values.text(slot.tool), slot.bareType);
            ReferenceText yourText = ReferenceText.parse(Values.text(slot.yours), slot.bareType);
            List<ResourceIndex.Resource> toolTargets = slot.fullUrl ? own(slot.toolOwner, toolIndex) : toolIndex.resolve(toolText);
            List<ResourceIndex.Resource> yourTargets = slot.fullUrl ? own(slot.yourOwner, yourIndex) : yourIndex.resolve(yourText);
            JsonNode paired = pairedTool(toolTargets, yourTargets);
            if (paired != null && toolText.sameFormAs(yourText)) {
                followers.add(paired, new Follower(slot, slot.at, slot.tool, slot.yours));
            } else if (paired != null) {
                slot.ready.add(difference(slot.at, DifferenceKind.WRITTEN_DIFFERENTLY, slot.tool, slot.yours, Grouping.ROLE, DifferenceGroup.BUILD,
                        Wording.referenceForms(toolText, yourText), null));
            } else if (slot.fullUrl || (!toolText.recognised() && !yourText.recognised())) {
                // Not a pointer at anything this tool can follow, so it is compared as the text it is.
                slot.ready.add(difference(slot.at, DifferenceKind.DIFFERENT_VALUE, slot.tool, slot.yours, Grouping.ROLE, null,
                        Wording.differentText(Values.text(slot.tool), Values.text(slot.yours)), null));
            } else {
                slot.ready.add(difference(slot.at, DifferenceKind.DIFFERENT_VALUE, slot.tool, slot.yours, Grouping.ROLE, DifferenceGroup.BUILD,
                        Wording.referenceElsewhere(toolText, toolTargets, yourText, yourTargets), null));
            }
        }

        /**
         * Two lists of references. An element of one is the counterpart of an element of the
         * other when the two are the same text, or when they point at a matched pair of resources
         * in the same form.
         */
        private void referenceList(Slot slot, Followers followers) {
            JsonNode tool = slot.tool;
            JsonNode yours = slot.yours;
            int[] partner = new int[tool.size()];
            Arrays.fill(partner, -1);
            boolean[] taken = new boolean[yours.size()];
            Map<String, Deque<Integer>> sameText = new HashMap<>();
            for (int j = 0; j < yours.size(); j++) {
                sameText.computeIfAbsent(Values.text(yours.get(j)), k -> new ArrayDeque<>()).add(j);
            }
            for (int i = 0; i < tool.size(); i++) {
                Deque<Integer> same = sameText.get(Values.text(tool.get(i)));
                if (same != null && !same.isEmpty()) {
                    partner[i] = same.poll();
                    taken[partner[i]] = true;
                }
            }
            // What is left of the other list, by the resource each element points at and the form it is written in.
            Map<JsonNode, Deque<Integer>> yourByTarget = new IdentityHashMap<>();
            ReferenceText[] yourTexts = new ReferenceText[yours.size()];
            for (int j = 0; j < yours.size(); j++) {
                yourTexts[j] = ReferenceText.parse(Values.text(yours.get(j)), slot.bareType);
                if (!taken[j]) {
                    for (ResourceIndex.Resource target : yourIndex.resolve(yourTexts[j])) {
                        yourByTarget.computeIfAbsent(target.node(), k -> new ArrayDeque<>()).add(j);
                    }
                }
            }
            for (int i = 0; i < tool.size(); i++) {
                if (partner[i] >= 0) {
                    continue;
                }
                ReferenceText toolText = ReferenceText.parse(Values.text(tool.get(i)), slot.bareType);
                for (ResourceIndex.Resource target : toolIndex.resolve(toolText)) {
                    JsonNode counterpart = pairs.get(target.node());
                    Deque<Integer> candidates = counterpart == null ? null : yourByTarget.get(counterpart);
                    Integer found = null;
                    while (candidates != null && !candidates.isEmpty() && found == null) {
                        int j = candidates.poll();
                        if (!taken[j] && toolText.sameFormAs(yourTexts[j])) {
                            found = j;
                        }
                    }
                    if (found != null) {
                        partner[i] = found;
                        taken[found] = true;
                        followers.add(target.node(), new Follower(slot, slot.at.element(String.valueOf(i), i, found), tool.get(i), yours.get(found)));
                        break;
                    }
                }
            }
            boolean complete = tool.size() == yours.size();
            boolean inOrder = true;
            for (int i = 0; i < tool.size(); i++) {
                complete &= partner[i] >= 0;
                inOrder &= partner[i] == i;
            }
            if (complete && !inOrder) {
                slot.ready.add(difference(slot.at, DifferenceKind.DIFFERENT_ORDER, tool, yours, Grouping.ROLE, null, Wording.differentOrder(), null));
            }
            for (int i = 0; i < tool.size(); i++) {
                if (partner[i] < 0) {
                    slot.ready.add(difference(slot.at.element(String.valueOf(i), i, -1), DifferenceKind.ONLY_IN_TOOL, tool.get(i), null,
                            Grouping.STRUCTURE, DifferenceGroup.BUILD, Wording.onlyReference(true), null));
                }
            }
            for (int j = 0; j < yours.size(); j++) {
                if (!taken[j]) {
                    slot.ready.add(difference(slot.at.element(String.valueOf(j), -1, j), DifferenceKind.ONLY_IN_YOURS, null, yours.get(j),
                            Grouping.STRUCTURE, DifferenceGroup.BUILD, Wording.onlyReference(false), null));
                }
            }
        }
    }
}
