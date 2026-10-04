package org.point32health.memberid.vendor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the id as stored in MMI into the vendor's format. Only a THP id stored as digits, spaces, digits
 * (TMP / SCO: normally 9 digits, 3 spaces, a 2-digit suffix) is reshaped; everything else is passed through
 * verbatim, so a Public Plans id ({@code 34567890101}) or an HPHC id ({@code HP456789012}) never changes.
 *
 * <p>The stored value is taken as MMI sends it: any kind of whitespace between the two digit groups (plain,
 * non-breaking, tab, several or one) and around the value is accepted, so a fixed-width or oddly padded field
 * still reshapes. When a value that is not plain letters and digits cannot be reshaped, a warning names its
 * shape (digits as #, letters as a, every other character by code point, never the digits themselves).
 */
public final class VendorFormatter {

    private static final Logger log = LoggerFactory.getLogger(VendorFormatter.class);

    /** Digits, one or more spaces, digits, after whitespace normalisation. A continuous id does not match. */
    private static final Pattern DIGITS_SPACE_DIGITS = Pattern.compile("^(\\d+) +(\\d+)$");

    public FormattedMemberId format(String storedMemberId, VendorIdFormat format) {
        String stored = normalizeWhitespace(storedMemberId);
        Matcher m = DIGITS_SPACE_DIGITS.matcher(stored);
        if (!m.matches()) {
            if (format != VendorIdFormat.AS_STORED && !stored.chars().allMatch(Character::isLetterOrDigit)) {
                log.warn("marker=STORED_ID_NOT_RESHAPED format={} shape={}: the stored id is not digits + spaces + digits; passed on as stored",
                        format, describe(storedMemberId));
            }
            return new FormattedMemberId(stored, null);
        }
        String core = m.group(1);
        String suffix = m.group(2);
        return switch (format) {
            case AS_STORED -> new FormattedMemberId(stored, null);
            case COMPACT_11 -> new FormattedMemberId(core + suffix, null);
            case SPACED_14 -> new FormattedMemberId(core + "   " + suffix, null);
            case SPLIT -> new FormattedMemberId(core + suffix, new FormattedMemberId.Parts(core, suffix));
        };
    }

    /** Every kind of whitespace (plain, non-breaking, tab, ...) becomes one plain space; the ends are trimmed. */
    static String normalizeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(Character.isWhitespace(cp) || Character.isSpaceChar(cp) ? ' ' : cp));
        return sb.toString().strip();
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
