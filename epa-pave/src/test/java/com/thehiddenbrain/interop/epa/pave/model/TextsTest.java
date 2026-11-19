package com.thehiddenbrain.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void presentMeansNeitherNullNorBlank() {
        assertThat(Texts.present(null)).isFalse();
        assertThat(Texts.present("")).isFalse();
        assertThat(Texts.present("   ")).isFalse();
        assertThat(Texts.present("\t\n")).isFalse();
        assertThat(Texts.present(" a ")).isTrue();
        assertThat(Texts.blank(" ")).isTrue();
        assertThat(Texts.blank("x")).isFalse();
    }

    @Test
    void trimToNullNeverReturnsAnEmptyString() {
        assertThat(Texts.trimToNull(null)).isNull();
        assertThat(Texts.trimToNull("  ")).isNull();
        assertThat(Texts.trimToNull("  a b ")).isEqualTo("a b");
    }

    @Test
    void firstPresentSkipsNullAndBlank() {
        assertThat(Texts.firstPresent(null, " ", " second ", "third")).isEqualTo("second");
        assertThat(Texts.firstPresent(null, "")).isNull();
        assertThat(Texts.firstPresent()).isNull();
        assertThat(Texts.firstPresent((String[]) null)).isNull();
    }

    @Test
    void orElseTreatsBlankAsMissing() {
        assertThat(Texts.orElse(" ", "fallback")).isEqualTo("fallback");
        assertThat(Texts.orElse("value", "fallback")).isEqualTo("value");
    }
}
