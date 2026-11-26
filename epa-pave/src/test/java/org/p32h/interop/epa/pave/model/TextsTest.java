package org.p32h.interop.epa.pave.model;

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

    /**
     * The loops that replaced the strips written as patterns give what those patterns gave, kept
     * here as text, on texts drawn from a fixed seed out of the characters that matter to them.
     */
    @Test
    void aStripFromTheEndGivesWhatThePatternItReplacedGave() {
        java.util.Random random = new java.util.Random(20261121L);
        String alphabet = "/-. \t\n\u000B\f\ra1!\u00a0\u0085\u2028\u2029";
        for (int round = 0; round < 100_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(12);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String t = text.toString();
            org.assertj.core.api.Assertions.assertThat(Texts.withoutTrailing(t, "/")).as(t).isEqualTo(t.replaceAll("/+$", ""));
            org.assertj.core.api.Assertions.assertThat(Texts.withoutTrailing(t, "." + Texts.WHITE_SPACE)).as(t)
                    .isEqualTo(t.replaceAll("[.\\s]+$", ""));
            org.assertj.core.api.Assertions.assertThat(Texts.withoutTrailing(Texts.withoutLeading(t, "-"), "-")).as(t)
                    .isEqualTo(t.replaceAll("^-+|-+$", ""));
        }
        org.assertj.core.api.Assertions.assertThat(Texts.withoutTrailing("/".repeat(5_000_000) + "!", "/")).hasSize(5_000_001);
        org.assertj.core.api.Assertions.assertThat(Texts.withoutTrailing(null, "/")).isNull();
    }
}
