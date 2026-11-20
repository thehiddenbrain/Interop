package org.p32h.interop.epa.pave.service.compare;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.service.compare.Values.JsonType;
import tools.jackson.databind.JsonNode;

/**
 * The sentences that explain a difference.
 *
 * <p>Every sentence here says what the two documents hold and what follows from their structure.
 * None says which of the two a receiver expects, and none quotes a value: a value can be a
 * member's name, and it is shown beside the sentence, not inside it. Keys, resource types and
 * counts are not values and are named freely.
 */
final class Wording {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T.+");

    private Wording() {
    }

    private static String side(boolean tool) {
        return tool ? "The tool's request" : "Your request";
    }

    private static String other(boolean tool) {
        return tool ? "your request" : "the tool's request";
    }

    /** "a Patient", "an Encounter". */
    static String a(String resourceType) {
        return ("AEIOU".indexOf(resourceType.charAt(0)) >= 0 ? "an " : "a ") + resourceType;
    }

    private static String emptyWords(JsonNode value) {
        return value.isArray() ? "an empty list" : value.isObject() ? "an empty object" : "an empty text";
    }

    static String onlyMember(boolean tool, JsonNode value) {
        return switch (JsonSafe.presence(value)) {
            case NULL -> side(tool) + " has this element, written as an explicit null, and " + other(tool) + " does not have it at all.";
            case EMPTY -> side(tool) + " has this element, as " + emptyWords(value) + ", and " + other(tool) + " does not have it at all.";
            default -> side(tool) + " has this element and " + other(tool) + " does not." + holds(value);
        };
    }

    static String onlyKey(boolean tool, String key, JsonNode value) {
        return side(tool) + " has the key `" + key + "` here and " + other(tool) + " does not." + holds(value);
    }

    private static String holds(JsonNode value) {
        String type = ResourceIndex.resourceType(value);
        return type == null ? "" : " It holds " + a(type) + " resource.";
    }

    static String onlyEntry(boolean tool, JsonNode entry) {
        String type = ResourceIndex.resourceType(JsonSafe.child(entry, "resource"));
        // "Left": the other Bundle may hold entries of this kind that were already matched with others.
        return type == null
                ? side(tool) + " has an entry here that holds no resource, and " + other(tool) + " has no such entry left to compare it with."
                : side(tool) + " has this " + type + " in the Bundle and " + other(tool) + " has no " + type + " left to compare it with.";
    }

    /**
     * @param name          what the list is matched on: url, system or type
     * @param keyed         false for an element that has none of its own
     * @param otherHasExtra true when the other list also holds an element without a partner
     */
    static String onlyKeyed(boolean tool, String name, boolean keyed, boolean otherHasExtra) {
        // "No counterpart" and not "does not have one": the other list may hold such elements that were matched already.
        String sentence = "This list is matched by " + name + ". " + side(tool) + " has an element with " + (keyed ? "this " : "no ") + name
                + " that " + other(tool) + " has no counterpart for.";
        return otherHasExtra ? sentence + " The other request has an unmatched element here too: the two were not compared with each "
                + "other, because they do not share a " + name + "." : sentence;
    }

    static String onlyPosition(boolean tool) {
        return side(tool) + " has more elements in this list: it has one at this position and " + other(tool) + " does not.";
    }

    static String onlyValue(boolean tool) {
        return tool ? "This value is in the tool's list and not in yours." : "This value is in your list and not in the tool's.";
    }

    static String onlyReference(boolean tool) {
        return tool ? "The tool's list has a reference here with no counterpart in your list."
                : "Your list has a reference here with no counterpart in the tool's list.";
    }

    static String differentType(JsonNode tool, JsonNode yours) {
        JsonType toolType = JsonType.of(tool);
        JsonType yourType = JsonType.of(yours);
        String sentence = "The tool's request writes this as " + toolType.words() + " and your request writes it as " + yourType.words() + ".";
        boolean scalars = !tool.isContainer() && !yours.isContainer() && !tool.isNull() && !yours.isNull();
        return scalars && Values.text(tool).equals(Values.text(yours))
                ? sentence + " They read the same, but the two are different JSON types." : sentence;
    }

    static String differentText(String tool, String yours) {
        String start = "The two requests hold different text here.";
        if (tool.isEmpty() || yours.isEmpty()) {
            return tool.isEmpty() ? "The tool's request has an empty text here and your request has a value."
                    : "Your request has an empty text here and the tool's request has a value.";
        }
        if (tool.strip().equals(yours.strip())) {
            return start + " They differ only in spaces at the start or the end.";
        }
        if (collapse(tool).equals(collapse(yours))) {
            return start + " They differ only in repeated spaces.";
        }
        if (unspaced(tool).equals(unspaced(yours))) {
            return start + " They differ only in spaces: one has spaces where the other has none.";
        }
        if (tool.equalsIgnoreCase(yours)) {
            return start + " They differ only in letter case.";
        }
        if (sameDay(tool, yours) || sameDay(yours, tool)) {
            return start + " One is a date and the other is a date and time on that same date.";
        }
        return start;
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String collapse(String text) {
        StringBuilder out = new StringBuilder();
        boolean pending = false;
        for (int codePoint : text.strip().codePoints().toArray()) {
            if (space(codePoint)) {
                pending = true;
            } else {
                if (pending && out.length() > 0) {
                    out.append(' ');
                }
                pending = false;
                out.appendCodePoint(codePoint);
            }
        }
        return out.toString();
    }

    private static String unspaced(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(codePoint -> !space(codePoint)).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean sameDay(String date, String dateTime) {
        Matcher matcher = DATE_TIME.matcher(dateTime);
        return DATE.matcher(date).matches() && matcher.matches() && matcher.group(1).equals(date);
    }

    static String differentNumber() {
        return "The two requests hold different numbers here.";
    }

    static String differentBoolean(boolean tool) {
        return "The tool's request says " + tool + " here and your request says " + !tool + ".";
    }

    static String writtenDifferently() {
        return "Both are the same number, written differently. It is listed and not treated as equal, because where an element is a "
                + "FHIR decimal the precision it is written with is part of its value.";
    }

    static String differentOrder() {
        return "Both lists hold the same values, in a different order.";
    }

    static String emptiness(JsonNode tool, JsonNode yours) {
        boolean toolEmpty = tool.isEmpty();
        JsonNode full = toolEmpty ? yours : tool;
        String filled = full.isArray() ? "a list with " + count(full.size(), "element") : "an object with " + count(full.size(), "member");
        return toolEmpty ? "The tool's request has " + emptyWords(tool) + " here and your request has " + filled + "."
                : "Your request has " + emptyWords(yours) + " here and the tool's request has " + filled + ".";
    }

    static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    static String differentResources(String toolType, String yourType) {
        return "The tool's request holds " + a(toolType) + " resource here and your request holds " + a(yourType)
                + " resource. Resources of different types are not compared element by element, so each is given whole.";
    }

    /**
     * @param yoursIsWrapped true when it is the other request that holds its resource inside a Bundle
     */
    static String wrappedInBundle(boolean yoursIsWrapped, String resourceType) {
        // When the other request is the wrapped one, the bare resource is the tool's, so the sentence starts with the tool.
        return side(yoursIsWrapped) + " holds " + a(resourceType) + " resource here and " + other(yoursIsWrapped) + " holds a Bundle with one "
                + resourceType + " in it. The two " + resourceType + " resources are compared with each other in the differences that "
                + "follow, and the rest of the Bundle is given whole here.";
    }

    static String differentKey(String resourceType, String toolKey, String yourKey) {
        return "The tool's request holds this " + resourceType + " under the key `" + toolKey + "` and your request holds it under `"
                + yourKey + "`.";
    }

    static String builtFrom() {
        return " The tool's was built from what was read out of yours, so the two are compared with each other.";
    }

    static String differentIds(String resourceType, int following) {
        String start = "The two requests give this " + resourceType + " different ids. An id is a label inside one request. ";
        if (following == 0) {
            return start + "No reference was found that differs only because it follows this one.";
        }
        return start + (following == 1 ? "1 reference differs only because it follows this id, and is not listed."
                : following + " references differ only because they follow this id, and are not listed one by one.");
    }

    static String differentLabels(String resourceType, int others) {
        String start = "The two requests label this " + resourceType + " differently, and this is one place where each uses its own label.";
        if (others == 0) {
            return start;
        }
        return start + (others == 1 ? " 1 other reference differs for the same reason and is not listed."
                : " " + others + " other references differ for the same reason and are not listed one by one.");
    }

    static String referenceForms(ReferenceText tool, ReferenceText yours) {
        String start = "Both point at the same matched resource, written in different forms: ";
        if (tool.form() != yours.form()) {
            return start + "the tool's request writes " + tool.form().words() + " and your request writes " + yours.form().words() + ".";
        }
        // The same form, so what differs is one of the two things that are part of a form.
        return start + (java.util.Objects.equals(tool.version(), yours.version()) ? "each is an absolute url, on a different base url."
                : "they do not name the same version of the resource.");
    }

    /**
     * @param toolTargets the resources the tool's reference points at inside the tool's request
     * @param yourTargets the same for the other request
     */
    static String referenceElsewhere(ReferenceText tool, List<ResourceIndex.Resource> toolTargets, ReferenceText yours,
                                     List<ResourceIndex.Resource> yourTargets) {
        String sentence = "The two references do not follow one matched pair of resources: the tool's "
                + target(tool, toolTargets, "the tool's request") + ", and yours " + target(yours, yourTargets, "your request") + ".";
        // When neither request carries what is pointed at, or one points at a server by an absolute
        // url, nothing shows whether the two name one thing or two.
        boolean bothOutside = toolTargets.isEmpty() && yourTargets.isEmpty();
        boolean urlOutside = (toolTargets.isEmpty() && tool.form() == ReferenceText.Form.ABSOLUTE)
                || (yourTargets.isEmpty() && yours.form() == ReferenceText.Form.ABSOLUTE);
        boolean sameType = tool.type() != null && tool.type().equals(yours.type());
        return (bothOutside || urlOutside) && sameType ? sentence + " Whether the two name the same " + tool.type() + " cannot be seen from the requests."
                : sentence;
    }

    /**
     * @param inTool  true when the tool's request carries more than one resource the reference can mean
     * @param inYours the same for the other request
     */
    static String ambiguousReference(boolean inTool, boolean inYours) {
        String where = inTool && inYours ? "Each request carries" : inTool ? "The tool's request carries" : "Your request carries";
        return where + " more than one resource with the type and id this reference names, so which one it means cannot be told. It is "
                + "listed here and not counted as following a renamed id.";
    }

    /**
     * @param paths the places of the resources that share one type and id, at least two
     */
    static String duplicated(boolean tool, String resourceType, List<String> paths) {
        String places = paths.size() == 2 ? paths.get(0) + " and " + paths.get(1)
                : String.join(", ", paths.subList(0, paths.size() - 1)) + ", and " + paths.get(paths.size() - 1);
        return side(tool) + " carries " + paths.size() + " " + resourceType + " resources with the same id, at " + places
                + ". A reference to that id can mean either.";
    }

    private static String target(ReferenceText reference, List<ResourceIndex.Resource> targets, String document) {
        if (!targets.isEmpty()) {
            return "points at " + a(targets.get(0).type()) + " in " + document;
        }
        return switch (reference.form()) {
            case CONTAINED -> "points at a resource contained in the resource that holds it";
            // The url names a server, and nothing in the request says the resource there is one it carries.
            case ABSOLUTE -> "is an absolute url" + (reference.type() == null ? "" : " to " + a(reference.type()))
                    + " that nothing in " + document + " ties to a resource the request carries";
            case UNRECOGNISED -> "is not written in a form this tool reads as a reference";
            default -> "points at " + (reference.type() == null ? "a resource" : a(reference.type())) + " that " + document + " does not contain";
        };
    }

    /**
     * A reference that is the same text in both requests and points at a matched resource in only
     * one of them, because the other request labels that resource differently.
     *
     * @param inTool true when it is the tool's request in which the text points at the resource
     * @param paths  the places the text is written at, never empty
     */
    static String sameTextAnotherTarget(boolean inTool, String resourceType, List<String> paths) {
        int others = paths.size() - 1;
        String where = "At " + paths.get(0) + (others == 0 ? "" : others == 1 ? " and at 1 other place" : " and at " + others + " other places");
        String here = inTool ? "the tool's request" : "your request";
        String there = inTool ? "your request" : "the tool's request";
        return where + " the two requests hold the same reference text. In " + here + " it points at " + a(resourceType)
                + " the request carries. The " + resourceType + " that " + there + " carries in its place is labelled differently, so in "
                + there + " the same text does not point at that " + resourceType + ".";
    }

    static String notClassified(boolean known) {
        return known ? " This tool does not say which group a difference in the value of this element belongs to."
                : " This tool has no description of this element, so the difference is not classified.";
    }

    static String depthReached(int levels, String firstPath, int places) {
        return "The comparison stopped at " + levels + " levels of nesting, " + (places == 1 ? "at " : "first at ") + firstPath
                + ". Nothing deeper was compared, so a difference below that level is not listed.";
    }

    /** True when a value is there and is neither null nor empty. */
    static boolean hasContent(JsonNode value) {
        return JsonSafe.presence(value) == Presence.VALUE;
    }
}
