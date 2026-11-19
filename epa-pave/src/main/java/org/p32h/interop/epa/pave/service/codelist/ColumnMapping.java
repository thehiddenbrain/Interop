package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.config.CodelistProperties;

/**
 * Which header of a file each known column was found under.
 *
 * <p>The header row of the real export is not known in advance, so nothing here is fixed. Each
 * column has a list of candidate names. They are tried in order, and the first candidate that
 * matches a header wins, compared as {@link CodeListText#key} compares. A header is read as one
 * column at most.
 *
 * <p>Every header ends up in exactly one place: read as a column, listed as not recognised, or
 * named in a warning that says why it was not read. None is dropped without a word, because a
 * column this tool quietly ignores is where a new export first goes wrong.
 */
final class ColumnMapping {

    private final Map<CodeListColumn, Integer> positions = new EnumMap<>(CodeListColumn.class);
    private final Map<String, String> recognised = new LinkedHashMap<>();
    private final List<String> unrecognised = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Integer> unnamed = new ArrayList<>();

    private ColumnMapping() {
    }

    static ColumnMapping match(List<String> headers, CodelistProperties.Columns columns) {
        ColumnMapping mapping = new ColumnMapping();
        List<String> keys = headers.stream().map(CodeListText::key).toList();
        Map<Integer, CodeListColumn> readAs = new HashMap<>();
        // The first column to list a name owns it, in case two columns have been configured with the same one.
        Map<String, CodeListColumn> knownNames = new HashMap<>();

        for (CodeListColumn column : CodeListColumn.values()) {
            for (String candidate : column.candidates(columns)) {
                String wanted = CodeListText.key(candidate);
                if (wanted.isEmpty()) {
                    continue;
                }
                knownNames.putIfAbsent(wanted, column);
                if (mapping.positions.containsKey(column)) {
                    continue;
                }
                int at = keys.indexOf(wanted);
                if (at < 0) {
                    continue;
                }
                CodeListColumn owner = readAs.get(at);
                if (owner != null) {
                    mapping.warnings.add("The header " + CodeListText.show(headers.get(at)) + " is listed under both " + owner.setting()
                            + " and " + column.setting() + ". It was read as " + owner.field() + " and not as " + column.field()
                            + ". Take it out of one of the two.");
                    continue;
                }
                readAs.put(at, column);
                mapping.positions.put(column, at);
                mapping.recognised.put(column.field(), CodeListText.clean(headers.get(at)));
            }
        }

        for (int at = 0; at < headers.size(); at++) {
            if (readAs.containsKey(at)) {
                continue;
            }
            String header = CodeListText.clean(headers.get(at));
            if (header == null) {
                mapping.unnamed.add(at);
                continue;
            }
            CodeListColumn known = knownNames.get(keys.get(at));
            Integer winner = known == null ? null : mapping.positions.get(known);
            if (winner == null) {
                mapping.unrecognised.add(header);
            } else if (keys.get(winner).equals(keys.get(at))) {
                mapping.warnings.add("The header " + CodeListText.show(header) + " appears more than once, in columns " + (winner + 1)
                        + " and " + (at + 1) + ". Column " + (winner + 1) + " was read as " + known.field() + " and column " + (at + 1)
                        + " was not read.");
            } else {
                // A name this tool knows, in a file where an earlier candidate was also present,
                // for example a short and a long description side by side. It matched a candidate,
                // so it does not belong among the headers that matched none, and it was not read
                // either, so it cannot go unmentioned.
                mapping.warnings.add("The header " + CodeListText.show(header) + " is a name this tool knows for " + known.field() + ", but "
                        + known.field() + " was read from " + CodeListText.show(headers.get(winner)) + ", which comes first in "
                        + known.setting() + ". The column " + CodeListText.show(header) + " was not read.");
            }
        }
        return mapping;
    }

    boolean has(CodeListColumn column) {
        return positions.containsKey(column);
    }

    /** The header position the column was found at, or -1 when the file does not have it. */
    int position(CodeListColumn column) {
        return positions.getOrDefault(column, -1);
    }

    /** The column's field name mapped to the header it was found under, in the order the columns are looked for. */
    Map<String, String> recognised() {
        return recognised;
    }

    /** Headers that matched no candidate of any column, as written. */
    List<String> unrecognised() {
        return unrecognised;
    }

    List<String> warnings() {
        return warnings;
    }

    /** Positions whose header cell is blank. Whether that matters depends on whether any row has a cell there. */
    List<Integer> unnamed() {
        return unnamed;
    }
}
