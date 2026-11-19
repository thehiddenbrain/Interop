package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;

/**
 * One record of a CSV text: the cells between two record ends, in the order they were written.
 *
 * <p>A record holds the cells that are there and no others. It is never padded to the width of
 * the header and never cut to it, so a row with too few or too many cells can be told from one
 * with blank cells.
 *
 * @param line  the line the record starts on, counting from one. A record with a line break
 *              inside a quoted cell covers more than one line
 * @param cells the cells. An unquoted cell has no white space around it. A quoted cell is exactly
 *              what stood between its quotes
 */
public record CsvRecord(int line, List<String> cells) {

    public CsvRecord {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /** True when no cell holds anything but white space: an empty line, or a line of delimiters only. */
    public boolean blank() {
        return cells.stream().allMatch(String::isBlank);
    }

    /**
     * The cell at a position, counting from zero, or null when the record has no cell there.
     * Null and not an empty string, so "the row stops before this column" stays apart from "the
     * cell is empty".
     */
    public String cell(int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : null;
    }

    /** True when a cell holds a line break, which only a quoted cell can. */
    public boolean spansLines() {
        return cells.stream().anyMatch(cell -> cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0);
    }
}
