package org.p32h.interop.epa.pave.service.suite;

import java.util.List;

/**
 * A suite file, read. Nothing has been run.
 *
 * @param name     the name the file was uploaded under
 * @param format   "CSV" or "JSON"
 * @param columns  the columns or keys the file has, as written
 * @param problems what is wrong with the file as a whole. When not empty, nothing can be run
 */
public record SuiteDefinition(String name, String format, List<String> columns, List<SuiteRow> rows, List<String> problems) {

    public SuiteDefinition {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** True when the file as a whole is sound and at least one row can be run. */
    public boolean runnable() {
        return problems.isEmpty() && rows.stream().anyMatch(SuiteRow::runnable);
    }
}
