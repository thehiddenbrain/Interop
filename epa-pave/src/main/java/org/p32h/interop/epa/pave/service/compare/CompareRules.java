package org.p32h.interop.epa.pave.service.compare;

import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import tools.jackson.databind.JsonNode;

/**
 * What the engine cannot know from JSON and FHIR alone about one kind of document. The paths are
 * the document paths of {@link ElementPath}.
 *
 * @param freeKeyed   objects whose keys are chosen by the sender or the receiver, so that the same
 *                    content can sit under two different keys
 * @param references  strings, or lists of strings, that point at a resource without being a FHIR
 *                    Reference. The value is the resource type a bare id there stands for, or an
 *                    empty text when the string always names its own type
 * @param codes       for a resource type, the element whose coded value says what the resource is,
 *                    for example {@code code} for an order for a service. Two entries of a Bundle
 *                    that are left after matching by id are matched on its code before they are
 *                    matched by position, so two lists of the same orders in another order are
 *                    not reported as two changed orders
 * @param serverBase  the path of a url that names the FHIR server the document's ids belong to, or
 *                    null. An absolute url on that server is followed to the resource the document
 *                    carries under the same type and id. An absolute url on any other server is
 *                    not: the same type and id there can be another resource
 */
public record CompareRules(Set<String> freeKeyed, Map<String, String> references, Map<String, String> codes, String serverBase) {

    public CompareRules {
        freeKeyed = freeKeyed == null ? Set.of() : Set.copyOf(freeKeyed);
        references = references == null ? Map.of() : Map.copyOf(references);
        codes = codes == null ? Map.of() : Map.copyOf(codes);
    }

    public CompareRules(Set<String> freeKeyed, Map<String, String> references) {
        this(freeKeyed, references, Map.of(), null);
    }

    /** Rules for a document about which nothing more is known than that it is JSON and may hold FHIR. */
    public static CompareRules none() {
        return new CompareRules(Set.of(), Map.of());
    }

    public boolean freeKeyedAt(String documentPath) {
        return freeKeyed.contains(documentPath);
    }

    public boolean referenceAt(String documentPath) {
        return references.containsKey(documentPath);
    }

    /** The resource type a bare id at this path stands for, or null when there is none. */
    public String bareTypeAt(String documentPath) {
        String type = references.get(documentPath);
        return type == null || type.isBlank() ? null : type;
    }

    /**
     * The code that says what a resource is: the code of the first coding with a code in the
     * element {@link #codes} names for its type. Null when the rules name no element for the type
     * or the resource carries no such code. The code alone, without its system: a sender that
     * writes the same code under another system url still means the same order, and that
     * difference is then shown on the pair.
     */
    public String codeOf(JsonNode resource, String resourceType) {
        String element = resourceType == null ? null : codes.get(resourceType);
        if (element == null) {
            return null;
        }
        for (JsonNode coding : JsonSafe.elements(JsonSafe.child(resource, element), "coding")) {
            if (JsonSafe.child(coding, "code").isString()) {
                return JsonSafe.text(coding, "code");
            }
        }
        return null;
    }

    /** The server base a document names at {@link #serverBase}, or null when it names none. */
    public String serverBaseOf(JsonNode root) {
        if (serverBase == null || root == null) {
            return null;
        }
        JsonNode node = JsonSafe.at(root, serverBase.split("\\."));
        return ResourceIndex.exact(node);
    }
}
