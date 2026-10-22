package org.p32h.interop.domain;

/**
 * The member's name as MMI stores it, kept only to be returned in each {@code memberId.forVendors} entry. Never
 * logged: {@link #toString()} hides the values. Absent (null) when the record has neither a first nor a last name.
 *
 * @param first  the first name, or null
 * @param middle the middle name, or null
 * @param last   the last name, or null
 */
public record MemberName(String first, String middle, String last) {

    /** Each part trimmed, a blank part null; null when there is neither a first nor a last name. */
    public static MemberName of(String first, String middle, String last) {
        String f = clean(first);
        String l = clean(last);
        return f == null && l == null ? null : new MemberName(f, clean(middle), l);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Override
    public String toString() {
        return "MemberName[hidden]";
    }
}
