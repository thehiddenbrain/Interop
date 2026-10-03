package com.thehiddenbrain.interop.memberid.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MemberIdParserTest {

    private final MemberIdParser parser = new MemberIdParser(List.of(9));

    @ParameterizedTest(name = "[{index}] ''{0}'' -> {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "123456789          | 123456789   | THP_9",
            "  123456789        | 123456789   | THP_9",
            "12345678901        | 12345678901 | ID_11",
            "123456789   01     | 12345678901 | ID_11",
            "123456789 01       | 12345678901 | ID_11",
            "123456789-01       | 12345678901 | ID_11",
            "123456789_01       | 12345678901 | ID_11",
            "123456789.01       | 12345678901 | ID_11",
            "123456789/01       | 12345678901 | ID_11",
            "123456789–01       | 12345678901 | ID_11",
            "123-456-789 02     | 12345678902 | ID_11",
            "000123456 07       | 00012345607 | ID_11",
            "34567890101        | 34567890101 | ID_11",
            "HP456789012        | HP456789012 | HPHC",
            "hp456789012        | HP456789012 | HPHC",
            "HP-456789012       | HP456789012 | HPHC",
            "HP 4567 89012      | HP456789012 | HPHC",
    })
    void acceptedShapes(String input, String searched, InputShape shape) {
        ParsedMemberId parsed = parser.parse(input);
        assertThat(parsed.received()).isEqualTo(input);
        assertThat(parsed.searched()).isEqualTo(searched);
        assertThat(parsed.shape()).isEqualTo(shape);
    }

    @ParameterizedTest(name = "[{index}] ''{0}'' -> {1}")
    @CsvSource(delimiter = '|', value = {
            "''                 | MEMBER_ID_MISSING",
            "'   '              | MEMBER_ID_MISSING",
            "1234567890         | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "123456789-1        | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "12345              | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "123456789012       | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "12345678901234     | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "123456789 0A       | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "12345678O1         | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "HP12               | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "HP12345678901      | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "HP456789012 01     | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "W123456789         | MEMBER_ID_UNRECOGNIZED_SHAPE",
            "123456789*01       | MEMBER_ID_ILLEGAL_CHARACTERS",
            "１２３４５６７８９  | MEMBER_ID_ILLEGAL_CHARACTERS",
    })
    void rejectedShapes(String input, String code) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> {
                    InvalidRequestException ire = (InvalidRequestException) e;
                    assertThat(ire.details()).hasSize(1);
                    assertThat(ire.details().get(0).field()).isEqualTo("memberId");
                    assertThat(ire.details().get(0).code()).isEqualTo(code);
                    if (!input.isBlank()) {
                        assertThat(ire.details().get(0).message()).doesNotContain(input.strip());
                    }
                });
    }

    @org.junit.jupiter.api.Test
    void tooLongAndNull() {
        assertThatThrownBy(() -> parser.parse("1".repeat(41))).isInstanceOfSatisfying(InvalidRequestException.class,
                e -> assertThat(e.details().get(0).code()).isEqualTo("MEMBER_ID_TOO_LONG"));
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidRequestException.class);
    }

    @org.junit.jupiter.api.Test
    void hphcLengthsAreConfigurable() {
        MemberIdParser twoLengths = new MemberIdParser(List.of(8, 9));
        assertThat(twoLengths.parse("HP12345678").shape()).isEqualTo(InputShape.HPHC);
        assertThat(twoLengths.parse("HP123456789").shape()).isEqualTo(InputShape.HPHC);
    }
}
