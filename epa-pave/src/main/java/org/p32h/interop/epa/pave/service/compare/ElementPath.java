package org.p32h.interop.epa.pave.service.compare;

/**
 * Where an element sits, in the two forms a glossary is keyed by. Neither holds what a list
 * element was matched on: {@code coding[http://example.org/codes].code} is {@code coding.code} here.
 *
 * @param document the path from the top of the document, keys joined by dots. A key of a map
 *                 whose keys are free is written {@code *}, for example {@code prefetch.*}
 * @param fhir     the path from the innermost FHIR resource the element sits in, starting with
 *                 the resource type, for example {@code ServiceRequest.code.coding.code}. The
 *                 resource itself is its type alone. Null outside any resource
 */
public record ElementPath(String document, String fhir) {
}
