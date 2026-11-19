package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One test for each thing that goes wrong when CSV is read by splitting lines on commas. The real
 * code list is an export whose descriptions hold commas, quotes and, now and then, a line break.
 */
class CsvReaderTest {

    private final CsvReader reader = CsvReader.commaSeparated();

    private static List<List<String>> cells(List<CsvRecord> records) {
        return records.stream().map(CsvRecord::cells).toList();
    }

    private static List<Integer> lines(List<CsvRecord> records) {
        return records.stream().map(CsvRecord::line).toList();
    }

    // ---- quoting ----

    @Test
    void aQuotedCellMayHoldTheDelimiterAndASplitOnCommasGetsItWrong() {
        String line = "E0260,\"Hospital bed, semi-electric, with rails\",Yes";
        CsvContent content = reader.read("Code,Description,PA\n" + line + "\n");

        assertThat(content.problems()).isEmpty();
        assertThat(content.rows()).singleElement().satisfies(row ->
                assertThat(row.cells()).containsExactly("E0260", "Hospital bed, semi-electric, with rails", "Yes"));
        // The naive reading puts "Yes" in a fifth column and leaves quote marks in the description.
        assertThat(line.split(",")).hasSize(5).contains("\"Hospital bed", " with rails\"");
        assertThat(content.longRows()).isEmpty();
    }

    @Test
    void aDoubleQuoteWrittenTwiceInsideAQuotedCellIsOneDoubleQuote() {
        CsvContent content = reader.read("Code,Description\nE2601,\"Seat cushion, under 22\"\" wide\"\nE0100,\"\"\"Quad\"\" cane\"\nE0105,\"\"\"\"\n");
        assertThat(content.problems()).isEmpty();
        assertThat(cells(content.rows())).containsExactly(
                List.of("E2601", "Seat cushion, under 22\" wide"),
                List.of("E0100", "\"Quad\" cane"),
                List.of("E0105", "\""));
    }

    @Test
    void aQuotedCellMayHoldALineBreakAndTheLinesAfterItAreStillCountedRight() {
        CsvContent content = reader.read("Code,Notes,PA\nA,\"first line\nsecond line\",Yes\nB,\"windows\r\nbreak\",No\nC,\"old mac\rbreak\",\nD,plain,Yes\n");
        assertThat(content.problems()).isEmpty();
        // The break is kept as the file has it. It is not turned into another kind of break or into a space.
        assertThat(cells(content.rows())).containsExactly(
                List.of("A", "first line\nsecond line", "Yes"),
                List.of("B", "windows\r\nbreak", "No"),
                List.of("C", "old mac\rbreak", ""),
                List.of("D", "plain", "Yes"));
        // A is on lines 2 and 3, B on 4 and 5, C on 6 and 7, so D starts on line 8.
        assertThat(lines(content.rows())).containsExactly(2, 4, 6, 8);
        assertThat(content.rows().stream().filter(CsvRecord::spansLines).map(CsvRecord::line)).containsExactly(2, 4, 6);
    }

    @Test
    void anEmptyQuotedCellAndATrailingDelimiterAreEachAnEmptyCell() {
        CsvContent content = reader.read("a,b,c\n1,\"\",3\n1,2,\n,,\"\"");
        assertThat(cells(content.records())).containsExactly(List.of("a", "b", "c"), List.of("1", "", "3"), List.of("1", "2", ""),
                List.of("", "", ""));
        assertThat(content.shortRows()).isEmpty();
        assertThat(content.longRows()).isEmpty();
    }

    @Test
    void aDoubleQuoteInsideAnUnquotedCellIsAnOrdinaryCharacter() {
        CsvContent content = reader.read("Code,Description\nA4450,Tape 18\" roll\nB,say \"when\" now\n");
        assertThat(content.problems()).isEmpty();
        assertThat(cells(content.rows())).containsExactly(List.of("A4450", "Tape 18\" roll"), List.of("B", "say \"when\" now"));
    }

    // ---- the start and the end of the text ----

    @Test
    void aByteOrderMarkAtTheStartIsNotPartOfTheFirstHeader() {
        CsvContent content = reader.read("﻿Code,Description\nE0601,CPAP device\n");
        assertThat(content.header().cells()).containsExactly("Code", "Description");
        assertThat(content.header().cells().get(0)).hasSize(4);
        // Only the mark at the very start is a mark. Anywhere else it is a character someone put in a cell.
        assertThat(reader.read("a\n\"﻿b\"\n").rows().get(0).cells()).containsExactly("﻿b");
        // A mark in front of a quoted first cell must not stop the quote from being seen as one.
        assertThat(reader.read("﻿\"Code, the\",Description\n").header().cells()).containsExactly("Code, the", "Description");
    }

    @Test
    void crlfLfAndALoneCrEachEndARecord() {
        List<List<String>> expected = List.of(List.of("Code", "PA"), List.of("A", "Yes"), List.of("B", "No"));
        for (String lineEnd : new String[] {"\r\n", "\n", "\r"}) {
            CsvContent content = reader.read("Code,PA" + lineEnd + "A,Yes" + lineEnd + "B,No" + lineEnd);
            assertThat(cells(content.records())).as(lineEnd.replace("\r", "CR").replace("\n", "LF")).isEqualTo(expected);
            assertThat(lines(content.records())).containsExactly(1, 2, 3);
        }
        // One file with all three, as a file passed between systems can have. CRLF is one line end, not two.
        CsvContent mixed = reader.read("Code,PA\r\nA,Yes\nB,No\rC,Yes");
        assertThat(cells(mixed.records())).containsExactly(List.of("Code", "PA"), List.of("A", "Yes"), List.of("B", "No"), List.of("C", "Yes"));
        assertThat(lines(mixed.records())).containsExactly(1, 2, 3, 4);
    }

    @Test
    void theLastRecordNeedsNoLineEndAndALineEndAddsNoRecord() {
        assertThat(cells(reader.read("Code,PA\nA,Yes").rows())).containsExactly(List.of("A", "Yes"));
        assertThat(cells(reader.read("Code,PA\nA,Yes\n").rows())).containsExactly(List.of("A", "Yes"));
        assertThat(reader.read("Code,PA\nA,Yes\n").records()).hasSize(2);
        // Also when the last cell is quoted, or is the text after a delimiter.
        assertThat(cells(reader.read("Code,PA\nA,\"Yes\"").rows())).containsExactly(List.of("A", "Yes"));
        assertThat(cells(reader.read("Code,PA\nA,").rows())).containsExactly(List.of("A", ""));
    }

    @Test
    void anEmptyFileHasNoHeaderAndNoRows() {
        for (String text : new String[] {null, "", "﻿"}) {
            CsvContent content = reader.read(text);
            assertThat(content.records()).isEmpty();
            assertThat(content.header()).isNull();
            assertThat(content.rows()).isEmpty();
            assertThat(content.shortRows()).isEmpty();
            assertThat(content.longRows()).isEmpty();
            assertThat(content.problems()).isEmpty();
        }
        // Blank lines only: records, but still no header.
        CsvContent blank = reader.read("\n  \n,,\n");
        assertThat(blank.records()).hasSize(3);
        assertThat(blank.header()).isNull();
        assertThat(blank.rows()).isEmpty();
    }

    @Test
    void aFileThatIsAHeaderAndNothingElseHasAHeaderAndNoRows() {
        for (String text : new String[] {"Code,Description,PA", "Code,Description,PA\n", "Code,Description,PA\r\n\r\n"}) {
            CsvContent content = reader.read(text);
            assertThat(content.header().cells()).containsExactly("Code", "Description", "PA");
            assertThat(content.rows()).isEmpty();
            assertThat(content.problems()).isEmpty();
        }
    }

    // ---- blank lines and ragged rows ----

    @Test
    void blankLinesAreNotRowsAndDoNotHideTheHeader() {
        CsvContent content = reader.read("\n\nCode,PA\n\nA,Yes\n   \n,\nB,No\n\n\n");
        assertThat(content.header().cells()).containsExactly("Code", "PA");
        assertThat(content.header().line()).isEqualTo(3);
        assertThat(cells(content.rows())).containsExactly(List.of("A", "Yes"), List.of("B", "No"));
        // The line numbers are those of the file, blank lines counted, so a message can point at the right line.
        assertThat(lines(content.rows())).containsExactly(5, 8);
        // Nothing was thrown away to get there: every blank line is still a record, and says it is blank.
        assertThat(content.records()).hasSize(10);
        assertThat(content.records().stream().filter(CsvRecord::blank).map(CsvRecord::line)).containsExactly(1, 2, 4, 6, 7, 9, 10);
        // A row of empty quoted cells says nothing either.
        assertThat(reader.read("a,b\n\"\",\" \"\n").rows()).isEmpty();
    }

    @Test
    void aRowShorterThanTheHeaderIsKeptReportedAndItsCellsStayInPlace() {
        CsvContent content = reader.read("Code,Description,PA,Vendor\nA,Sleep study,Yes,eviCore\nB,Home test\nC,,No,\n");
        assertThat(cells(content.rows())).containsExactly(List.of("A", "Sleep study", "Yes", "eviCore"), List.of("B", "Home test"),
                List.of("C", "", "No", ""));
        assertThat(lines(content.shortRows())).containsExactly(3);
        CsvRecord shortRow = content.shortRows().get(0);
        assertThat(shortRow.cell(0)).isEqualTo("B");
        assertThat(shortRow.cell(1)).isEqualTo("Home test");
        // The row stops there. It has no third cell, which is not the same as an empty third cell.
        assertThat(shortRow.cell(2)).isNull();
        assertThat(shortRow.cell(3)).isNull();
        assertThat(content.rows().get(2).cell(1)).isEmpty();
        assertThat(content.longRows()).isEmpty();
    }

    @Test
    void aRowLongerThanTheHeaderIsKeptWholeAndReported() {
        // The description holds a comma and nobody quoted it, so every later cell sits one place to the right.
        CsvContent content = reader.read("Code,Description,PA\nE0260,Hospital bed, semi-electric,Yes\nE0601,CPAP device,No\n");
        assertThat(cells(content.rows())).containsExactly(List.of("E0260", "Hospital bed", "semi-electric", "Yes"),
                List.of("E0601", "CPAP device", "No"));
        assertThat(lines(content.longRows())).containsExactly(2);
        // Nothing is dropped and nothing is merged to make it fit: the fourth cell is still there to be seen.
        assertThat(content.longRows().get(0).cell(3)).isEqualTo("Yes");
        assertThat(content.shortRows()).isEmpty();
    }

    // ---- white space ----

    @Test
    void whiteSpaceAroundAnUnquotedCellIsNotPartOfItAndInsideQuotesItIs() {
        CsvContent content = reader.read(" Code , PA Required\t,Notes\n  95810 ,\tYes ,  two  words  \n\" 95811 \",\"\tNo\",\"\"\n");
        assertThat(content.header().cells()).containsExactly("Code", "PA Required", "Notes");
        assertThat(cells(content.rows())).containsExactly(
                // Space inside an unquoted cell stays; only the space around it goes.
                List.of("95810", "Yes", "two  words"),
                // What is inside quotes is the author's, space included.
                List.of(" 95811 ", "\tNo", ""));
    }

    @Test
    void whiteSpaceBetweenADelimiterAndAQuotedCellDoesNotUnquoteIt() {
        CsvContent content = reader.read("Code,Description,PA\nE0260, \"Hospital bed, semi-electric\" ,Yes\nE0601,\t\"CPAP, auto\"\t,No\n");
        assertThat(content.problems()).isEmpty();
        assertThat(cells(content.rows())).containsExactly(List.of("E0260", "Hospital bed, semi-electric", "Yes"),
                List.of("E0601", "CPAP, auto", "No"));
    }

    // ---- the delimiter ----

    @Test
    void theDelimiterIsConfigurable() {
        assertThat(cells(new CsvReader(';').read("Code;Description;PA\nE0260;Hospital bed, semi-electric;Yes\n").records()))
                .containsExactly(List.of("Code", "Description", "PA"), List.of("E0260", "Hospital bed, semi-electric", "Yes"));
        assertThat(cells(new CsvReader('|').read("Code|Description\nE0260|\"Bed | with rails\"\n").rows()))
                .containsExactly(List.of("E0260", "Bed | with rails"));
        // With a tab between cells a tab is never padding, so an empty cell between two tabs is still a cell.
        assertThat(cells(new CsvReader('\t').read("Code\tDescription\tPA\nE0260\t\tYes\nE0601\t \"CPAP\tdevice\" \tNo\n").rows()))
                .containsExactly(List.of("E0260", "", "Yes"), List.of("E0601", "CPAP\tdevice", "No"));
        assertThat(new CsvReader('|').delimiter()).isEqualTo('|');
        assertThat(CsvReader.commaSeparated().delimiter()).isEqualTo(',');
    }

    @Test
    void aDelimiterThatAlreadyMeansSomethingElseIsRefused() {
        for (char bad : new char[] {'"', '\r', '\n'}) {
            assertThatThrownBy(() -> new CsvReader(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The delimiter cannot be a double quote or a line break.");
        }
    }

    // ---- quoting that cannot be what the author meant ----

    @Test
    void aQuoteOpenedAndNeverClosedIsReportedAndDoesNotSwallowTheRestOfTheFile() {
        // No other double quote anywhere after it: read as written, the quote would run to the end of the text.
        CsvContent content = reader.read("Code,Description,PA\nA,ok,Yes\nB,\"never closed,No\nC,fine,Yes\nD,last,No\n");

        assertThat(content.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.kind()).isEqualTo(CsvProblem.Kind.UNCLOSED_QUOTE);
            assertThat(problem.line()).isEqualTo(3);
            assertThat(problem.message()).startsWith("The double quote that opens a cell on line 3 has no closing quote that ends the cell.");
            // A message is shown for suite files too, where a cell can be a member ID, so it never quotes one.
            assertThat(problem.message()).doesNotContain("never closed");
        });
        // The rows after the bad one are all still there, as rows, and none of them is inside a cell.
        assertThat(cells(content.rows())).containsExactly(List.of("A", "ok", "Yes"), List.of("B", "\"never closed", "No"),
                List.of("C", "fine", "Yes"), List.of("D", "last", "No"));
        assertThat(lines(content.rows())).containsExactly(2, 3, 4, 5);
        assertThat(content.rows()).noneMatch(CsvRecord::spansLines);
    }

    @Test
    void aQuoteNeverClosedOnTheLastLineIsReportedToo() {
        for (String tail : new String[] {"B,\"never closed", "B,\"never closed\n", "B,\"ends with a doubled quote\"\""}) {
            CsvContent content = reader.read("Code,Description\nA,ok\n" + tail);
            assertThat(content.problems()).as(tail).singleElement().satisfies(problem -> {
                assertThat(problem.kind()).isEqualTo(CsvProblem.Kind.UNCLOSED_QUOTE);
                assertThat(problem.line()).isEqualTo(3);
            });
            assertThat(content.rows()).hasSize(2);
            assertThat(content.rows().get(1).cells().get(0)).isEqualTo("B");
            assertThat(content.rows().get(1).cells().get(1)).startsWith("\"");
        }
    }

    @Test
    void anUnclosedQuoteDoesNotSwallowTheRowsUpToTheNextQuotedCell() {
        // In a real export other cells are quoted too. Read as written, the quote left open on line 3
        // would be "closed" by the quote that opens a cell on line 6, and lines 4 and 5 would be gone.
        CsvContent content = reader.read("Code,Description,PA\nA,\"quoted, properly\",Yes\nB,\"never closed,No\nC,fine,Yes\nD,fine,No\n"
                + "E,\"quoted, again\",Yes\nF,\"\",No\n");

        assertThat(content.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.kind()).isEqualTo(CsvProblem.Kind.UNCLOSED_QUOTE);
            assertThat(problem.line()).isEqualTo(3);
        });
        assertThat(cells(content.rows())).containsExactly(List.of("A", "quoted, properly", "Yes"), List.of("B", "\"never closed", "No"),
                List.of("C", "fine", "Yes"), List.of("D", "fine", "No"), List.of("E", "quoted, again", "Yes"), List.of("F", "", "No"));
        assertThat(lines(content.rows())).containsExactly(2, 3, 4, 5, 6, 7);
    }

    @Test
    void twoUnclosedQuotesAreEachReportedOnTheirOwnLine() {
        CsvContent content = reader.read("Code,Description\nA,\"open one\nB,fine\nC,\"open two\nD,fine\nE,\"closed\"\n");
        assertThat(content.problems()).extracting(CsvProblem::kind)
                .containsExactly(CsvProblem.Kind.UNCLOSED_QUOTE, CsvProblem.Kind.UNCLOSED_QUOTE);
        assertThat(content.problems()).extracting(CsvProblem::line).containsExactly(2, 4);
        assertThat(cells(content.rows())).containsExactly(List.of("A", "\"open one"), List.of("B", "fine"), List.of("C", "\"open two"),
                List.of("D", "fine"), List.of("E", "closed"));
    }

    @Test
    void aCellOverSeveralLinesThatDoesNotEndCleanlyIsReadAsNeverClosed() {
        // The price of the rule above: a cell that was meant to hold a line break, with a quote
        // inside it that was not doubled, is taken for an unclosed one. It is reported either way.
        CsvContent content = reader.read("Code,Notes,PA\nA,\"first line\nsecond \"word\" here\",Yes\nB,plain,No\n");
        assertThat(content.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.kind()).isEqualTo(CsvProblem.Kind.UNCLOSED_QUOTE);
            assertThat(problem.line()).isEqualTo(2);
        });
        assertThat(cells(content.rows())).containsExactly(List.of("A", "\"first line"), List.of("second \"word\" here\"", "Yes"),
                List.of("B", "plain", "No"));
        // Both pieces have the wrong number of cells, so a caller that reports ragged rows reports them as well.
        assertThat(lines(content.shortRows())).containsExactly(2, 3);
    }

    @Test
    void anUnclosedQuoteThatALaterQuoteClosesCleanlyCanOnlyBeSeenAsACellSpanningLines() {
        // The quote on line 2 is closed by the inch mark that ends a cell on line 3. Every quote is
        // followed by a delimiter, so nothing about the text is wrong, and two rows have become one.
        // This is the one case the reader cannot report. It marks the record, so a caller can.
        CsvContent content = reader.read("Code,Description,PA\nA,\"not closed,Yes\nB,pipe 5\",No\nC,plain,Yes\n");

        assertThat(content.problems()).isEmpty();
        assertThat(cells(content.rows())).containsExactly(List.of("A", "not closed,Yes\nB,pipe 5", "No"), List.of("C", "plain", "Yes"));
        assertThat(lines(content.rows())).containsExactly(2, 4);
        assertThat(content.rows().get(0).spansLines()).isTrue();
        assertThat(content.rows().get(1).spansLines()).isFalse();
    }

    @Test
    void textAfterAClosingQuoteIsReportedAndTheDamageStaysInThatRow() {
        CsvContent content = reader.read("Code,Description,PA\nE2601,\"Cushion, 22\" wide\",Yes\nE0601,CPAP device,No\n");

        assertThat(content.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.kind()).isEqualTo(CsvProblem.Kind.TEXT_AFTER_CLOSING_QUOTE);
            assertThat(problem.line()).isEqualTo(2);
            assertThat(problem.message()).startsWith("On line 2 text follows the closing double quote of a cell.");
            // A message is shown for suite files too, where a cell can be a member ID, so it never quotes one.
            assertThat(problem.message()).doesNotContain("Cushion").doesNotContain("wide");
        });
        // The cell is kept as it was written, without the quote that opened it. No character is dropped.
        assertThat(cells(content.rows())).containsExactly(List.of("E2601", "Cushion, 22\" wide\"", "Yes"), List.of("E0601", "CPAP device", "No"));
    }

    /**
     * Writes random tables the way RFC 4180 says to and reads them back. The seed is fixed, so
     * the same four hundred files are read on every run. Each cell is drawn from the characters
     * that make CSV hard: the delimiters, the double quote, both line end characters, space and tab.
     */
    @Test
    void whateverIsWrittenByTheRulesIsReadBackCellForCell() {
        java.util.Random random = new java.util.Random(4180);
        char[] alphabet = {'a', 'B', '7', ' ', ' ', '\t', ',', ';', '|', '"', '"', '\r', '\n', '-', '.'};
        char[] delimiters = {',', ';', '|', '\t'};
        String[] lineEnds = {"\r\n", "\n", "\r"};
        for (int file = 0; file < 400; file++) {
            char delimiter = delimiters[random.nextInt(delimiters.length)];
            List<List<String>> written = new java.util.ArrayList<>();
            StringBuilder text = new StringBuilder(random.nextBoolean() ? "﻿" : "");
            int records = random.nextInt(6);
            for (int r = 0; r < records; r++) {
                List<String> record = new java.util.ArrayList<>();
                int cellCount = 1 + random.nextInt(5);
                for (int c = 0; c < cellCount; c++) {
                    StringBuilder cell = new StringBuilder();
                    int length = random.nextInt(7);
                    for (int i = 0; i < length; i++) {
                        cell.append(alphabet[random.nextInt(alphabet.length)]);
                    }
                    record.add(cell.toString());
                    String value = cell.toString();
                    boolean mustQuote = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0
                            || value.indexOf('\n') >= 0 || !value.equals(value.strip());
                    text.append(c == 0 ? "" : String.valueOf(delimiter));
                    text.append(mustQuote || random.nextInt(4) == 0 ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
                }
                written.add(record);
                text.append(lineEnds[random.nextInt(lineEnds.length)]);
            }
            CsvContent content = new CsvReader(delimiter).read(text.toString());
            assertThat(content.problems()).as("file %d: %s", file, text).isEmpty();
            assertThat(cells(content.records())).as("file %d: %s", file, text).isEqualTo(written);
        }
    }

    @Test
    void aRecordSaysWhichLineItStartsOn() {
        CsvContent content = reader.read("﻿h1,h2\r\n\r\nA,\"x\r\ny\"\r\nB,z");
        assertThat(lines(content.records())).containsExactly(1, 2, 3, 5);
        assertThat(content.records().get(1).blank()).isTrue();
        assertThat(content.records().get(0).blank()).isFalse();
    }
}
