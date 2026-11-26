package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunRecord;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Runs as CSV, for sign-off in a spreadsheet.
 *
 * <p><b>Every field of the record is a column.</b> The columns are taken from the record type
 * itself, in the order a ledger line writes them, and each cell is the value as the line holds it.
 * A field added to the record later is therefore exported without anyone remembering to add it
 * here. A list is joined inside one cell. The correlation map becomes one column for each key.
 *
 * <p><b>Quoting</b> follows RFC 4180: a cell that contains a comma, a double quote, a carriage
 * return or a line feed is put in double quotes, and a double quote inside it is doubled. Rows end
 * in CRLF.
 *
 * <p><b>A cell is never a formula.</b> A spreadsheet treats a cell that begins with {@code =},
 * {@code +}, {@code -} or {@code @} as something to calculate, and a note is free text a tester
 * typed, a request name comes from a vendor's collection, and a member ID is whatever the member
 * has. So any cell whose first character, after any leading spaces, tabs or line breaks, is one of
 * those four is written with a single quote in front of it. The spreadsheet then shows the text,
 * single quote included, and calculates nothing. This is done to every cell, not only to the
 * free-text ones, so there is no list of "safe" columns to get wrong. The cost is that a value
 * which really does begin with one of the four characters carries a single quote it did not have.
 */
public final class RunCsv {

    /**
     * What joins the entries of a list inside one cell. The entries are codes, which the record
     * replaces when they contain a semicolon, and names this tool gives.
     */
    private static final String LIST_SEPARATOR = "; ";

    private static final String CORRELATION = "correlation";

    /** The keys a record can be correlated under, each of which is a column. A test fails if the record gains one that is not here. */
    static final List<String> CORRELATION_KEYS = List.of(RunRecord.CORR_CLAIM_ID, RunRecord.CORR_CLAIM_RESPONSE_ID,
            RunRecord.CORR_AUTHORIZATION_REF, RunRecord.CORR_RUN_ID, RunRecord.CORR_SUBSCRIPTION_ID, RunRecord.CORR_SUBSCRIPTION_RUN_ID,
            RunRecord.CORR_QUESTIONNAIRE);

    /**
     * Spreadsheet programs take a file with no byte order mark for the local eight-bit encoding and
     * show anything outside ASCII as two wrong characters. The mark makes them read UTF-8.
     */
    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private RunCsv() {
    }

    /** The column names: the fields of the record in the order a line writes them, with the correlation keys in place of the map. */
    public static List<String> header() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : RunRecord.class.getRecordComponents()) {
            if (CORRELATION.equals(component.getName())) {
                CORRELATION_KEYS.forEach(key -> names.add(CORRELATION + "." + key));
            } else {
                names.add(component.getName());
            }
        }
        return names;
    }

    /** The file a browser downloads: a byte order mark, the header row, then one row for each run. */
    public static byte[] bytes(List<RunRecord> runs) {
        byte[] text = text(runs).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BYTE_ORDER_MARK.length + text.length];
        System.arraycopy(BYTE_ORDER_MARK, 0, out, 0, BYTE_ORDER_MARK.length);
        System.arraycopy(text, 0, out, BYTE_ORDER_MARK.length, text.length);
        return out;
    }

    /** The header row and one row for each run, in the order given. */
    public static String text(List<RunRecord> runs) {
        StringBuilder out = new StringBuilder();
        appendRow(out, header());
        for (RunRecord run : runs) {
            appendRow(out, cells(run));
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(values.get(i)));
        }
        out.append("\r\n");
    }

    /** One run as cell values, not yet quoted. Each is read from the record as a ledger line writes it. */
    private static List<String> cells(RunRecord run) {
        JsonNode line = JsonSafe.mapper().valueToTree(run);
        List<String> values = new ArrayList<>();
        for (RecordComponent component : RunRecord.class.getRecordComponents()) {
            JsonNode value = JsonSafe.child(line, component.getName());
            if (CORRELATION.equals(component.getName())) {
                CORRELATION_KEYS.forEach(key -> values.add(text(JsonSafe.child(value, key))));
            } else if (JsonSafe.missing(value) && component.getType() == Boolean.class) {
                // A line leaves a flag out when it is false. In a spreadsheet an empty cell would read as "not known".
                values.add("false");
            } else {
                values.add(text(value));
            }
        }
        return values;
    }

    private static String text(JsonNode value) {
        if (JsonSafe.isArray(value)) {
            return String.join(LIST_SEPARATOR, JsonSafe.elements(value).stream().map(RunCsv::text).toList());
        }
        if (JsonSafe.isObject(value)) {
            // The record has one map and it has its own columns. Anything else of this shape is shown as it is, never dropped.
            return JsonSafe.compact(value);
        }
        String text = JsonSafe.text(value);
        return text == null ? "" : text;
    }

    /** One cell: made safe to open in a spreadsheet, then quoted where RFC 4180 requires it. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = startsLikeAFormula(value) ? "'" + value : value;
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0;
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /**
     * True when the first character that is not white space or a control character is one a
     * spreadsheet takes as the start of a formula. Leading white space is looked past because some
     * spreadsheet programs discard it before deciding.
     */
    private static boolean startsLikeAFormula(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' || c == '+' || c == '-' || c == '@') {
                return true;
            }
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c)) {
                return false;
            }
        }
        return false;
    }
}
