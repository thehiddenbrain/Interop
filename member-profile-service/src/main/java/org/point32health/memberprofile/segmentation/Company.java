package org.point32health.memberprofile.segmentation;

import java.util.Optional;

/** The two companies whose members League serves; {@code memberTypeCode} in the MemberDomain payload. */
public enum Company {
    HPHC, THP;

    /** Case-insensitive, trimmed; empty for anything else so a new or mistyped code is a 422, not a silent "no features". */
    public static Optional<Company> fromMemberTypeCode(String memberTypeCode) {
        if (memberTypeCode == null) return Optional.empty();
        String code = memberTypeCode.trim().toUpperCase(java.util.Locale.ROOT);
        for (Company c : values()) {
            if (c.name().equals(code)) return Optional.of(c);
        }
        return Optional.empty();
    }
}
