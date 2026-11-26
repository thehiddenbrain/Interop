package org.p32h.interop.epa.pave.model.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One place where the request this tool builds and the request a tester pasted are not the same.
 *
 * <p>The two values are {@link Shown}, so a value that is absent, an explicit null and an empty
 * text, list or object stay three different things on screen. The prose fields name elements and
 * never quote a value: a value can be a member's name or date of birth, and it belongs in the two
 * value fields, which exist to be shown.
 *
 * @param path                where the element sits, readable and stable. What an element of a list
 *                            was matched on is shown in brackets, for example
 *                            {@code entry[ServiceRequest]} or {@code coding[http://example.org/codes]}
 * @param toolPointer         the JSON Pointer of the element in the tool's request, or null when
 *                            that request does not have it
 * @param yourPointer         the JSON Pointer of the element in the pasted request, or null
 * @param kindLabel           the words for the kind
 * @param toolValue           what the tool's request holds there. A list or an object is given as
 *                            compact JSON. Text longer than 2,000 characters is cut and says so.
 *                            For a different key, the key
 * @param yourValue           the same for the pasted request
 * @param toolType            the JSON type of the tool's value: string, number, boolean, null,
 *                            array or object. Null when the request does not have the element, and
 *                            for a different key. It tells an empty text from an empty list
 * @param yourType            the same for the pasted request
 * @param what                what the element is, in one plain sentence, or null when this tool
 *                            has no description of it
 * @param explanation         what the difference means, as far as the structure alone says
 * @param referencesFollowing for a difference in a label: how many references differ only because
 *                            they follow it, and are therefore not listed. Null for any other difference
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"path", "kind", "kindLabel", "group", "toolValue", "yourValue", "toolType", "yourType", "what", "explanation",
        "referencesFollowing", "toolPointer", "yourPointer"})
public record Difference(String path, String toolPointer, String yourPointer, DifferenceKind kind, String kindLabel,
                         DifferenceGroup group, Shown toolValue, Shown yourValue, String toolType, String yourType, String what,
                         String explanation, Integer referencesFollowing) {

    public Difference {
        if (path == null || kind == null || group == null || toolValue == null || yourValue == null || explanation == null) {
            throw new IllegalArgumentException("A difference needs a path, a kind, a group, both values and an explanation.");
        }
    }

    /** Prints where and what kind, never a value: a value can be member data. */
    @Override
    public String toString() {
        return "Difference[" + path + ", " + kind + ", " + group + "]";
    }
}
