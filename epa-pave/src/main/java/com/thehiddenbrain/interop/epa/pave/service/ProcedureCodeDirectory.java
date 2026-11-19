package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.CodeListSelfCheck;
import com.thehiddenbrain.interop.epa.pave.model.ProcedureCode;
import java.time.LocalDate;
import java.util.List;

/**
 * What the procedure code list says. The value of this data is that it can disagree with what the
 * service answers, so it is read as it is and never tidied towards an expected answer.
 *
 * <p><b>How a line of business is matched.</b> A requested line matches a row when it equals a
 * line the row names, after the normalisation used for column headers: case, spaces, underscores,
 * hyphens and dots are ignored. A cell that names several lines separated by a comma, semicolon,
 * slash or pipe belongs to each of them. {@code codelist.line-of-business-aliases} maps a name as
 * the member API gives it to the names the list uses for it. There is no fuzzy matching: Medicare,
 * Medicaid and Commercial use different lists and logic and are never conflated.
 */
public interface ProcedureCodeDirectory {

    /**
     * Codes matching a type-ahead query on code or description.
     *
     * @param lineOfBusiness when present, only rows for that line plus rows that state no line. Rows
     *                       stating no line are always offered, so a list without that column never
     *                       comes back empty
     * @param limit          the most rows to return
     */
    List<ProcedureCode> search(String query, String lineOfBusiness, int limit);

    /**
     * Every row for one exact code that applies to a line of business on a date.
     *
     * @param lineOfBusiness rows for that line and rows that state no line. Null or blank for every
     *                       row of the code whatever its line
     * @param onDate         rows whose effective and end dates, where they could be read as dates,
     *                       include this day. A row whose dates could not be read is kept. Null for
     *                       no date filter
     */
    List<ProcedureCode> rowsFor(String code, String lineOfBusiness, LocalDate onDate);

    /**
     * True when at least one row of the list names this line of business, under the matching rule
     * above. It lets a caller tell "the list has no row for this code on this line" from "the list
     * does not know this line at all", which are different findings.
     */
    boolean statesLine(String lineOfBusiness);

    /** What the tool made of the list it loaded. */
    CodeListSelfCheck selfCheck();
}
