package org.p32h.interop.epa.pave.model;

import java.util.List;
import java.util.Map;

/**
 * What the tool made of the procedure code list. The first thing to call after dropping in a new
 * export.
 *
 * @param source                 the file that was loaded
 * @param placeholder            true when the bundled placeholder list is in use, not a real export
 * @param format                 csv or json
 * @param rowCount               rows read
 * @param statingAuthRequirement rows whose prior authorization requirement is not UNKNOWN
 * @param namingVendor           rows that name a delegated vendor
 * @param linesOfBusiness        each distinct line of business the list names, with its row count
 * @param recognisedColumns      the tool's column name mapped to the header it was found under
 * @param unrecognisedColumns    headers in the file that matched no candidate. Reported, never dropped silently
 * @param warnings               anything else worth a look, such as cells the auth flag could not read
 */
public record CodeListSelfCheck(String source, boolean placeholder, String format, int rowCount, int statingAuthRequirement,
                                int namingVendor, Map<String, Integer> linesOfBusiness, Map<String, String> recognisedColumns,
                                List<String> unrecognisedColumns, List<String> warnings) {

    public CodeListSelfCheck {
        linesOfBusiness = linesOfBusiness == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(linesOfBusiness));
        recognisedColumns = recognisedColumns == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(recognisedColumns));
        unrecognisedColumns = unrecognisedColumns == null ? List.of() : List.copyOf(unrecognisedColumns);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
