package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the request builder says about the request it built: one plain sentence for each thing
 * left out or worked out, and, apart from those, the elements a PAS 2.2.1 profile requires that
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
     * An element PAS 2.2.1 requires that the request goes out without: one a profile requires, a
     * code without the system its required binding needs, or a resource a reference in the bundle
     * names, which the specification requires the bundle to carry.
     *
     * @param element what was left out, named by its profile and path, such as "PASClaim item.category"
     * @param note    why it was left out and what would supply it, or null when one note made apart says it for several
     */
    void missing(String element, String note) {
        if (!leftOut.contains(element)) {
            leftOut.add(element);
        }
        if (Texts.present(note)) {
            notes.add(note);
        }
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

    /** A note in the past tense of a request that was sent, and what it becomes for a request that was only shown. */
    private static final List<Map.Entry<Pattern, String>> PREVIEW_TENSE = List.of(
            Map.entry(Pattern.compile("\\b(?:was|were) not sent\\b"), "would not be sent"),
            Map.entry(Pattern.compile("\\b(?:was|were) sent\\b"), "would be sent"),
            Map.entry(Pattern.compile("\\bgoes out\\b"), "would go out"),
            Map.entry(Pattern.compile("\\bwent out\\b"), "would go out"));

    /**
     * The notes of a request as a preview shows them. A preview sends nothing, so a note that says
     * something "was sent" is untrue of it; the same note says what "would be sent". Only a run
     * that was sent keeps the past tense.
     */
    static List<String> asPreview(List<String> notes) {
        List<String> out = new ArrayList<>();
        for (String note : notes) {
            String text = note;
            for (Map.Entry<Pattern, String> tense : PREVIEW_TENSE) {
                text = tense.getKey().matcher(text).replaceAll(tense.getValue());
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    /** The one sentence that opens the notes of a request sent without something the profiles require. */
    static String summary(List<String> leftOut) {
        int count = leftOut.size();
        return "This request goes out without " + count + (count == 1 ? " element" : " elements")
                + " PAS 2.2.1 requires, because nobody supplied " + (count == 1 ? "it" : "them") + ": "
                + String.join("; ", leftOut) + ". The service may refuse the request for that alone.";
    }
}
