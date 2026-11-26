package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.config.CodelistProperties;

/**
 * Which header of a file each known column was found under.
 *
 * <p>The header row of the real export is not known in advance, so nothing here is fixed. Each
 * column has a list of candidate names. They are tried in order, and the first candidate that
 * matches a header wins, compared as {@link CodeListText#headerKey} compares. A header is read as
 * one column at most.
 *
 * <p>A header may also end in a bracketed part, such as "Auth Required (Y/N)", and that part is
 * ignored: but only for a column that no header matches as it is written, and only when exactly
 * one header matches without its ending. What stands in the brackets may be the one thing that
 * tells two columns apart, "PA Required (Medicare)" and "PA Required (Commercial)", and reading
 * the first of those as the flag would answer for every member with one line's column. Between two
 * such headers this class does not choose. It says so, and says how to name the one that is meant.
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
        // Each header in the two forms it can match in: as it is written, and without a bracketed ending.
        List<String> written = headers.stream().map(CodeListText::headerKey).toList();
        List<String> shortened = headers.stream().map(CodeListText::headerBase).toList();
        Map<Integer, CodeListColumn> readAs = new HashMap<>();
        // The first column to list a name owns it, in case two columns have been configured with the same one.
        Map<String, CodeListColumn> knownNames = new HashMap<>();

        // First, every column by a header that is one of its names as written.
        for (CodeListColumn column : CodeListColumn.values()) {
            for (String candidate : column.candidates(columns)) {
                String wanted = CodeListText.headerKey(candidate);
                if (wanted.isEmpty()) {
                    continue;
                }
                knownNames.putIfAbsent(wanted, column);
                if (mapping.positions.containsKey(column)) {
                    continue;
                }
                int at = written.indexOf(wanted);
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
            }
        }

        // Then, for a column still not found, by a header that is one of its names once its
        // bracketed ending is left out. Only now, so that a header which is a name as written is
        // never passed over for one that merely stands further left or matches an earlier name.
        Map<String, List<Integer>> undecided = new LinkedHashMap<>();
        for (CodeListColumn column : CodeListColumn.values()) {
            for (String candidate : column.candidates(columns)) {
                // The name as it is written, never its own shortened form. A name configured with a
                // bracketed ending is therefore found by no header here, since no shortened header
                // ends in one: whoever wrote the ending into a name meant that column and no other.
                String wanted = CodeListText.headerKey(candidate);
                if (mapping.positions.containsKey(column) || wanted.isEmpty()) {
                    continue;
                }
                CodeListColumn owner = knownNames.get(wanted);
                if (owner != column) {
                    // The name is also listed under an earlier column, which owns it. That is said
                    // when the earlier column was in fact found through it, as it is for a header
                    // that matches as written.
                    Integer taken = mapping.positions.get(owner);
                    if (taken != null && shortened.get(taken).equals(wanted) && !written.get(taken).equals(wanted)) {
                        mapping.warnings.add("The header " + CodeListText.show(headers.get(taken)) + " reads as a name that is listed under "
                                + "both " + owner.setting() + " and " + column.setting() + ". It was read as " + owner.field()
                                + " and not as " + column.field() + ". Take the name out of one of the two.");
                    }
                    continue;
                }
                // A header that is this name as written would have been read in the first pass, so
                // whatever is found here is found by its shortened form alone.
                List<Integer> found = new ArrayList<>();
                for (int at = 0; at < headers.size(); at++) {
                    if (!readAs.containsKey(at) && shortened.get(at).equals(wanted)) {
                        found.add(at);
                    }
                }
                if (found.size() == 1) {
                    readAs.put(found.get(0), column);
                    mapping.positions.put(column, found.get(0));
                } else if (found.size() > 1) {
                    undecided.putIfAbsent(wanted, found);
                }
            }
        }

        // In the order the columns are looked for, whichever of the two ways each was found.
        mapping.positions.forEach((column, at) -> mapping.recognised.put(column.field(), CodeListText.clean(headers.get(at))));

        Set<Integer> amongUndecided = new HashSet<>();
        undecided.values().forEach(amongUndecided::addAll);
        for (int at = 0; at < headers.size(); at++) {
            if (readAs.containsKey(at) || amongUndecided.contains(at)) {
                continue;
            }
            String header = CodeListText.clean(headers.get(at));
            if (header == null) {
                mapping.unnamed.add(at);
                continue;
            }
            CodeListColumn known = knownNames.get(written.get(at));
            boolean asWritten = known != null;
            if (known == null) {
                known = knownNames.get(shortened.get(at));
            }
            Integer winner = known == null ? null : mapping.positions.get(known);
            if (winner == null) {
                mapping.unrecognised.add(header);
            } else if (!asWritten) {
                mapping.warnings.add("The header " + CodeListText.show(header) + " reads as a name this tool knows for " + known.field()
                        + " once its bracketed ending is left out, but " + known.field() + " was read from "
                        + CodeListText.show(headers.get(winner)) + ". The column " + CodeListText.show(header) + " was not read.");
            } else if (written.get(winner).equals(written.get(at))) {
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
        undecided.forEach((name, found) -> {
            CodeListColumn column = knownNames.get(name);
            mapping.warnings.add("The headers " + CodeListText.join(found.stream().map(at -> CodeListText.show(headers.get(at))).toList())
                    + " read as the same name once their bracketed endings are left out, a name this tool knows for " + column.field()
                    + ". Which of them is meant cannot be told, so " + (found.size() == 2 ? "neither was" : "none of them was")
                    + " read. Name the one to read, exactly as written, under " + column.setting() + ".");
        });
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
