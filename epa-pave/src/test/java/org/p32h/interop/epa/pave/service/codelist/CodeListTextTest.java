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
    void nothingElseIsIgnoredSoTwoDifferentNamesStayDifferent() {
        assertThat(CodeListText.key("PA Required?")).isEqualTo("parequired?");
        assertThat(CodeListText.key("CPT/HCPCS")).isEqualTo("cpt/hcpcs");
        assertThat(CodeListText.key("Code (CPT)")).isEqualTo("code(cpt)");
        assertThat(CodeListText.key("Code #")).isEqualTo("code#");
        // Comparing is never a prefix or a part: these are three lines of business, not one.
        assertThat(List.of(CodeListText.key("Medicare"), CodeListText.key("Medicare Advantage"), CodeListText.key("Medicaid"))).doesNotHaveDuplicates();
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
                // Other ways of writing a date, a time of day, a spreadsheet serial number, and words.
                "2026/01/05", "05-Jan-2026", "Jan 5, 2026", "1-5-2026", "2026-1-5", "2026-01-05 00:00:00", "2026-01-05T00:00:00", "1/5/2026 0:00",
                "46027", "2026", "202601", "TBD", "n/a", "-"}) {
            assertThat(CodeListDates.read(written)).as(written).isNull();
        }
        assertThat(CodeListDates.read(null)).isNull();
        assertThat(CodeListDates.read("  ")).isNull();
    }
}
