package org.p32h.interop.epa.pave.service.pas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the references inside a request bundle the way FHIR R4 resolves references in a
 * bundle: an absolute reference names the entry whose fullUrl it is, and a relative one resolves
 * only against the base of a RESTful fullUrl of the entry that holds it. A relative reference in
 * an entry whose fullUrl is a urn:uuid names nothing, which is the mistake this exists to catch.
 */
public final class PasBundles {

    private PasBundles() {
    }

    /** Every reference in the bundle that names no entry of it, with where it sat. Contained references are not looked at. */
    public static List<String> unresolved(JsonNode bundle) {
        List<String> out = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : bundle.get("entry")) {
            String fullUrl = entry.has("fullUrl") ? entry.get("fullUrl").stringValue() : null;
            List<String> references = new ArrayList<>();
            collect(entry.get("resource"), references);
            for (String reference : references) {
                if (resolve(bundle, fullUrl, reference) == null) {
                    out.add("entry " + index + ": " + reference);
                }
            }
            index++;
        }
        return out;
    }

    /** The resource a reference held by the entry with the given fullUrl names, or null. */
    public static JsonNode resolve(JsonNode bundle, String holderFullUrl, String reference) {
        String target = reference;
        if (!reference.contains(":") && holderFullUrl != null && holderFullUrl.matches("https?://.*")) {
            String[] parts = holderFullUrl.split("/");
            String base = String.join("/", java.util.Arrays.copyOf(parts, parts.length - 2));
            target = base + "/" + reference;
        }
        for (JsonNode entry : bundle.get("entry")) {
            if (entry.has("fullUrl") && target.equals(entry.get("fullUrl").stringValue())) {
                return entry.get("resource");
            }
        }
        return null;
    }

    /** The fullUrl of the entry holding the first resource of a type. */
    public static String fullUrlOf(JsonNode bundle, String resourceType) {
        for (JsonNode entry : bundle.get("entry")) {
            if (resourceType.equals(entry.get("resource").get("resourceType").stringValue())) {
                return entry.has("fullUrl") ? entry.get("fullUrl").stringValue() : null;
            }
        }
        return null;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (property.getKey().equals("reference") && property.getValue().isString()) {
                    if (!property.getValue().stringValue().startsWith("#")) {
                        out.add(property.getValue().stringValue());
                    }
                } else if (!property.getKey().equals("contained")) {
                    collect(property.getValue(), out);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collect(element, out);
            }
        }
    }
}
