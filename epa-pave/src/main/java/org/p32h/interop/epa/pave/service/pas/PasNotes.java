package org.p32h.interop.epa.pave.service.pas;

import java.util.ArrayList;
import java.util.List;

/**
 * What the request builder says about the request it built: one plain sentence for each thing
 * left out or worked out, and, apart from those, the elements a PAS 2.0.1 profile requires that
 * went out without a value.
 *
 * <p>The two are kept apart because they lead to different things on the result. Every note is
 * shown beside the request. An element left out that the profile requires also becomes the first
 * note, with the count, and one finding, so that a refusal by the service is not read as a fault
 * of the service.
 */
final class PasNotes {

    private final List<String> notes = new ArrayList<>();
    private final List<String> leftOut = new ArrayList<>();

    /** A sentence about the request that claims no requirement. */
    void add(String note) {
        notes.add(note);
    }

    /**
     * An element a PAS 2.0.1 profile requires that the request goes out without.
     *
     * @param element what was left out, named by its profile and path, such as "PASClaim item.category"
     * @param note    why it was left out and what would supply it
     */
    void missing(String element, String note) {
        if (!leftOut.contains(element)) {
            leftOut.add(element);
        }
        notes.add(note);
    }

    List<String> leftOut() {
        return List.copyOf(leftOut);
    }

    /** The notes, with the statement of what the profiles require first when something was left out. */
    List<String> notes() {
        List<String> out = new ArrayList<>();
        if (!leftOut.isEmpty()) {
            out.add(summary(leftOut));
        }
        out.addAll(notes);
        return List.copyOf(out);
    }

    /** The one sentence that opens the notes of a request sent without something the profiles require. */
    static String summary(List<String> leftOut) {
        int count = leftOut.size();
        return "This request goes out without " + count + (count == 1 ? " element" : " elements")
                + " the PAS 2.0.1 profiles require, because nobody supplied " + (count == 1 ? "it" : "them") + ": "
                + String.join("; ", leftOut) + ". The service may refuse the request for that alone.";
    }
}
