package org.p32h.interop.epa.pave.service.crd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Names;
import tools.jackson.databind.JsonNode;

/**
 * How a vendor's coverage-check sample arranges its prefetch: each key in the sample's order, and
 * what it holds there, by kind and resource type only.
 *
 * <p>CDS Hooks fixes no names for the prefetch. A service names its keys in its discovery answer,
 * each with a FHIR read or search, and a client sends a read as the resource itself and a search as
 * a searchset Bundle. PAVE's five named Bundles are what one earlier sandbox accepted (R-37), so a
 * service that looks for the patient under another key finds none. The sample is the vendor's own
 * statement of its keys, and PAVE follows its arrangement while building every resource from the
 * case: nothing of a sample's resources is kept here, only keys, kinds, resource types, Bundle types
 * and search modes (D-309).
 *
 * <p>The body is read once as a tree and the prefetch by fixed paths, two levels down, so nothing
 * here grows with the depth of the file.
 *
 * @param keys each key of the sample's prefetch, in its order; never empty
 */
public record PrefetchLayout(List<Key> keys) {

    /** The resource types PAVE builds from the case, which are all a prefetch it sends can hold. */
    public static final Set<String> BUILT = Set.of("Patient", "Coverage", "Practitioner", "PractitionerRole", "Organization",
            "ServiceRequest");

    /** What one key of the sample holds. */
    public enum Kind {
        /** A single resource, as a FHIR read gives it. */
        RESOURCE,
        /** A Bundle, as a FHIR search gives it. */
        BUNDLE,
        /** JSON null, which CDS Hooks reads as "could not be fetched". */
        NULL,
        /** A Postman placeholder, whose value is the vendor's own and is never sent. */
        PLACEHOLDER,
        /** Anything else: a text, a number, an array, or an object with no resource type. */
        OTHER
    }

    /**
     * One entry of a sample's Bundle.
     *
     * @param resourceType the entry's resource type, or null when the entry names none
     * @param searchMode   the entry's search mode, or null when it states none
     */
    public record Entry(String resourceType, String searchMode) {
    }

    /**
     * One key of the sample's prefetch.
     *
     * @param name         the key, as the sample writes it
     * @param kind         what it holds
     * @param resourceType the resource type of a single resource, or null
     * @param bundleType   the type of a Bundle as the sample states it, or null when it states none
     * @param total        whether the sample's Bundle states a total
     * @param entries      the entries of a Bundle, in the sample's order; empty otherwise
     */
    public record Key(String name, Kind kind, String resourceType, String bundleType, boolean total, List<Entry> entries) {

        public Key {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** The key as a sentence quotes it. */
        public String shownName() {
            return "\"" + Names.shown(name) + "\"";
        }

        /** What the key holds, in words: "a Patient", "a Bundle of type searchset holding Coverage". */
        public String holds() {
            return switch (kind) {
                case RESOURCE -> article(resourceType) + " " + Names.shown(resourceType);
                case BUNDLE -> "a Bundle" + (bundleType == null ? " with no type" : " of type " + Names.shown(bundleType))
                        + (entryTypes().isEmpty() ? " with no entries" : " holding " + words(entryTypes().stream().map(Names::shown).toList()));
                case NULL -> "null";
                case PLACEHOLDER -> "a placeholder";
                case OTHER -> "something other than a resource or a Bundle";
            };
        }

        /** The resource types of the entries, each once, in the order they first appear; an entry with none is passed over. */
        public List<String> entryTypes() {
            List<String> types = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.resourceType() != null && !types.contains(entry.resourceType())) {
                    types.add(entry.resourceType());
                }
            }
            return types;
        }

        /** The search mode the sample's first entry of a type states, or null. */
        public String searchMode(String resourceType) {
            for (Entry entry : entries) {
                if (resourceType.equals(entry.resourceType()) && entry.searchMode() != null) {
                    return entry.searchMode();
                }
            }
            return null;
        }

        /** Names only: a key is the vendor's word, never member data. */
        @Override
        public String toString() {
            return "Key[" + kind + "]";
        }
    }

    public PrefetchLayout {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** Counts only. */
    @Override
    public String toString() {
        return "PrefetchLayout[" + keys.size() + " keys]";
    }

    /**
     * The arrangement of the prefetch a saved body writes.
     *
     * @param savedBody the body as saved in the collection, placeholders intact, or null
     * @return the layout, or null when there is no body, it is not JSON, or its prefetch is absent,
     *         not an object, or empty: such a sample says nothing of the keys its service reads
     */
    public static PrefetchLayout read(String savedBody) {
        if (!Texts.present(savedBody)) {
            return null;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersQuoted(savedBody));
        if (!parsed.ok()) {
            return null;
        }
        JsonNode prefetch = JsonSafe.child(parsed.node(), "prefetch");
        if (!JsonSafe.isObject(prefetch)) {
            return null;
        }
        List<Key> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : JsonSafe.properties(prefetch)) {
            keys.add(key(member.getKey(), member.getValue()));
        }
        return keys.isEmpty() ? null : new PrefetchLayout(keys);
    }

    /**
     * The layout a call follows: none for a sample whose name says it is a sample of an error, which
     * may send the very arrangement the service refuses in order to show the refusal.
     *
     * @param requestName the name of the collection request the call comes from, or null
     */
    public static PrefetchLayout ofCall(String requestName, String savedBody) {
        if (errorSample(requestName)) {
            return null;
        }
        return read(savedBody);
    }

    /** True when a request's name says it is a sample of an error, as every reader of samples passes it over. */
    public static boolean errorSample(String requestName) {
        return Texts.orElse(requestName, "").toLowerCase(Locale.ROOT).contains("error");
    }

    private static Key key(String name, JsonNode value) {
        if (value == null || value.isNull()) {
            return new Key(name, Kind.NULL, null, null, false, List.of());
        }
        if (value.isString() && placeholder(value.asString())) {
            return new Key(name, Kind.PLACEHOLDER, null, null, false, List.of());
        }
        String type = JsonSafe.presentText(value, "resourceType");
        if (!JsonSafe.isObject(value) || type == null) {
            return new Key(name, Kind.OTHER, null, null, false, List.of());
        }
        if (!"Bundle".equals(type)) {
            return new Key(name, Kind.RESOURCE, type, null, false, List.of());
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : JsonSafe.elements(value, "entry")) {
            entries.add(new Entry(JsonSafe.presentText(JsonSafe.child(entry, "resource"), "resourceType"),
                    JsonSafe.presentText(JsonSafe.child(entry, "search"), "mode")));
        }
        String bundleType = JsonSafe.presentText(value, "type");
        return new Key(name, Kind.BUNDLE, null, bundleType != null && placeholder(bundleType) ? null : bundleType,
                value.has("total"), entries);
    }

    private static boolean placeholder(String text) {
        String value = text.strip();
        return value.startsWith("{{") && value.endsWith("}}");
    }

    /** The sample's keys as the shape's option names them: "patient, coverage". */
    public String keyNames() {
        return String.join(", ", keys.stream().map(key -> Names.shown(key.name())).toList());
    }

    /** The words of the option As the collection's sample, with the keys it would send. */
    public String optionWords() {
        return "As the collection's sample (" + keyNames() + ")";
    }

    /**
     * What a request in this arrangement leaves out whatever the case, one line per key or entry,
     * by key and type and never by value: a type PAVE does not build, a placeholder, a value that is
     * no resource. The same lines stand in the form and in the request's notes.
     */
    public List<String> leftOutLines() {
        List<String> lines = new ArrayList<>();
        for (Key key : keys) {
            String line = leftOutLine(key);
            if (line != null) {
                lines.add(line);
            }
            if (key.kind() == Kind.BUNDLE) {
                for (String type : key.entryTypes()) {
                    if (!BUILT.contains(type)) {
                        lines.add(notBuilt(type, "in the Bundle under " + key.shownName()));
                    }
                }
                if (key.entries().stream().anyMatch(entry -> entry.resourceType() == null)) {
                    lines.add("The sample's Bundle under " + key.shownName() + " has an entry with no resource type. PAVE cannot tell "
                            + "what it would hold, so it is not sent.");
                }
            }
        }
        return lines;
    }

    /** The line for a whole key left out whatever the case, or null when the key can be filled. */
    private static String leftOutLine(Key key) {
        return switch (key.kind()) {
            case RESOURCE -> BUILT.contains(key.resourceType()) ? null : notBuilt(key.resourceType(), "under " + key.shownName());
            case PLACEHOLDER -> "The sample's prefetch writes " + key.shownName() + " as a placeholder. PAVE sends nothing of a sample's "
                    + "values, so it is not sent.";
            case OTHER -> "The sample's prefetch holds something other than a resource or a Bundle under " + key.shownName()
                    + ". PAVE cannot fill it from the case, so it is not sent.";
            case BUNDLE -> !key.entryTypes().isEmpty() && key.entryTypes().stream().noneMatch(BUILT::contains)
                    ? "The sample's Bundle under " + key.shownName() + " holds no type PAVE builds, so the key is not sent." : null;
            case NULL -> null;
        };
    }

    /** "The sample's prefetch also carries Encounter under "encounter". PAVE does not build it, so it is not sent." */
    static String notBuilt(String type, String where) {
        return "The sample's prefetch also carries " + Names.shown(type) + " " + where + ". PAVE does not build it, so it is not sent.";
    }

    static String article(String word) {
        return word != null && !word.isEmpty() && "AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an" : "a";
    }

    static String words(List<String> items) {
        return items.size() < 2 ? String.join("", items)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
