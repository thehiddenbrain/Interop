package org.point32health.memberid.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VendorFormatterTest {

    private final VendorFormatter formatter = new VendorFormatter();

    @ParameterizedTest(name = "[{index}] {1} of ''{0}'' -> ''{2}''")
    @CsvSource(delimiter = '|', value = {
            // MMI's documented shape: 9 digits, 3 spaces, 2-digit suffix
            "123456789   01 | COMPACT_11 | 12345678901",
            "123456789   01 | SPACED_14  | 123456789   01",
            "123456789   01 | AS_STORED  | 123456789   01",
            "123456789   01 | SPLIT      | 12345678901",
            "123456789 01   | SPACED_14  | 123456789   01",
            "' 123456789   01 ' | COMPACT_11 | 12345678901",
            // whatever MMI really puts between the two digit groups
            "'123456789\u00A0\u00A0\u00A001' | COMPACT_11 | 12345678901",
            "'123456789\u00A0\u00A0\u00A001' | SPACED_14  | 123456789   01",
            "'123456789\u200B\u200B\u200B01' | COMPACT_11 | 12345678901",
            "'123456789\t01'      | COMPACT_11 | 12345678901",
            "'123456789-01'       | COMPACT_11 | 12345678901",
            "'123456789 . 01'     | SPLIT      | 12345678901",
            "'123456789 01   '    | COMPACT_11 | 12345678901",
            "'  123456789  01'    | SPLIT      | 12345678901",
            "'123456789   01\u00A0' | SPACED_14 | 123456789   01",
            "'123456789   001'    | COMPACT_11 | 123456789001",
            // no separator between the digits: Public Plans, passed as stored even with padding
            "34567890101    | SPACED_14  | 34567890101",
            "34567890101    | COMPACT_11 | 34567890101",
            "'34567890101 '  | SPACED_14  | 34567890101",
            "'\u00A034567890101' | COMPACT_11 | 34567890101",
            // too few digits: HPHC, passed as stored
            "HP456789012    | COMPACT_11 | HP456789012",
            "HP456789012    | SPACED_14  | HP456789012",
            "HP-456789012   | COMPACT_11 | HP-456789012",
    })
    void formats(String stored, VendorIdFormat format, String expected) {
        assertThat(formatter.format(stored, format).value()).isEqualTo(expected);
    }

    @Test
    void blanksOfAnyKindAreTrimmedFromTheEnds() {
        assertThat(VendorFormatter.trim("\u00A0 123456789   01\t ")).isEqualTo("123456789   01");
        assertThat(VendorFormatter.trim(null)).isEmpty();
    }

    @Test
    void separatedIdSplitsIntoCoreAndSuffixWhateverTheSeparator() {
        for (String stored : java.util.List.of("123456789   01", "123456789\u00A0\u00A0\u00A001", "123456789\u200B01", "123456789-01", "123-456-789 01")) {
            FormattedMemberId f = formatter.format(stored, VendorIdFormat.SPLIT);
            assertThat(f.parts()).as(stored).isNotNull();
            assertThat(f.parts().memberId()).isEqualTo("123456789");
            assertThat(f.parts().suffix()).isEqualTo("01");
        }
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
