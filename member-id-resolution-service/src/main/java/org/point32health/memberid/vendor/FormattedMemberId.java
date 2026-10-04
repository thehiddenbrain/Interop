package org.point32health.memberid.vendor;

/**
 * @param value the single string most vendors take
 * @param parts the two fields for {@link VendorIdFormat#SPLIT}, else null
 */
public record FormattedMemberId(String value, Parts parts) {

    public record Parts(String memberId, String suffix) {
    }

    public String describe() {
        return parts == null ? "'" + value + "'" : "'" + parts.memberId() + "' + '" + parts.suffix() + "' (joined '" + value + "')";
    }
}
