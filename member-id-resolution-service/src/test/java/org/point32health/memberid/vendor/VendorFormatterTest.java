package org.point32health.memberid.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VendorFormatterTest {

    private final VendorFormatter formatter = new VendorFormatter();

    @ParameterizedTest(name = "[{index}] {1} of ''{0}'' -> ''{2}''")
    @CsvSource(delimiter = '|', value = {
            // the real TMP shape: a letter and 8 digits, 3 spaces, the 2-digit suffix. The S is kept.
            "S12345678   01 | COMPACT_11 | S1234567801",
            "S12345678   01 | SPACED_14  | S12345678   01",
            "S12345678   01 | AS_STORED  | S12345678   01",
            "S12345678   01 | SPLIT      | S1234567801",
            "'S12345678\u00A0\u00A0\u00A001' | COMPACT_11 | S1234567801",
            "'S12345678\u200B\u200B\u200B01' | SPACED_14  | S12345678   01",
            "'s12345678 01   '  | COMPACT_11 | s1234567801",
            // an all-digit core works the same way
            "123456789   01 | COMPACT_11 | 12345678901",
            "123456789   01 | SPACED_14  | 123456789   01",
            "123456789 01   | SPACED_14  | 123456789   01",
            "' 123456789   01 ' | COMPACT_11 | 12345678901",
            "'123456789\t01'    | COMPACT_11 | 12345678901",
            "'123456789-01'     | COMPACT_11 | 12345678901",
            "'123456789 . 01'   | SPLIT      | 12345678901",
            "'123456789   001'  | COMPACT_11 | 123456789001",
            // one run of letters and digits: Public Plans and HPHC, passed as stored even with padding
            "34567890101    | SPACED_14  | 34567890101",
            "34567890101    | COMPACT_11 | 34567890101",
            "'34567890101 '  | SPACED_14  | 34567890101",
            "'\u00A034567890101' | COMPACT_11 | 34567890101",
            "HP456789012    | COMPACT_11 | HP456789012",
            "HP456789012    | SPACED_14  | HP456789012",
            // two groups that are not core + short numeric suffix: passed as stored
            "HP-456789012   | COMPACT_11 | HP-456789012",
            "'123456789 AB'  | COMPACT_11 | 123456789 AB",
            "'123-456-789 01' | COMPACT_11 | 123-456-789 01",
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
        for (String stored : java.util.List.of("S12345678   01", "S12345678\u00A0\u00A0\u00A001", "S12345678\u200B01", "S12345678-01")) {
            FormattedMemberId f = formatter.format(stored, VendorIdFormat.SPLIT);
            assertThat(f.parts()).as(stored).isNotNull();
            assertThat(f.parts().memberId()).as("the S is part of the core and is kept").isEqualTo("S12345678");
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
        assertThat(formatter.format("S12345678   01", VendorIdFormat.SPLIT).parts().memberId()).isEqualTo("S12345678");
        assertThat(f.parts().suffix()).isEqualTo("01");
        assertThat(formatter.format("34567890101", VendorIdFormat.SPLIT).parts()).isNull();
    }
}
