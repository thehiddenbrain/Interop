package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a code list written as CSV into a {@link SourceTable}.
 *
 * <p>The first record that is not blank is the header row. Every later record that is not blank
 * is a row, with the cells it has. Whatever the reader noticed about the file is turned into a
 * warning here and no row is left out because of it: a list with one bad row is still the list.
 */
final class CsvCodeList {

    private CsvCodeList() {
    }

    static SourceTable read(String text, char delimiter) {
        CsvContent content = new CsvReader(delimiter).read(text);
        CsvRecord header = content.header();
        List<String> warnings = new ArrayList<>();
        content.problems().forEach(problem -> warnings.add(problem.message()));
        if (header == null) {
            return new SourceTable(SourceTable.CSV, List.of(), List.of(), warnings);
        }
        int width = header.cells().size();
        List<Integer> shortRows = content.shortRows().stream().map(CsvRecord::line).toList();
        if (!shortRows.isEmpty()) {
            warnings.add(CodeListText.count(shortRows.size(), "row has", "rows have") + " fewer cells than the header row, which names "
                    + width + " columns: " + lines(shortRows) + ". The cells that are there were read in place and the missing ones "
                    + "as blank.");
        }
        List<Integer> longRows = content.longRows().stream().map(CsvRecord::line).toList();
        if (!longRows.isEmpty()) {
            warnings.add(CodeListText.count(longRows.size(), "row has", "rows have") + " more cells than the header row, which names "
                    + width + " columns: " + lines(longRows) + ". The cells beyond the last header were not read. A cell that holds "
                    + "the delimiter must be in double quotes, so the cells of these rows may sit under the wrong columns.");
        }
        // A description over two lines is possible. In a code list it is far more often a quote
        // that was never closed and happened to be closed further down, with the rows in between
        // read into one cell. The reader cannot tell the two apart, so every such row is named.
        List<Integer> spanning = content.rows().stream().filter(CsvRecord::spansLines).map(CsvRecord::line).toList();
        if (!spanning.isEmpty()) {
            warnings.add(CodeListText.count(spanning.size(), "row has", "rows have") + " a cell that holds a line break: " + lines(spanning)
                    + ". If that was not meant, a double quote on that line is not closed and the lines after it were read into the cell.");
        }
        List<SourceTable.Row> rows = content.rows().stream().map(record -> new SourceTable.Row(record.line(), record.cells())).toList();
        return new SourceTable(SourceTable.CSV, header.cells(), rows, warnings);
    }

    private static String lines(List<Integer> lines) {
        return (lines.size() == 1 ? "line " : "lines ") + CodeListText.numbers(lines);
    }
}
