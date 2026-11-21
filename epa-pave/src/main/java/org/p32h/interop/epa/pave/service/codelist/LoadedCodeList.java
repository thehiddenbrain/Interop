package org.p32h.interop.epa.pave.service.codelist;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;

/**
 * A code list after loading: its rows in file order, and what the tool made of the file.
 *
 * @param entries   every row that has a code
 * @param lineNames each line of business the list names, by its compared form, as first written
 * @param selfCheck the report served by the self-check endpoint
 */
record LoadedCodeList(List<Entry> entries, Map<String, String> lineNames, CodeListSelfCheck selfCheck) {

    LoadedCodeList {
        entries = List.copyOf(entries);
        lineNames = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(lineNames));
    }

    /**
     * One row with what matching needs worked out once, at load.
     *
     * @param row            the row as it is served
     * @param codeKey        the code in lower case
     * @param descriptionKey the description in lower case, empty when the row has none
     * @param lines          the compared forms of the lines of business the row names. Empty when it states none
     * @param effective      the effective date when the cell could be read as one, otherwise null
     * @param end            the end date when the cell could be read as one, otherwise null
     */
    record Entry(ProcedureCode row, String codeKey, String descriptionKey, Set<String> lines, LocalDate effective, LocalDate end) {

        Entry {
            // In the order the cell names them, so a sentence that lists them reads the same on every run.
            lines = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(lines));
        }

        boolean statesNoLine() {
            return lines.isEmpty();
        }

        boolean names(Set<String> wanted) {
            for (String line : wanted) {
                if (lines.contains(line)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * True when the row applies on the day. A bound that is absent, or that could not be read
         * as a date, rules nothing out: a row is never left out on the strength of a date this
         * tool could not read. Both ends of the range are part of it.
         */
        boolean appliesOn(LocalDate day) {
            return day == null || ((effective == null || !day.isBefore(effective)) && (end == null || !day.isAfter(end)));
        }
    }
}
