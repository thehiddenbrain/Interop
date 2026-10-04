package org.point32health.memberid.vendor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the id as stored in MMI into the vendor's format. Only a THP id stored with spaces between the
 * 9 digits and the suffix is reshaped; everything else is passed through verbatim, so a Public Plans id
 * ({@code 34567890101}) or an HPHC id ({@code HP456789012}) never changes.
 */
public final class VendorFormatter {

    /** 9 digits, at least one space, 2 digits: the stored TMP / SCO shape. A continuous 11-digit id does not match. */
    private static final Pattern THP_SPACED = Pattern.compile("^(\\d{9})\\s+(\\d{2})$");

    public FormattedMemberId format(String storedMemberId, VendorIdFormat format) {
        String stored = storedMemberId == null ? "" : storedMemberId.strip();
        Matcher m = THP_SPACED.matcher(stored);
        if (!m.matches()) {
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
}
