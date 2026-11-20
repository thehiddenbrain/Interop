package org.p32h.interop.epa.pave.service.suite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A suite file read into rows of named cells, whichever format it was written in. Every cell is
 * text: a JSON number or boolean is carried as its text, and a JSON list as its values joined with
 * a bar, so one set of rules reads both formats.
 *
 * @param format   "CSV" or "JSON"
 * @param columns  the column or key names as written, in the order first seen
 * @param problems what is wrong with the file as a whole. When not empty, nothing can be run
 * @param skipped  rows left out as comments, because their first cell starts with #
 */
public record SuiteTable(String format, List<String> columns, List<SourceRow> rows, List<String> problems, int skipped) {

    public SuiteTable(String format, List<String> columns, List<SourceRow> rows, List<String> problems) {
        this(format, columns, rows, problems, 0);
    }

    public static final String CSV = "CSV";
    public static final String JSON = "JSON";

    /** Joins the values of a JSON list into one cell, and separates procedure codes in a CSV cell. */
    public static final String LIST_SEPARATOR = "|";

    public SuiteTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static SuiteTable unreadable(String format, String problem) {
        return new SuiteTable(format, List.of(), List.of(), List.of(problem));
    }

    /**
     * One case as written.
     *
     * @param number   the position of the case in the file, counting the first as 1
     * @param cells    the cells that hold something, by column name as written. A blank cell is absent
     * @param problems what is wrong with the row as written, such as a cell too many
     */
    public record SourceRow(int number, Map<String, String> cells, List<String> problems) {

        public SourceRow {
            cells = cells == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(cells));
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** Prints the position only. The cells hold a member ID. */
        @Override
        public String toString() {
            return "SourceRow[" + number + "]";
        }
    }

    /** Prints the format and the size. The columns are the first row of the file, which can be a case. */
    @Override
    public String toString() {
        return "SuiteTable[" + format + ", " + rows.size() + " rows]";
    }
}
