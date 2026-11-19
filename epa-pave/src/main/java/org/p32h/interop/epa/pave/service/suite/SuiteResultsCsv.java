package org.p32h.interop.epa.pave.service.suite;

import java.util.List;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;

/**
 * The results of a suite run as CSV, for sign-off in a spreadsheet. One row per case, in file
 * order, each tied to its ledger records by run id. It holds what the results table holds, so it
 * never holds a member ID.
 */
public final class SuiteResultsCsv {

    private static final List<String> HEADER = List.of("suiteRunId", "suiteName", "row", "caseId", "runs", "status", "expected",
            "outcome", "outcomeLabel", "result", "elapsedMillis", "slow", "httpStatus", "findings", "nextAction", "reason", "notes",
            "notRecorded", "previousOutcome", "previousResult", "changed", "runId", "runIds", "chainId", "environment",
            "environmentKind", "mode", "target", "startedAt", "finishedAt");

    private SuiteResultsCsv() {
    }

    public static String of(SuiteRunView run) {
        StringBuilder out = new StringBuilder();
        line(out, HEADER);
        for (SuiteCaseResult row : run.results() == null ? List.<SuiteCaseResult>of() : run.results()) {
            line(out, java.util.Arrays.asList(
                    run.suiteRunId(),
                    run.name(),
                    String.valueOf(row.row()),
                    row.caseId(),
                    row.what(),
                    row.statusLine(),
                    row.expected(),
                    row.outcome(),
                    row.outcomeLabel(),
                    row.result() == null ? null : row.result().wire(),
                    row.elapsedMillis() == null ? null : String.valueOf(row.elapsedMillis()),
                    row.slow() ? "yes" : "no",
                    row.httpStatus() == null ? null : String.valueOf(row.httpStatus()),
                    String.join(" ", row.findings()),
                    row.nextAction(),
                    row.reason(),
                    String.join(" ", row.notes()),
                    String.join(" ", row.notRecorded()),
                    row.previousOutcome(),
                    row.previousResult() == null ? null : row.previousResult().wire(),
                    row.changed() == null ? null : (row.changed() ? "yes" : "no"),
                    row.runId(),
                    String.join(" ", row.runIds()),
                    row.chainId(),
                    run.environment() == null ? null : run.environment().name(),
                    run.environment() == null || run.environment().kind() == null ? null : run.environment().kind().wire(),
                    run.mode(),
                    run.target(),
                    run.startedAt(),
                    run.finishedAt()));
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        // RFC 4180 line ending, which every spreadsheet reads.
        out.append("\r\n");
    }

    /**
     * One cell. Quoted when it holds a comma, a quote or a line break, with quotes doubled.
     *
     * <p>A spreadsheet runs a cell that starts with =, +, - or @ as a formula. Free text from a
     * suite file reaches this export (a case id, an expectation, a reason that quotes one), so such
     * a cell is written with a leading apostrophe, which a spreadsheet shows as text. The same is
     * done for a leading tab or carriage return, which some spreadsheets skip before reading the
     * formula sign.
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
