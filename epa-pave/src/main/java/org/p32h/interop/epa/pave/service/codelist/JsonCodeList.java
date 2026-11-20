package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.JsonNode;

/**
 * Reads a code list written as JSON into a {@link SourceTable}.
 *
 * <p>The file is a list of objects whose keys are the headers, or an object with exactly one
 * property that holds such a list. The headers of the table are every key any row uses. Two keys
 * that differ only in case, in spacing, or in an underscore, hyphen, dot, slash, question mark or
 * colon are one header, since that is how headers are compared as written anyway. Two keys that
 * differ in a bracketed ending stay two headers, so that neither is lost from the report.
 *
 * <p>A value that is not text is read as text: a number and a boolean as written, so a flag of 1
 * or true reaches the one reader of the authorization flag, and a list of plain values as those
 * values with commas between them, which is how a row names several lines of business.
 */
final class JsonCodeList {

    private static final String SHAPE = "A JSON code list is a list of rows, or an object with exactly one property that holds the list.";

    private JsonCodeList() {
    }

    /**
     * @param subject how a message names the file, as the start of a sentence
     * @throws UnusableCodeListException when the text is not JSON or is not one of the two shapes
     */
    static SourceTable read(String text, String subject) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            // The parser's own message is never used: only the fixed phrase and the position it was reduced to.
            throw new UnusableCodeListException(subject + parsed.error().replaceFirst("^The body", "") + " " + SHAPE);
        }
        List<String> warnings = new ArrayList<>();
        JsonNode list = parsed.node();
        if (JsonSafe.isObject(list)) {
            List<String> lists = new ArrayList<>();
            List<String> others = new ArrayList<>();
            for (Map.Entry<String, JsonNode> property : JsonSafe.properties(list)) {
                (JsonSafe.isArray(property.getValue()) ? lists : others).add(CodeListText.show(property.getKey()));
            }
            if (lists.size() != 1) {
                throw new UnusableCodeListException(subject + " is a JSON object with " + (lists.isEmpty() ? "no list in it"
                        : lists.size() + " lists in it (" + CodeListText.join(lists) + "), so this tool cannot tell which one holds the rows")
                        + ". " + SHAPE);
            }
            if (!others.isEmpty()) {
                warnings.add("Beside the list of rows under " + lists.get(0) + ", the top of the file also holds " + CodeListText.join(others)
                        + ". That was not read.");
            }
            list = JsonSafe.properties(list).stream().filter(p -> JsonSafe.isArray(p.getValue())).findFirst().orElseThrow().getValue();
        } else if (!JsonSafe.isArray(list)) {
            throw new UnusableCodeListException(subject + " is JSON, but it is neither a list nor an object. " + SHAPE);
        }
        if (parsed.repeatedKeys()) {
            warnings.add("An object in the file has the same key more than once. Only the last value of such a key was read.");
        }

        // Header identity is the compared form, so "Code" in one row and "code" in the next are one column.
        Map<String, Integer> positions = new LinkedHashMap<>();
        List<String> headers = new ArrayList<>();
        List<SourceTable.Row> rows = new ArrayList<>();
        List<Integer> notObjects = new ArrayList<>();
        List<Integer> twiceUnderOneHeader = new ArrayList<>();
        List<Integer> nested = new ArrayList<>();
        int at = 0;
        for (JsonNode entry : JsonSafe.elements(list)) {
            at++;
            if (!JsonSafe.isObject(entry)) {
                notObjects.add(at);
                continue;
            }
            Map<Integer, String> cells = new LinkedHashMap<>();
            boolean twice = false;
            boolean container = false;
            for (Map.Entry<String, JsonNode> property : JsonSafe.properties(entry)) {
                String key = CodeListText.headerKey(property.getKey());
                Integer position = positions.get(key);
                if (position == null) {
                    position = headers.size();
                    positions.put(key, position);
                    headers.add(property.getKey());
                }
                if (cells.containsKey(position)) {
                    twice = true;
                    continue;
                }
                JsonNode value = property.getValue();
                container |= nestedContainer(value);
                cells.put(position, text(value));
            }
            if (twice) {
                twiceUnderOneHeader.add(at);
            }
            if (container) {
                nested.add(at);
            }
            if (cells.values().stream().anyMatch(Texts::present)) {
                List<String> aligned = new ArrayList<>();
                int width = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                for (int i = 0; i < width; i++) {
                    aligned.add(cells.get(i));
                }
                rows.add(new SourceTable.Row(at, aligned));
            }
        }
        if (!notObjects.isEmpty()) {
            warnings.add(CodeListText.count(notObjects.size(), "entry of the list is not an object and was", "entries of the list are not "
                    + "objects and were") + " not read: " + entries(notObjects) + ".");
        }
        if (!twiceUnderOneHeader.isEmpty()) {
            warnings.add(CodeListText.count(twiceUnderOneHeader.size(), "entry has", "entries have") + " two keys that differ only in case, "
                    + "spacing or punctuation, which this tool reads as one header: " + entries(twiceUnderOneHeader)
                    + ". The first of the two was read.");
        }
        if (!nested.isEmpty()) {
            warnings.add(CodeListText.count(nested.size(), "entry has", "entries have") + " a value that is an object, or a list holding one: "
                    + entries(nested) + ". Such a value was read as its JSON text.");
        }
        return new SourceTable(SourceTable.JSON, headers, rows, warnings);
    }

    /** True for an object, and for a list with an object or a list inside it. A list of plain values is not nested. */
    private static boolean nestedContainer(JsonNode value) {
        if (JsonSafe.isObject(value)) {
            return true;
        }
        return JsonSafe.isArray(value) && JsonSafe.elements(value).stream().anyMatch(element -> element.isContainer());
    }

    /** The value as the text of a cell. Null for a JSON null. */
    private static String text(JsonNode value) {
        if (JsonSafe.nullOrMissing(value)) {
            return null;
        }
        if (nestedContainer(value)) {
            return JsonSafe.compact(value);
        }
        if (JsonSafe.isArray(value)) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : JsonSafe.elements(value)) {
                String part = JsonSafe.text(element);
                if (Texts.present(part)) {
                    parts.add(part);
                }
            }
            return String.join(", ", parts);
        }
        return JsonSafe.text(value);
    }

    private static String entries(List<Integer> at) {
        return (at.size() == 1 ? "entry " : "entries ") + CodeListText.numbers(at);
    }
}
