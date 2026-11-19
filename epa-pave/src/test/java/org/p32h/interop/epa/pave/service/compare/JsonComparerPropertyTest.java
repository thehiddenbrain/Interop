package org.p32h.interop.epa.pave.service.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What must hold for any two documents, shown on several hundred generated ones.
 *
 * <p>The tests beside this one each show one rule on one pair. These show what no single pair
 * can: that a document never differs from itself, that no single change to a document goes
 * unreported, and that every difference points at a place that is really there. The documents are
 * generated from fixed seeds, so every run compares the same ones.
 */
class JsonComparerPropertyTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final CompareRules RULES = new CompareRules(Set.of("prefetch"),
            Map.of("context.patientId", "Patient", "context.encounterId", "Encounter", "context.userId", "", "context.selections", ""));
    private static final JsonComparer COMPARER = new JsonComparer(RULES, Glossary.NONE);
    private static final int DOCUMENTS = 300;

    private static final List<String> TYPES = List.of("Patient", "Coverage", "ServiceRequest", "Practitioner", "Encounter");
    private static final List<String> IDS = List.of("a", "b", "c", "id-1");
    private static final List<String> SYSTEMS = List.of("http://example.org/one", "http://example.org/two", "urn:oid:1.2.3");
    private static final List<String> URNS = List.of("urn:uuid:00000000-0000-4000-8000-000000000001", "urn:uuid:00000000-0000-4000-8000-000000000002");
    private static final List<String> KEYS = List.of("status", "value", "name", "meta", "note", "x", "y", "given", "period", "text");

    // ---- generating documents shaped like the ones this engine is for ----

    private static <T> T pick(Random random, List<T> from) {
        return from.get(random.nextInt(from.size()));
    }

    private static JsonNode scalar(Random random) {
        return switch (random.nextInt(9)) {
            case 0 -> JSON.nullNode();
            case 1 -> JSON.booleanNode(random.nextBoolean());
            case 2 -> JSON.numberNode(random.nextInt(3));
            case 3 -> JSON.numberNode(new java.math.BigDecimal(random.nextInt(3) + ".0"));
            case 4 -> JSON.stringNode("");
            case 5 -> JSON.stringNode(pick(random, TYPES) + "/" + pick(random, IDS));
            case 6 -> JSON.stringNode(pick(random, URNS));
            default -> JSON.stringNode(pick(random, List.of("draft", "active", "Draft", "2026-03-20", "2026-03-20T10:00:00Z", "1", "a b", "a  b")));
        };
    }

    private static JsonNode any(Random random, int depth) {
        int choice = random.nextInt(10);
        if (depth >= 5 || choice < 3) {
            return scalar(random);
        }
        if (choice < 5) {
            return plain(random, depth);
        }
        if (choice < 7) {
            return list(random, depth);
        }
        return choice < 9 ? resource(random, depth) : bundle(random, depth);
    }

    private static ObjectNode plain(Random random, int depth) {
        ObjectNode object = JSON.objectNode();
        int members = random.nextInt(4);
        for (int i = 0; i < members; i++) {
            object.set(pick(random, KEYS), any(random, depth + 1));
        }
        if (random.nextInt(4) == 0) {
            object.put("reference", random.nextBoolean() ? pick(random, TYPES) + "/" + pick(random, IDS) : pick(random, URNS));
        }
        return object;
    }

    private static ObjectNode resource(Random random, int depth) {
        ObjectNode resource = JSON.objectNode().put("resourceType", pick(random, TYPES));
        if (random.nextInt(5) > 0) {
            resource.put("id", pick(random, IDS));
        }
        if (random.nextBoolean()) {
            resource.set("subject", JSON.objectNode().put("reference", pick(random, TYPES) + "/" + pick(random, IDS)));
        }
        if (random.nextBoolean()) {
            resource.set("identifier", keyed(random, "system", SYSTEMS, depth));
        }
        if (random.nextBoolean()) {
            // One number in one of two written forms, so that two matched resources often hold the same number written differently.
            resource.set("quantity", random.nextBoolean() ? JSON.numberNode(1) : JSON.numberNode(new java.math.BigDecimal("1.0")));
        }
        if (random.nextInt(3) == 0) {
            resource.set("extension", keyed(random, "url", SYSTEMS, depth));
        }
        if (random.nextInt(3) == 0) {
            ArrayNode classes = resource.putArray("class");
            for (int i = random.nextInt(3); i > 0; i--) {
                classes.addObject().put("value", pick(random, IDS)).putObject("type").putArray("coding").addObject()
                        .put("code", pick(random, List.of("plan", "group")));
            }
        }
        for (int i = random.nextInt(3); i > 0; i--) {
            resource.set(pick(random, KEYS), any(random, depth + 1));
        }
        return resource;
    }

    /** A list of objects that are matched on one key, some of them without it. */
    private static ArrayNode keyed(Random random, String key, List<String> values, int depth) {
        ArrayNode list = JSON.arrayNode();
        for (int i = random.nextInt(4); i > 0; i--) {
            ObjectNode element = list.addObject();
            if (random.nextInt(4) > 0) {
                element.put(key, pick(random, values));
            }
            element.set("value", scalar(random));
            if (random.nextInt(4) == 0) {
                element.set("extra", any(random, depth + 2));
            }
        }
        return list;
    }

    private static ObjectNode bundle(Random random, int depth) {
        ObjectNode bundle = JSON.objectNode().put("resourceType", "Bundle");
        ArrayNode entries = bundle.putArray("entry");
        for (int i = random.nextInt(4); i > 0; i--) {
            ObjectNode entry = entries.addObject();
            if (random.nextInt(3) == 0) {
                entry.put("fullUrl", pick(random, URNS));
            }
            if (random.nextInt(6) > 0) {
                entry.set("resource", resource(random, depth + 2));
            }
        }
        return bundle;
    }

    private static ArrayNode list(Random random, int depth) {
        ArrayNode list = JSON.arrayNode();
        boolean scalars = random.nextBoolean();
        for (int i = random.nextInt(4); i > 0; i--) {
            list.add(scalars ? scalar(random) : any(random, depth + 1));
        }
        return list;
    }

    /** A document with the outline of a hook request, filled with whatever the seed gives. */
    private static ObjectNode document(long seed) {
        Random random = new Random(seed);
        ObjectNode root = JSON.objectNode().put("hook", "order-sign");
        ObjectNode context = root.putObject("context");
        context.put("patientId", pick(random, IDS));
        context.put("userId", "Practitioner/" + pick(random, IDS));
        ArrayNode selections = context.putArray("selections");
        for (int i = random.nextInt(3); i > 0; i--) {
            selections.add("ServiceRequest/" + pick(random, IDS));
        }
        context.set("draftOrders", bundle(random, 2));
        ObjectNode prefetch = root.putObject("prefetch");
        for (int i = random.nextInt(4); i > 0; i--) {
            prefetch.set("key" + random.nextInt(4), random.nextInt(4) == 0 ? bundle(random, 2) : resource(random, 2));
        }
        for (int i = random.nextInt(3); i > 0; i--) {
            root.set(pick(random, KEYS), any(random, 1));
        }
        return root;
    }

    // ---- walking a document ----

    /** One place in a document: the container that holds it and how the container names it. */
    private record Spot(JsonNode container, String key, int index, String pointer) {

        JsonNode node() {
            return key != null ? container.get(key) : container.get(index);
        }

        void replaceWith(JsonNode value) {
            if (key != null) {
                ((ObjectNode) container).set(key, value);
            } else {
                ((ArrayNode) container).set(index, value);
            }
        }
    }

    private static void spots(JsonNode node, String pointer, List<Spot> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String child = Paths.member(pointer, member.getKey());
                out.add(new Spot(node, member.getKey(), -1, child));
                spots(member.getValue(), child, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String child = Paths.element(pointer, i);
                out.add(new Spot(node, null, i, child));
                spots(node.get(i), child, out);
            }
        }
    }

    private static List<Spot> spots(JsonNode root) {
        List<Spot> out = new ArrayList<>();
        spots(root, "", out);
        return out;
    }

    // ---- what a difference has to be consistent with ----

    private static void assertPointsAtWhatIsThere(JsonNode tool, JsonNode yours, List<Difference> differences, String described) {
        for (Difference difference : differences) {
            String where = described + ", " + difference;
            if (difference.kind() == DifferenceKind.ONLY_IN_TOOL) {
                assertThat(difference.yourPointer()).as(where).isNull();
                assertThat(difference.yourValue().presence()).as(where).isEqualTo(Presence.ABSENT);
                assertThat(difference.toolPointer()).as(where).isNotNull();
            } else if (difference.kind() == DifferenceKind.ONLY_IN_YOURS) {
                assertThat(difference.toolPointer()).as(where).isNull();
                assertThat(difference.toolValue().presence()).as(where).isEqualTo(Presence.ABSENT);
                assertThat(difference.yourPointer()).as(where).isNotNull();
            } else {
                assertThat(difference.toolPointer()).as(where).isNotNull();
                assertThat(difference.yourPointer()).as(where).isNotNull();
            }
            if (difference.toolPointer() != null) {
                JsonNode pointed = tool.at(difference.toolPointer());
                assertThat(pointed.isMissingNode()).as(where + " points into the tool's document").isFalse();
                if (difference.kind() != DifferenceKind.DIFFERENT_KEY) {
                    assertThat(difference.toolValue()).as(where + " shows what the tool's document holds there").isEqualTo(Values.shown(pointed));
                    assertThat(difference.toolType()).as(where).isEqualTo(Values.type(pointed));
                }
            }
            if (difference.yourPointer() != null) {
                JsonNode pointed = yours.at(difference.yourPointer());
                assertThat(pointed.isMissingNode()).as(where + " points into the other document").isFalse();
                if (difference.kind() != DifferenceKind.DIFFERENT_KEY) {
                    assertThat(difference.yourValue()).as(where + " shows what the other document holds there").isEqualTo(Values.shown(pointed));
                    assertThat(difference.yourType()).as(where).isEqualTo(Values.type(pointed));
                }
            }
            assertThat(difference.explanation()).as(where).isNotBlank();
        }
    }

    // ---- the properties ----

    @Test
    void aDocumentNeverDiffersFromItself() {
        for (long seed = 1; seed <= DOCUMENTS; seed++) {
            ObjectNode document = document(seed);
            JsonComparer.Result result = COMPARER.compare(document, document.deepCopy());
            assertThat(result.differences()).as("seed " + seed).isEmpty();
            assertThat(result.notes()).as("seed " + seed).isEmpty();
        }
    }

    @Test
    void noSingleChangedValueGoesUnreported() {
        int changed = 0;
        for (long seed = 1; seed <= DOCUMENTS; seed++) {
            ObjectNode original = document(seed);
            Random random = new Random(seed * 31);
            List<Spot> all = spots(original);
            for (int attempt = 0; attempt < 4; attempt++) {
                Spot chosen = all.get(random.nextInt(all.size()));
                if (chosen.node().isContainer()) {
                    continue;
                }
                ObjectNode other = original.deepCopy();
                spots(other).stream().filter(spot -> spot.pointer().equals(chosen.pointer())).findFirst().orElseThrow()
                        .replaceWith(JSON.stringNode("changed-" + attempt));
                String described = "seed " + seed + ", " + chosen.pointer() + " changed";
                for (boolean changeIsInYours : List.of(true, false)) {
                    JsonNode tool = changeIsInYours ? original : other;
                    JsonNode yours = changeIsInYours ? other : original;
                    JsonComparer.Result result = COMPARER.compare(tool, yours);
                    assertThat(result.differences()).as(described).isNotEmpty();
                    assertPointsAtWhatIsThere(tool, yours, result.differences(), described);
                }
                changed++;
            }
        }
        assertThat(changed).as("values that were changed").isGreaterThan(500);
    }

    @Test
    void noSingleRemovedOrAddedElementGoesUnreported() {
        int removed = 0;
        for (long seed = 1; seed <= DOCUMENTS; seed++) {
            ObjectNode original = document(seed);
            Random random = new Random(seed * 17);
            List<Spot> all = spots(original);
            for (int attempt = 0; attempt < 4; attempt++) {
                Spot chosen = all.get(random.nextInt(all.size()));
                ObjectNode other = original.deepCopy();
                Spot there = spots(other).stream().filter(spot -> spot.pointer().equals(chosen.pointer())).findFirst().orElseThrow();
                if (there.key() != null) {
                    ((ObjectNode) there.container()).remove(there.key());
                } else {
                    ((ArrayNode) there.container()).remove(there.index());
                }
                String described = "seed " + seed + ", " + chosen.pointer() + " removed";
                // Removed from the other document, and then seen from the other side: added to it.
                for (boolean removedFromYours : List.of(true, false)) {
                    JsonNode tool = removedFromYours ? original : other;
                    JsonNode yours = removedFromYours ? other : original;
                    JsonComparer.Result result = COMPARER.compare(tool, yours);
                    assertThat(result.differences()).as(described).isNotEmpty();
                    assertPointsAtWhatIsThere(tool, yours, result.differences(), described);
                }
                removed++;
            }
        }
        assertThat(removed).isEqualTo(DOCUMENTS * 4);
    }

    @Test
    void anyTwoDocumentsAreComparedWithoutFailingAndEveryDifferencePointsAtWhatIsThere() {
        int differences = 0;
        int referencesFollowing = 0;
        Set<DifferenceKind> kinds = java.util.EnumSet.noneOf(DifferenceKind.class);
        Set<org.p32h.interop.epa.pave.model.compare.DifferenceGroup> groups =
                java.util.EnumSet.noneOf(org.p32h.interop.epa.pave.model.compare.DifferenceGroup.class);
        for (long seed = 1; seed < DOCUMENTS; seed++) {
            ObjectNode tool = document(seed);
            ObjectNode yours = document(seed + 1);
            JsonComparer.Result result = COMPARER.compare(tool, yours);
            assertPointsAtWhatIsThere(tool, yours, result.differences(), "seeds " + seed + " and " + (seed + 1));
            // The same two documents always give the same differences, in the same order.
            assertThat(COMPARER.compare(tool, yours).differences()).as("seed " + seed).isEqualTo(result.differences());
            differences += result.differences().size();
            for (Difference difference : result.differences()) {
                kinds.add(difference.kind());
                groups.add(difference.group());
                referencesFollowing += difference.referencesFollowing() == null ? 0 : difference.referencesFollowing();
            }
        }
        assertThat(differences).isGreaterThan(1000);
        // The generated documents are not all alike: between them they reach every kind of difference,
        // the labels of matched resources, and references that follow those labels.
        assertThat(kinds).containsExactlyInAnyOrder(DifferenceKind.values());
        assertThat(groups).contains(org.p32h.interop.epa.pave.model.compare.DifferenceGroup.LABELS,
                org.p32h.interop.epa.pave.model.compare.DifferenceGroup.BUILD);
        assertThat(referencesFollowing).isPositive();
    }
}
