package org.p32h.interop.epa.pave.service.compare;

import org.p32h.interop.epa.pave.model.compare.DifferenceKind;

/**
 * What the elements of one kind of document are. The comparison engine knows JSON and the general
 * shapes of FHIR; everything it says about what an element is, and which part it plays, it asks
 * of this.
 *
 * <p>An element the glossary does not know is answered with null, and its differences are then
 * reported as not classified. That is the intended outcome for anything a glossary is not sure
 * of: a tester repeats a description to a vendor, so a wrong one costs more than a missing one.
 */
public interface Glossary {

    /** A glossary that knows nothing. Every difference is then described by its structure alone. */
    Glossary NONE = element -> null;

    /**
     * What one element is.
     *
     * @param what one plain sentence
     * @param role the part the element plays, or null when the element is known but its role
     *             depends on where it is used. A difference in its value is then not classified
     */
    record Entry(String what, ElementRole role) {
    }

    /** The element at this place, or null when this glossary does not know it. */
    Entry describe(ElementPath element);

    /**
     * A sentence to add to the explanation of a difference of this kind at this element, or null
     * for none. For what follows from the structure of the document or from how the sender
     * builds it, such as which variable fills an element. Never for what a receiver expects.
     */
    default String remark(ElementPath element, DifferenceKind kind) {
        return null;
    }
}
