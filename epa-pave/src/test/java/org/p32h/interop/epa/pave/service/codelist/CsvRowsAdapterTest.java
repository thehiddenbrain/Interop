package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.suite.CsvRows;
import org.p32h.interop.epa.pave.service.suite.SuiteFileReader;
import org.p32h.interop.epa.pave.service.suite.SuiteTable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A suite file read through the real CSV reader, by the real suite reader. The suite tests
 * themselves use a stand-in that splits on commas, so this is where a file a spreadsheet wrote
 * meets the code that will run it.
 */
class CsvRowsAdapterTest {

    private final SuiteFileReader suites = new SuiteFileReader(new CsvRowsAdapter());

    @Test
    void aSuiteFileASpreadsheetWroteIsReadCellForCell() {
        // As a spreadsheet saves "CSV UTF-8": a byte order mark, CRLF between rows, LF inside a
        // cell, a cell with a comma or a quote in double quotes, and a blank line at the end.
        String file = "﻿caseId,memberId,npi,procedureCodes,expected,note\r\n"
                + "TC-1,S123456789,1234567893,95810,AUTH_REQUIRED,\"Sleep study, attended\"\r\n"
                + "TC-2,S223456789,1234567893,95810|95811,,\"first line\nsecond line\"\r\n"
                + "TC-3,S323456789,1234567893,E2601,NO_AUTH_REQUIRED,\"Cushion under 22\"\" wide, \"\"general use\"\"\"\r\n"
                + "\r\n";

        SuiteTable table = suites.read("cases.csv", file);

        assertThat(table.format()).isEqualTo(SuiteTable.CSV);
        assertThat(table.problems()).isEmpty();
        // The byte order mark is not part of the first column's name.
        assertThat(table.columns()).containsExactly("caseId", "memberId", "npi", "procedureCodes", "expected", "note");
        assertThat(table.rows()).hasSize(3);
        assertThat(table.rows()).allSatisfy(row -> assertThat(row.problems()).isEmpty());
        assertThat(table.rows().get(0).cells()).containsExactly(Map.entry("caseId", "TC-1"), Map.entry("memberId", "S123456789"),
                Map.entry("npi", "1234567893"), Map.entry("procedureCodes", "95810"), Map.entry("expected", "AUTH_REQUIRED"),
                // The comma is part of the note. It did not start a seventh cell.
                Map.entry("note", "Sleep study, attended"));
        assertThat(table.rows().get(1).cells()).containsExactly(Map.entry("caseId", "TC-2"), Map.entry("memberId", "S223456789"),
                Map.entry("npi", "1234567893"), Map.entry("procedureCodes", "95810|95811"),
                // The blank expectation is absent, and the line break is part of the note. It did not start a new case.
                Map.entry("note", "first line\nsecond line"));
        assertThat(table.rows().get(2).cells()).containsExactly(Map.entry("caseId", "TC-3"), Map.entry("memberId", "S323456789"),
                Map.entry("npi", "1234567893"), Map.entry("procedureCodes", "E2601"), Map.entry("expected", "NO_AUTH_REQUIRED"),
                Map.entry("note", "Cushion under 22\" wide, \"general use\""));
        // The blank line at the end is not a case and takes no number.
        assertThat(table.rows()).extracting(SuiteTable.SourceRow::number).containsExactly(1, 2, 3);
    }

    @Test
    void aRowIsHandedOverWithTheCellsItHasSoTheSuiteReaderCanRefuseIt() {
        SuiteTable table = suites.read("cases.csv", "caseId,memberId,npi\nTC-1,S123456789\nTC-2,S223456789,1234567893,extra\nTC-3,S323456789,1234567893\n");
        assertThat(table.problems()).isEmpty();
        assertThat(table.rows()).hasSize(3);
        // Padded to three cells, TC-1 would have run with no NPI and nobody told. Cut to three, TC-2 would have run as if nothing were wrong.
        assertThat(table.rows().get(0).problems()).singleElement().asString().startsWith("The row has 2 cells and the first row names 3 columns.");
        assertThat(table.rows().get(0).cells()).isEmpty();
        assertThat(table.rows().get(1).problems()).singleElement().asString().startsWith("The row has 4 cells and the first row names 3 columns.");
        assertThat(table.rows().get(1).cells()).isEmpty();
        assertThat(table.rows().get(2).problems()).isEmpty();
        assertThat(table.rows().get(2).cells()).containsExactly(Map.entry("caseId", "TC-3"), Map.entry("memberId", "S323456789"),
                Map.entry("npi", "1234567893"));
    }

    @Test
    void aQuoteThatIsNeverClosedStopsTheWholeFileAndIsNotThrown() {
        SuiteTable table = suites.read("cases.csv", "caseId,memberId,note\nTC-1,S123456789,\"never closed\nTC-2,S223456789,fine\n");
        assertThat(table.problems()).singleElement().asString()
                .startsWith("The double quote that opens a cell on line 2 has no closing quote that ends the cell.");
        // Not one case is offered to run: the cells of a file with a broken quote cannot be trusted to be in their columns.
        assertThat(table.rows()).isEmpty();
        // The problem never repeats a cell, which here would be a member ID.
        assertThat(table.problems().toString()).doesNotContain("S123456789").doesNotContain("S223456789").doesNotContain("never closed");
    }

    @Test
    void textAfterAClosingQuoteStopsTheWholeFileToo() {
        SuiteTable table = suites.read("cases.csv", "caseId,memberId,note\nTC-1,S123456789,\"a \"marked\" word\"\nTC-2,S223456789,fine\n");
        assertThat(table.problems()).singleElement().asString().startsWith("On line 2 text follows the closing double quote of a cell.");
        assertThat(table.rows()).isEmpty();
        assertThat(table.problems().toString()).doesNotContain("S123456789").doesNotContain("marked");
    }

    @Test
    void theHeaderIsTheFirstRowWhateverItHolds() {
        CsvRows adapter = new CsvRowsAdapter();
        CsvRows.Table table = adapter.read("caseId, memberId \n\n TC-1 ,\" S123456789 \"\n,\n");
        assertThat(table.header()).containsExactly("caseId", "memberId");
        // Blank rows are left out. What is inside quotes is handed over as written, and the suite reader strips it.
        assertThat(table.rows()).containsExactly(List.of("TC-1", " S123456789 "));
        assertThat(table.problems()).isEmpty();

        // A first row of empty cells is still the header, so the suite reader can say that it names no columns.
        // Taken for a blank line, the first case would become the header and its cells would be reported as unknown columns.
        assertThat(adapter.read(",,\nTC-1,S123456789,95810\n").header()).containsExactly("", "", "");
        assertThat(suites.read("cases.csv", ",,\nTC-1,S123456789,95810\n").problems()).singleElement().asString()
                .isEqualTo("The first row of the file must name the columns, and it is blank.");
        assertThat(suites.read("cases.csv", "caseId,,codes\nTC-1,S123456789,95810\n").problems()).singleElement().asString()
                .startsWith("Column 2 has no name in the first row.");
    }

    @Test
    void textWithNoRecordsIsAnEmptyTable() {
        CsvRows adapter = new CsvRowsAdapter();
        for (String text : new String[] {null, "", "﻿"}) {
            CsvRows.Table table = adapter.read(text);
            assertThat(table.header()).isEmpty();
            assertThat(table.rows()).isEmpty();
            assertThat(table.problems()).isEmpty();
        }
    }

    @Test
    void aSuiteFileIsCommaSeparatedUnlessTheAdapterIsBuiltOtherwise() {
        assertThat(new CsvRowsAdapter().read("caseId;memberId\n").header()).containsExactly("caseId;memberId");
        assertThat(new CsvRowsAdapter(new CsvReader(';')).read("caseId;memberId\nTC-1;S123456789\n").rows())
                .containsExactly(List.of("TC-1", "S123456789"));
    }

    @Test
    void itIsABeanOnBothProfiles() {
        // The suite reader is needed on mock and on live, so the adapter is a plain component with no profile.
        assertThat(CsvRowsAdapter.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(CsvRowsAdapter.class.isAnnotationPresent(Profile.class)).isFalse();
    }
}
