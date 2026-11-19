package org.p32h.interop.epa.pave.service.codelist;

/**
 * Something about the quoting of a CSV text that means a row may not be what its author meant.
 *
 * <p>The reader never throws on such text and never leaves anything out because of it. It reads
 * on by a fixed rule and says here what it met, so the caller decides whether the file can still
 * be used.
 *
 * @param line    the line of the text the problem is on, counting from one
 * @param message a plain sentence for the person who has to correct the file. It names the line
 *                and never quotes a cell, because a cell of a suite file can be a member ID
 */
public record CsvProblem(Kind kind, int line, String message) {

    public enum Kind {
        /**
         * A cell starts with a double quote and nothing ends it: the text stops first, or the quote
         * runs over a line end and the next quote it meets has text after it.
         */
        UNCLOSED_QUOTE,
        /** Text follows the closing double quote of a cell on the same line, as when a quote inside a cell was not doubled. */
        TEXT_AFTER_CLOSING_QUOTE
    }
}
