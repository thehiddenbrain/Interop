package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lines of a ledger file: how one is written and how a file's bytes are read back.
 *
 * <p>A file holds three kinds of line. A run line is a {@link RunRecord} exactly as its annotations
 * write it. A note line names a run and carries the note and the tester reference as they stand
 * after an amendment, because a run's own line is never rewritten. A case line names the last run
 * of a chain case and carries the result of the case as a whole, which is known only once that
 * run's line has been written.
 *
 * <p>Lines are written and read with the plain mapper, not the application's. The application's
 * mapper carries web configuration that an operator can change, and a setting such as indented
 * output would break a format whose whole contract is one record per line.
 */
final class LedgerLines {

    /** The record type of a note line. */
    static final String TYPE_NOTE = "note";

    private static final JsonMapper MAPPER = JsonSafe.mapper();

    private LedgerLines() {
    }

    /**
     * A note line. Both values are always written, with null for none, so the latest note line for
     * a run is the whole answer to "what are its note and tester reference now".
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"recordType", "runId", "timestamp", "note", "testerRef"})
    record NoteLine(String recordType, String runId, String timestamp, String note, String testerRef) {

        /** Names the run only. A note is free text a tester typed, and it must not reach a log through a default toString. */
        @Override
        public String toString() {
            return "NoteLine[" + runId + "]";
        }
    }

    /**
     * What one file holds.
     *
     * @param lines      the run lines and the note lines, in the order they were written
     * @param unreadable how many lines could not be read as either
     * @param byteCount  the size of the file when it was read
     */
    record Content(List<RunRecord> lines, int unreadable, long byteCount) {

        int runs() {
            return (int) lines.stream().filter(LedgerLines::isRun).count();
        }
    }

    static boolean isRun(RunRecord line) {
        return RunRecord.TYPE_RUN.equals(line.recordType());
    }

    static boolean isNote(RunRecord line) {
        return TYPE_NOTE.equals(line.recordType());
    }

    static boolean isCase(RunRecord line) {
        return RunRecord.TYPE_CASE.equals(line.recordType());
    }

    /** The bytes of one run line, ending in a newline. */
    static byte[] runLine(RunRecord record) {
        return oneLine(MAPPER.writeValueAsString(record));
    }

    /** The bytes of one case line, ending in a newline. It is a record of its own type and is written as one. */
    static byte[] caseLine(RunRecord line) {
        return oneLine(MAPPER.writeValueAsString(line));
    }

    /** The bytes of one note line, ending in a newline. */
    static byte[] noteLine(String runId, String timestamp, String note, String testerRef) {
        return oneLine(MAPPER.writeValueAsString(new NoteLine(TYPE_NOTE, runId, timestamp, note, testerRef)));
    }

    private static byte[] oneLine(String json) {
        // The mapper escapes line breaks inside text. If that ever stopped being true, a record
        // spread over two lines would be unreadable and would take its neighbour with it, so it is
        // refused here and reported as a failed write.
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            throw new IllegalStateException("A ledger line must not contain a line break.");
        }
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a file's bytes line by line. Never throws: a line that cannot be read for any reason,
     * a torn last line included, is counted and left out.
     */
    static Content parse(byte[] bytes) {
        List<RunRecord> lines = new ArrayList<>();
        int unreadable = 0;
        // A file opened and saved in an editor can gain a byte order mark, which is not part of the first line.
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        for (int end = start; end <= bytes.length; end++) {
            if (end < bytes.length && bytes[end] != '\n') {
                continue;
            }
            String text = new String(bytes, start, end - start, StandardCharsets.UTF_8).strip();
            start = end + 1;
            if (text.isEmpty()) {
                // An empty line holds nothing that could have been a run.
                continue;
            }
            RunRecord line = read(text);
            if (line == null) {
                unreadable++;
            } else {
                lines.add(line);
            }
        }
        return new Content(List.copyOf(lines), unreadable, bytes.length);
    }

    /** One line as a run, a note or a case line, or null when it is none of them. */
    private static RunRecord read(String text) {
        try {
            RunRecord line = MAPPER.readValue(text, RunRecord.class);
            if (line == null) {
                return null;
            }
            if (isRun(line)) {
                return line;
            }
            // A note that names no run can be applied to nothing. A record type this version does
            // not know is counted too: leaving it out without a word would hide that something is there.
            return (isNote(line) || isCase(line)) && Texts.present(line.runId()) ? line : null;
        } catch (RuntimeException e) {
            // Not JSON, not an object, or a run line without what makes it a run. The record's own
            // constructor refuses the last of these.
            return null;
        }
    }
}
