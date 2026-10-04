package org.point32health.memberid.vendor;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the id as stored in MMI into the vendor's format.
 *
 * <p>A TMP / SCO id is stored as a core of letters and digits (9 characters, for TMP a letter and 8 digits such as
 * {@code S12345678}), a separator (MMI: three spaces; any blanks or punctuation are accepted) and a short numeric
 * suffix ({@code 01}). Only such a value is reshaped, and nothing in it is ever dropped or changed: the core and the
 * suffix are copied character for character. A value with a single run of letters and digits (a Public Plans id
 * {@code 34567890101}, an HPHC id {@code HP456789012}) or any other shape is passed on exactly as stored, whatever
 * the vendor's format.
 *
 * <p>When a value that is not plain letters and digits still cannot be reshaped, a warning names its shape (digits
 * as #, letters as a, every other character by code point, never the characters themselves).
 */
public final class VendorFormatter {

    private static final Logger log = LoggerFactory.getLogger(VendorFormatter.class);

    private static final int MIN_CORE_LENGTH = 5;
    private static final int MAX_SUFFIX_LENGTH = 3;

    public FormattedMemberId format(String storedMemberId, VendorIdFormat format) {
        String stored = trim(storedMemberId);
        Split split = split(stored);
        if (split == null) {
            if (format != VendorIdFormat.AS_STORED && !stored.chars().allMatch(Character::isLetterOrDigit)) {
                log.warn("marker=STORED_ID_NOT_RESHAPED format={} shape={}: not a core + separator + numeric suffix; passed on as stored",
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

    /** Core and suffix of "letters/digits, separator, 1-3 digits"; null for anything else. Characters are never altered. */
    private static Split split(String stored) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < stored.length(); ) {
            int cp = stored.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp);
            } else if (current.length() > 0) {
                groups.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            groups.add(current.toString());
        }
        if (groups.size() != 2) {
            return null;
        }
        String core = groups.get(0);
        String suffix = groups.get(1);
        boolean numericSuffix = suffix.chars().allMatch(Character::isDigit);
        if (core.length() < MIN_CORE_LENGTH || suffix.length() > MAX_SUFFIX_LENGTH || !numericSuffix) {
            return null;
        }
        return new Split(core, suffix);
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

    /** The shape of a stored id for the log: digits #, letters a, anything else as [U+XXXX]. Never the characters. */
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
