package org.p32h.interop.epa.pave.service.compare;

import java.util.Map;
import java.util.Set;

/**
 * What the engine cannot know from JSON and FHIR alone about one kind of document. Both are keyed
 * by the document path of {@link ElementPath}.
 *
 * @param freeKeyed  objects whose keys are chosen by the sender or the receiver, so that the same
 *                   content can sit under two different keys
 * @param references strings, or lists of strings, that point at a resource without being a FHIR
 *                   Reference. The value is the resource type a bare id there stands for, or an
 *                   empty text when the string always names its own type
 */
public record CompareRules(Set<String> freeKeyed, Map<String, String> references) {

    public CompareRules {
        freeKeyed = freeKeyed == null ? Set.of() : Set.copyOf(freeKeyed);
        references = references == null ? Map.of() : Map.copyOf(references);
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
}
