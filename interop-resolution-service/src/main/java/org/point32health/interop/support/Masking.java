package org.point32health.interop.support;

/**
 * Member ids are PHI. Every log line and every error message goes through here: only the last four
 * digits of the core and the suffix survive ({@code *****6789 01}, {@code HP*****6789}).
 */
public final class Masking {

    private Masking() {
    }

    /** The shape of an arbitrary upstream value for diagnostics: digits become #, letters become a, nothing else survives. */
    public static String shape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        value.codePoints().limit(24).forEach(cp -> sb.append(Character.isDigit(cp) ? '#' : Character.isLetter(cp) ? 'a' : (char) cp));
        return sb.toString() + (value.length() > 24 ? "..." : "");
    }

    public static String memberId(String value) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return "";
        }
        String prefix = "";
        String body = s;
        if (s.length() > 2 && s.substring(0, 2).equalsIgnoreCase("HP")) {
            prefix = s.substring(0, 2).toUpperCase();
            body = s.substring(2).strip();
        }
        // split off a trailing 2-digit suffix separated by whitespace, if any
        String suffix = "";
        int ws = body.lastIndexOf(' ');
        if (ws > 0 && body.length() - ws - 1 == 2 && body.substring(ws + 1).chars().allMatch(Character::isDigit)) {
            suffix = " " + body.substring(ws + 1);
            body = body.substring(0, ws).strip();
        }
        if (body.length() <= 4) {
            return prefix + "*".repeat(body.length()) + suffix;
        }
        return prefix + "*".repeat(body.length() - 4) + body.substring(body.length() - 4) + suffix;
    }
}
