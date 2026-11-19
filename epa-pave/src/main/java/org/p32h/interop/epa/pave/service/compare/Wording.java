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

    // ---- an element only one request has ----

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
     * @param name          what the list is matched on: url or system
     * @param keyed         false for an element that has no url or system at all
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

    // ---- an element both have ----

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
        return "Both are the same number, written differently. FHIR treats the written precision of a decimal as meaningful, so "
                + "this is listed and not treated as equal.";
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

    // ---- labels and references ----

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
        if (tool.form() == ReferenceText.Form.ABSOLUTE && yours.form() == ReferenceText.Form.ABSOLUTE) {
            return start + "each is an absolute url, on a different base url.";
        }
        return start + "the tool's request writes " + tool.form().words() + " and your request writes " + yours.form().words() + ".";
    }

    /**
     * @param toolTargets the resources the tool's reference points at inside the tool's request
     * @param yourTargets the same for the other request
     */
    static String referenceElsewhere(ReferenceText tool, List<ResourceIndex.Resource> toolTargets, ReferenceText yours,
                                     List<ResourceIndex.Resource> yourTargets) {
        String sentence = "The two references do not follow one matched pair of resources: the tool's "
                + target(tool, toolTargets, "the tool's request") + ", and yours " + target(yours, yourTargets, "your request") + ".";
        // When neither request carries what is pointed at, nothing shows whether the two ids name one thing or two.
        boolean bothOutside = toolTargets.isEmpty() && yourTargets.isEmpty() && tool.type() != null && tool.type().equals(yours.type());
        return bothOutside ? sentence + " Whether the two name the same " + tool.type() + " cannot be seen from the requests." : sentence;
    }

    private static String target(ReferenceText reference, List<ResourceIndex.Resource> targets, String document) {
        if (!targets.isEmpty()) {
            return "points at " + a(targets.get(0).type()) + " in " + document;
        }
        return switch (reference.form()) {
            case CONTAINED -> "points at a resource contained in the resource that holds it";
            case UNRECOGNISED -> "is not written in a form this tool reads as a reference";
            default -> "points at " + (reference.type() == null ? "a resource" : a(reference.type())) + " that " + document + " does not contain";
        };
    }

    // ---- what the engine could not do ----

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
