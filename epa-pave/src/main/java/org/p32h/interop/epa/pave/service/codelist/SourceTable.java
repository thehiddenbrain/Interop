package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A code list file as headers and rows of text cells, whichever format it was written in, so that
 * one set of rules finds the columns and reads the rows of both.
 *
 * @param format   csv or json
 * @param headers  the header cells or the keys, as written, in the order first seen
 * @param rows     the rows that hold something
 * @param warnings what was noticed about the file's structure while it was read
 */
record SourceTable(String format, List<String> headers, List<Row> rows, List<String> warnings) {

    static final String CSV = "csv";
    static final String JSON = "json";

    SourceTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    /**
     * @param at    where the row is, counting from one: the line of a CSV file it starts on, or its place in a JSON list
     * @param cells the cells by header position. Null where the row has no such cell. May be
     *              shorter or longer than the headers: a row is never padded or cut
     */
    record Row(int at, List<String> cells) {

        Row {
            cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }

        /** The cell under a header position, or null when the row has none there. */
        String cell(int position) {
            return position >= 0 && position < cells.size() ? cells.get(position) : null;
        }
    }

    boolean json() {
        return JSON.equals(format);
    }

    /** Where rows are, as a message says it: "line 3", "lines 3, 9 and 12", "entry 3", "entries 3 and 9". */
    String where(List<Integer> at) {
        String one = json() ? "entry" : "line";
        String several = json() ? "entries" : "lines";
        return (at.size() == 1 ? one : several) + " " + CodeListText.numbers(at);
    }
}
