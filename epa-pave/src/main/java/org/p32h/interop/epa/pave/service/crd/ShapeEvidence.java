package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.RequestShape;

/**
 * What a vendor's own sample request shows about one request shape: the value its body takes and
 * the request it was read from, so the screen and the request notes can say "from the
 * collection's sample" and name that sample.
 *
 * @param shape       the shape the sample decides
 * @param value       one of the shape's option values, for example {@link RequestShape#LEFT_OUT}
 * @param requestName the name of the collection request whose saved body shows it
 * @param layout      for the prefetch, the arrangement the sample writes; null for every other shape
 */
public record ShapeEvidence(RequestShape shape, String value, String requestName, PrefetchLayout layout) {

    public ShapeEvidence(RequestShape shape, String value, String requestName) {
        this(shape, value, requestName, null);
    }

    /** Prints the shape and the value. The request name is the vendor's text and is said to be there, never printed. */
    @Override
    public String toString() {
        return "ShapeEvidence[" + shape + ", " + value + (requestName == null ? "" : ", request named") + "]";
    }
}
