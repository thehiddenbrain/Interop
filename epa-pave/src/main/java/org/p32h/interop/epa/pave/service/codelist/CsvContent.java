package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;

/**
 * What the reader made of a CSV text: every record in the order it was written, and what was wrong
 * with the quoting.
 *
 * <p>Nothing is decided here about which record is the header or which records count. Blank
 * records are kept in {@link #records()} with their line numbers, and the two views below are the
 * reading most callers want: the first record that is not blank names the columns, and the records
 * after it that are not blank are the rows.
 *
 * @param records  every record, blank ones included
 * @param problems quoting the reader had to read past. Empty for a well formed text
 */
public record CsvContent(List<CsvRecord> records, List<CsvProblem> problems) {

    public CsvContent {
        records = records == null ? List.of() : List.copyOf(records);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * Prints how many records and problems there are, never a cell: the records of a suite file
     * hold member IDs and notes. Each record prints the same way on its own.
     */
    @Override
    public String toString() {
        return "CsvContent[records=" + records.size() + ", problems=" + problems.size() + "]";
    }

    /** The first record that is not blank, or null when the text has none: an empty file, or blank lines only. */
    public CsvRecord header() {
        return records.stream().filter(record -> !record.blank()).findFirst().orElse(null);
    }

    /** The records after the header that are not blank. Empty for an empty file and for a file that is a header and nothing else. */
    public List<CsvRecord> rows() {
        return records.stream().filter(record -> !record.blank()).skip(1).toList();
    }

    /** Rows with fewer cells than the header. They are still in {@link #rows()}, with the cells they have. */
    public List<CsvRecord> shortRows() {
        CsvRecord header = header();
        return header == null ? List.of() : rows().stream().filter(row -> row.cells().size() < header.cells().size()).toList();
    }

    /** Rows with more cells than the header. They are still in {@link #rows()}, with every cell they have. */
    public List<CsvRecord> longRows() {
        CsvRecord header = header();
        return header == null ? List.of() : rows().stream().filter(row -> row.cells().size() > header.cells().size()).toList();
    }
}
