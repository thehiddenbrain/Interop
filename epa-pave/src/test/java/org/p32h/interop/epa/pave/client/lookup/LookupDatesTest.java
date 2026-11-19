package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The four ways a lookup API may write a date, and the many ways that are deliberately not read. */
class LookupDatesTest {

    private static final LocalDate DATE = LocalDate.of(1970, 1, 31);

    @Test
    void anIsoDateIsRead() {
        assertThat(LookupDates.parse("1970-01-31")).isEqualTo(DATE);
        assertThat(LookupDates.parse("  1970-01-31\t")).isEqualTo(DATE);
        assertThat(LookupDates.toIso("1970-01-31")).isEqualTo("1970-01-31");
        // The last day of February in a leap year, and its absence in any other.
        assertThat(LookupDates.parse("2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(LookupDates.parse("2026-02-29")).isNull();
    }

    @Test
    void theDatePartOfAnIsoDateTimeIsTakenAsWritten() {
        for (String text : List.of("1970-01-31T00:00:00Z", "1970-01-31T00:00:00", "1970-01-31T00:00", "1970-01-31T05:00:00.000+00:00",
                "1970-01-31T05:00:00.123456789+0000", "1970-01-31T23:59:59-05:00", "1970-01-31t00:00:00z", "1970-01-31 00:00:00",
                "1970-01-31T24:00:00", "1970-01-31T00:00:00+01:00[Europe/Paris]", "1970-01-31T00:00:00,5Z")) {
            assertThat(LookupDates.parse(text)).as(text).isEqualTo(DATE);
        }
        // Late in the evening five hours behind UTC is already the next day in UTC. The day is not moved.
        assertThat(LookupDates.toIso("1970-01-31T23:30:00-05:00")).isEqualTo("1970-01-31");
    }

    @Test
    void monthDayYearIsReadWithTheMonthFirst() {
        assertThat(LookupDates.parse("01/31/1970")).isEqualTo(DATE);
        assertThat(LookupDates.parse("1/31/1970")).isEqualTo(DATE);
        assertThat(LookupDates.parse("01/02/1970")).isEqualTo(LocalDate.of(1970, 1, 2));
        assertThat(LookupDates.toIso("12/31/2025")).isEqualTo("2025-12-31");
    }

    @Test
    void eightDigitsAreReadAsYearMonthDay() {
        assertThat(LookupDates.parse("19700131")).isEqualTo(DATE);
        assertThat(LookupDates.toIso("20251231")).isEqualTo("2025-12-31");
    }

    @Test
    void anythingElseIsNotADateAndIsNeverGuessedAt() {
        for (String text : List.of("31/01/1970", "02/30/1970", "13/01/1970", "1/31/70", "01-31-1970", "1970/01/31", "1970-1-31",
                "1970-01-32", "1970-13-01", "19701301", "1970013", "197001311", "31-Jan-1970", "January 31, 1970", "0", "31536000000",
                "1970-01-31T", "1970-01-31T99:00:00Z", "1970-01-31T00:61:00Z", "1970-01-31 midnight", "not a date", "1970-01-31x")) {
            assertThat(LookupDates.parse(text)).as(text).isNull();
            assertThat(LookupDates.toIso(text)).as(text).isNull();
        }
        assertThat(LookupDates.parse(null)).isNull();
        assertThat(LookupDates.parse("")).isNull();
        assertThat(LookupDates.parse("   ")).isNull();
    }
}
