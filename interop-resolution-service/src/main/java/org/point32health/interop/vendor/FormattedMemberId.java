package org.point32health.interop.vendor;

/** @param value the stored id in the vendor's format: the one string that goes into the vendor's payload */
public record FormattedMemberId(String value) {

    public String describe() {
        return "'" + value + "'";
    }
}
