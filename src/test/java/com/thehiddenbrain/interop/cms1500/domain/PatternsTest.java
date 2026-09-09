package com.thehiddenbrain.interop.cms1500.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {"1234567893", "9876543213", "1111111112", "1000000004"})
    void acceptsNpisWithCorrectCheckDigit(String npi) {
        assertThat(Patterns.validNpi(npi)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "1234567894", "123456789", "12345678931", "abcdefghij", ""})
    void rejectsNpisWithWrongCheckDigitOrShape(String npi) {
        assertThat(Patterns.validNpi(npi)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"S82.101A", "S82101A", "M54.5", "E11.9", "I10", "Z00.00", "A00"})
    void acceptsIcd10Codes(String code) {
        assertThat(Patterns.ICD10_CODE.matcher(code).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"250.00", "25000", "V70.0", "E812.1", "401.9", "401"})
    void acceptsIcd9Codes(String code) {
        assertThat(Patterns.ICD9_CODE.matcher(code).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "S8", "1A2", "S82.101AB9"})
    void rejectsMalformedIcd10Codes(String code) {
        assertThat(Patterns.ICD10_CODE.matcher(code).matches()).isFalse();
    }
}
