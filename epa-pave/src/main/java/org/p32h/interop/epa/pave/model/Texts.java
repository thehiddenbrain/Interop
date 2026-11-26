package org.p32h.interop.epa.pave.model;

/**
 * String guards for values that can be absent, null or blank.
 *
 * <p>A guard that checks only {@code != null} on a field that can arrive blank has produced both the
 * literal text "null" on screen and a NullPointerException further down in earlier builds of this
 * tool. Every such check goes through {@link #present(String)}.
 */
public final class Texts {

    private Texts() {
    }

    /** True when the value is neither null nor blank. */
    public static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** True when the value is null or blank. */
    public static boolean blank(String value) {
        return !present(value);
    }

    /** The stripped value, or null when it is not present. Never returns an empty string. */
    public static String trimToNull(String value) {
        return present(value) ? value.strip() : null;
    }

    /** The value when present, otherwise the fallback. */
    public static String orElse(String value, String fallback) {
        return present(value) ? value : fallback;
    }

    /** The first present value, stripped, or null when none is present. */
    public static String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (present(value)) {
                return value.strip();
            }
        }
        return null;
    }

    /** The characters a regular expression's \\s stands for: space, tab, line feed, vertical tab, form feed, carriage return. */
    public static final String WHITE_SPACE = " \t\n\u000B\f\r";

    /**
     * The text without the run of the given characters at its end, read from the end: what
     * {@code text.replaceAll("[" + characters + "]+$", "")} gives, without its cost. Such a pattern is
     * tried again from every character of a long run that something else follows, which takes time in
     * the square of the run's length.
     *
     * <p>As in the pattern, the end is also the place before a line terminator that ends the text: a
     * run there is removed and the terminator kept, unless the terminator is one of the characters.
     */
    public static String withoutTrailing(String text, String characters) {
        if (text == null) {
            return null;
        }
        int end = runStart(text, text.length(), characters);
        if (end < text.length()) {
            return text.substring(0, end);
        }
        int terminator = finalTerminator(text);
        if (terminator < 0) {
            return text;
        }
        return text.substring(0, runStart(text, terminator, characters)) + text.substring(terminator);
    }

    /** Where the run of the characters that ends at a position starts. */
    private static int runStart(String text, int end, String characters) {
        while (end > 0 && characters.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return end;
    }

    /**
     * Where the line terminator that ends the text starts, as a pattern's {@code $} reads one:
     * {@code \r\n}, or one of {@code \n}, {@code \r}, U+0085, U+2028 and U+2029; -1 when there is none.
     */
    private static int finalTerminator(String text) {
        int length = text.length();
        if (length >= 2 && text.charAt(length - 2) == '\r' && text.charAt(length - 1) == '\n') {
            return length - 2;
        }
        if (length >= 1 && "\n\r\u0085\u2028\u2029".indexOf(text.charAt(length - 1)) >= 0) {
            return length - 1;
        }
        return -1;
    }

    /** The text without the run of the given characters at its start. */
    public static String withoutLeading(String text, String characters) {
        if (text == null) {
            return null;
        }
        int start = 0;
        while (start < text.length() && characters.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        return text.substring(start);
    }
}
