package org.p32h.interop.epa.pave.model.dtr;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the structure FHIR gives a Questionnaire and a QuestionnaireResponse: items
 * nested in items, answers keyed by linkId.
 *
 * <p>The request builder, the enableWhen evaluation, the response mapper and the mock client all
 * walk these two trees, and they have to agree on what counts as a question and what counts as an
 * answer. So the walking is done here once. Everything here is structure the FHIR specification
 * defines. Nothing here is an assumption about a vendor: those live in the client layer.
 */
public final class QuestionnaireJson {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private QuestionnaireJson() {
    }

    /** One item of a questionnaire with its place in the tree. */
    public static final class Item {

        private final String path;
        private final JsonNode node;
        private final Item parent;
        private final List<Item> children = new ArrayList<>();

        private Item(String path, JsonNode node, Item parent) {
            this.path = path;
            this.node = node;
            this.parent = parent;
        }

        /** Where the item sits in its questionnaire, for example {@code item[0].item[1]}. */
        public String path() {
            return path;
        }

        public JsonNode node() {
            return node;
        }

        /** The item this one is nested in, or null at the top. */
        public Item parent() {
            return parent;
        }

        public List<Item> children() {
            return Collections.unmodifiableList(children);
        }

        /** The linkId, or null when the item has none. */
        public String linkId() {
            return JsonSafe.presentText(node, "linkId");
        }

        public String text() {
            return JsonSafe.presentText(node, "text");
        }

        public String type() {
            return JsonSafe.presentText(node, "type");
        }

        /** True for a group, and for an item that states no type and holds other items. */
        public boolean group() {
            String type = type();
            return "group".equals(type) || (type == null && !children.isEmpty());
        }

        public boolean display() {
            return "display".equals(type());
        }

        /** True when the item takes an answer: anything that is neither a group nor display text. */
        public boolean question() {
            return !group() && !display();
        }

        public boolean required() {
            return Boolean.TRUE.equals(JsonSafe.bool(node, "required"));
        }

        public boolean repeats() {
            return Boolean.TRUE.equals(JsonSafe.bool(node, "repeats"));
        }

        /** The item named by its linkId and text. An item with no linkId is named by where it sits. */
        public QuestionRef ref() {
            return new QuestionRef(Texts.orElse(linkId(), path), text());
        }

        @Override
        public String toString() {
            return "Item[" + path + "]";
        }
    }

    public static boolean isType(JsonNode node, String resourceType) {
        return node != null && node.isObject() && resourceType.equals(JsonSafe.text(node, "resourceType"));
    }

    /** The items at the top of a questionnaire, each holding its children. An entry that is not an object is left out. */
    public static List<Item> items(JsonNode questionnaire) {
        List<Item> roots = new ArrayList<>();
        collect(JsonSafe.child(questionnaire, "item"), "item", null, roots);
        return roots;
    }

    private static void collect(JsonNode array, String prefix, Item parent, List<Item> into) {
        int index = 0;
        for (JsonNode element : JsonSafe.elements(array)) {
            if (element.isObject()) {
                Item item = new Item(prefix + "[" + index + "]", element, parent);
                into.add(item);
                collect(JsonSafe.child(element, "item"), item.path + ".item", item, item.children);
            }
            index++;
        }
    }

    /** Every item in document order, each parent before its children. */
    public static List<Item> flatten(List<Item> roots) {
        List<Item> out = new ArrayList<>();
        for (Item root : roots) {
            out.add(root);
            out.addAll(flatten(root.children));
        }
        return out;
    }

    /** Every item of a questionnaire that takes an answer, in document order. */
    public static List<Item> questions(JsonNode questionnaire) {
        return flatten(items(questionnaire)).stream().filter(Item::question).toList();
    }

    /**
     * What stops a questionnaire from being answered, one plain sentence each. Empty when nothing
     * does. An answer is tied to its question by linkId and by nothing else, so an item with no
     * linkId, or a linkId shared by two items, leaves an answer with nowhere certain to go.
     */
    public static List<String> problems(JsonNode questionnaire) {
        List<String> problems = new ArrayList<>();
        if (questionnaire == null || !questionnaire.isObject()) {
            problems.add("It is not a JSON object.");
            return problems;
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        check(questionnaire.get("item"), "item", problems, seen);
        seen.forEach((linkId, count) -> {
            if (count > 1) {
                problems.add("The linkId \"" + linkId + "\" is used by " + count + " items, so an answer cannot be tied to one of them.");
            }
        });
        return problems;
    }

    private static void check(JsonNode array, String prefix, List<String> problems, Map<String, Integer> seen) {
        if (JsonSafe.nullOrMissing(array)) {
            return;
        }
        if (!array.isArray()) {
            problems.add("The element " + prefix + " is not a list, so the items in it cannot be read.");
            return;
        }
        int index = 0;
        for (JsonNode element : array) {
            String path = prefix + "[" + index + "]";
            if (!element.isObject()) {
                problems.add(path + " is not an object, so it cannot be read as an item.");
            } else {
                String linkId = JsonSafe.presentText(element, "linkId");
                if (linkId != null) {
                    seen.merge(linkId, 1, Integer::sum);
                } else if (!"display".equals(JsonSafe.presentText(element, "type"))) {
                    // Display text takes no answer, so it is the one item that can be shown without a linkId.
                    problems.add(path + " has no linkId, so an answer cannot be tied to it.");
                }
                check(element.get("item"), path + ".item", problems, seen);
            }
            index++;
        }
    }

    /** The url of a questionnaire, with its version after a bar when it states one. Null when it has no url. */
    public static String canonical(JsonNode questionnaire) {
        String url = JsonSafe.presentText(questionnaire, "url");
        if (url == null) {
            return null;
        }
        String version = JsonSafe.presentText(questionnaire, "version");
        return version == null ? url : url + "|" + version;
    }

    /**
     * True when a canonical that was asked for names this questionnaire: by its url alone, or by
     * its url and version. A canonical that names a version is not matched by another version.
     */
    public static boolean names(String requested, JsonNode questionnaire) {
        String url = JsonSafe.presentText(questionnaire, "url");
        if (Texts.blank(requested) || url == null) {
            return false;
        }
        String wanted = requested.strip();
        return wanted.equals(url) || wanted.equals(canonical(questionnaire));
    }

    /**
     * The QuestionnaireResponse in a body: the body itself, or the first one a Parameters resource
     * carries. Null when the body holds none.
     */
    public static JsonNode findResponse(JsonNode body) {
        if (isType(body, "QuestionnaireResponse")) {
            return body;
        }
        if (isType(body, "Parameters")) {
            return findInParameters(JsonSafe.child(body, "parameter"));
        }
        return null;
    }

    private static JsonNode findInParameters(JsonNode parameters) {
        for (JsonNode parameter : JsonSafe.elements(parameters)) {
            JsonNode resource = JsonSafe.child(parameter, "resource");
            if (isType(resource, "QuestionnaireResponse")) {
                return resource;
            }
            JsonNode nested = findInParameters(JsonSafe.child(parameter, "part"));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * The questionnaire a response carries inside itself. When its {@code questionnaire} element is
     * a local reference such as {@code #q1}, that is the contained questionnaire with that id and
     * no other. Otherwise it is the contained questionnaire when there is exactly one. Null when
     * the response carries none that can be told to be the one it answers.
     */
    public static JsonNode containedQuestionnaire(JsonNode response) {
        List<JsonNode> contained = new ArrayList<>();
        for (JsonNode resource : JsonSafe.elements(response, "contained")) {
            if (isType(resource, "Questionnaire")) {
                contained.add(resource);
            }
        }
        String pointer = JsonSafe.presentText(response, "questionnaire");
        if (pointer != null && pointer.startsWith("#")) {
            String id = pointer.substring(1);
            return contained.stream().filter(q -> id.equals(JsonSafe.presentText(q, "id"))).findFirst().orElse(null);
        }
        return contained.size() == 1 ? contained.get(0) : null;
    }

    /**
     * The name of the property that holds a value on an answer, an answer option, an initial value
     * or an enableWhen: the first property whose name starts with the prefix, such as
     * {@code valueCoding} or {@code answerBoolean}. Null when there is none.
     */
    public static String valueKey(JsonNode holder, String prefix) {
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(holder)) {
            if (property.getKey().startsWith(prefix) && property.getKey().length() > prefix.length()) {
                return property.getKey();
            }
        }
        return null;
    }

    /** The value an answer carries, or a missing node. */
    public static JsonNode value(JsonNode answer) {
        String key = valueKey(answer, "value");
        return key == null ? MissingNode.getInstance() : answer.get(key);
    }

    /** True when an answer entry really carries a value. A null or an empty string is not an answer. */
    public static boolean answered(JsonNode answer) {
        return JsonSafe.presence(value(answer)) == Presence.VALUE;
    }

    /**
     * A value as the short text it is matched by: the code of a coding, the reference of a
     * reference, the number of a quantity, a scalar as it is written. Null when there is no value.
     *
     * @param key the name of the property the value sits under, which says what type it is
     */
    public static String valueText(String key, JsonNode value) {
        if (key == null || JsonSafe.nullOrMissing(value)) {
            return null;
        }
        if (!value.isContainer()) {
            return JsonSafe.text(value);
        }
        if (key.endsWith("Coding")) {
            return Texts.firstPresent(JsonSafe.presentText(value, "code"), JsonSafe.presentText(value, "display"));
        }
        if (key.endsWith("Reference")) {
            return Texts.firstPresent(JsonSafe.presentText(value, "reference"), JsonSafe.presentText(value, "display"));
        }
        if (key.endsWith("Quantity")) {
            return JsonSafe.presentText(value, "value");
        }
        return value.toString();
    }

    /** As {@link #valueText(String, JsonNode)}, for the value of an answer. */
    public static String answerText(JsonNode answer) {
        String key = valueKey(answer, "value");
        return key == null ? null : valueText(key, answer.get(key));
    }

    /**
     * A value as a tester reads it: the display of a coding with its code after it, a quantity
     * with its unit, a scalar as it is written, and anything else as its JSON.
     */
    public static String valueDisplay(String key, JsonNode value) {
        if (key == null || JsonSafe.nullOrMissing(value)) {
            return null;
        }
        if (!value.isContainer()) {
            return JsonSafe.text(value);
        }
        if (key.endsWith("Coding")) {
            String code = JsonSafe.presentText(value, "code");
            String display = JsonSafe.presentText(value, "display");
            return display != null && code != null ? display + " (" + code + ")" : Texts.firstPresent(display, code, value.toString());
        }
        if (key.endsWith("Reference")) {
            return Texts.firstPresent(JsonSafe.presentText(value, "display"), JsonSafe.presentText(value, "reference"), value.toString());
        }
        if (key.endsWith("Quantity")) {
            String number = JsonSafe.presentText(value, "value");
            String unit = Texts.firstPresent(JsonSafe.presentText(value, "unit"), JsonSafe.presentText(value, "code"));
            return number == null ? value.toString() : unit == null ? number : number + " " + unit;
        }
        return value.toString();
    }

    /** As {@link #valueDisplay(String, JsonNode)}, for the value of an answer. */
    public static String answerDisplay(JsonNode answer) {
        String key = valueKey(answer, "value");
        return key == null ? null : valueDisplay(key, answer.get(key));
    }

    /**
     * The answers a QuestionnaireResponse holds, by linkId, in document order. An item with no
     * answer has no entry. Answers are found wherever FHIR lets an item sit: beneath a group, and
     * beneath the answer of a question that has follow-up items.
     */
    public static Map<String, List<JsonNode>> answers(JsonNode response) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        collectAnswers(JsonSafe.child(response, "item"), out);
        return out;
    }

    private static void collectAnswers(JsonNode items, Map<String, List<JsonNode>> out) {
        for (JsonNode item : JsonSafe.elements(items)) {
            if (!item.isObject()) {
                continue;
            }
            String linkId = JsonSafe.presentText(item, "linkId");
            for (JsonNode answer : JsonSafe.elements(item, "answer")) {
                if (!answer.isObject()) {
                    continue;
                }
                if (linkId != null && answered(answer)) {
                    out.computeIfAbsent(linkId, key -> new ArrayList<>()).add(answer);
                }
                collectAnswers(JsonSafe.child(answer, "item"), out);
            }
            collectAnswers(JsonSafe.child(item, "item"), out);
        }
    }

    /** The items of a QuestionnaireResponse by linkId, wherever they sit. The first item wins when a linkId repeats. */
    public static Map<String, JsonNode> responseItems(JsonNode response) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        collectItems(JsonSafe.child(response, "item"), out);
        return out;
    }

    private static void collectItems(JsonNode items, Map<String, JsonNode> out) {
        for (JsonNode item : JsonSafe.elements(items)) {
            if (!item.isObject()) {
                continue;
            }
            String linkId = JsonSafe.presentText(item, "linkId");
            if (linkId != null) {
                out.putIfAbsent(linkId, item);
            }
            for (JsonNode answer : JsonSafe.elements(item, "answer")) {
                collectItems(JsonSafe.child(answer, "item"), out);
            }
            collectItems(JsonSafe.child(item, "item"), out);
        }
    }

    /**
     * Builds the {@code item} list of a QuestionnaireResponse from a questionnaire and a set of
     * answers. The items follow the questionnaire: a group holds the items beneath it, and a
     * question's follow-up items sit beneath its first answer, which is where FHIR puts them. An
     * item with no answer and nothing answered beneath it is left out.
     *
     * @param roots    the items of the questionnaire being answered
     * @param answers  the answers by linkId, each as a FHIR answer object
     * @param existing the items of an earlier response by linkId. Whatever else such an item
     *                 carried, such as an extension the service put on it, is kept. May be empty
     * @param include  which items may appear at all. An item it refuses is left out with
     *                 everything beneath it
     */
    public static ArrayNode buildItems(List<Item> roots, Map<String, List<JsonNode>> answers, Map<String, JsonNode> existing,
                                       Predicate<Item> include) {
        ArrayNode out = JSON.arrayNode();
        for (Item item : roots) {
            String linkId = item.linkId();
            if (linkId == null || !include.test(item)) {
                continue;
            }
            ArrayNode children = buildItems(item.children, answers, existing, include);
            List<JsonNode> given = item.question() ? answers.getOrDefault(linkId, List.of()) : List.of();
            if (given.isEmpty() && children.isEmpty()) {
                continue;
            }
            ObjectNode node = JSON.objectNode();
            node.put("linkId", linkId);
            JsonNode earlier = existing.get(linkId);
            String text = Texts.firstPresent(JsonSafe.presentText(earlier, "text"), item.text());
            if (text != null) {
                node.put("text", text);
            }
            for (Map.Entry<String, JsonNode> property : JsonSafe.properties(earlier)) {
                if (!List.of("linkId", "text", "answer", "item").contains(property.getKey())) {
                    node.set(property.getKey(), property.getValue().deepCopy());
                }
            }
            if (!given.isEmpty()) {
                ArrayNode answerArray = node.putArray("answer");
                for (JsonNode answer : given) {
                    ObjectNode copy = (ObjectNode) answer.deepCopy();
                    copy.remove("item");
                    answerArray.add(copy);
                }
                if (!children.isEmpty()) {
                    ((ObjectNode) answerArray.get(0)).set("item", children);
                }
            } else {
                node.set("item", children);
            }
            out.add(node);
        }
        return out;
    }
}
