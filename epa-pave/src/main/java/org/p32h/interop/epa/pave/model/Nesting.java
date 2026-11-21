package org.p32h.interop.epa.pave.model;

import tools.jackson.core.StreamReadConstraints;

/**
 * Where a text nests its objects and lists deeper than the JSON parser reads.
 *
 * <p>The parser stops at a nesting limit and reports it with no position, so a text that is valid
 * JSON would otherwise be answered as one that cannot be read, with nothing to say where. This
 * finds the place by counting brackets outside strings. It does not check that the text is JSON.
 */
public final class Nesting {

    /**
     * The first place the text goes deeper than the parser reads.
     *
     * @param limit  how many levels the parser reads
     * @param line   the line of the bracket that goes one level deeper, from 1
     * @param column its column, from 1
     */
    public record TooDeep(int limit, int line, int column) {
    }

    private Nesting() {
    }

    /** The levels the parser reads, as this application configures it. */
    public static int limit() {
        return StreamReadConstraints.defaults().getMaxNestingDepth();
    }

    /** The first place the text nests deeper than {@link #limit()}, or null when it never does. */
    public static TooDeep tooDeep(String text) {
        return tooDeep(text, limit());
    }

    static TooDeep tooDeep(String text, int limit) {
        if (text == null) {
            return null;
        }
        int depth = 0;
        int line = 1;
        int column = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            column++;
            if (c == '\n') {
                line++;
                column = 0;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth > limit) {
                    return new TooDeep(limit, line, column);
                }
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return null;
    }
}
