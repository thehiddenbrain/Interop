package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.CodeListSelfCheck;
import com.thehiddenbrain.interop.epa.pave.model.ProcedureCode;
import java.util.List;

/**
 * What the procedure code list says. The value of this data is that it can disagree with what the
 * service answers, so it is read as it is and never tidied towards an expected answer.
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
     * Every row for one exact code that applies to a line of business: rows for that line and rows
     * that state no line. With a null or blank line, every row for the code.
     */
    List<ProcedureCode> rowsFor(String code, String lineOfBusiness);

    /** What the tool made of the list it loaded. */
    CodeListSelfCheck selfCheck();
}
