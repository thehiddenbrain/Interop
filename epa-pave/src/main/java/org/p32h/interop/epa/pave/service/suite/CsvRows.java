package org.p32h.interop.epa.pave.service.suite;

import java.util.List;

/**
 * Reads CSV text into a header and rows of cells. The suite reader depends on this and not on a
 * CSV parser, so the one RFC 4180 reader the tool has, written for the procedure code list, is
 * the one that reads a suite file too.
 */
@FunctionalInterface
public interface CsvRows {

    Table read(String text);

    /**
     * @param header   the cells of the first row
     * @param rows     every row after the header that is not blank
     * @param problems what is wrong with the text as a whole, such as a quote opened and never closed
     */
    record Table(List<String> header, List<List<String>> rows, List<String> problems) {

        public Table {
            header = header == null ? List.of() : List.copyOf(header);
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** Prints the size only. Every cell, the header included, can be a member's. */
        @Override
        public String toString() {
            return "Table[" + rows.size() + " rows]";
        }
    }
}
