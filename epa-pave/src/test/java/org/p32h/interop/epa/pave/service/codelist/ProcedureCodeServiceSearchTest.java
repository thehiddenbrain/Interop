package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.ProcedureCode;

/**
 * The picker's search, the narrowing to a line of business, and the rows the conformance checks
 * ask for. One rule sits under all three: THP Medicare and Medicaid and HPHC Commercial are
 * different lists with different logic, and a row for one is never an answer for another.
 */
class ProcedureCodeServiceSearchTest {

    /** Notes carry a row number, so a test can say which row it means when a code appears more than once. */
    private static final String LIST = "Code,Description,PA,Line of Business,Vendor,Effective Date,End Date,Notes\n"
            + "95810,Sleep study in a lab,Y,Medicare,eviCore,1/1/2026,12/31/2026,r1\n"
            + "95810,Sleep study in a lab,N,Commercial,,1/1/2026,,r2\n"
            + "95811,Sleep study with CPAP titration,Y,\"Medicare, Medicaid\",eviCore,,,r3\n"
            + "E0601,CPAP device,N,,,,,r4\n"
            + "A7030,Full face mask for CPAP device,,,,,,r5\n"
            + "E0470,Bi-level device,Y,Medicare Advantage,,,,r6\n"
            + "G0399,Home sleep study,Y,Medicaid,eviCore,not a date,TBD,r7\n"
            + "9581,Shorter code,N,Commercial,,,,r8\n"
            + "95810X,Longer code,N,,,,,r9\n"
            + "70553,MRI brain 95810 mentioned in text,Y,Commercial,Evolent,,,r10\n";

    @TempDir
    Path directory;

    private ProcedureCodeService service;

    @BeforeEach
    void load() {
        service = TestCodeLists.csv(directory, LIST);
    }

    private static List<String> rows(List<ProcedureCode> codes) {
        return codes.stream().map(ProcedureCode::notes).toList();
    }

    // ---- matching and order ----

    @Test
    void aCodeMatchesByPrefixIgnoringCaseAndADescriptionByContainment() {
        assertThat(rows(service.search("e06", null, 25))).containsExactly("r4");
        assertThat(rows(service.search("E06", null, 25))).containsExactly("r4");
        // In the middle of a description, in any case.
        assertThat(rows(service.search("FACE MASK", null, 25))).containsExactly("r5");
        assertThat(rows(service.search("titration", null, 25))).containsExactly("r3");
        // A code is matched from its start only. "0601" is inside E0601 and is not a match for it.
        assertThat(service.search("0601", null, 25)).isEmpty();
        assertThat(service.search("no such thing", null, 25)).isEmpty();
        // White space around what was typed is not part of the query, and the answer says what was searched.
        assertThat(rows(service.search("  e06 ", null, 25))).containsExactly("r4");
        assertThat(service.find("  e06 ", null, 25).query()).isEqualTo("e06");
        assertThat(service.find("\u00A0E06\u00A0", null, 25).query()).isEqualTo("E06");
        assertThat(service.find("Face  Mask", null, 25).query()).isEqualTo("Face  Mask");
        assertThat(service.find("  ", null, 25).query()).isNull();
        assertThat(service.find(null, null, 25).query()).isNull();
    }

    @Test
    void codeMatchesComeBeforeDescriptionMatchesAndAnExactCodeComesFirst() {
        // r10 holds "95810" in its description only, so it comes last although it stands in the file before nothing else.
        assertThat(rows(service.search("95810", null, 25))).containsExactly("r1", "r2", "r9", "r10");
        // The whole code first, then codes that start with the query, then descriptions, each in the order of the file.
        assertThat(rows(service.search("9581", null, 25))).containsExactly("r8", "r1", "r2", "r3", "r9", "r10");
        assertThat(rows(service.search("cpap", null, 25))).containsExactly("r3", "r4", "r5");
        assertThat(rows(service.search("e0", null, 25))).containsExactly("r4", "r6");

        // A description match that stands first in the file still comes after a code match that stands last.
        ProcedureCodeService ordered = TestCodeLists.csv(directory, "Code,Description,Notes\nA1,about K0823 chairs,r1\nB2,K08 in text,r2\n"
                + "K0823,Power wheelchair,r3\nK08,Short,r4\n");
        assertThat(rows(ordered.search("k08", null, 25))).containsExactly("r4", "r3", "r1", "r2");
        // A row found by its code and by its description alike is offered once.
        ProcedureCodeService both = TestCodeLists.csv(directory, "Code,Description,Notes\nCPAP1,CPAP device,r1\nX1,other,r2\n");
        assertThat(rows(both.search("cpap", null, 25))).containsExactly("r1");
        assertThat(both.find("cpap", null, 25).matched()).isEqualTo(1);
    }

    @Test
    void aBlankQueryOffersTheFirstRowsOfTheList() {
        for (String blank : new String[] {null, "", "   ", "\u00A0"}) {
            assertThat(rows(service.search(blank, null, 3))).as("[" + blank + "]").containsExactly("r1", "r2", "r3");
        }
        assertThat(service.search("", null, 25)).hasSize(10);
    }

    @Test
    void theLimitCutsTheAnswerAndTheCountSaysHowManyMatchedInAll() {
        CodeSearch cut = service.find("9581", null, 2);
        // The best matches survive the cut: the exact code, then the first code that starts with the query.
        assertThat(rows(cut.codes())).containsExactly("r8", "r1");
        assertThat(cut.matched()).isEqualTo(6);
        assertThat(rows(service.search("9581", null, 4))).containsExactly("r8", "r1", "r2", "r3");
        CodeSearch whole = service.find("9581", null, 25);
        assertThat(whole.codes()).hasSize(6);
        assertThat(whole.matched()).isEqualTo(6);
        // A limit of nothing offers nothing, and still says what there is.
        assertThat(service.search("9581", null, 0)).isEmpty();
        assertThat(service.find("9581", null, 0).matched()).isEqualTo(6);
        assertThat(service.search("9581", null, -1)).isEmpty();
        assertThat(service.find("", null, 4).matched()).isEqualTo(10);
    }

    // ---- narrowing to a line of business ----

    @Test
    void aLineOfBusinessNarrowsToItsRowsAndTheRowsThatStateNoLine() {
        CodeSearch medicare = service.find("", "Medicare", 25);
        assertThat(medicare.narrowed()).isTrue();
        assertThat(medicare.notice()).isNull();
        // r1 and r3 name Medicare. r4, r5 and r9 state no line and are always offered.
        assertThat(rows(medicare.codes())).containsExactly("r1", "r3", "r4", "r5", "r9");
        assertThat(medicare.matched()).isEqualTo(5);
        assertThat(rows(service.search("", "Commercial", 25))).containsExactly("r2", "r4", "r5", "r8", "r9", "r10");
        assertThat(rows(service.search("", "Medicaid", 25))).containsExactly("r3", "r4", "r5", "r7", "r9");
        // The same rule as for headers: case, spaces, underscores, hyphens and dots do not matter.
        for (String written : new String[] {"medicare", " MEDICARE ", "Medi-care", "m.e.d.i.c.a.r.e", "Medicare\u00A0"}) {
            assertThat(rows(service.search("", written, 25))).as(written).containsExactly("r1", "r3", "r4", "r5", "r9");
        }
    }

    @Test
    void linesOfBusinessAreNeverConflated() {
        // Medicare is not Medicare Advantage and not Medicaid. No prefix, no part, no nearest name.
        assertThat(rows(service.search("", "Medicare", 25))).doesNotContain("r6", "r7", "r2");
        assertThat(rows(service.search("", "Medicare Advantage", 25))).containsExactly("r4", "r5", "r6", "r9");
        assertThat(rows(service.search("", "Medicaid", 25))).doesNotContain("r1", "r6");
        assertThat(rows(service.search("95810", "Medicare", 25))).containsExactly("r1", "r9");
        assertThat(rows(service.search("95810", "Commercial", 25))).containsExactly("r2", "r9", "r10");
        // "Medi" is the start of three lines this list names and is none of them: nothing is narrowed, and that is said.
        CodeSearch partial = service.find("", "Medi", 25);
        assertThat(partial.narrowed()).isFalse();
        assertThat(partial.codes()).hasSize(10);
        assertThat(partial.notice()).startsWith("The code list has no row for the line of business \"Medi\"");
    }

    @Test
    void aCellThatNamesSeveralLinesBelongsToEachOfThem() {
        assertThat(rows(service.search("95811", "Medicare", 25))).containsExactly("r3");
        assertThat(rows(service.search("95811", "Medicaid", 25))).containsExactly("r3");
        assertThat(service.search("95811", "Commercial", 25)).isEmpty();
        // The row still carries the cell as it was written.
        assertThat(service.search("95811", "Medicaid", 25).get(0).lineOfBusiness()).isEqualTo("Medicare, Medicaid");

        ProcedureCodeService separators = TestCodeLists.csv(directory, "Code,LOB,Notes\nA1,Medicare/Medicaid,r1\nA2,Medicare;Commercial,r2\n"
                + "A3,Medicaid | Commercial,r3\nA4,\"Medicare, Commercial\",r4\n");
        assertThat(rows(separators.search("", "Medicare", 25))).containsExactly("r1", "r2", "r4");
        assertThat(rows(separators.search("", "Medicaid", 25))).containsExactly("r1", "r3");
        assertThat(rows(separators.search("", "Commercial", 25))).containsExactly("r2", "r3", "r4");
    }

    @Test
    void aLineTheListDoesNotNameDoesNotNarrowAndTheAnswerSaysSo() {
        CodeSearch unknown = service.find("958", "Dual Eligible", 25);
        // Narrowed, this would be the one row that states no line, and the tester would not know why.
        assertThat(unknown.narrowed()).isFalse();
        assertThat(rows(unknown.codes())).containsExactly("r1", "r2", "r3", "r8", "r9", "r10");
        assertThat(unknown.matched()).isEqualTo(6);
        assertThat(unknown.notice()).isEqualTo("The code list has no row for the line of business \"Dual Eligible\", so the codes are not "
                + "narrowed to it. The list names: Medicare, Commercial, Medicaid and Medicare Advantage. If one of those is the same "
                + "line under another name, add it to codelist.line-of-business-aliases.");
        assertThat(unknown.otherLines()).isNull();
        assertThat(service.statesLine("Dual Eligible")).isFalse();
    }

    @Test
    void aListThatStatesNoLineAtAllIsNeverNarrowedAndNeverComesBackEmpty() {
        ProcedureCodeService noLines = TestCodeLists.csv(directory, "Code,Description,PA\n95810,Sleep study,Y\nE0601,CPAP device,N\n");
        CodeSearch search = noLines.find("", "Medicare", 25);
        assertThat(search.codes()).hasSize(2);
        assertThat(search.narrowed()).isFalse();
        assertThat(search.notice()).isEqualTo("The code list states no line of business on any row, so every row is offered and the codes "
                + "are not narrowed to \"Medicare\".");
        assertThat(noLines.statesLine("Medicare")).isFalse();
        // With no line asked for there is nothing to explain.
        assertThat(noLines.find("", null, 25).notice()).isNull();
        assertThat(noLines.find("", "  ", 25).notice()).isNull();
        assertThat(noLines.find("", "  ", 25).narrowed()).isFalse();
    }

    @Test
    void aCodeThatIsInTheListUnderAnotherLineIsSaidToBeThere() {
        // E0470 is in the list, under another line. An empty picker alone would read as "not in the list".
        CodeSearch search = service.find("e0470", "Commercial", 25);
        assertThat(search.narrowed()).isTrue();
        assertThat(search.codes()).isEmpty();
        assertThat(search.matched()).isZero();
        assertThat(search.notice()).isNull();
        assertThat(search.otherLines()).isEqualTo("The list has 1 row for \"E0470\" under another line of business (Medicare Advantage). "
                + "It is not offered for \"Commercial\".");

        // Also when the answer is not empty: what is offered here is another code, and the one that was typed is elsewhere.
        CodeSearch typed = service.find("95810", "Medicaid", 25);
        assertThat(rows(typed.codes())).containsExactly("r9");
        assertThat(typed.otherLines()).isEqualTo("The list has 2 rows for \"95810\" under other lines of business (Medicare and Commercial). "
                + "They are not offered for \"Medicaid\".");
    }

    @Test
    void anAnswerLeftEmptyByTheLineSaysWhereTheMatchesAre() {
        CodeSearch search = service.find("sleep", "Medicare Advantage", 25);
        assertThat(search.narrowed()).isTrue();
        assertThat(search.codes()).isEmpty();
        assertThat(search.otherLines()).isEqualTo("Nothing is offered for \"Medicare Advantage\". 4 rows match under other lines of business "
                + "(Medicare, Commercial and Medicaid).");
        assertThat(service.find("titration", "Commercial", 25).otherLines()).isEqualTo("Nothing is offered for \"Commercial\". 1 row matches "
                + "under other lines of business (Medicare and Medicaid).");
    }

    @Test
    void rowsHeldBackAreNotMentionedWhenTheAnswerStandsOnItsOwn() {
        // Rows for other lines are held back on almost every narrowed query. Saying so each time would be noise.
        CodeSearch prefix = service.find("958", "Medicare", 25);
        assertThat(rows(prefix.codes())).containsExactly("r1", "r3", "r9");
        assertThat(prefix.otherLines()).isNull();
        // The usual case: the code is typed in full, the member's own row is offered, and the
        // Commercial row for the same code is none of this member's business.
        CodeSearch typed = service.find("95810", "Medicare", 25);
        assertThat(rows(typed.codes())).containsExactly("r1", "r9");
        assertThat(typed.otherLines()).isNull();
        assertThat(service.find("", "Medicare", 25).otherLines()).isNull();
        assertThat(service.find("sleep", "Commercial", 25).otherLines()).isNull();
        // Nothing held back, nothing to say. And with no line asked for nothing is held back at all.
        assertThat(service.find("E0601", "Commercial", 25).otherLines()).isNull();
        assertThat(service.find("E0470", null, 25).otherLines()).isNull();
        assertThat(service.find("no such thing", "Commercial", 25).otherLines()).isNull();
        // Not narrowed means nothing was held back either: the notice explains that case.
        assertThat(service.find("E0470", "Dual Eligible", 25).otherLines()).isNull();
    }

    @Test
    void anAliasMapsTheNameTheMemberApiUsesToTheNamesTheListUses() {
        ProcedureCodeService aliased = TestCodeLists.csv(directory, LIST, Map.of(
                "line-of-business-aliases.MCR[0]", "Medicare",
                "line-of-business-aliases.[Medicaid/CHIP (MassHealth)][0]", "Medicaid",
                "line-of-business-aliases.Senior[0]", "Medicare",
                "line-of-business-aliases.Senior[1]", "Medicare Advantage"));

        CodeSearch mcr = aliased.find("", "MCR", 25);
        assertThat(mcr.narrowed()).isTrue();
        assertThat(rows(mcr.codes())).containsExactly("r1", "r3", "r4", "r5", "r9");
        assertThat(aliased.statesLine("mcr")).isTrue();
        assertThat(rows(aliased.rowsFor("95810", "MCR", null))).containsExactly("r1");
        // A name with a slash in it is one name. It is not split into two lines, and it reaches the list through its alias.
        assertThat(rows(aliased.search("", "Medicaid/CHIP (MassHealth)", 25))).containsExactly("r3", "r4", "r5", "r7", "r9");
        assertThat(aliased.statesLine("medicaid/chip (masshealth)")).isTrue();
        // One name may stand for several of the list's lines.
        assertThat(rows(aliased.search("", "Senior", 25))).containsExactly("r1", "r3", "r4", "r5", "r6", "r9");

        // An alias runs one way. The list's own name does not start matching the member API's.
        assertThat(rows(aliased.search("", "Medicare", 25))).containsExactly("r1", "r3", "r4", "r5", "r9");
        // And with no alias configured the same name narrows nothing.
        assertThat(service.find("", "MCR", 25).narrowed()).isFalse();
        assertThat(service.statesLine("MCR")).isFalse();
        assertThat(service.find("", "Medicaid/CHIP (MassHealth)", 25).narrowed()).isFalse();
    }

    @Test
    void statesLineSaysWhetherTheListKnowsALineAtAll() {
        assertThat(service.statesLine("Medicare")).isTrue();
        assertThat(service.statesLine("  medicaid ")).isTrue();
        assertThat(service.statesLine("COMMERCIAL")).isTrue();
        assertThat(service.statesLine("Medicare Advantage")).isTrue();
        assertThat(service.statesLine("Medicare Adv")).isFalse();
        assertThat(service.statesLine("Medi")).isFalse();
        assertThat(service.statesLine("One Care")).isFalse();
        for (String nothing : new String[] {null, "", "  ", "-", "/"}) {
            assertThat(service.statesLine(nothing)).as("[" + nothing + "]").isFalse();
        }
    }

    // ---- the rows for one code ----

    @Test
    void rowsForACodeAreForExactlyThatCode() {
        assertThat(rows(service.rowsFor("95810", null, null))).containsExactly("r1", "r2");
        // Not a prefix in either direction, and not a description.
        assertThat(rows(service.rowsFor("9581", null, null))).containsExactly("r8");
        assertThat(rows(service.rowsFor("95810X", null, null))).containsExactly("r9");
        assertThat(service.rowsFor("958", null, null)).isEmpty();
        assertThat(service.rowsFor("99999", null, null)).isEmpty();
        // Case and the white space around the code do not make it another code.
        assertThat(rows(service.rowsFor(" e0601 ", null, null))).containsExactly("r4");
        assertThat(rows(service.rowsFor("95810x", null, null))).containsExactly("r9");
        for (String blank : new String[] {null, "", "  "}) {
            assertThat(service.rowsFor(blank, null, null)).as("[" + blank + "]").isEmpty();
        }
    }

    @Test
    void rowsForALineAreItsRowsAndTheRowsThatStateNoLineAndNeverAnotherLinesRows() {
        assertThat(rows(service.rowsFor("95810", "Medicare", null))).containsExactly("r1");
        assertThat(rows(service.rowsFor("95810", "Commercial", null))).containsExactly("r2");
        assertThat(service.rowsFor("95810", "Medicaid", null)).isEmpty();
        // A row that states no line speaks for every line.
        assertThat(rows(service.rowsFor("E0601", "Medicare", null))).containsExactly("r4");
        assertThat(rows(service.rowsFor("E0601", "Commercial", null))).containsExactly("r4");
        assertThat(service.rowsFor("E0601", "Medicare", null).get(0).lineOfBusiness()).isNull();
        // No line given: every row of the code, whatever its line. Each still says which line it is for.
        assertThat(service.rowsFor("95810", "  ", null)).extracting(ProcedureCode::lineOfBusiness).containsExactly("Medicare", "Commercial");

        // Unlike the picker, a line the list does not know gets no fallback here: the caller is
        // comparing an answer for one member, and must not be handed some other line's row.
        assertThat(service.rowsFor("95810", "Dual Eligible", null)).isEmpty();
        assertThat(rows(service.rowsFor("E0601", "Dual Eligible", null))).containsExactly("r4");
        assertThat(service.rowsFor("E0470", "Medicare", null)).isEmpty();
    }

    @Test
    void aDateKeepsTheRowsWhoseRangeIncludesIt() {
        // r1 runs from 2026-01-01 to 2026-12-31. Both days are part of the range.
        assertThat(rows(service.rowsFor("95810", "Medicare", LocalDate.of(2026, 6, 15)))).containsExactly("r1");
        assertThat(rows(service.rowsFor("95810", "Medicare", LocalDate.of(2026, 1, 1)))).containsExactly("r1");
        assertThat(rows(service.rowsFor("95810", "Medicare", LocalDate.of(2026, 12, 31)))).containsExactly("r1");
        // The day before it starts and the day after it ends.
        assertThat(service.rowsFor("95810", "Medicare", LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(service.rowsFor("95810", "Medicare", LocalDate.of(2027, 1, 1))).isEmpty();
        // r2 has a start and no end, so it applies from its start for good.
        assertThat(rows(service.rowsFor("95810", "Commercial", LocalDate.of(2031, 3, 1)))).containsExactly("r2");
        assertThat(service.rowsFor("95810", "Commercial", LocalDate.of(2025, 12, 31))).isEmpty();
        // A row with no dates applies on every day.
        assertThat(rows(service.rowsFor("E0601", null, LocalDate.of(1999, 1, 1)))).containsExactly("r4");
        // With no date nothing is filtered by date.
        assertThat(rows(service.rowsFor("95810", null, null))).containsExactly("r1", "r2");
    }

    @Test
    void aRowWhoseDatesCannotBeReadIsKeptOnEveryDate() {
        for (LocalDate day : new LocalDate[] {LocalDate.of(1990, 1, 1), LocalDate.of(2026, 6, 15), LocalDate.of(2090, 1, 1)}) {
            List<ProcedureCode> kept = service.rowsFor("G0399", "Medicaid", day);
            assertThat(rows(kept)).as(day.toString()).containsExactly("r7");
            // Served as written, so whoever reads the row can see why no date applied to it.
            assertThat(kept.get(0).effectiveDate()).isEqualTo("not a date");
            assertThat(kept.get(0).endDate()).isEqualTo("TBD");
        }
    }

    @Test
    void aDateThatCanBeReadStillAppliesWhenTheOtherOneCannot() {
        ProcedureCodeService mixed = TestCodeLists.csv(directory, "Code,Effective Date,End Date,Notes\n"
                + "A1,6/1/2026,TBD,r1\nA1,sometime,6/30/2026,r2\n");
        // r1 has not started in March, whatever its end is. r2 has no readable start and ends in June.
        assertThat(rows(mixed.rowsFor("A1", null, LocalDate.of(2026, 3, 1)))).containsExactly("r2");
        assertThat(rows(mixed.rowsFor("A1", null, LocalDate.of(2026, 6, 15)))).containsExactly("r1", "r2");
        assertThat(rows(mixed.rowsFor("A1", null, LocalDate.of(2026, 7, 1)))).containsExactly("r1");
    }

    @Test
    void theRowsServedSayWhatTheListSaysAndNothingMore() {
        ProcedureCode stated = service.rowsFor("95810", "Medicare", null).get(0);
        assertThat(stated.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(stated.delegatedVendor()).isEqualTo("eviCore");
        ProcedureCode silent = service.rowsFor("A7030", null, null).get(0);
        // The list does not say. That is never served as "no", as a default line, or as a default vendor.
        assertThat(silent.authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(silent.authRequiredSource()).isNull();
        assertThat(silent.lineOfBusiness()).isNull();
        assertThat(silent.delegatedVendor()).isNull();
        assertThat(silent.effectiveDate()).isNull();
    }
}
