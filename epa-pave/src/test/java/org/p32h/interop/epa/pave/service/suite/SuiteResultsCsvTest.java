package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.suite.SuiteTestBed.suite;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;

class SuiteResultsCsvTest {

    private final SuiteTestBed bed = new SuiteTestBed();

    @Test
    void theExportHasOneRowPerCaseTiedToItsLedgerRecord() {
        bed.gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        SuiteService service = bed.service();
        SuiteRunView run = service.start("sleep.csv", suite(
                "TC-1,crd,P32H-NO-AUTH,1234567893,95810,NO_AUTH_REQUIRED",
                "TC-2,crd,P32H-EMPTY,1234567893,95810,NO_AUTH_REQUIRED",
                "TC-3,crd,P32H-NO-AUTH,12,95810,"), "QA-17", null, null);
        String csv = service.resultsCsv(run.suiteRunId());
        List<String> lines = List.of(csv.split("\r\n", -1));
        assertThat(lines.get(0)).isEqualTo("suiteRunId,suiteName,row,caseId,runs,status,expected,outcome,outcomeLabel,result,"
                + "elapsedMillis,slow,httpStatus,findings,nextAction,reason,notes,notRecorded,previousOutcome,previousResult,changed,"
                + "runId,runIds,chainId,environment,environmentKind,mode,target,startedAt,finishedAt");
        // Header, three cases, and the empty text after the last line ending.
        assertThat(lines).hasSize(5);
        assertThat(lines.get(4)).isEmpty();
        String runId1 = run.results().get(0).runId();
        assertThat(lines.get(1)).startsWith(run.suiteRunId() + ",sleep.csv,1,TC-1,CRD order-sign,Passed: the outcome is what the row expected.,"
                + "NO_AUTH_REQUIRED,NO_AUTH_REQUIRED,Prior authorization is not required,pass,");
        assertThat(lines.get(1)).contains("," + runId1 + "," + runId1 + ",").contains(",Onyx Sandbox,SANDBOX,mock,onyx,");
        // The two outcomes a tester must never confuse are two different rows of the export.
        assertThat(lines.get(2)).contains(",NO_AUTH_REQUIRED,NO_DETERMINATION,The service returned no determination,fail,");
        // A row that was not sent has no outcome, no result and no run id, and says why.
        assertThat(lines.get(3)).contains(",3,TC-3,CRD order-sign,Not sent: the row is not valid.,,,,,,no,,,,\"The NPI is not valid");
        assertThat(csv).doesNotContain("P32H-NO-AUTH").doesNotContain("P32H-EMPTY").doesNotContain(SuiteTestBed.SURNAME);
    }

    @Test
    void aCellIsQuotedWhenItMustBeAndNeverRunAsAFormula() {
        assertThat(SuiteResultsCsv.cell(null)).isEmpty();
        assertThat(SuiteResultsCsv.cell("")).isEmpty();
        assertThat(SuiteResultsCsv.cell("plain text")).isEqualTo("plain text");
        assertThat(SuiteResultsCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(SuiteResultsCsv.cell("say \"no\"")).isEqualTo("\"say \"\"no\"\"\"");
        assertThat(SuiteResultsCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(SuiteResultsCsv.cell("two\rlines")).isEqualTo("\"two\rlines\"");
        // A spreadsheet would run these. The apostrophe makes each one text.
        assertThat(SuiteResultsCsv.cell("=HYPERLINK(\"http://example.invalid\")")).isEqualTo("\"'=HYPERLINK(\"\"http://example.invalid\"\")\"");
        assertThat(SuiteResultsCsv.cell("+1")).isEqualTo("'+1");
        assertThat(SuiteResultsCsv.cell("-1")).isEqualTo("'-1");
        assertThat(SuiteResultsCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SuiteResultsCsv.cell("\t=1")).isEqualTo("'\t=1");
        assertThat(SuiteResultsCsv.cell("\r=1")).isEqualTo("\"'\r=1\"");
        // A sign that is not first is ordinary text.
        assertThat(SuiteResultsCsv.cell("pa-needed=no-auth")).isEqualTo("pa-needed=no-auth");
    }

    @Test
    void freeTextFromTheSuiteFileCannotBecomeAFormulaInTheExport() {
        SuiteService service = bed.service();
        // An expectation is free text until the service reads it. This one is refused, and the refusal quotes it.
        SuiteRunView run = service.start("=cmd.csv", "caseId,protocol,memberId,npi,codes,expected\n"
                + "TC-1,pas,P32H-NO-AUTH,1234567893,95810,=1+1", null, null, null);
        String csv = service.resultsCsv(run.suiteRunId());
        String row = csv.split("\r\n")[1];
        assertThat(row).contains(",'=cmd.csv,").contains(",'=1+1,");
        for (String cell : row.split(",")) {
            assertThat(cell).doesNotStartWith("=").doesNotStartWith("+").doesNotStartWith("@");
        }
    }
}
