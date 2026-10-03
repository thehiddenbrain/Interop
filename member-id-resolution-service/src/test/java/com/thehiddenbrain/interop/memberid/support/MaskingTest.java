package com.thehiddenbrain.interop.memberid.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void masksAllButTheLastFourDigitsAndKeepsTheSuffix() {
        assertThat(Masking.memberId("123456789   01")).isEqualTo("*****6789 01");
        assertThat(Masking.memberId("12345678901")).isEqualTo("*******8901");
        assertThat(Masking.memberId("123456789")).isEqualTo("*****6789");
        assertThat(Masking.memberId("HP456789012")).isEqualTo("HP*****9012");
        assertThat(Masking.memberId("hp-456789012")).isEqualTo("HP******9012");
        assertThat(Masking.memberId("12")).isEqualTo("**");
        assertThat(Masking.memberId("")).isEqualTo("");
        assertThat(Masking.memberId(null)).isNull();
    }
}
