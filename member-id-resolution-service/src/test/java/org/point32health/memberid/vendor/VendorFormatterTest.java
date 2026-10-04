package org.point32health.memberid.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VendorFormatterTest {

    private final VendorFormatter formatter = new VendorFormatter();

    @ParameterizedTest(name = "[{index}] {1} of ''{0}'' -> ''{2}''")
    @CsvSource(delimiter = '|', value = {
            "123456789   01 | COMPACT_11 | 12345678901",
            "123456789   01 | SPACED_14  | 123456789   01",
            "123456789   01 | AS_STORED  | 123456789   01",
            "123456789   01 | SPLIT      | 12345678901",
            "123456789 01   | SPACED_14  | 123456789   01",
            "34567890101    | SPACED_14  | 34567890101",
            "34567890101    | COMPACT_11 | 34567890101",
            "HP456789012    | COMPACT_11 | HP456789012",
            "HP456789012    | SPACED_14  | HP456789012",
            "' 123456789   01 ' | COMPACT_11 | 12345678901",
            "'123456789\u00A0\u00A0\u00A001' | COMPACT_11 | 12345678901",
            "'123456789\u00A0\u00A0\u00A001' | SPACED_14  | 123456789   01",
            "'123456789\t01'      | COMPACT_11 | 12345678901",
            "'123456789 01   '    | COMPACT_11 | 12345678901",
            "'  123456789  01'    | SPLIT      | 12345678901",
            "'123456789   01\u00A0' | SPACED_14 | 123456789   01",
    })
    void formats(String stored, VendorIdFormat format, String expected) {
        assertThat(formatter.format(stored, format).value()).isEqualTo(expected);
    }

    @Test
    void whitespaceOfAnyKindIsNormalised() {
        assertThat(VendorFormatter.normalizeWhitespace("\u00A0123456789\u00A0\u00A0\u00A001\t")).isEqualTo("123456789   01");
        assertThat(VendorFormatter.normalizeWhitespace(null)).isEmpty();
    }

    @Test
    void describeNamesTheShapeWithoutTheDigits() {
        assertThat(VendorFormatter.describe("123456789\u00A0\u00A001")).isEqualTo("#########[U+00A0][U+00A0]## (length 13)");
        assertThat(VendorFormatter.describe("HP-456789012")).isEqualTo("aa[U+002D]######### (length 12)");
    }

    @Test
    void splitCarriesBothParts() {
        FormattedMemberId f = formatter.format("123456789   01", VendorIdFormat.SPLIT);
        assertThat(f.parts().memberId()).isEqualTo("123456789");
        assertThat(f.parts().suffix()).isEqualTo("01");
        assertThat(formatter.format("34567890101", VendorIdFormat.SPLIT).parts()).isNull();
    }
}
