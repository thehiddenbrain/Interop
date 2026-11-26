package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemberIdsTest {

    @Test
    void aSpacedIdIsSentWithoutSpacesAndBothFormsAreKept() {
        // Nine characters, three spaces, a two digit suffix: the spaced format in the S123456789 placeholder style.
        MemberIdForms forms = MemberIds.forms("S12345678   01");
        assertThat(forms.typed()).isEqualTo("S12345678   01");
        assertThat(forms.sent()).isEqualTo("S1234567801");
        assertThat(forms.changed()).isTrue();
    }

    @Test
    void anUnspacedIdIsSentAsTyped() {
        MemberIdForms forms = MemberIds.forms("S123456789");
        assertThat(forms.sent()).isEqualTo("S123456789");
        assertThat(forms.changed()).isFalse();
    }

    @Test
    void onlyWhitespaceIsRemoved() {
        // Case and punctuation are the tester's input. Changing them would be guessing.
        assertThat(MemberIds.noSpace(" ab-c_1 2\t3\u00a0x ")).isEqualTo("ab-c_123x");
        assertThat(MemberIds.forms("abc").sent()).isEqualTo("abc");
    }

    @Test
    void nullAndBlankYieldABlankSentForm() {
        assertThat(MemberIds.forms(null).sent()).isEmpty();
        assertThat(MemberIds.forms(null).typed()).isEmpty();
        assertThat(MemberIds.forms("   ").sent()).isEmpty();
        assertThat(MemberIds.forms("   ").changed()).isTrue();
    }

    @Test
    void toStringDoesNotPrintTheId() {
        assertThat(MemberIds.forms("S123456789").toString()).doesNotContain("S123456789");
    }
}
