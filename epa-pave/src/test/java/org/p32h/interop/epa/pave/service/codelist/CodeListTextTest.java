package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a cell is trimmed, compared, read as a date and shown in a warning. */
class CodeListTextTest {

    @Test
    void aCellLosesTheWhiteSpaceAroundItIncludingTheKindNobodyCanSee() {
        assertThat(CodeListText.clean("  95810\t")).isEqualTo("95810");
        // A no-break space, a zero width space and a byte order mark: what a cell pasted from a web page brings along.
        assertThat(CodeListText.clean("\u00A095810\u00A0")).isEqualTo("95810");
        assertThat(CodeListText.clean("\u200B95810\uFEFF")).isEqualTo("95810");
        assertThat(CodeListText.clean("\u2007Yes\u202F")).isEqualTo("Yes");
        // Nothing inside the value is touched.
        assertThat(CodeListText.clean(" Not\u00A0Required ")).isEqualTo("Not\u00A0Required");
        assertThat(CodeListText.clean("two  words")).isEqualTo("two  words");
        assertThat(CodeListText.clean("line one\nline two")).isEqualTo("line one\nline two");
    }

    @Test
    void aCellWithNothingInItIsNullAndNeverAnEmptyString() {
        for (String blank : new String[] {null, "", "   ", "\t\n", "\u00A0", "\u200B\uFEFF "}) {
            assertThat(CodeListText.clean(blank)).as("[" + blank + "]").isNull();
        }
    }

    @Test
    void namesAreComparedIgnoringCaseSpacesUnderscoresHyphensAndDots() {
        String expected = "parequired";
        for (String written : new String[] {"PA Required", "pa_required", "PA-Required", "Pa.Required.", " P A  REQUIRED ", "pa\u00A0required",
                "PA\u2013Required", "PA\u2011Required", "\uFEFFPA Required"}) {
            assertThat(CodeListText.key(written)).as(written).isEqualTo(expected);
        }
        assertThat(CodeListText.key(null)).isEmpty();
        assertThat(CodeListText.key(" - _ . ")).isEmpty();
    }

    @Test
    void inALineOfBusinessNothingElseIsIgnoredSoTwoDifferentLinesStayDifferent() {
        // This is the form lines of business are compared in. What a header may lose beyond it, a line keeps.
        assertThat(CodeListText.key("Medicare?")).isEqualTo("medicare?");
        assertThat(CodeListText.key("Medicare/Medicaid")).isEqualTo("medicare/medicaid");
        assertThat(CodeListText.key("Medicare (HMO)")).isEqualTo("medicare(hmo)");
        assertThat(CodeListText.key("Medicare:")).isEqualTo("medicare:");
        assertThat(CodeListText.key("Plan #2")).isEqualTo("plan#2");
        // Comparing is never a prefix or a part: these are three lines of business, not one.
        assertThat(List.of(CodeListText.key("Medicare"), CodeListText.key("Medicare Advantage"), CodeListText.key("Medicaid"))).doesNotHaveDuplicates();
    }

    @Test
    void aHeaderIsComparedIgnoringASlashAQuestionMarkAndAColonAsWell() {
        String expected = "parequired";
        for (String written : new String[] {"PA Required", "PA Required?", "PA Required:", "pa_required?", "PA-Required ?", "P/A Required",
                "PA\u00A0Required\u00A0?", "\uFEFFPA Required:"}) {
            assertThat(CodeListText.headerKey(written)).as(written).isEqualTo(expected);
        }
        assertThat(CodeListText.headerKey("CPT/HCPCS Code")).isEqualTo("cpthcpcscode");
        assertThat(CodeListText.headerKey("cpt hcpcs code")).isEqualTo("cpthcpcscode");
        assertThat(CodeListText.headerKey(null)).isEmpty();
        assertThat(CodeListText.headerKey(" ? / : - ")).isEmpty();
        // A bracket is kept in this form, and so is everything else that is not one of those three.
        assertThat(CodeListText.headerKey("Auth Required (Y/N)")).isEqualTo("authrequired(yn)");
        assertThat(CodeListText.headerKey("Code #")).isEqualTo("code#");
        assertThat(CodeListText.headerKey("PA Required*")).isEqualTo("parequired*");
        assertThat(CodeListText.headerKey("Vendor & Delegate")).isEqualTo("vendor&delegate");
        assertThat(CodeListText.headerKey("Code\\Type")).isEqualTo("code\\type");
    }

    @Test
    void aBracketedPartAtTheEndOfAHeaderIsLeftOutOfItsShorterForm() {
        assertThat(CodeListText.headerBase("Auth Required (Y/N)")).isEqualTo("authrequired");
        assertThat(CodeListText.headerBase("Auth Required [Y/N]")).isEqualTo("authrequired");
        assertThat(CodeListText.headerBase("Auth Required (Y/N)?")).isEqualTo("authrequired");
        assertThat(CodeListText.headerBase("Effective Date (MM/DD/YYYY):")).isEqualTo("effectivedate");
        // More than one, one after the other.
        assertThat(CodeListText.headerBase("Notes (internal) [2026]")).isEqualTo("notes");
        assertThat(CodeListText.headerBase("Code ()")).isEqualTo("code");
        // Only at the end, only when closed, and only when something stands before it.
        assertThat(CodeListText.headerBase("PA (prior) Required")).isEqualTo("pa(prior)required");
        assertThat(CodeListText.headerBase("Description (short")).isEqualTo("description(short");
        assertThat(CodeListText.headerBase("Notes) more")).isEqualTo("notes)more");
        assertThat(CodeListText.headerBase("(Y/N)")).isEqualTo("(yn)");
        assertThat(CodeListText.headerBase("Code (CPT (or HCPCS))")).isEqualTo("code(cpt(orhcpcs))");
        // A header with no bracket has one form.
        assertThat(CodeListText.headerBase("PA Required?")).isEqualTo("parequired");
        assertThat(CodeListText.headerBase(null)).isEmpty();
    }

    @Test
    void aValueInAWarningShowsWhatTheEyeWouldMiss() {
        assertThat(CodeListText.show("See policy")).isEqualTo("\"See policy\"");
        // This cell reads "Not Required" on screen and is not read as one. The warning has to say why.
        assertThat(CodeListText.show("Not\u00A0Required")).isEqualTo("\"Not<U+00A0>Required\"");
        assertThat(CodeListText.show("a\tb\nc")).isEqualTo("\"a<U+0009>b<U+000A>c\"");
        assertThat(CodeListText.show("x\u200By")).isEqualTo("\"x<U+200B>y\"");
        // An accented letter and the replacement mark are visible, so they are shown as they are.
        assertThat(CodeListText.show("caf\u00E9 \uFFFD")).isEqualTo("\"caf\u00E9 \uFFFD\"");
        assertThat(CodeListText.show(null)).isEqualTo("(nothing)");
        // A long value, such as a description that slid into the wrong column, is cut.
        assertThat(CodeListText.show("x".repeat(200))).isEqualTo("\"" + "x".repeat(60) + "...\"");
        assertThat(CodeListText.show("x".repeat(60))).isEqualTo("\"" + "x".repeat(60) + "\"");
    }

    @Test
    void aListReadsAsASentenceAndIsCutAfterTen() {
        assertThat(CodeListText.numbers(List.of(3))).isEqualTo("3");
        assertThat(CodeListText.numbers(List.of(3, 9))).isEqualTo("3 and 9");
        assertThat(CodeListText.numbers(List.of(3, 9, 12))).isEqualTo("3, 9 and 12");
        List<Integer> many = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            many.add(i);
        }
        assertThat(CodeListText.numbers(many)).isEqualTo("1, 2, 3, 4, 5, 6, 7, 8, 9, 10 and 40 more");
        assertThat(CodeListText.numbers(many.subList(0, 10))).isEqualTo("1, 2, 3, 4, 5, 6, 7, 8, 9 and 10");
        assertThat(CodeListText.join(List.of())).isEmpty();
        assertThat(CodeListText.count(1, "row has", "rows have")).isEqualTo("1 row has");
        assertThat(CodeListText.count(3, "row has", "rows have")).isEqualTo("3 rows have");
    }

    // ---- dates ----

    @Test
    void aDateIsReadInTheThreeFormsAnExportWritesOne() {
        LocalDate expected = LocalDate.of(2026, 1, 5);
        for (String written : new String[] {"2026-01-05", "1/5/2026", "01/05/2026", "1/05/2026", "01/5/2026", "20260105", " 2026-01-05 ",
                "\u00A01/5/2026"}) {
            assertThat(CodeListDates.read(written)).as(written).isEqualTo(expected);
        }
        // With slashes the month is first.
        assertThat(CodeListDates.read("3/4/2026")).isEqualTo(LocalDate.of(2026, 3, 4));
        // The open ended date many payer systems write.
        assertThat(CodeListDates.read("12/31/9999")).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(CodeListDates.read("99991231")).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(CodeListDates.read("2/29/2024")).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void aDateIsNeverGuessed() {
        for (String written : new String[] {
                // A day that does not exist is not rolled forward to one that does.
                "2/30/2026", "2026-02-30", "20260230", "2/29/2026", "13/1/2026", "0/5/2026", "2026-13-01", "20261301",
                // A two digit year has no century.
                "1/5/26", "01/05/26",
                // The day first: 31/1/2026 cannot be month 31, and it is not turned round into January.
                "31/1/2026",
                // Other ways of writing a date, a spreadsheet serial number, and words.
                "2026/01/05", "05-Jan-2026", "Jan 5, 2026", "1-5-2026", "2026-1-5", "46027", "2026", "202601", "TBD", "n/a", "-"}) {
            assertThat(CodeListDates.read(written)).as(written).isNull();
        }
        assertThat(CodeListDates.read(null)).isNull();
        assertThat(CodeListDates.read("  ")).isNull();
    }

    @Test
    void aDateFollowedByATimeOfDayIsReadAsThatDate() {
        // What a database or a spreadsheet writes when the column is a date and a time.
        LocalDate expected = LocalDate.of(2026, 1, 5);
        for (String written : new String[] {
                // Each of the three forms, with and without seconds.
                "1/5/2026 0:00", "1/5/2026 00:00", "01/05/2026 0:00:00", "2026-01-05 00:00:00", "2026-01-05 00:00", "20260105 00:00:00",
                // The T of a timestamp, in either case.
                "2026-01-05T00:00:00", "2026-01-05T00:00", "2026-01-05t00:00:00",
                // Fractions of a second.
                "2026-01-05 00:00:00.000", "2026-01-05T00:00:00.0000000", "2026-01-05 00:00:00,5",
                // The twelve hour clock, as a spreadsheet in the United States shows it.
                "1/5/2026 12:00:00 AM", "1/5/2026 12:00 am", "1/5/2026 11:59 PM", "1/5/2026 1:05:09PM",
                // A zone, written as Z, as an offset or as the two names for none.
                "2026-01-05T00:00:00Z", "2026-01-05T00:00:00z", "2026-01-05T00:00:00.000Z", "2026-01-05T00:00:00+00:00",
                "2026-01-05T00:00:00-0500", "2026-01-05 00:00:00+00", "2026-01-05 00:00:00 -05:00", "2026-01-05 00:00:00 UTC",
                "2026-01-05 00:00:00 GMT", "2026-01-05 00:00:00.0000000 +00:00",
                // More than one space between the two, and white space around the cell.
                "2026-01-05   00:00:00", "  1/5/2026 0:00  "}) {
            assertThat(CodeListDates.read(written)).as(written).isEqualTo(expected);
        }
        // The time and the zone decide nothing. The date is the one that is written.
        assertThat(CodeListDates.read("12/31/2026 23:59:59")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(CodeListDates.read("2026-12-31T23:59:59-05:00")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(CodeListDates.read("2026-12-31T00:30:00+14:00")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(CodeListDates.read("12/31/9999 0:00")).isEqualTo(LocalDate.of(9999, 12, 31));
    }

    @Test
    void whatFollowsTheDateHasToBeATimeOfDayOrTheCellIsNotRead() {
        for (String written : new String[] {
                // A time that is not a time.
                "2026-01-05 25:00", "2026-01-05 24:00:00", "1/5/2026 0:60", "1/5/2026 0:00:60", "2026-01-05 99:99:99", "2026-01-05 -1:00",
                // The twelve hour clock has no hour 0 and no hour 13.
                "1/5/2026 0:00 AM", "1/5/2026 13:00 PM",
                // Not the shape of one: no minutes, one digit of minutes, no colons, too many parts.
                "2026-01-05 00", "2026-01-05 0:0", "20260105T000000", "2026-01-05 00:00:00:00", "2026-01-05 000:00",
                // A zone that is not one.
                "2026-01-05T00:00:00+25:00", "2026-01-05T00:00:00+05:75", "2026-01-05 00:00:00 EST5", "2026-01-05T00:00:00ZZ",
                // Words, a second time, a separator with nothing after it, and a zone with no time before it.
                "2026-01-05 noon", "2026-01-05 00:00 TBD", "2026-01-05 00:00 00:00", "2026-01-05T", "2026-01-05Z", "2026-01-05+01:00",
                // A good time after something that is not one of the three date forms, or not a real day.
                "2026-02-30 00:00:00", "13/1/2026 0:00", "1/5/26 0:00", "2026-1-5 00:00:00", "05-Jan-2026 00:00:00", "2026/01/05 00:00"}) {
            assertThat(CodeListDates.read(written)).as(written).isNull();
        }
    }

    @Test
    void aCellWrittenWithATimeZoneIsToldApartBecauseItsDateMayBeAnotherDaySomewhereElse() {
        for (String zoned : new String[] {"2026-01-05T00:00:00Z", "2026-01-05T00:00:00z", "2026-01-05 00:00:00+00", "2026-01-05T00:00:00-0500",
                "2026-01-05 00:00:00 -05:00", "2026-01-05 00:00:00 UTC", "2026-01-05 00:00:00 gmt", "1/5/2026 12:00 AM +01:00"}) {
            assertThat(CodeListDates.zoned(zoned)).as(zoned).isTrue();
            assertThat(CodeListDates.read(zoned)).as(zoned).isEqualTo(LocalDate.of(2026, 1, 5));
        }
        // No zone, no time, not a date at all, and nothing.
        for (String plain : new String[] {"2026-01-05 00:00:00", "1/5/2026 0:00", "1/5/2026 11:59 PM", "2026-01-05", "20260105",
                "2026-01-05T25:00:00Z", "TBD", "", null}) {
            assertThat(CodeListDates.zoned(plain)).as(String.valueOf(plain)).isFalse();
        }
    }
}
