package org.point32health.memberid.vendor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the id as stored in MMI into the vendor's format.
 *
 * <p>The TMP / SCO id is stored as a 9-digit core, a separator (MMI: three spaces) and a 2-digit suffix. Whatever
 * MMI actually puts between the two digit groups (plain or non-breaking spaces, tabs, zero-width characters, a
 * hyphen, any padding), the rule is the same: trim the ends; if the value has at least ten digits and at least one
 * non-digit character between its digits, the first nine digits are the core and the remaining digits are the
 * suffix. A value with no separator between its digits (a Public Plans id, {@code 34567890101}) or with fewer
 * digits (an HPHC id, {@code HP456789012}) is passed on exactly as stored, whatever the vendor's format.
 *
 * <p>When a value that is not plain letters and digits still cannot be reshaped, a warning names its shape (digits
 * as #, letters as a, every other character by code point, never the digits themselves).
 */
public final class VendorFormatter {

    private static final Logger log = LoggerFactory.getLogger(VendorFormatter.class);

    public FormattedMemberId format(String storedMemberId, VendorIdFormat format) {
        String stored = trim(storedMemberId);
        Split split = split(stored);
        if (split == null) {
            if (format != VendorIdFormat.AS_STORED && !stored.chars().allMatch(Character::isLetterOrDigit)) {
                log.warn("marker=STORED_ID_NOT_RESHAPED format={} shape={}: not a core + separator + suffix id; passed on as stored",
                        format, describe(storedMemberId));
            }
            return new FormattedMemberId(stored, null);
        }
        return switch (format) {
            case AS_STORED -> new FormattedMemberId(stored, null);
            case COMPACT_11 -> new FormattedMemberId(split.core() + split.suffix(), null);
            case SPACED_14 -> new FormattedMemberId(split.core() + "   " + split.suffix(), null);
            case SPLIT -> new FormattedMemberId(split.core() + split.suffix(), new FormattedMemberId.Parts(split.core(), split.suffix()));
        };
    }

    private record Split(String core, String suffix) {
    }

    /** The core and suffix of a separated id, or null when the value is not one. */
    private static Split split(String stored) {
        StringBuilder digits = new StringBuilder();
        boolean separatorInside = false;
        boolean pendingSeparator = false;
        for (int i = 0; i < stored.length(); ) {
            int cp = stored.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isDigit(cp)) {
                if (pendingSeparator && digits.length() > 0) {
                    separatorInside = true;
                }
                pendingSeparator = false;
                digits.append(Character.digit(cp, 10));
            } else {
                pendingSeparator = true;
            }
        }
        if (!separatorInside || digits.length() < 10) {
            return null;
        }
        return new Split(digits.substring(0, 9), digits.substring(9));
    }

    /** Trims every kind of blank (plain, non-breaking, tab, ...) from both ends. */
    static String trim(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isBlank(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isBlank(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    private static boolean isBlank(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /** The shape of a stored id for the log: digits #, letters a, anything else as [U+XXXX]. Never the digits. */
    static String describe(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        s.codePoints().limit(40).forEach(cp -> {
            if (Character.isDigit(cp)) {
                sb.append('#');
            } else if (Character.isLetter(cp)) {
                sb.append('a');
            } else {
                sb.append(String.format("[U+%04X]", cp));
            }
        });
        return sb.toString() + (s.codePointCount(0, s.length()) > 40 ? "..." : "") + " (length " + s.length() + ")";
    }
}
