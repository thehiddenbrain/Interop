package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the procedure code list says about prior authorization for one code.
 *
 * <p>UNKNOWN means the list does not say. It is never shown as "no".
 */
public enum AuthRequirement {
    REQUIRED,
    NOT_REQUIRED,
    UNKNOWN;

    // The spellings are kept in pairs: beside each way of saying yes stands what a list that writes
    // it would write for no. "Prior Auth" goes with "No Prior Auth", "PA Required" with both
    // "PA Not Required" and "No PA Required", "Auth Needed" with "Auth Not Needed". A list would
    // otherwise have only one of its two answers read, and the half that was read would look like
    // the whole list. The "requires" forms and "none" stand alone: how a list would write their
    // opposite is not known, and a cell this reader does not know is served as not stated and
    // listed by the self-check, so nothing is lost by waiting to see one.
    private static final Set<String> YES = Set.of("required", "yes", "y", "true", "t", "1", "needed",
            "pa", "auth", "authorization", "prior auth", "prior authorization",
            "pa required", "auth required", "authorization required", "prior auth required", "prior authorization required",
            "pa needed", "auth needed",
            "requires pa", "requires auth", "requires prior auth", "requires prior authorization");
    private static final Set<String> NO = Set.of("not required", "no", "n", "false", "f", "0", "not needed", "none",
            "no pa", "no auth", "no authorization", "no prior auth", "no prior authorization",
            "pa not required", "auth not required", "authorization not required", "prior auth not required",
            "prior authorization not required",
            "no pa required", "no auth required", "no authorization required", "no prior auth required",
            "no prior authorization required",
            "pa not needed", "auth not needed");

    /**
     * The three spaces a spreadsheet writes that the usual white space test does not count: the
     * no-break space, the figure space and the narrow no-break space. A cell holding one of them
     * reads "Not Required" on screen, and has to be read as that here.
     */
    private static final Pattern SPREADSHEET_SPACE = Pattern.compile("[\\u00A0\\u2007\\u202F]");

    /** What stands between two words of a cell: white space, an underscore or a hyphen. */
    private static final Pattern BETWEEN_WORDS = Pattern.compile("[\\s_-]+");

    /**
     * Reads the flag from the text of a list cell or a JSON value: the common spellings of yes and
     * no, in any case, with white space, the no-break spaces a spreadsheet writes, underscores and
     * hyphens treated alike. Anything else, a blank included, is UNKNOWN. It is never a guess, and
     * never REQUIRED or NOT_REQUIRED by default.
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
        String key = key(text);
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
        String key = key(text);
        return YES.contains(key) || NO.contains(key) || key.equals("unknown");
    }

    /**
     * The form a cell is looked up in: without the white space around it, in lower case, with one
     * plain space where its words are apart. The spreadsheet's spaces are made plain ones first,
     * so that the trim takes them off the ends of a cell as it takes any other space. Nothing else
     * is taken off the ends: a cell that starts or ends with a hyphen is not one of the spellings.
     */
    private static String key(String text) {
        String spaced = SPREADSHEET_SPACE.matcher(text).replaceAll(" ");
        return BETWEEN_WORDS.matcher(spaced.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
