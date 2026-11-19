package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;
import org.p32h.interop.epa.pave.model.ProcedureCode;

/**
 * The answer to one type-ahead query, with everything the picker needs to explain it.
 *
 * <p>The rows, the count and the two notices come out of one pass over the list, so the picker
 * can never show rows that were narrowed while saying they were not, or the other way round.
 *
 * @param codes      the rows offered, best match first, at most the limit that was asked for
 * @param matched    how many rows matched in all. More than {@code codes} holds when the limit cut the answer short
 * @param narrowed   true when only rows for the requested line of business, and rows stating no line, were offered
 * @param notice     why the rows were not narrowed although a line of business was given. Null otherwise
 * @param otherLines a sentence naming the other lines of business the list has matching rows
 *                   under, in the two cases where leaving it unsaid would mislead: narrowing held
 *                   back rows for the very code that was typed, or left nothing to offer at all.
 *                   Null otherwise
 */
public record CodeSearch(List<ProcedureCode> codes, int matched, boolean narrowed, String notice, String otherLines) {

    public CodeSearch {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }
}
