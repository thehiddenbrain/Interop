package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuitePreview;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The preview says what will really happen: a row it counts as one that can be run runs, a row
 * this build cannot run is counted apart with the reason, and what a service would refuse before
 * sending is found here. Every problem names the column to change and quotes no member ID.
 */
class SuitePreviewTruthTest {

    private static final String NPI = "1234567893";
    private static final String HEADER = "caseId,protocol,operation,memberId,npi,codes,scenarioId,expected";

    private final SuiteTestBed bed = new SuiteTestBed();

    private static String file(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    private static SuiteRow only(SuiteDefinition definition) {
        assertThat(definition.problems()).isEmpty();
        return definition.rows().get(0);
    }

    private SuiteDefinition define(String text) {
        OutcomeCatalogue outcomes = SuiteTestBed.outcomes();
        return SuiteCases.define("cases.csv", new SuiteFileReader(new org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter())
                .read("cases.csv", text), true, outcomes, bed.runner());
    }

    /**
     * Two rows that share a case id holding the member's name (mock, where the preview looks the
     * member up). The second row's repeat is said without quoting the id, so neither row's problems
     * nor its name on screen carry the name.
     */
    @Test
    void aRepeatedCaseIdThatHoldsTheMembersNameIsNotQuotedByTheRepeat() {
        String text = "caseId,protocol,memberId,npi,codes,expected\n"
                + "row-Surname,crd,P32H-DOC-DTR," + NPI + ",95810,DOCUMENTATION_REQUIRED\n"
                + "row-Surname,crd,P32H-DOC-DTR," + NPI + ",95810,DOCUMENTATION_REQUIRED\n";
        SuiteDefinition definition = SuiteCases.define("cases.csv", new SuiteFileReader(new org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter())
                .read("cases.csv", text), true, SuiteTestBed.outcomes(), bed.runner().previewMembers(SuiteTestBed.members()));
        List<SuiteRow> rows = definition.rows();
        String refusal = "caseId: The case id holds the member's last name. The ledger keeps it as written and may not hold member data, "
                + "so leave it out. Nothing was sent.";
        assertThat(rows.get(0).problems()).containsExactly(refusal);
        assertThat(rows.get(1).problems()).containsExactly("This case id is already used by case 1 of the file. Each case needs its own id, "
                + "because results are matched to cases by it.", refusal);
        assertThat(rows.stream().flatMap(row -> row.problems().stream()).toList().toString() + rows.stream().map(SuiteRow::caseId).toList())
                .doesNotContain("Surname");
    }

    /**
     * A row with a problem of another kind is still held to the check made once the member is looked
     * up (mock), so the preview, and the run, which reports a row by the preview's problems, list
     * every problem of the row at once, and the tester does not learn of the name only after
     * correcting the rest. An expectation refused for the name is not quoted by a problem with its terms.
     */
    @Test
    void aRowWithAnotherProblemIsStillCheckedForTheMembersNameSoEveryProblemIsListedAtOnce() {
        org.p32h.interop.epa.pave.service.MemberLookup lookup = id -> new org.p32h.interop.epa.pave.model.MemberLookupResult(
                org.p32h.interop.epa.pave.model.LookupStatus.FOUND, org.p32h.interop.epa.pave.model.MemberIds.forms(id),
                org.p32h.interop.epa.pave.model.MemberDetail.builder().memberId(id).firstName("Devon").lastName(SuiteTestBed.SURNAME).build(),
                List.of(), List.of(), List.of(), null, null, "message");
        String text = "caseId,protocol,memberId,npi,codes,expected,note\n"
                + "a,crd,P32H-DOC-DTR," + NPI + ",95810,NOT_AN_OUTCOME,asked Devon\n"
                + "b,chain,P32H-DOC-DTR," + NPI + ",95810,crd: NOT_AN_OUTCOME,asked Devon\n"
                + "c-Surname,crd,P32H-DOC-DTR,123,95810,DOCUMENTATION_REQUIRED,\n"
                + "d,crd,P32H-DOC-DTR," + NPI + ",95810,Devon,\n";
        SuiteDefinition definition = SuiteCases.define("cases.csv", new SuiteFileReader(new org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter())
                .read("cases.csv", text), true, SuiteTestBed.outcomes(), bed.runner().previewMembers(lookup));
        String note = "note: The note holds the member's first name. The ledger keeps it as written and may not hold member data, so leave "
                + "it out. Nothing was sent.";
        List<SuiteRow> rows = definition.rows();
        assertThat(rows.get(0).problems()).hasSize(2).anySatisfy(p -> assertThat(p).contains("NOT_AN_OUTCOME")).contains(note);
        assertThat(rows.get(1).problems()).hasSize(2).anySatisfy(p -> assertThat(p).contains("NOT_AN_OUTCOME")).contains(note);
        assertThat(rows.get(2).problems()).hasSize(2).anySatisfy(p -> assertThat(p).startsWith("The NPI is not valid"))
                .contains("caseId: The case id holds the member's last name. The ledger keeps it as written and may not hold member data, "
                        + "so leave it out. Nothing was sent.");
        assertThat(rows.get(2).caseId()).isEqualTo("case-3 [id not shown]");
        assertThat(rows.get(3).problems()).containsExactly("expected: The expectation holds the member's first name. The ledger keeps it "
                + "as written and may not hold member data, so leave it out. Nothing was sent.");
        assertThat(rows).allSatisfy(row -> assertThat(row.spec()).isNull());
        assertThat(rows.stream().flatMap(row -> row.problems().stream()).toList().toString() + rows.stream().map(SuiteRow::caseId).toList())
                .doesNotContain("Devon").doesNotContain("Surname");
    }

    @Test
    void thePreviewCountsOnlyWhatWillRunAndWhatItCountsIsWhatHappens() {
        String text = file(
                "ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,",
                "nocodes,crd,,P32H-NO-AUTH," + NPI + ",,,",
                "nonpi,crd,,P32H-NO-AUTH,,95810,,",
                "scen-bad,crd,,P32H-NO-AUTH," + NPI + ",95810,crd-99-nothing,",
                "chain-scen,chain,,P32H-PA-REQ," + NPI + ",95810,crd-01-pa-required,",
                "chain-nonpi,chain,,P32H-PA-REQ,,95810,,",
                "sub,,SUBSCRIPTION_GET,P32H-NO-AUTH," + NPI + ",95810,,",
                "dtr-next,,DTR_NEXT_QUESTION,P32H-NO-AUTH," + NPI + ",95810,,",
                "pas,pas,,P32H-PA-REQ," + NPI + ",95810,,");
        SuitePreview preview = bed.service().preview("cases.csv", text);
        assertThat(preview.headline()).isEqualTo("9 cases: 2 can be run, 2 cannot be run in this build and will be reported as not "
                + "runnable, and 5 have a problem and will be reported as not valid.");
        assertThat(preview.valid()).isEqualTo(2);
        assertThat(preview.cannotRunCount()).isEqualTo(2);
        assertThat(preview.cannotRun()).extracting(SuitePreview.RowProblem::caseId).containsExactly("sub", "dtr-next");
        assertThat(preview.cannotRun().get(1).problems()).singleElement().asString()
                .startsWith("DTR next question answers a questionnaire, and the answers are a person's");
        assertThat(preview.rowProblems()).extracting(SuitePreview.RowProblem::caseId)
                .containsExactly("nocodes", "nonpi", "scen-bad", "chain-scen", "chain-nonpi");
        Map<String, List<String>> problems = new java.util.LinkedHashMap<>();
        preview.rowProblems().forEach(p -> problems.put(p.caseId(), p.problems()));
        assertThat(problems.get("nocodes")).containsExactly("procedureCodes: Choose at least one procedure code.");
        assertThat(problems.get("nonpi")).containsExactly("npi: Enter the ordering provider's NPI.");
        assertThat(problems.get("scen-bad")).containsExactly("scenarioId: There is no CRD scenario with that id.");
        assertThat(problems.get("chain-scen")).singleElement().asString().startsWith("scenarioId: A chain cannot choose a scenario.");
        assertThat(problems.get("chain-nonpi")).containsExactly("npi: A chain starts with CRD, which needs the ordering provider's NPI.");

        SuiteRunView run = bed.service().start("cases.csv", text, null, null, null);
        Map<String, SuiteCaseResult> rows = new java.util.LinkedHashMap<>();
        run.results().forEach(r -> rows.put(r.caseId(), r));
        assertThat(rows.get("ok").status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(rows.get("pas").status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(rows.get("sub").status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        assertThat(rows.get("sub").reason()).isEqualTo(preview.cannotRun().get(0).problems().get(0));
        assertThat(rows.get("nonpi").status()).isEqualTo(CaseStatus.INVALID);
        assertThat(rows.get("nonpi").reason()).isEqualTo("npi: Enter the ordering provider's NPI.");
        assertThat(run.counts().notRunnable()).isEqualTo(2);
        assertThat(run.counts().invalid()).isEqualTo(5);
        // Exactly the two rows the preview counted went out.
        assertThat(bed.ledger.records()).filteredOn(r -> RunRecord.TYPE_RUN.equals(r.recordType())).extracting(RunRecord::caseId)
                .containsOnly("ok", "pas");
    }

    @Test
    void theNoteAboutWritesCountsOnlyWritesThatWillRunAndSaysWhatHappensToThem() {
        bed.gateway.environment("Onyx Production", EnvironmentKind.PROD);
        SuitePreview preview = bed.service().preview("cases.csv", file("pas,pas,,P32H-PA-REQ,,95810,,", "ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,"));
        // The one PAS row cannot be sent at all for want of an NPI, so it is not a write that will be blocked.
        assertThat(preview.writes()).isZero();
        assertThat(preview.writeNote()).isNull();
    }

    /** A name of a million characters made this one sentence a megabyte long. */
    @Test
    void theNoteAboutWritesOnLiveQuotesALongEnvironmentNameByItsStartAndItsLength() {
        bed.mode = "live";
        String name = "S".repeat(1_000_000);
        bed.gateway.environment(name, EnvironmentKind.SANDBOX);
        SuitePreview preview = bed.service().preview("cases.csv", file("pas,pas,,P32H-PA-REQ," + NPI + ",95810,,"));
        assertThat(preview.writeNote()).isEqualTo("1 case can create or change something on the far side in " + "S".repeat(60)
                + "... (a name of 1000000 characters).");
        bed.gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        assertThat(bed.service().preview("cases.csv", file("pas,pas,,P32H-PA-REQ," + NPI + ",95810,,")).writeNote())
                .isEqualTo("1 case can create or change something on the far side in Onyx Sandbox.");
    }

    @Test
    void anExpectationThatCanNeverHoldIsRefusedBeforeAnythingIsSent() {
        assertThat(only(define(file("e3,crd,,P32H-NO-AUTH," + NPI + ",95810,,AUTH_REQUIRED;NO_AUTH_REQUIRED"))).problems())
                .containsExactly("The expectation names more than one outcome: AUTH_REQUIRED and NO_AUTH_REQUIRED. A run has exactly one "
                        + "outcome and every term must hold, so this could never pass. Keep the one you expect.");
        assertThat(only(define(file("e9,chain,,P32H-PA-REQ," + NPI + ",95810,,crd: AUTH_REQUIRED; crd: NO_AUTH_REQUIRED"))).problems())
                .singleElement().asString().startsWith("For the CRD step: The expectation names more than one outcome");
        assertThat(only(define(file("e7,crd,,P32H-NO-AUTH," + NPI + ",95810,,;"))).problems()).containsExactly(OutcomeCatalogue.NO_TERMS);
        // An outcome and a code assertion together are one outcome, and both can hold.
        assertThat(only(define(file("ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,NO_AUTH_REQUIRED; pa-needed=no-auth"))).runnable()).isTrue();
        // A chain's DTR and PAS parts accept only what a chain can produce.
        assertThat(only(define(file("i14,chain,,P32H-DOC-DTR," + NPI + ",95810,,dtr: RESPONSE_COMPLETED"))).problems()).singleElement()
                .asString().startsWith("For the DTR step: RESPONSE_COMPLETED comes from answering a questionnaire");
        assertThat(only(define(file("i15,chain,,P32H-PA-REQ," + NPI + ",95810,,pas: AUTHORIZATION_NOT_FOUND"))).problems()).singleElement()
                .asString().startsWith("For the PAS step: AUTHORIZATION_NOT_FOUND comes from an inquiry");
        // A comma where a semicolon belongs gets the hint.
        assertThat(only(define(file("i02,chain,,P32H-PA-REQ," + NPI + ",95810,,\"crd: AUTH_REQUIRED, pas: APPROVED\""))).problems())
                .anyMatch(p -> p.contains("separate the terms of a chain's expectation with a semicolon"));
    }

    @Test
    void aRowOfTheWrongShapeIsReportedForItsShapeAlone() {
        SuiteDefinition definition = define(file("c1,crd,,P32H-PA-REQ," + NPI + ",97110,,AUTH_REQUIRED,extra"));
        assertThat(only(definition).problems()).singleElement().asString().startsWith("The row has 9 cells and the first row names 8 columns.");
        SuiteDefinition json = SuiteCases.define("cases.json", new SuiteFileReader(new org.p32h.interop.epa.pave.service.codelist.CsvRowsAdapter())
                .read("cases.json", "[\"not an object\"]"), true, SuiteTestBed.outcomes(), bed.runner());
        assertThat(json.rows().get(0).problems()).containsExactly("The case is not a JSON object.");
    }

    @Test
    void aMisspeltExpectationIsReportedWithAMisspeltProtocolSoOneFixShowsEverything() {
        List<String> problems = only(define(file("multi,crdd,,P32H-NO-AUTH," + NPI + ",95810,,AUTH_REQIURED"))).problems();
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).isEqualTo("The protocol must be crd, dtr, pas or chain.");
        assertThat(problems.get(1)).startsWith("The expectation is not an outcome or a code assertion of crd, dtr or pas");
        // A well formed expectation is not called into question while the protocol is wrong.
        assertThat(only(define(file("multi,crdd,,P32H-NO-AUTH," + NPI + ",95810,,AUTH_REQUIRED"))).problems()).hasSize(1);
    }

    @Test
    void wordsAndSignsBetweenCodesAreRefusedAndTheDocumentedSeparatorsAreNot() {
        for (String cell : List.of("97110 and 97112", "97110 & 97112", "97110/97112", "\"97110, (97112)\"")) {
            assertThat(only(define(file("c,crd,,P32H-NO-AUTH," + NPI + "," + cell + ",,"))).problems()).as(cell).singleElement().asString()
                    .startsWith("The procedureCodes cell holds something that is not a procedure code");
        }
        SuiteRow fine = only(define(file("c,crd,,P32H-NO-AUTH," + NPI + ",\"97110, 97112|E0601;J1234 0001U\",,")));
        assertThat(fine.spec().procedureCodes()).containsExactly("97110", "97112", "E0601", "J1234", "0001U");
    }

    @Test
    void aCorrelationColumnTheRowDoesNotUseIsRefused() {
        String header = "caseId,protocol,operation,memberId,npi,codes,claimId,subscriptionId";
        assertThat(only(define(header + "\no13,pas,,P32H-PA-REQ," + NPI + ",95810,claim-S999,")).problems()).singleElement().asString()
                .startsWith("The claimId column is used by a PAS inquiry only. This row runs PAS submit");
        assertThat(only(define(header + "\no15,crd,,P32H-NO-AUTH," + NPI + ",95810,claim-1,")).problems()).singleElement().asString()
                .startsWith("The claimId column is used by a PAS inquiry only.");
        assertThat(only(define(header + "\no08,,PAS_INQUIRE,P32H-PA-REQ," + NPI + ",95810,claim-1,sub-1")).problems())
                .containsExactly("The subscriptionId column is not used by any row a suite can run. Leave the cell empty.");
        assertThat(only(define(header + "\no09,,PAS_INQUIRE,P32H-PA-REQ," + NPI + ",95810,claim-1,")).runnable()).isTrue();
    }

    @Test
    void aFileSavedWithAnotherSeparatorIsSaidToBeThatAndNoCellIsQuoted() {
        SuiteDefinition semicolons = define("caseId;protocol;memberId;expected\nc1;crd;S123456789;AUTH_REQUIRED");
        assertThat(semicolons.problems()).containsExactly("The file looks separated by semicolons, not by commas: its first row is one cell. "
                + "In a spreadsheet, save it as \"CSV UTF-8 (Comma delimited)\" and choose it again.");
        assertThat(semicolons.columns()).isEmpty();
        SuiteDefinition tabs = define("caseId\tprotocol\tmemberId\nc1\tcrd\tS123456789");
        assertThat(tabs.problems()).singleElement().asString().startsWith("The file looks separated by tabs");
        assertThat(String.join(" ", tabs.problems())).doesNotContain("S123456789");
    }

    @Test
    void aFirstRowIsAHeaderOnlyWhenItReadsAsOneAndOtherwiseNoCellOfItIsRepeated() {
        for (String first : List.of("#S123456789,Zzlastname,1961-07-23", "S123456789,crd,note,Zzlastname,1961-07-23")) {
            SuiteDefinition definition = define(first + "\nS223456789,crd,x,y,z");
            assertThat(definition.problems()).as(first).singleElement().asString().startsWith("The file has no header row");
            assertThat(definition.columns()).as(first).isEmpty();
            assertThat(String.join(" ", definition.problems())).doesNotContain("S123456789").doesNotContain("Zzlastname")
                    .doesNotContain("1961-07-23");
        }
        // A header with one column misspelt is still a header, and the misspelt name is reported as a column.
        SuiteDefinition misspelt = define("caseId,protocol,memberId,expcted\nc1,crd,S123456789,AUTH_REQUIRED");
        assertThat(misspelt.problems()).singleElement().asString().startsWith("This tool does not know the column \"expcted\"");
    }

    @Test
    void aRowWhoseFirstCellStartsWithAHashIsACommentAndIsCountedNotRun() {
        String text = "caseId,protocol,memberId,npi,codes\n#comment,crd,P32H-PA-REQ," + NPI + ",95810\n# a comment row,,,,\n"
                + "c1,crd,P32H-NO-AUTH," + NPI + ",95810";
        SuitePreview preview = bed.service().preview("cases.csv", text);
        assertThat(preview.skipped()).isEqualTo(2);
        assertThat(preview.total()).isEqualTo(1);
        assertThat(preview.headline()).isEqualTo("1 case, and every one can be run.");
        bed.service().start("cases.csv", text, null, null, null);
        assertThat(bed.ledger.records()).extracting(RunRecord::caseId).containsExactly("c1");
    }

    @Test
    void aSuiteWhoseTesterReferenceIsTooLongIsNotStartedAndNothingIsSent() {
        String ref = "R".repeat(RunRecord.LONGEST_TESTER_REF + 1);
        assertThatThrownBy(() -> bed.service().start("cases.csv", file("ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,"), ref, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("The suite was not started. Nothing was sent.")
                .satisfies(e -> assertThat(((ValidationException) e).fieldErrors()).extracting(FieldError::field).containsExactly("testerRef"));
        assertThat(bed.ledger.records()).isEmpty();
        assertThat(bed.gateway.intents()).isEmpty();
        // At the limit it is accepted.
        bed.service().start("cases.csv", file("ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,"), "R".repeat(RunRecord.LONGEST_TESTER_REF), null, null);
        assertThat(bed.ledger.records()).hasSize(1);
    }

    @Test
    void aCaseHoldsItsMemberIdOnlyUntilItHasRun() throws ReflectiveOperationException {
        bed.hold = true;
        SuiteService service = bed.service();
        SuiteRunView run = service.start("cases.csv", file("ok,crd,,P32H-NO-AUTH," + NPI + ",95810,,", "two,crd,,P32H-NO-AUTH," + NPI + ",95810,,"),
                null, null, null);
        // Before the cases run, each holds its case, member ID included. This is what the check below would see if it were kept.
        assertThat(specs(service, run.suiteRunId())).allMatch(java.util.Objects::nonNull);
        bed.queued.poll().run();
        assertThat(specs(service, run.suiteRunId())).hasSize(2).allMatch(java.util.Objects::isNull);
    }

    @Test
    void aCaseThatHasRunDropsItsMemberIdWhileTheSuiteIsStillRunning() throws ReflectiveOperationException {
        // Read while the second case is being run, before the run ends and drops every case at once.
        List<List<Object>> seen = new java.util.ArrayList<>();
        SuiteService[] service = new SuiteService[1];
        String[] runId = new String[1];
        bed.hold = true;
        bed.wrap = real -> spec -> {
            if ("two".equals(spec.caseId())) {
                try {
                    seen.add(specs(service[0], runId[0]));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return real.run(spec);
        };
        service[0] = bed.service();
        runId[0] = service[0].start("cases.csv", file("one,crd,,P32H-NO-AUTH," + NPI + ",95810,,", "two,crd,,P32H-NO-AUTH," + NPI + ",95810,,"),
                null, null, null).suiteRunId();
        bed.queued.poll().run();
        assertThat(seen).singleElement().satisfies(specs -> {
            assertThat(specs.get(0)).as("the case that has run").isNull();
            assertThat(specs.get(1)).as("the case being run").isNotNull();
        });
    }

    /** The case each entry of a run holds, read the only way it can be: it is never handed out. */
    private static List<Object> specs(SuiteService service, String suiteRunId) throws ReflectiveOperationException {
        Field runsField = SuiteService.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        Object run = ((Map<?, ?>) runsField.get(service)).get(suiteRunId);
        Field entriesField = run.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<Object> specs = new java.util.ArrayList<>();
        for (Object entry : (List<?>) entriesField.get(run)) {
            Field spec = entry.getClass().getDeclaredField("spec");
            spec.setAccessible(true);
            specs.add(spec.get(entry));
        }
        return specs;
    }
}
