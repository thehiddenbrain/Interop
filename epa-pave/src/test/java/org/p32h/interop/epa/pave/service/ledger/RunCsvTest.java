package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The CSV a tester opens in a spreadsheet for sign-off. The rows are read back here by a reader
 * that follows RFC 4180, so a cell that was quoted wrongly comes back as the wrong number of cells.
 */
class RunCsvTest {

    private static RunRecord.Builder minimal(String runId) {
        return RunRecord.builder().runId(runId).timestamp("2026-03-10T11:00:00-04:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome("NO_AUTH_REQUIRED").result(TestResult.UNVERIFIED);
    }

    private static RunRecord everyFieldSet() {
        return RunRecord.builder().runId("run-full").timestamp("2026-03-10T11:00:00-04:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).target("onyx").profile("live").environment("Onyx UAT", EnvironmentKind.UAT)
                .scenarioId("pas-01").procedureCodes(List.of("95810", "E0601")).lineOfBusiness("Commercial").outcome("PENDED")
                .coverageCodes(List.of("95810: review-action=A4", "E0601: (no answer)")).delegatedVendor("Evolent; Carelon").httpStatus(200)
                .elapsedMillis(321).errorType(OnyxFailureType.NONE).expected("PENDED").result(TestResult.PASS).note("first submission")
                .testerRef("QA-17").chainId("chain-00000000-0000-4000-8000-000000000001").suiteRunId("suite-1").caseId("case-7")
                .replayOfRunId("run-earlier").hook("order-sign").attachmentMode("x12-275").nextAction(NextAction.CHECK_STATUS)
                .targetSource("Claim submit").memberId("S123456789").memberToken("0f".repeat(32)).memberIdLast4("6789").npi("1234567893")
                .dateOfService("2026-03-20")
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1", RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001",
                        RunRecord.CORR_QUESTIONNAIRE, "https://example.org/Questionnaire/q1 https://example.org/Questionnaire/q2"))
                .findings(List.of("SLOW_RESPONSE", "CODE_LIST_NOT_COMPARED")).unrecordedInputs(List.of(RunRecord.INPUT_RUN_VALUES)).slow(true)
                .caseExpected("crd: AUTH_REQUIRED; pas: PENDED").caseResult(TestResult.FAIL)
                .build();
    }

    /** Reads CSV as RFC 4180 defines it: quoted cells, doubled quotes, and line breaks inside quotes. */
    private static List<List<String>> read(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        assertThat(quoted).as("every quoted cell is closed").isFalse();
        assertThat(cell.length()).as("the last row ends in a line break").isZero();
        assertThat(row).isEmpty();
        return rows;
    }

    /** The cells of the only data row, by column name. */
    private static Map<String, String> onlyRow(RunRecord run) {
        List<List<String>> rows = read(RunCsv.text(List.of(run)));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).hasSameSizeAs(rows.get(0));
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            cells.put(rows.get(0).get(i), rows.get(1).get(i));
        }
        return cells;
    }

    @Test
    void thereIsAColumnForEveryFieldOfTheRecordAndForEveryCorrelationKey() throws IllegalAccessException {
        List<String> header = RunCsv.header();
        List<String> expected = new ArrayList<>();
        List<String> correlationKeys = new ArrayList<>();
        for (Field field : RunRecord.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("CORR_")) {
                correlationKeys.add((String) field.get(null));
            }
        }
        // A key added to the record without a column here would be dropped from every export.
        assertThat(RunCsv.CORRELATION_KEYS).containsExactlyInAnyOrderElementsOf(correlationKeys).hasSize(7);
        for (RecordComponent component : RunRecord.class.getRecordComponents()) {
            if (component.getName().equals("correlation")) {
                RunCsv.CORRELATION_KEYS.forEach(key -> expected.add("correlation." + key));
            } else {
                expected.add(component.getName());
            }
        }
        assertThat(header).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(header).contains("nextAction", "attachmentMode", "runId", "note", "testerRef", "correlation.authorizationRef");
        assertThat(header.subList(0, 5)).containsExactly("recordType", "runId", "timestamp", "protocol", "operation");
        // With no runs there is still a header, so an empty export opens as an empty table and not as nothing.
        assertThat(RunCsv.text(List.of())).isEqualTo(String.join(",", header) + "\r\n");
    }

    @Test
    void aRowHoldsEveryFieldAsTheLedgerLineWritesIt() {
        Map<String, String> cells = onlyRow(everyFieldSet());

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("recordType", "run");
        expected.put("runId", "run-full");
        expected.put("timestamp", "2026-03-10T11:00:00-04:00");
        expected.put("protocol", "PAS");
        expected.put("operation", "PAS_SUBMIT");
        expected.put("target", "onyx");
        expected.put("profile", "live");
        expected.put("environment", "Onyx UAT");
        expected.put("environmentKind", "uat");
        expected.put("scenarioId", "pas-01");
        // A list is joined inside one cell.
        expected.put("procedureCodes", "95810; E0601");
        expected.put("lineOfBusiness", "Commercial");
        expected.put("outcome", "PENDED");
        expected.put("coverageCodes", "95810: review-action=A4; E0601: (no answer)");
        expected.put("delegatedVendor", "Evolent; Carelon");
        expected.put("httpStatus", "200");
        expected.put("elapsedMillis", "321");
        expected.put("errorType", "NONE");
        expected.put("expected", "PENDED");
        expected.put("result", "pass");
        expected.put("note", "first submission");
        expected.put("testerRef", "QA-17");
        expected.put("chainId", "chain-00000000-0000-4000-8000-000000000001");
        expected.put("suiteRunId", "suite-1");
        expected.put("caseId", "case-7");
        expected.put("replayOfRunId", "run-earlier");
        expected.put("hook", "order-sign");
        expected.put("attachmentMode", "x12-275");
        expected.put("nextAction", "CHECK_STATUS");
        expected.put("targetSource", "Claim submit");
        expected.put("memberId", "S123456789");
        expected.put("memberToken", "0f".repeat(32));
        expected.put("memberIdLast4", "6789");
        expected.put("npi", "1234567893");
        expected.put("dateOfService", "2026-03-20");
        // The correlation map is one column for each key, empty where the run has none.
        expected.put("correlation.claimId", "claim-1");
        expected.put("correlation.claimResponseId", "");
        expected.put("correlation.authorizationRef", "PA-PLACEHOLDER-0001");
        expected.put("correlation.correlatedRunId", "");
        expected.put("correlation.subscriptionId", "");
        expected.put("correlation.subscriptionRunId", "");
        expected.put("correlation.questionnaire", "https://example.org/Questionnaire/q1 https://example.org/Questionnaire/q2");
        expected.put("findings", "SLOW_RESPONSE; CODE_LIST_NOT_COMPARED");
        expected.put("unrecordedInputs", "run-values");
        expected.put("slow", "true");
        // The result of a chain case as a whole, which its case line gives the last step when the line is read.
        expected.put("caseExpected", "crd: AUTH_REQUIRED; pas: PENDED");
        expected.put("caseResult", "fail");
        expected.put("answeredBy", "SERVICE");
        assertThat(cells).containsExactlyEntriesOf(expected);
        // Nothing in the record was left without an expectation above.
        assertThat(expected.keySet()).containsExactlyElementsOf(RunCsv.header());
    }

    @Test
    void whatARunDoesNotHaveIsAnEmptyCellAndAFlagThatIsFalseSaysSo() {
        Map<String, String> cells = onlyRow(minimal("run-1").build());

        for (String empty : List.of("target", "environment", "environmentKind", "scenarioId", "procedureCodes", "lineOfBusiness", "coverageCodes",
                "delegatedVendor", "httpStatus", "expected", "note", "testerRef", "chainId", "memberId", "memberToken", "npi", "dateOfService",
                "correlation.claimId", "findings", "unrecordedInputs", "nextAction", "attachmentMode")) {
            assertThat(cells.get(empty)).as(empty).isEmpty();
        }
        // Never the word null, which a reader would take for a value.
        assertThat(RunCsv.text(List.of(minimal("run-1").build()))).doesNotContain("null");
        assertThat(cells.get("slow")).isEqualTo("false");
        // Nothing measured is an empty cell, never 0.
        assertThat(cells.get("elapsedMillis")).isEmpty();
        assertThat(cells.get("result")).isEqualTo("unverified");
        assertThat(cells.get("errorType")).isEqualTo("NONE");
    }

    @Test
    void aCellWithACommaAQuoteOrALineBreakIsQuotedAndItsQuotesAreDoubled() {
        assertThat(RunCsv.cell("plain")).isEqualTo("plain");
        assertThat(RunCsv.cell("two words")).isEqualTo("two words");
        assertThat(RunCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(RunCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(RunCsv.cell("line one\nline two")).isEqualTo("\"line one\nline two\"");
        assertThat(RunCsv.cell("line one\r\nline two")).isEqualTo("\"line one\r\nline two\"");
        assertThat(RunCsv.cell("carriage\rreturn")).isEqualTo("\"carriage\rreturn\"");
        assertThat(RunCsv.cell("")).isEmpty();
        assertThat(RunCsv.cell(null)).isEmpty();

        // And through a whole export: the note comes back exactly, and it is still one row of the same width.
        String note = "first, then \"second\"\r\nthird line, with a comma";
        Map<String, String> cells = onlyRow(minimal("run-1").note(note).testerRef("QA, team 2").lineOfBusiness("Medicaid/CHIP (MassHealth)")
                .procedureCodes(List.of("95810", "E0601")).build());
        assertThat(cells.get("note")).isEqualTo(note);
        assertThat(cells.get("testerRef")).isEqualTo("QA, team 2");
        assertThat(cells.get("lineOfBusiness")).isEqualTo("Medicaid/CHIP (MassHealth)");
        assertThat(cells.get("procedureCodes")).isEqualTo("95810; E0601");
        assertThat(cells.get("runId")).isEqualTo("run-1");
    }

    @Test
    void aCellThatASpreadsheetWouldRunAsAFormulaIsWrittenAsText() {
        // A single quote in front: the spreadsheet shows the text and calculates nothing.
        assertThat(RunCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(RunCsv.cell("+1")).isEqualTo("'+1");
        assertThat(RunCsv.cell("-1")).isEqualTo("'-1");
        assertThat(RunCsv.cell("@SUM(A1:A9)")).isEqualTo("'@SUM(A1:A9)");
        assertThat(RunCsv.cell("=cmd|' /C calc'!A0")).isEqualTo("'=cmd|' /C calc'!A0");
        assertThat(RunCsv.cell("=HYPERLINK(\"http://example.invalid\",\"open\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://example.invalid\"\",\"\"open\"\")\"");
        // White space in front does not hide it: some spreadsheet programs discard it first.
        assertThat(RunCsv.cell(" =1+1")).isEqualTo("' =1+1");
        assertThat(RunCsv.cell("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(RunCsv.cell("\r\n@x")).isEqualTo("\"'\r\n@x\"");
        // What does not start that way is left exactly as it is.
        for (String untouched : List.of("a=b", "pa-needed=no-auth", "1-2", "QA-17", "'already quoted", "2026-03-10T11:00:00-04:00", "x@example.invalid",
                "95810")) {
            assertThat(RunCsv.cell(untouched)).isEqualTo(untouched);
        }
    }

    @Test
    void everyCellIsMadeSafeAndNotOnlyTheFreeTextOnes() {
        // A note and a tester reference are typed by a tester. A request name comes from a vendor's
        // collection, an environment name from an upload, a member ID from the member.
        Map<String, String> cells = onlyRow(minimal("run-1").note("=1+1").testerRef("@QA").expected("+NO_AUTH_REQUIRED")
                .targetSource("- submit (error case)").environment("=Sandbox", EnvironmentKind.SANDBOX).memberId("-A1234").caseId("@case")
                .lineOfBusiness("+Medicare").correlation(Map.of(RunRecord.CORR_CLAIM_ID, "-claim")).build());

        assertThat(cells.get("note")).isEqualTo("'=1+1");
        assertThat(cells.get("testerRef")).isEqualTo("'@QA");
        assertThat(cells.get("expected")).isEqualTo("'+NO_AUTH_REQUIRED");
        assertThat(cells.get("targetSource")).isEqualTo("'- submit (error case)");
        assertThat(cells.get("environment")).isEqualTo("'=Sandbox");
        assertThat(cells.get("memberId")).isEqualTo("'-A1234");
        assertThat(cells.get("caseId")).isEqualTo("'@case");
        assertThat(cells.get("lineOfBusiness")).isEqualTo("'+Medicare");
        // A claim id is one identifier token, so of the four signs only a leading hyphen can reach it.
        assertThat(cells.get("correlation.claimId")).isEqualTo("'-claim");
        // No cell of the file starts a formula.
        for (List<String> row : read(RunCsv.text(List.of(everyFieldSet(), minimal("run-2").note("=2+2").build())))) {
            assertThat(row).noneMatch(cell -> cell.startsWith("=") || cell.startsWith("+") || cell.startsWith("-") || cell.startsWith("@"));
        }
    }

    @Test
    void theFileStartsWithAByteOrderMarkIsUtf8AndEveryRowEndsInCrLf() {
        byte[] file = RunCsv.bytes(List.of(minimal("run-1").note("caf\u00e9, na\u00efve").build(), minimal("run-2").build()));

        assertThat(Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo(RunCsv.text(List.of(minimal("run-1").note("caf\u00e9, na\u00efve").build(), minimal("run-2").build())));
        assertThat(text).endsWith("\r\n").contains("\"caf\u00e9, na\u00efve\"");
        // One header row and two runs, in the order given.
        List<List<String>> rows = read(text);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get(1)).isEqualTo("run-1");
        assertThat(rows.get(2).get(1)).isEqualTo("run-2");
        // Outside a quoted cell a line feed is always part of a CRLF.
        assertThat(text.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    }
}
