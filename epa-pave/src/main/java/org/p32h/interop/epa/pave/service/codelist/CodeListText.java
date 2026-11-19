package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;
import java.util.Locale;

/**
 * How the text of a code list cell or header is read, compared and shown. Kept in one place so
 * that a header, a line of business and a requested line are all compared by the same rule.
 */
final class CodeListText {

    /** The most values or line numbers one warning lists before it says how many more there are. */
    static final int LISTED = 10;

    private static final int SHOWN_LENGTH = 60;

    private CodeListText() {
    }

    /**
     * The cell without the white space around it, or null when nothing is left.
     *
     * <p>White space here includes the kinds a spreadsheet cell picks up from a pasted web page
     * and that nobody can see: the no-break space, the zero width space, a stray byte order mark.
     * The usual trim leaves those in place, and a code followed by one of them would then match no
     * code a tester types. Nothing inside the value is touched.
     */
    static String clean(String cell) {
        if (cell == null) {
            return null;
        }
        int start = 0;
        int end = cell.length();
        while (start < end) {
            int codePoint = cell.codePointAt(start);
            if (!invisible(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = cell.codePointBefore(end);
            if (!invisible(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return start == end ? null : cell.substring(start, end);
    }

    /**
     * The form two names are compared in: lower case, with spaces, underscores, hyphens and dots
     * left out. "PA Required", "pa_required" and "PA-Required." all become "parequired". A space
     * or a hyphen of any kind counts, since a header typed in a spreadsheet can hold a no-break
     * space or a dash the keyboard does not have. Nothing else is left out: a question mark or a
     * slash still tells two names apart.
     */
    static String key(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            boolean left = invisible(codePoint) || codePoint == '_' || codePoint == '.' || codePoint == '-'
                    || Character.getType(codePoint) == Character.DASH_PUNCTUATION;
            if (!left) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private static boolean invisible(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.getType(codePoint) == Character.FORMAT;
    }

    /**
     * A value as a warning shows it: in double quotes, cut when long, and with every character a
     * reader could not see written out as its code point. A cell that reads "Yes" on screen and
     * is not read as a yes is otherwise a puzzle with no way in.
     */
    static String show(String value) {
        return quote(value, SHOWN_LENGTH);
    }

    /** As {@link #show}, with nothing cut: for a setting, which has to be found again in a configuration file. */
    static String quote(String value) {
        return quote(value, Integer.MAX_VALUE);
    }

    private static String quote(String value, int length) {
        if (value == null) {
            return "(nothing)";
        }
        StringBuilder out = new StringBuilder("\"");
        int shown = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (shown == length) {
                out.append("...");
                break;
            }
            shown++;
            boolean plain = codePoint == ' ' || !(invisible(codePoint) || Character.isISOControl(codePoint));
            if (plain) {
                out.appendCodePoint(codePoint);
            } else {
                out.append(String.format(Locale.ROOT, "<U+%04X>", codePoint));
            }
        }
        return out.append('"').toString();
    }

    /** "3", "3 and 9", "3, 9 and 12", and after ten of them "... and 40 more". */
    static String numbers(List<Integer> values) {
        return join(values.stream().map(String::valueOf).toList());
    }

    /** A list as a sentence would write it, cut after ten entries with a count of the rest. */
    static String join(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() > LISTED) {
            return String.join(", ", values.subList(0, LISTED)) + " and " + (values.size() - LISTED) + " more";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + " and " + values.get(values.size() - 1);
    }

    /** "1 row has" or "3 rows have", so a warning reads as a sentence whatever the count. */
    static String count(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
