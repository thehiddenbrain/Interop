package org.p32h.interop.epa.pave.service.lookup;

import java.util.ArrayList;
import java.util.List;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * A place in a lookup answer as a tester writes it (R-11): names and {@code [n]} joined by dots,
 * such as {@code data.members[0].dob}, at most {@value #MAX_PARTS} parts.
 *
 * <p>Read character by character, with no pattern and no recursion (R-23): a path is typed on a
 * screen and its length is the tester's, so nothing here may grow with it beyond one pass.
 */
public final class LookupPath {

    public static final int MAX_PARTS = 12;
    /** Far above any field name; it only keeps a pasted paragraph from being stored as a path. */
    static final int MAX_LENGTH = 400;
    private static final int MAX_INDEX_DIGITS = 6;

    public static final String RULE = "A path is names and [n] joined by dots, for example data.members[0].dob, at most " + MAX_PARTS
            + " parts.";

    /**
     * One part of the path.
     *
     * @param name  the name as written
     * @param index the position taken when the name holds a list, or -1
     */
    public record Step(String name, int index) {
    }

    private LookupPath() {
    }

    /** The parts of a path, or null when it breaks {@link #RULE}. A blank path has no parts and is not one. */
    public static List<Step> parse(String path) {
        if (Texts.blank(path)) {
            return null;
        }
        String text = path.strip();
        if (text.length() > MAX_LENGTH) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        int i = 0;
        int length = text.length();
        while (i <= length) {
            int nameStart = i;
            while (i < length && text.charAt(i) != '.' && text.charAt(i) != '[' && text.charAt(i) != ']') {
                char c = text.charAt(i);
                if (Character.isWhitespace(c) || c == '{' || c == '}' || c == '"') {
                    return null;
                }
                i++;
            }
            if (i == nameStart) {
                return null;
            }
            String name = text.substring(nameStart, i);
            int index = -1;
            if (i < length && text.charAt(i) == '[') {
                i++;
                int digitsStart = i;
                while (i < length && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                    i++;
                }
                int digits = i - digitsStart;
                if (digits == 0 || digits > MAX_INDEX_DIGITS || i >= length || text.charAt(i) != ']') {
                    return null;
                }
                index = Integer.parseInt(text.substring(digitsStart, i));
                i++;
            }
            steps.add(new Step(name, index));
            if (steps.size() > MAX_PARTS) {
                return null;
            }
            if (i == length) {
                return List.copyOf(steps);
            }
            if (text.charAt(i) != '.') {
                return null;
            }
            i++;
            if (i == length) {
                return null;
            }
        }
        return null;
    }

    /** True when the path keeps {@link #RULE}. */
    public static boolean valid(String path) {
        return parse(path) != null;
    }
}
