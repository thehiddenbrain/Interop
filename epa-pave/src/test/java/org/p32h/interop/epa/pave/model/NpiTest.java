package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NpiTest {

    @Test
    void acceptsTheKnownValidExample() {
        assertThat(Npi.isValid("1234567893")).isTrue();
    }

    @Test
    void theSentinelNpiForProviderNotFoundIsItselfValid() {
        // The sentinel has to pass the check, or the form would reject it before the mock is asked.
        assertThat(Npi.isValid("9999999995")).isTrue();
    }

    @Test
    void rejectsAWrongCheckDigit() {
        for (char last = '0'; last <= '9'; last++) {
            String candidate = "123456789" + last;
            assertThat(Npi.isValid(candidate)).as(candidate).isEqualTo(last == '3');
        }
    }

    @Test
    void rejectsAnythingThatIsNotTenDigits() {
        assertThat(Npi.isValid(null)).isFalse();
        assertThat(Npi.isValid("")).isFalse();
        assertThat(Npi.isValid("123456789")).isFalse();
        assertThat(Npi.isValid("12345678931")).isFalse();
        assertThat(Npi.isValid("12345 67893")).isFalse();
        assertThat(Npi.isValid("123456789X")).isFalse();
        assertThat(Npi.isValid(" 1234567893")).isFalse();
    }

    @Test
    void generatesWhatItValidates() {
        for (int base = 100000000; base < 100000400; base += 7) {
            String npi = Npi.withCheckDigit(String.valueOf(base));
            assertThat(Npi.isValid(npi)).as(npi).isTrue();
        }
        assertThat(Npi.withCheckDigit("123456789")).isEqualTo("1234567893");
    }

    @Test
    void aSingleTransposedDigitIsCaught() {
        // Luhn catches every single-digit error, which is the typing mistake the check exists for.
        String valid = "1234567893";
        for (int position = 0; position < 9; position++) {
            char original = valid.charAt(position);
            char changed = original == '9' ? '0' : (char) (original + 1);
            String mistyped = valid.substring(0, position) + changed + valid.substring(position + 1);
            assertThat(Npi.isValid(mistyped)).as(mistyped).isFalse();
        }
    }

    @Test
    void checkDigitRefusesAMalformedBase() {
        assertThatThrownBy(() -> Npi.checkDigit("12345678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Npi.checkDigit("12345678A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Npi.checkDigit(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
