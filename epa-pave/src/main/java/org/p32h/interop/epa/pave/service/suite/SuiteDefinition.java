package org.p32h.interop.epa.pave.service.suite;

import java.util.List;

/**
 * A suite file, read. Nothing has been run.
 *
 * @param name     the name the file was uploaded under
 * @param format   "CSV" or "JSON"
 * @param columns  the columns or keys the file has, as written
 * @param problems what is wrong with the file as a whole. When not empty, nothing can be run
 * @param skipped  rows left out as comments
 */
public record SuiteDefinition(String name, String format, List<String> columns, List<SuiteRow> rows, List<String> problems, int skipped) {

    public SuiteDefinition(String name, String format, List<String> columns, List<SuiteRow> rows, List<String> problems) {
        this(name, format, columns, rows, problems, 0);
    }

    public SuiteDefinition {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** True when the file as a whole is sound and at least one row can be run. */
    public boolean runnable() {
        return problems.isEmpty() && rows.stream().anyMatch(SuiteRow::runnable);
    }

    /** Prints the format and the size. The name, the columns and the rows come from the file. */
    @Override
    public String toString() {
        return "SuiteDefinition[" + format + ", " + rows.size() + " rows]";
    }
}
