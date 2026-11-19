package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import java.util.Set;

/**
 * What the procedure code list says about prior authorization for one code.
 *
 * <p>UNKNOWN means the list does not say. It is never shown as "no".
 */
public enum AuthRequirement {
    REQUIRED,
    NOT_REQUIRED,
    UNKNOWN;

    private static final Set<String> YES = Set.of("required", "yes", "y", "true", "t", "1", "pa", "pa required", "auth required",
            "authorization required", "prior auth required", "prior authorization required", "needed", "auth needed");
    private static final Set<String> NO = Set.of("not required", "no", "n", "false", "f", "0", "no pa", "pa not required", "no auth",
            "auth not required", "authorization not required", "no prior auth", "prior auth not required",
            "prior authorization not required", "not needed", "none");

    /**
     * Reads the flag from the text of a list cell or a JSON value: the common spellings of yes and
     * no, in any case, with spaces, underscores and hyphens treated alike. Anything else, a blank
     * included, is UNKNOWN. It is never a guess, and never REQUIRED or NOT_REQUIRED by default.
     *
     * <p>This is the one reader for both the CSV and the JSON form of the list. It is declared as
     * the JSON reader so that a number is read by its meaning, 1 for yes and 0 for no, and never as
     * a position in this list, where 1 would have meant "not required".
     */
    @JsonCreator
    public static AuthRequirement parse(String text) {
        if (Texts.blank(text)) {
            return UNKNOWN;
        }
        String key = text.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
        if (YES.contains(key)) {
            return REQUIRED;
        }
        if (NO.contains(key)) {
            return NOT_REQUIRED;
        }
        return UNKNOWN;
    }

    /** True when the text is one of the spellings {@link #parse} reads as a yes, a no or an explicit "unknown". */
    public static boolean recognises(String text) {
        if (Texts.blank(text)) {
            return false;
        }
        String key = text.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
        return YES.contains(key) || NO.contains(key) || key.equals("unknown");
    }
}
