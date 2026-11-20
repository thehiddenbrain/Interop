package org.p32h.interop.epa.pave.service.suite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.suite.SuiteTable.SourceRow;
import tools.jackson.databind.JsonNode;

/**
 * Reads a suite file, CSV or JSON, into rows of named cells. It decides nothing about what a row
 * means; {@link SuiteCases} does that.
 *
 * <p>Nothing here logs a cell or puts one in a message: a cell can be a member ID.
 */
public final class SuiteFileReader {

    private final CsvRows csv;

    public SuiteFileReader(CsvRows csv) {
        this.csv = csv;
    }

    /**
     * @param fileName the name the file was uploaded under, used only to tell the format
     * @param text     the file as text
     */
    public SuiteTable read(String fileName, String text) {
        boolean json = looksLikeJson(fileName, text);
        if (Texts.blank(text) || Texts.blank(text.replace("﻿", ""))) {
            return SuiteTable.unreadable(json ? SuiteTable.JSON : SuiteTable.CSV, "The file is empty.");
        }
        return json ? readJson(text) : readCsv(text);
    }

    /** Larger than any suite of a few thousand cases, and small enough to read into memory. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    /**
     * The uploaded bytes as text. The file must be UTF-8: read under a guessed encoding, a cell
     * could come out as something the tester never wrote.
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length > MAX_BYTES) {
            throw new BadInputException("The suite file is larger than 5 MB, which is more than this tool reads as a suite.",
                    "Split the file and run the parts one after another.");
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new BadInputException("The suite file is not UTF-8 text.",
                    "In a spreadsheet, save it as \"CSV UTF-8 (Comma delimited)\" and choose it again.");
        }
    }

    /** By extension when there is one, otherwise by how the text starts. */
    static boolean looksLikeJson(String fileName, String text) {
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return true;
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return false;
        }
        String start = text == null ? "" : text.replace("﻿", "").stripLeading();
        return start.startsWith("[") || start.startsWith("{");
    }

    private SuiteTable readCsv(String text) {
        CsvRows.Table table = csv.read(text);
        if (!table.problems().isEmpty()) {
            return new SuiteTable(SuiteTable.CSV, table.header(), List.of(), table.problems());
        }
        List<String> header = table.header().stream().map(h -> h == null ? "" : h.strip()).toList();
        List<String> problems = new ArrayList<>();
        List<String> headerNames = header.stream().filter(h -> !h.isEmpty()).toList();
        if (header.stream().allMatch(String::isEmpty)) {
            problems.add("The first row of the file must name the columns, and it is blank.");
        } else if (headerNames.size() == 1 && (headerNames.get(0).contains(";") || headerNames.get(0).contains("\t"))) {
            // The whole first row arrived as one cell, so the file was saved with another separator. Its cells are not quoted.
            String separator = headerNames.get(0).contains(";") ? "semicolons" : "tabs";
            return new SuiteTable(SuiteTable.CSV, List.of(), List.of(), List.of("The file looks separated by " + separator + ", not by "
                    + "commas: its first row is one cell. In a spreadsheet, save it as \"CSV UTF-8 (Comma delimited)\" and choose it again."));
        } else if (headerNames.stream().filter(h -> SuiteColumns.find(h) != null || SuiteColumns.ignored(h)).count() * 2 < headerNames.size()) {
            // A first row is a header only when at least half of it reads as one. Otherwise it is taken for a case, and none of its cells
            // is quoted back: a case holds a member ID, and one cell that happens to match a column name does not make it a header.
            return new SuiteTable(SuiteTable.CSV, List.of(), List.of(), List.of("The file has no header row: most of its first row is "
                    + "not a column this tool knows, so it looks like a case. Add a first row that names the columns, for example "
                    + "caseId,protocol,memberId,npi,procedureCodes,expected. The columns this tool knows are: " + SuiteColumns.known() + "."));
        } else if (header.contains("")) {
            problems.add("Column " + (header.indexOf("") + 1) + " has no name in the first row. Every column needs one.");
        }
        List<SourceRow> rows = new ArrayList<>();
        int number = 0;
        int skipped = 0;
        for (List<String> cells : table.rows()) {
            if (cells.stream().allMatch(Texts::blank)) {
                continue;
            }
            if (!cells.isEmpty() && cells.get(0) != null && cells.get(0).strip().startsWith(SuiteColumns.IGNORED_PREFIX)) {
                // A row whose first cell starts with # is the tester's comment, as a column named that way is. It is counted, not run.
                skipped++;
                continue;
            }
            number++;
            Map<String, String> named = new LinkedHashMap<>();
            List<String> rowProblems = new ArrayList<>();
            // Cells are matched to columns by position, so a row with a different number of cells
            // could put a value under the wrong column. It is reported and not run, never shifted.
            if (cells.size() != header.size()) {
                rowProblems.add("The row has " + cells.size() + " cells and the first row names " + header.size()
                        + " columns. A cell holding a comma or a line break must be in double quotes.");
            } else {
                for (int i = 0; i < header.size(); i++) {
                    if (Texts.present(cells.get(i))) {
                        named.put(header.get(i), cells.get(i).strip());
                    }
                }
            }
            rows.add(new SourceRow(number, named, rowProblems));
        }
        return new SuiteTable(SuiteTable.CSV, header, rows, problems, skipped);
    }

    private SuiteTable readJson(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            return SuiteTable.unreadable(SuiteTable.JSON, parsed.error().replace("The body", "The file"));
        }
        if (parsed.repeatedKeys()) {
            return SuiteTable.unreadable(SuiteTable.JSON, "A case in the file has the same key twice, so one of the two values "
                    + "would be dropped. Remove the repeated key.");
        }
        JsonNode cases = parsed.node();
        if (JsonSafe.isObject(cases)) {
            Set<String> keys = new LinkedHashSet<>(cases.propertyNames());
            keys.remove("name");
            keys.remove("cases");
            if (!cases.has("cases") || !keys.isEmpty()) {
                return SuiteTable.unreadable(SuiteTable.JSON, "A JSON suite file is a list of cases, or an object with \"name\" and "
                        + "\"cases\"." + (keys.isEmpty() ? "" : " This object also has: " + String.join(", ", keys) + "."));
            }
            cases = cases.get("cases");
        }
        if (!JsonSafe.isArray(cases)) {
            return SuiteTable.unreadable(SuiteTable.JSON, "A JSON suite file is a list of cases, or an object with \"name\" and \"cases\".");
        }
        Set<String> columns = new LinkedHashSet<>();
        List<SourceRow> rows = new ArrayList<>();
        int number = 0;
        for (JsonNode item : cases) {
            number++;
            Map<String, String> named = new LinkedHashMap<>();
            List<String> rowProblems = new ArrayList<>();
            if (!JsonSafe.isObject(item)) {
                rowProblems.add("The case is not a JSON object.");
            } else {
                for (Map.Entry<String, JsonNode> property : item.properties()) {
                    columns.add(property.getKey());
                    readValue(property.getKey(), property.getValue(), named, rowProblems);
                }
            }
            rows.add(new SourceRow(number, named, rowProblems));
        }
        return new SuiteTable(SuiteTable.JSON, List.copyOf(columns), rows, List.of());
    }

    /** Puts one JSON value into the row as text, or says why it cannot be read. Never quotes the value. */
    private static void readValue(String key, JsonNode value, Map<String, String> named, List<String> problems) {
        if (JsonSafe.nullOrMissing(value)) {
            return;
        }
        if (value.isObject()) {
            // The one object a case may hold: identifiers for a follow-up read, keyed by name.
            if (SuiteColumns.isCorrelation(key)) {
                for (Map.Entry<String, JsonNode> entry : value.properties()) {
                    String text = scalar(entry.getValue());
                    if (text == null && !JsonSafe.nullOrMissing(entry.getValue())) {
                        problems.add("\"" + key + "." + entry.getKey() + "\" must be text.");
                    } else if (Texts.present(text)) {
                        named.put(entry.getKey(), text.strip());
                    }
                }
            } else {
                problems.add("\"" + key + "\" holds an object. It must be text.");
            }
            return;
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : value) {
                String text = scalar(element);
                if (text == null) {
                    problems.add("\"" + key + "\" holds a list with something in it that is not text.");
                    return;
                }
                if (Texts.present(text)) {
                    parts.add(text.strip());
                }
            }
            if (!parts.isEmpty()) {
                named.put(key, String.join(SuiteTable.LIST_SEPARATOR, parts));
            }
            return;
        }
        String text = scalar(value);
        if (text == null) {
            problems.add("\"" + key + "\" is a number with a decimal point. Write it as text, in double quotes.");
        } else if (Texts.present(text)) {
            named.put(key, text.strip());
        }
    }

    /**
     * Text, a whole number or a boolean as text. Null for anything else. A decimal is refused and
     * not rounded: an identifier written as 1234567893.0 is a mistake, not an NPI.
     */
    private static String scalar(JsonNode node) {
        if (node == null || node.isContainer() || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            return null;
        }
        return JsonSafe.text(node);
    }
}
