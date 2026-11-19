package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A code list file as headers and rows of text cells, whichever format it was written in, so that
 * one set of rules finds the columns and reads the rows of both.
 *
 * @param format   csv or json
 * @param rowWord  what a row's position counts, for a message: a line of a CSV file, an entry of a JSON list
 * @param headers  the header cells or the keys, as written, in the order first seen
 * @param rows     the rows that hold something
 * @param warnings what was noticed about the file's structure while it was read
 */
record SourceTable(String format, String rowWord, List<String> headers, List<Row> rows, List<String> warnings) {

    static final String CSV = "csv";
    static final String JSON = "json";

    SourceTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    /**
     * @param at    the line the row starts on, or its place in the list, counting from one
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

    /** "line 7" or "entry 7". */
    String where(int at) {
        return rowWord + " " + at;
    }

    /** "lines 3, 9 and 12", or "line 3" for one. */
    String where(List<Integer> at) {
        return rowWord + (at.size() == 1 ? " " : "s ") + CodeListText.numbers(at);
    }
}
