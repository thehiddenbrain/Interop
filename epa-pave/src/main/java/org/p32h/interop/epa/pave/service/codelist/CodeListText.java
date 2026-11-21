package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;
import java.util.Locale;

/**
 * How the text of a code list cell or header is read, compared and shown. Kept in one place so
 * that a line of business in the list and the line a member has are compared by one rule, and a
 * header in the file and a candidate name by one rule. The two rules are not the same: a header
 * may lose more than a line of business may.
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
     * The form two lines of business are compared in: lower case, with spaces, underscores,
     * hyphens and dots left out. "Medicare Advantage", "medicare_advantage" and
     * "Medicare-Advantage." all become "medicareadvantage". A space or a hyphen of any kind counts,
     * since a cell typed in a spreadsheet can hold a no-break space or a dash the keyboard does
     * not have. Nothing else is left out: a slash, a question mark or a bracket still tells two
     * lines apart, because two lines of business are two lists with two answers.
     */
    static String key(String text) {
        return compared(text, false);
    }

    /**
     * The form a header and a candidate name are compared in: as {@link #key}, with a slash, a
     * question mark and a colon left out as well. "PA Required?", "pa_required:" and "PA-Required"
     * all become "parequired", and "CPT/HCPCS Code" becomes "cpthcpcscode". A header is titled by a
     * person, and those three are how a person titles a column. A bracket is kept here: see
     * {@link #headerBase}.
     */
    static String headerKey(String text) {
        return compared(text, true);
    }

    /**
     * A header's compared form without a bracketed part at its end: "Auth Required (Y/N)" becomes
     * "authrequired", as does "Auth Required [Y/N] (2026)". Round and square brackets count, and
     * only at the end. A header with no such ending, or with nothing before it, comes back as
     * {@link #headerKey} gives it.
     *
     * <p>This is the weaker of the two forms and is used only where the stronger one found
     * nothing. What stands in the brackets may say which values a column takes, and it may be the
     * one thing that tells two columns apart.
     */
    static String headerBase(String text) {
        String written = headerKey(text);
        String shortened = withoutBracketedEnding(written);
        return shortened.isEmpty() ? written : shortened;
    }

    /**
     * The text without the groups in round or square brackets that end it, a group being an
     * opening bracket, no bracket, and a closing one. What the pattern
     * {@code (?:[(\[][^()\[\]]*[)\]])+$} removed with replaceFirst, read from the end in one
     * pass: that pattern called itself once per group, and tried again from every bracket, so a
     * header of many thousand brackets overflowed the stack or ran for minutes. Like the pattern's
     * {@code $}, the end may also be just before one line terminator that ends the text.
     */
    static String withoutBracketedEnding(String text) {
        int length = text.length();
        int end;
        if (length > 0 && (text.charAt(length - 1) == ')' || text.charAt(length - 1) == ']')) {
            end = length;
        } else if (text.endsWith("\r\n")) {
            end = length - 2;
        } else if (length > 0 && "\n\r\u0085\u2028\u2029".indexOf(text.charAt(length - 1)) >= 0) {
            end = length - 1;
        } else {
            return text;
        }
        int start = end;
        while (start > 0 && (text.charAt(start - 1) == ')' || text.charAt(start - 1) == ']')) {
            int opening = start - 2;
            while (opening >= 0 && "()[]".indexOf(text.charAt(opening)) < 0) {
                opening--;
            }
            if (opening < 0 || (text.charAt(opening) != '(' && text.charAt(opening) != '[')) {
                break;
            }
            start = opening;
        }
        return start == end ? text : text.substring(0, start) + text.substring(end);
    }

    private static String compared(String text, boolean header) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            boolean left = invisible(codePoint) || codePoint == '_' || codePoint == '.' || codePoint == '-'
                    || Character.getType(codePoint) == Character.DASH_PUNCTUATION
                    || (header && (codePoint == '/' || codePoint == '?' || codePoint == ':'));
            if (!left) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    /** True when the text holds a space, or a character a reader could not see, anywhere in it. */
    static boolean holdsSpaceOrUnseen(String text) {
        return text != null && text.codePoints().anyMatch(codePoint -> invisible(codePoint) || Character.isISOControl(codePoint));
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
