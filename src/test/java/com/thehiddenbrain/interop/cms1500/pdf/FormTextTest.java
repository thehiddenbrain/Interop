package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FormTextTest {

    private final FormText upper = new FormText(true, true);
    private final FormText asIs = new FormText(false, false);

    @Test
    void namesFollowLastFirstMiddleWithComma() {
        assertThat(upper.personName(ClaimFixtures.name("doe", "john", "a"))).isEqualTo("DOE, JOHN A");
        assertThat(asIs.personName(ClaimFixtures.name("Doe", "John", null))).isEqualTo("Doe, John");
        assertThat(asIs.personName(ClaimFixtures.name("Doe", null, "Q"))).isEqualTo("Doe Q");
        assertThat(upper.personName(ClaimFixtures.name("  O'Neil ", "Mary-Jane", "Xavier"))).isEqualTo("O'NEIL, MARY-JANE X");
        assertThat(upper.personName(null)).isNull();
    }

    @Test
    void moneyHasNoSymbolsAndCentsAfterASpace() {
        assertThat(FormText.money(new BigDecimal("125"))).isEqualTo("125 00");
        assertThat(FormText.money(new BigDecimal("0.5"))).isEqualTo("0 50");
        assertThat(FormText.money(new BigDecimal("1234.567"))).isEqualTo("1234 57");
        assertThat(FormText.money(null)).isNull();
    }

    @Test
    void unitsDropTrailingZeros() {
        assertThat(FormText.units(new BigDecimal("1.00"))).isEqualTo("1");
        assertThat(FormText.units(new BigDecimal("1.50"))).isEqualTo("1.5");
        assertThat(FormText.units(new BigDecimal("100"))).isEqualTo("100");
        assertThat(FormText.units(new BigDecimal("1E+1"))).isEqualTo("10");
    }

    @Test
    void datesSplitIntoParts() {
        LocalDate d = LocalDate.of(2026, 3, 7);
        assertThat(FormText.twoDigitMonth(d)).isEqualTo("03");
        assertThat(FormText.twoDigitDay(d)).isEqualTo("07");
        assertThat(FormText.twoDigitYear(d)).isEqualTo("26");
        assertThat(FormText.fourDigitYear(d)).isEqualTo("2026");
        assertThat(FormText.dateText(d)).isEqualTo("03 07 2026");
        assertThat(FormText.twoDigitYear(LocalDate.of(2000, 1, 1))).isEqualTo("00");
    }

    @Test
    void diagnosisCodesLosePeriodsOnlyWhenConfigured() {
        assertThat(upper.diagnosis("s82.101a")).isEqualTo("S82101A");
        assertThat(asIs.diagnosis("s82.101a")).isEqualTo("S82.101A");
    }

    @Test
    void zipsAndPhonesKeepDigitsOnly() {
        assertThat(FormText.zip("62704-1234")).isEqualTo("627041234");
        assertThat(FormText.digits("(217) 555-1234")).isEqualTo("2175551234");
        assertThat(FormText.digits("abc")).isNull();
    }

    @Test
    void cityStateZipSkipsMissingParts() {
        assertThat(upper.cityStateZip("Springfield", "il", "62704")).isEqualTo("SPRINGFIELD IL 62704");
        assertThat(upper.cityStateZip("Springfield", null, null)).isEqualTo("SPRINGFIELD");
        assertThat(upper.cityStateZip(null, null, null)).isNull();
    }

    @Test
    void cleanFoldsAccentsCollapsesWhitespaceAndReplacesUnprintable() {
        assertThat(FormText.clean("  Café \t du   Monde ")).isEqualTo("Cafe du Monde");
        assertThat(FormText.clean("Straße")).isEqualTo("Strasse");
        assertThat(FormText.clean("Ærø \u2014 \u201Cquoted\u201D 東京")).isEqualTo("AEro - \"quoted\" ??");
        assertThat(FormText.clean("   ")).isNull();
        assertThat(upper.text("mixed Case")).isEqualTo("MIXED CASE");
        assertThat(asIs.text("mixed Case")).isEqualTo("mixed Case");
        assertThat(FormText.code("dn ")).isEqualTo("DN");
    }
}
