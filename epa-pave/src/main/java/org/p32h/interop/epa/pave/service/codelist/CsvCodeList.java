package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a code list written as CSV into a {@link SourceTable}.
 *
 * <p>The first record that is not blank is the header row. Every later record that is not blank
 * is a row, with the cells it has. Whatever the reader noticed about the file is turned into a
 * warning here and no row is left out because of it: a list with one bad row is still the list.
 *
 * <p>Each kind of trouble is one warning that lists its lines, however many lines have it. A file
 * with the same mistake on every row would otherwise bury every other warning under its own.
 */
final class CsvCodeList {

    private CsvCodeList() {
    }

    static SourceTable read(String text, char delimiter) {
        CsvContent content = new CsvReader(delimiter).read(text);
        CsvRecord header = content.header();
        List<String> warnings = new ArrayList<>();
        List<Integer> unclosed = lines(content, CsvProblem.Kind.UNCLOSED_QUOTE);
        if (!unclosed.isEmpty()) {
            boolean one = unclosed.size() == 1;
            warnings.add(CodeListText.count(unclosed.size(), "double quote opens a cell and has", "double quotes open a cell and have")
                    + " no closing quote that ends it: " + lines(unclosed) + ". " + (one ? "It was" : "Each was") + " read as an ordinary "
                    + "character, so " + (one ? "that row" : "those rows") + " may not be what the file meant. A cell that starts with a "
                    + "double quote must end with one, and a double quote inside it is written twice.");
        }
        List<Integer> textAfter = lines(content, CsvProblem.Kind.TEXT_AFTER_CLOSING_QUOTE);
        if (!textAfter.isEmpty()) {
            boolean one = textAfter.size() == 1;
            warnings.add("On " + CodeListText.count(textAfter.size(), "line", "lines") + " text follows the closing double quote of a cell: "
                    + lines(textAfter) + ". " + (one ? "The cell was" : "The cells were") + " kept as written, but "
                    + (one ? "the row" : "the rows") + " may not be what the file meant. Inside a quoted cell a double quote is written twice.");
        }
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

    /** The lines that have a problem of one kind, each once, in the order of the file. */
    private static List<Integer> lines(CsvContent content, CsvProblem.Kind kind) {
        return content.problems().stream().filter(problem -> problem.kind() == kind).map(CsvProblem::line).distinct().toList();
    }

    private static String lines(List<Integer> lines) {
        return (lines.size() == 1 ? "line " : "lines ") + CodeListText.numbers(lines);
    }
}
