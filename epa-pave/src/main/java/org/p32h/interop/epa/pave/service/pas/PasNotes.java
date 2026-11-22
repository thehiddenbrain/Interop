package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the request builder says about the request it built: one plain sentence for each thing
 * left out or worked out, and, apart from those, the elements a profile of the PAS guide in force
 * requires that went out without a value.
 *
 * <p>The two are kept apart because they lead to different things on the result. Every note is
 * shown beside the request. An element left out that the profile requires also becomes the first
 * note, with the count, and one finding, so that a refusal by the service is not read as a fault
 * of the service.
 */
final class PasNotes {

    private final List<String> notes = new ArrayList<>();
    private final List<String> leftOut = new ArrayList<>();
    private final List<RequestPreview.ToComplete> toComplete = new ArrayList<>();
    private final PasGuide guide;

    PasNotes() {
        this(PasGuide.V201);
    }

    /** @param guide the PAS version the request is held against, which every sentence names */
    PasNotes(PasGuide guide) {
        this.guide = guide == null ? PasGuide.V201 : guide;
    }

    PasGuide guide() {
        return guide;
    }

    /** The guide as a sentence names it, such as "PAS 2.0.1". */
    String pas() {
        return guide.label();
    }

    /** The US Core version the guide's profiles are built on. */
    String usCore() {
        return guide.usCore();
    }

    /** A sentence about the request that claims no requirement. */
    void add(String note) {
        notes.add(note);
    }

    /**
     * An element the PAS guide in force requires that the request goes out without: one a profile requires, a
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

    /**
     * A gap the tester can close before sending, with the field that closes it, for "To complete
     * before sending". Said once per field: several elements one value supplies are one gap.
     *
     * @param field a case field such as {@code provider.phone}, or a value of the environment such as {@code values.payerName}
     * @param line  what is missing, the rule that wants it, and where to add it, starting with the words the chip shows
     */
    void toComplete(String field, String line) {
        if (toComplete.stream().noneMatch(gap -> gap.field().equals(field))) {
            toComplete.add(new RequestPreview.ToComplete(field, line));
        }
    }

    /**
     * A value the tester gave for an element the guide in force does not define: it is not sent,
     * and the note names it by the label the tester gave it under.
     */
    void notSent(String label) {
        notes.add(label + ": not sent, " + guide.label() + " has no place for it");
    }

    List<RequestPreview.ToComplete> toComplete() {
        return List.copyOf(toComplete);
    }

    List<String> leftOut() {
        return List.copyOf(leftOut);
    }

    /** The notes, with the statement of what the profiles require first when something was left out. */
    List<String> notes() {
        List<String> out = new ArrayList<>();
        if (!leftOut.isEmpty()) {
            out.add(summary(leftOut, guide));
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
        return summary(leftOut, PasGuide.V201);
    }

    static String summary(List<String> leftOut, PasGuide guide) {
        int count = leftOut.size();
        return "This request goes out without " + count + (count == 1 ? " element" : " elements")
                + " " + (guide == null ? PasGuide.V201 : guide).label() + " requires, because nobody supplied " + (count == 1 ? "it" : "them") + ": "
                + String.join("; ", leftOut) + ". The service may refuse the request for that alone.";
    }
}
