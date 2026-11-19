package org.p32h.interop.epa.pave.client.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import tools.jackson.databind.JsonNode;

/**
 * Reads the fields of one record through {@link TrackedJson}.
 *
 * <p>Only the value a field is finally read from is marked as used. Every other spelling that was
 * looked at and passed over, and everything the mapping never asked about, stays among the
 * returned fields, because a field this tool did not interpret is the evidence needed to finish a
 * mapping whose contract is not final.
 */
final class LookupReader {

    /**
     * What came back for one field.
     *
     * @param shown    the value as returned, with its presence, for the screen
     * @param text     the value for use in logic: stripped, and null when nothing or only blank came back
     * @param source   where it was read from, written the way the returned fields write a path. Null
     *                 when the response carried no spelling the mapping knows
     * @param spelling the spelling that matched, or null
     */
    record Read(Shown shown, String text, String source, Spelling spelling) {

        boolean hasText() {
            return Texts.present(text);
        }

        /**
         * The value as a yes or a no, for a field that asks a question: true, false, or null when
         * the value is neither. A JSON true or false, and the words a service writes for them.
         */
        Boolean yesOrNo() {
            if (!hasText()) {
                return null;
            }
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "y" -> Boolean.TRUE;
                case "false", "no", "n" -> Boolean.FALSE;
                default -> null;
            };
        }

        LookupField field(String name, String label) {
            return new LookupField(name, label, shown, source);
        }

        /** Prints the presence only. The text is member data. */
        @Override
        public String toString() {
            return "Read[" + shown.presence() + "]";
        }
    }

    private static final Read NOT_RETURNED = new Read(Shown.absent(), null, null, null);

    private final List<TrackedJson.Node> scopes;

    /** @param scopes the record first, then the objects that wrap it, nearest first */
    LookupReader(List<TrackedJson.Node> scopes) {
        this.scopes = List.copyOf(scopes);
    }

    /**
     * Reads one field from the first spelling that carries a value. When none does, the first
     * spelling that is in the response at all is reported, as null or empty, so "returned with
     * nothing in it" stays apart from "not returned".
     *
     * <p>Only a single value or an explicit null is read. An object or a list sitting where one
     * value was expected is not this field in a form the tool understands, so it is left among the
     * returned fields and not forced into a value.
     */
    Read read(List<Spelling> spellings) {
        TrackedJson.Node withoutValue = null;
        Spelling withoutValueSpelling = null;
        for (Spelling spelling : spellings) {
            for (TrackedJson.Node candidate : candidates(spelling)) {
                JsonNode raw = candidate.raw();
                if (raw.isContainer()) {
                    continue;
                }
                if (JsonSafe.presentText(raw) != null) {
                    return chosen(candidate, spelling);
                }
                if (withoutValue == null) {
                    withoutValue = candidate;
                    withoutValueSpelling = spelling;
                }
            }
        }
        return withoutValue == null ? NOT_RETURNED : chosen(withoutValue, withoutValueSpelling);
    }

    /**
     * The first object found under one of the names, in the record and then in what wraps it. Null
     * when there is none. For a group of fields that must all come from one place, such as the
     * parts of an address: read from two objects they would describe an address nobody has.
     *
     * <p>Unlike a single value, an object under one of these names is looked for beside a wrapped
     * record too. The names it is called with are names of things a record has, such as an
     * address, and a response has none of its own.
     */
    TrackedJson.Node object(List<String> names) {
        for (TrackedJson.Node scope : scopes) {
            for (String name : names) {
                for (TrackedJson.Node candidate : find(scope, Spelling.of(name).steps())) {
                    TrackedJson.Node one = theOne(candidate);
                    if (one != null && one.isObject()) {
                        return one;
                    }
                }
            }
        }
        return null;
    }

    private static Read chosen(TrackedJson.Node node, Spelling spelling) {
        // shown() is what marks the value as used, so it is called for the one value read and no other.
        Shown shown = node.shown();
        return new Read(shown, Texts.trimToNull(shown.orNull()), node.path(), spelling);
    }

    private List<TrackedJson.Node> candidates(Spelling spelling) {
        List<TrackedJson.Node> out = new ArrayList<>();
        // A single name is looked for in the record only. See Spelling.nested().
        List<TrackedJson.Node> where = spelling.nested() ? scopes : scopes.subList(0, Math.min(1, scopes.size()));
        for (TrackedJson.Node scope : where) {
            out.addAll(find(scope, spelling.steps()));
        }
        return out;
    }

    private static List<TrackedJson.Node> find(TrackedJson.Node from, List<Spelling.Step> steps) {
        List<TrackedJson.Node> current = List.of(from);
        for (Spelling.Step step : steps) {
            List<TrackedJson.Node> next = new ArrayList<>();
            for (TrackedJson.Node node : current) {
                TrackedJson.Node object = theOne(node);
                if (object == null || !object.isObject()) {
                    continue;
                }
                for (Map.Entry<String, TrackedJson.Node> property : object.properties()) {
                    if (!Spelling.normalise(property.getKey()).equals(step.name())) {
                        continue;
                    }
                    TrackedJson.Node child = property.getValue();
                    if (step.index() < 0) {
                        next.add(child);
                    } else {
                        List<TrackedJson.Node> elements = child.elements();
                        if (step.index() < elements.size()) {
                            next.add(elements.get(step.index()));
                        }
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    /**
     * A list of exactly one object is read as that object, so {@code "name": [{...}]} reads like
     * {@code "name": {...}}. A list of several is never chosen from: which entry is the right one
     * is not something this tool knows, and all of them stay among the returned fields.
     */
    private static TrackedJson.Node theOne(TrackedJson.Node node) {
        if (!node.isArray()) {
            return node;
        }
        List<TrackedJson.Node> elements = node.elements();
        return elements.size() == 1 ? elements.get(0) : null;
    }
}
