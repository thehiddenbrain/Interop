package org.p32h.interop.epa.pave.service.compare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.JsonNode;

/**
 * The FHIR resources one document carries and the references it makes, so a reference can be
 * followed to the resource it points at inside that same document.
 *
 * <p>A resource is any object that states a {@code resourceType}, wherever it sits. A reference
 * is a {@code reference} inside a resource, or a string at one of the places the rules name. A
 * Bundle entry's {@code fullUrl} is not a reference: it is the address of the entry's own
 * resource, which a reference elsewhere may use.
 */
public final class ResourceIndex {

    /**
     * One resource in the document.
     *
     * @param id      its id, or null when it has none
     * @param fullUrl the full url of the Bundle entry that holds it, or null
     * @param path    where it sits, written with list positions
     */
    public record Resource(JsonNode node, String type, String id, String fullUrl, String path, String pointer) {

        /** Prints the type and the place, never the resource. */
        @Override
        public String toString() {
            return "Resource[" + type + " at " + path + "]";
        }
    }

    /** One reference the document makes, with the place it is written at. */
    public record Use(String path, String pointer, ReferenceText text) {
    }

    private final CompareRules rules;
    private final List<Resource> resources = new ArrayList<>();
    private final List<Use> uses = new ArrayList<>();
    private final Map<String, List<Resource>> byTypeAndId = new HashMap<>();
    private final Map<String, List<Resource>> byFullUrl = new HashMap<>();
    /** By identity: two resources of one document can be equal and are still two. */
    private final Map<JsonNode, Resource> byNode = new java.util.IdentityHashMap<>();

    private ResourceIndex(CompareRules rules) {
        this.rules = rules == null ? CompareRules.none() : rules;
    }

    public static ResourceIndex of(JsonNode root, CompareRules rules) {
        ResourceIndex index = new ResourceIndex(rules);
        if (root != null) {
            index.walk(root, "", "", "", false, 1, null);
        }
        return index;
    }

    /** The resource type an object states as text, or null when the node is not a resource. */
    public static String resourceType(JsonNode node) {
        JsonNode type = JsonSafe.child(node, "resourceType");
        return type.isString() ? Texts.trimToNull(type.stringValue()) : null;
    }

    // Bounded by the same depth as the comparison, so a document nested without end cannot exhaust the stack.
    private void walk(JsonNode node, String path, String pointer, String document, boolean inResource, int depth, String fullUrl) {
        if (depth > JsonComparer.MAX_DEPTH) {
            return;
        }
        if (node.isObject()) {
            String type = resourceType(node);
            if (type != null) {
                add(new Resource(node, type, exact(JsonSafe.child(node, "id")), fullUrl, path, pointer));
            }
            boolean within = inResource || type != null;
            boolean freeKeys = rules.freeKeyedAt(document);
            String entryUrl = exact(JsonSafe.child(node, "fullUrl"));
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String key = member.getKey();
                JsonNode child = member.getValue();
                String childPath = Paths.join(path, Paths.display(key));
                String childPointer = Paths.member(pointer, key);
                String childDocument = Paths.join(document, freeKeys ? "*" : key);
                boolean reference = (within && key.equals("reference")) || rules.referenceAt(childDocument);
                if (reference && child.isString()) {
                    uses.add(new Use(childPath, childPointer, ReferenceText.parse(child.stringValue(), rules.bareTypeAt(childDocument))));
                } else if (reference && child.isArray()) {
                    for (int i = 0; i < child.size(); i++) {
                        if (child.get(i).isString()) {
                            uses.add(new Use(childPath + "[" + i + "]", Paths.element(childPointer, i),
                                    ReferenceText.parse(child.get(i).stringValue(), rules.bareTypeAt(childDocument))));
                        }
                    }
                }
                walk(child, childPath, childPointer, childDocument, within, depth + 1, key.equals("resource") ? entryUrl : null);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", Paths.element(pointer, i), document, inResource, depth + 1, null);
            }
        }
    }

    private void add(Resource resource) {
        resources.add(resource);
        byNode.put(resource.node(), resource);
        if (resource.id() != null) {
            byTypeAndId.computeIfAbsent(resource.type() + "/" + resource.id(), k -> new ArrayList<>()).add(resource);
        }
        if (resource.fullUrl() != null) {
            byFullUrl.computeIfAbsent(urlKey(resource.fullUrl()), k -> new ArrayList<>()).add(resource);
        }
    }

    /** A label exactly as written, or null when the node is not text or is blank. Never trimmed: a label with a space in it is another label. */
    static String exact(JsonNode node) {
        return node.isString() && !node.stringValue().isBlank() ? node.stringValue() : null;
    }

    // A urn is compared without regard to letter case, since a UUID may be written either way. A url is not.
    private static String urlKey(String url) {
        return url.regionMatches(true, 0, "urn:", 0, 4) ? url.toLowerCase(Locale.ROOT) : url;
    }

    /** True when two full urls are the same address, or when neither entry has one. */
    public static boolean sameUrl(String one, String other) {
        return one == null || other == null ? one == null && other == null : urlKey(one).equals(urlKey(other));
    }

    /** What the index knows of the resource that this very node is, or null when the node is not one of its resources. */
    public Resource at(JsonNode node) {
        return node == null ? null : byNode.get(node);
    }

    /** Every resource, in document order. */
    public List<Resource> resources() {
        return List.copyOf(resources);
    }

    /** Every resource of one type, in document order. */
    public List<Resource> resources(String type) {
        return resources.stream().filter(resource -> resource.type().equals(type)).toList();
    }

    /** Every reference the document makes, in document order. */
    public List<Use> uses() {
        return List.copyOf(uses);
    }

    /**
     * The resources in this document a reference points at. Empty when it points outside the
     * document or is not written in a form that can be followed. More than one when the document
     * carries the same resource twice.
     */
    public List<Resource> resolve(ReferenceText reference) {
        if (reference == null) {
            return List.of();
        }
        return switch (reference.form()) {
            case RELATIVE, BARE -> byTypeAndId.getOrDefault(reference.type() + "/" + reference.id(), List.of());
            case URN -> byFullUrl.getOrDefault(urlKey(reference.raw()), List.of());
            case ABSOLUTE -> {
                List<Resource> byUrl = byFullUrl.getOrDefault(urlKey(reference.raw()), List.of());
                if (!byUrl.isEmpty() || reference.type() == null) {
                    yield byUrl;
                }
                // By its last two segments, but never to a resource whose entry gives it another
                // absolute address: that one is somewhere else, whatever its id.
                yield byTypeAndId.getOrDefault(reference.type() + "/" + reference.id(), List.of()).stream()
                        .filter(resource -> resource.fullUrl() == null || ReferenceText.parse(resource.fullUrl(), null).form() != ReferenceText.Form.ABSOLUTE)
                        .toList();
            }
            default -> List.of();
        };
    }

    /**
     * The references that point at a resource this document does not carry, among those that
     * could only be followed inside it or on a FHIR server the document names: a type and id, a
     * bare id and a urn. An absolute url says for itself where its target is, and a pointer to a
     * contained resource never leaves its own resource, so neither is listed.
     */
    public List<Use> pointingOutside() {
        List<Use> out = new ArrayList<>();
        for (Use use : uses) {
            ReferenceText.Form form = use.text().form();
            boolean local = form == ReferenceText.Form.RELATIVE || form == ReferenceText.Form.BARE || form == ReferenceText.Form.URN;
            if (local && Texts.present(use.text().raw()) && resolve(use.text()).isEmpty()) {
                out.add(use);
            }
        }
        return out;
    }
}
