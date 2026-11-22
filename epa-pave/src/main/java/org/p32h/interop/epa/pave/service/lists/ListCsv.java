package org.p32h.interop.epa.pave.service.lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.service.codelist.CsvContent;
import org.p32h.interop.epa.pave.service.codelist.CsvProblem;
import org.p32h.interop.epa.pave.service.codelist.CsvReader;
import org.p32h.interop.epa.pave.service.codelist.CsvRecord;

/**
 * A saved list as CSV, read with the tool's one CSV reader (iterative, never throws on content).
 * The header decides which column is which; what it names that a list does not keep is reported,
 * never silently dropped, and a column a list keeps that the file lacks is reported as absent so
 * that a re-import leaves the stored value alone.
 */
final class ListCsv {

    /**
     * What a file holds, by column.
     *
     * @param columnsUsed    the list's names of the columns read, in the order the file has them
     * @param columnsIgnored the file's headers that name nothing the list keeps, as the file writes them
     */
    record Table(List<String> columnsUsed, List<String> columnsIgnored, List<Row> rows) {

        @Override
        public String toString() {
            return "Table[used=" + columnsUsed.size() + ", ignored=" + columnsIgnored.size() + ", rows=" + rows.size() + "]";
        }
    }

    /**
     * One row of the file.
     *
     * @param line    the line it starts on, counting the header as line 1 when it is the first
     * @param cells   by column name, for the columns this row reaches. A blank cell is there with
     *                a null value, which clears a stored value; a column the row stops short of is
     *                not there, which keeps it
     * @param problem why the row cannot be read as its author meant, or null
     */
    record Row(int line, Map<String, String> cells, String problem) {

        /** The line only: the cells are member data. */
        @Override
        public String toString() {
            return "Row[line=" + line + ", cells=" + cells.size() + "]";
        }
    }

    private ListCsv() {
    }

    /** Null when the text holds no header row. */
    static Table read(String text, List<String> columns) {
        CsvContent content = CsvReader.commaSeparated().read(text);
        CsvRecord header = content.header();
        if (header == null) {
            return null;
        }
        Map<String, String> byKey = new HashMap<>();
        for (String column : columns) {
            byKey.put(key(column), column);
        }
        // For each position of the header, the column it fills, or null when it fills none.
        List<String> positions = new ArrayList<>();
        List<String> used = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String written = header.cells().get(i).strip();
            String column = byKey.get(key(written));
            if (column != null && seen.add(column)) {
                positions.add(column);
                used.add(column);
            } else {
                positions.add(null);
                // A second column of the same name is left out rather than allowed to overwrite the first.
                ignored.add(written.isEmpty() ? "column " + (i + 1) + " (no header)" : written);
            }
        }
        Set<Integer> quotingLines = new HashSet<>();
        for (CsvProblem problem : content.problems()) {
            quotingLines.add(problem.line());
        }
        List<Row> rows = new ArrayList<>();
        for (CsvRecord record : content.rows()) {
            rows.add(row(record, positions, quotingLines));
        }
        return new Table(List.copyOf(used), List.copyOf(ignored), List.copyOf(rows));
    }

    private static Row row(CsvRecord record, List<String> positions, Set<Integer> quotingLines) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < positions.size() && i < record.cells().size(); i++) {
            if (positions.get(i) != null) {
                String cell = record.cells().get(i).strip();
                cells.put(positions.get(i), cell.isEmpty() ? null : cell);
            }
        }
        String problem = null;
        if (quotingLines.contains(record.line()) || record.spansLines()) {
            problem = "The quotes on this line do not pair up, so its values cannot be told apart. Check the quotes in the file.";
        } else if (extraCells(record, positions.size())) {
            // Most often a comma inside a value that was not quoted: every value after it moved one column.
            problem = "This row has more values than the header has columns. Put a value that holds a comma in double quotes.";
        }
        return new Row(record.line(), cells, problem);
    }

    private static boolean extraCells(CsvRecord record, int width) {
        for (int i = width; i < record.cells().size(); i++) {
            if (!record.cells().get(i).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Headers are matched ignoring case, spaces, underscores, hyphens and dots, so "Member ID" is memberId. */
    private static String key(String header) {
        StringBuilder key = new StringBuilder(header.length());
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c != ' ' && c != '_' && c != '-' && c != '.' && c != '\t') {
                key.append(c);
            }
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }

    /** A CSV text with a header row, each cell quoted only where it has to be. */
    static String write(List<String> columns, List<Map<String, String>> rows) {
        StringBuilder out = new StringBuilder();
        line(out, columns);
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                String value = row.get(column);
                cells.add(value == null ? "" : value);
            }
            line(out, cells);
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String cell = cells.get(i);
            boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
            if (quote) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        // CRLF, as RFC 4180 writes it and as Excel expects.
        out.append("\r\n");
    }
}
