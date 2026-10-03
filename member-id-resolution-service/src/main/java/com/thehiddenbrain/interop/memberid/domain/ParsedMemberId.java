package com.thehiddenbrain.interop.memberid.domain;

/**
 * @param received exactly what Onyx sent
 * @param searched the value sent to MMI (separators removed, upper-cased)
 * @param shape    which of the three shapes it is
 */
public record ParsedMemberId(String received, String searched, InputShape shape) {

    /** The first nine digits for THP shapes, null for HPHC. */
    public String thpCore() {
        return shape == InputShape.HPHC ? null : searched.substring(0, 9);
    }
}
