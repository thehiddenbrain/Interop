package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV text the way RFC 4180 writes it. This is the one CSV reader the tool has: the
 * procedure code list and the suite files are both read through it.
 *
 * <p>What it reads:
 * <ul>
 * <li>a cell in double quotes may hold the delimiter, a line break, and a double quote written twice</li>
 * <li>a record ends at CRLF, at LF or at a lone CR, and the last record needs no line end</li>
 * <li>one byte order mark at the start of the text is skipped</li>
 * <li>the delimiter is whatever the caller says it is</li>
 * </ul>
 *
 * <p>Two allowances beyond the RFC, both for files edited by hand. White space around an unquoted
 * cell is not part of it, so {@code a, b} is two cells "a" and "b"; inside quotes every character
 * is kept. A quoted cell may have white space between the delimiter and its quotes.
 *
 * <p>The reader never throws on what a file holds and never leaves a record out. A blank line is
 * a record with one empty cell. A row keeps the cells it has, however many the header has.
 *
 * <p>Where the quoting cannot be what the author meant, it reads on by a fixed rule and reports
 * the line in {@link CsvContent#problems()}:
 * <ul>
 * <li>text after the closing quote of a cell, on the same line: the cell is kept as written,
 * without the quote that opened it</li>
 * <li>a quote that is still open at the end of the text, or that crosses a line end and then
 * meets a quote with text after it: the opening quote is read as an ordinary character, so the
 * rows after it are still rows</li>
 * </ul>
 *
 * <p>One case no reader can see: a quote left open that a later quote closes cleanly. The rows
 * between the two then sit inside one cell, and nothing about the text says that was not meant.
 * Such a cell holds a line break, and {@link CsvRecord#spansLines()} says so, which is why a
 * caller that does not expect line breaks in its cells should report the records that have one.
 */
public final class CsvReader {

    private static final char QUOTE = '"';
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final char delimiter;

    /**
     * @param delimiter the character between cells. Not a double quote and not a line break, which
     *                  already mean something else in this format
     */
    public CsvReader(char delimiter) {
        if (delimiter == QUOTE || delimiter == '\r' || delimiter == '\n') {
            throw new IllegalArgumentException("The delimiter cannot be a double quote or a line break.");
        }
        this.delimiter = delimiter;
    }

    public static CsvReader commaSeparated() {
        return new CsvReader(',');
    }

    public char delimiter() {
        return delimiter;
    }

    /** Reads the whole text. Null and empty text have no records. */
    public CsvContent read(String text) {
        return text == null ? new CsvContent(List.of(), List.of()) : new Pass(text).run();
    }

    /** One reading of one text. The position and the line count move together, so they live together. */
    private final class Pass {

        private final String text;
        private final int end;
        private final List<CsvRecord> records = new ArrayList<>();
        private final List<CsvProblem> problems = new ArrayList<>();
        private int at;
        private int line = 1;

        Pass(String text) {
            this.text = text;
            this.end = text.length();
            this.at = end > 0 && text.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        }

        CsvContent run() {
            while (at < end) {
                int startLine = line;
                List<String> cells = new ArrayList<>();
                boolean more = true;
                while (more) {
                    cells.add(cell());
                    more = false;
                    if (at < end && text.charAt(at) == delimiter) {
                        at++;
                        // A delimiter is always followed by a cell, also when the text ends right after it.
                        more = true;
                    } else if (at < end) {
                        lineEnd();
                    }
                }
                records.add(new CsvRecord(startLine, cells));
            }
            return new CsvContent(records, problems);
        }

        /** Reads one cell and leaves the position on the delimiter or line end after it, or at the end of the text. */
        private String cell() {
            int start = at;
            while (at < end && padding(text.charAt(at))) {
                at++;
            }
            if (at < end && text.charAt(at) == QUOTE) {
                return quoted(start);
            }
            at = start;
            return unquoted();
        }

        private String unquoted() {
            int start = at;
            toEndOfCell();
            return text.substring(start, at).strip();
        }

        private String quoted(int cellStart) {
            int openedOn = line;
            at++;
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (at < end && !closed) {
                char c = text.charAt(at);
                if (c == QUOTE && at + 1 < end && text.charAt(at + 1) == QUOTE) {
                    value.append(QUOTE);
                    at += 2;
                } else if (c == QUOTE) {
                    at++;
                    closed = true;
                } else if (c == '\r' || c == '\n') {
                    int from = at;
                    lineEnd();
                    // Kept as written: the cell holds the line break the file holds.
                    value.append(text, from, at);
                } else {
                    value.append(c);
                    at++;
                }
            }
            int afterQuote = at;
            while (closed && at < end && padding(text.charAt(at))) {
                at++;
            }
            boolean endsCleanly = closed && (at >= end || text.charAt(at) == delimiter || text.charAt(at) == '\r' || text.charAt(at) == '\n');
            if (endsCleanly) {
                return value.toString();
            }
            if (!closed || line != openedOn) {
                // The quote ran to the end of the text, or ran over one or more line ends and
                // then met a quote with text after it, which is the opening quote of some later
                // cell far more often than the end of this one. Read on from there and every row
                // in between is lost inside one cell. Going back and reading the quote as a plain
                // character keeps the damage to the row that has it.
                problems.add(new CsvProblem(CsvProblem.Kind.UNCLOSED_QUOTE, openedOn, "The double quote that opens a cell on line "
                        + openedOn + " has no closing quote that ends the cell. It was read as an ordinary character, so that row "
                        + "may not be what the file meant. A cell that starts with a double quote must end with one, and a double "
                        + "quote inside it is written twice."));
                at = cellStart;
                line = openedOn;
                return unquoted();
            }
            problems.add(new CsvProblem(CsvProblem.Kind.TEXT_AFTER_CLOSING_QUOTE, line, "On line " + line
                    + " text follows the closing double quote of a cell. The cell was kept as written, but the row may not "
                    + "be what the file meant. Inside a quoted cell a double quote is written twice."));
            toEndOfCell();
            // What stood between the quotes, then the quote that turned out not to be the last
            // one and the text after it, exactly as written.
            return value.append(QUOTE).append(text, afterQuote, at).toString().stripTrailing();
        }

        private void toEndOfCell() {
            while (at < end) {
                char c = text.charAt(at);
                if (c == delimiter || c == '\r' || c == '\n') {
                    return;
                }
                at++;
            }
        }

        /** Steps over one line end, CRLF counting as one, and counts the line. */
        private void lineEnd() {
            if (text.charAt(at) == '\r' && at + 1 < end && text.charAt(at + 1) == '\n') {
                at += 2;
            } else {
                at++;
            }
            line++;
        }

        /** A space or a tab, unless the tab is the delimiter of this file. */
        private boolean padding(char c) {
            return c != delimiter && (c == ' ' || c == '\t');
        }
    }
}
