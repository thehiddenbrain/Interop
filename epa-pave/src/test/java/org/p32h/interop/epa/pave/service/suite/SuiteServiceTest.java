package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.suite.SuiteTestBed.suite;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuiteCounts;
import org.p32h.interop.epa.pave.model.suite.SuitePreview;
import org.p32h.interop.epa.pave.model.suite.SuiteRowState;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.model.suite.SuiteState;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.BusyException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class SuiteServiceTest {

    private static final String NPI = "1234567893";
    private final SuiteTestBed bed = new SuiteTestBed();

    private static String row(String caseId, String memberId, String expected) {
        return caseId + ",crd," + memberId + "," + NPI + ",95810," + expected;
    }

    @Test
    void everyCaseRunsInFileOrderAndWritesItsOwnLedgerRecord() {
        SuiteRunView run = bed.service().start("sleep-studies.csv", suite(
                row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                row("TC-2", "P32H-PA-REQ", "NO_AUTH_REQUIRED"),
                row("TC-3", "P32H-EMPTY", "")), "QA-17", null, null);

        assertThat(run.state()).isEqualTo(SuiteState.COMPLETED);
        assertThat(run.suiteRunId()).startsWith("suite-");
        assertThat(run.name()).isEqualTo("sleep-studies.csv");
        assertThat(run.format()).isEqualTo("CSV");
        assertThat(run.headline()).isEqualTo("Finished: 3 cases. 1 passed, 1 failed, 1 unverified.");
        assertThat(run.stopReason()).isNull();
        assertThat(run.mode()).isEqualTo("mock");
        assertThat(run.target()).isEqualTo("onyx");
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.finishedAt()).isNotNull();
        // Every case here has something for a person to look at. The test code list holds no row for 95810,
        // so nothing was compared, and the empty answer states no coverage information, which CRD 2.0.1 does not require.
        assertThat(run.counts()).isEqualTo(new SuiteCounts(3, 3, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0));
        // Each case gives the member ID alone, so each request went without what CRD 2.0.1 requires of a Patient.
        assertThat(run.findingCounts()).containsExactly(Map.entry("CODE_LIST_NOT_COMPARED", 2), Map.entry("NOT_STATED", 1),
                Map.entry("REQUEST_INCOMPLETE", 3));

        assertThat(run.results()).extracting(SuiteCaseResult::caseId).containsExactly("TC-1", "TC-2", "TC-3");
        SuiteCaseResult passed = run.results().get(0);
        assertThat(passed.row()).isEqualTo(1);
        assertThat(passed.what()).isEqualTo("CRD order-sign");
        assertThat(passed.state()).isEqualTo(SuiteRowState.DONE);
        assertThat(passed.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(passed.statusLine()).isEqualTo("Passed: the outcome is what the row expected.");
        assertThat(passed.expected()).isEqualTo("NO_AUTH_REQUIRED");
        // The authorization outcome and the test result are two components, each stated on its own.
        assertThat(passed.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(passed.outcomeLabel()).isEqualTo("Prior authorization is not required");
        assertThat(passed.tone()).isEqualTo(Tone.CLEAR);
        assertThat(passed.result()).isEqualTo(TestResult.PASS);
        assertThat(passed.httpStatus()).isEqualTo(200);
        assertThat(passed.elapsedMillis()).isNotNull();

        SuiteCaseResult failed = run.results().get(1);
        assertThat(failed.outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(failed.outcomeLabel()).isEqualTo("Prior authorization is required");
        assertThat(failed.result()).isEqualTo(TestResult.FAIL);
        assertThat(failed.statusLine()).isEqualTo("Failed: the outcome is not what the row expected.");

        // "No determination" is its own outcome. With nothing declared the row is unverified, never passed.
        SuiteCaseResult unverified = run.results().get(2);
        assertThat(unverified.outcome()).isEqualTo("NO_DETERMINATION");
        assertThat(unverified.outcomeLabel()).isEqualTo("The service returned no determination");
        assertThat(unverified.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(unverified.statusLine()).isEqualTo("Unverified: the row declared no expected outcome.");
        assertThat(unverified.expected()).isNull();

        // One ledger record per case, each carrying the suite run id and its case id, and each row points at its record.
        List<RunRecord> records = bed.ledger.query(RunQuery.forSuite(run.suiteRunId()));
        assertThat(records).hasSize(3);
        assertThat(bed.ledger.records()).extracting(RunRecord::caseId).containsExactly("TC-1", "TC-2", "TC-3");
        assertThat(bed.ledger.records()).extracting(RunRecord::runId)
                .containsExactly(run.results().get(0).runId(), run.results().get(1).runId(), run.results().get(2).runId());
        assertThat(bed.ledger.records()).allSatisfy(record -> {
            assertThat(record.suiteRunId()).isEqualTo(run.suiteRunId());
            // The reference given for the suite is recorded on every case that gave none of its own.
            assertThat(record.testerRef()).isEqualTo("QA-17");
        });
        assertThat(run.results().get(0).runIds()).containsExactly(run.results().get(0).runId());
    }

    @Test
    void findingsAreCountedByKindAndAreNotFailures() {
        // The code list says 95811 needs prior authorization for this line of business, and the service says it does not.
        bed.codeList.row("95811", AuthRequirement.REQUIRED, "Medicare", null);
        SuiteRunView run = bed.service().start("cases.csv", suite(
                "TC-1,crd,P32H-NO-AUTH," + NPI + ",95811,NO_AUTH_REQUIRED",
                "TC-2,crd,P32H-NO-AUTH," + NPI + ",95811,NO_AUTH_REQUIRED",
                row("TC-3", "P32H-NO-AUTH", "NO_AUTH_REQUIRED")), null, null, null);
        // REQUEST_INCOMPLETE: the case gives the member ID alone, and CRD 2.0.1 requires more of a Patient.
        assertThat(run.results().get(0).findings()).containsExactly("CODE_LIST_DISAGREES_ON_AUTH", "REQUEST_INCOMPLETE");
        // The case still passed: it got the outcome it declared. The disagreement is for a person to look at.
        assertThat(run.results().get(0).result()).isEqualTo(TestResult.PASS);
        assertThat(run.findingCounts()).containsExactly(Map.entry("CODE_LIST_DISAGREES_ON_AUTH", 2), Map.entry("CODE_LIST_NOT_COMPARED", 1),
                Map.entry("REQUEST_INCOMPLETE", 3));
        assertThat(run.counts().withFindings()).isEqualTo(3);
        assertThat(run.counts().passed()).isEqualTo(3);
        // The list has no row for 95810, which is said, so a row with no disagreement is not read as agreement.
        assertThat(run.results().get(2).findings()).containsExactly("CODE_LIST_NOT_COMPARED", "REQUEST_INCOMPLETE");
        // The ledger line of the case carries the same kinds.
        assertThat(bed.ledger.records().get(0).findings()).containsExactly("CODE_LIST_DISAGREES_ON_AUTH", "REQUEST_INCOMPLETE");
    }

    @Test
    void noAuthAndNoDeterminationAreTwoDifferentRowsOfTheTable() {
        SuiteRunView run = bed.service().start("cases.csv", suite(
                row("TC-NO-AUTH", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                row("TC-EMPTY", "P32H-EMPTY", "NO_AUTH_REQUIRED")), null, null, null);
        SuiteCaseResult noAuth = run.results().get(0);
        SuiteCaseResult empty = run.results().get(1);
        assertThat(noAuth.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(noAuth.result()).isEqualTo(TestResult.PASS);
        assertThat(empty.outcome()).isEqualTo("NO_DETERMINATION");
        assertThat(empty.result()).isEqualTo(TestResult.FAIL);
        assertThat(noAuth.outcomeLabel()).isNotEqualTo(empty.outcomeLabel());
        assertThat(noAuth.tone()).isNotEqualTo(empty.tone());
    }

    @Test
    void theViewNeverHoldsAMemberIdOrAnythingLookedUpForTheMember() {
        SuiteService service = bed.service();
        String file = suite(row("TC-1", "S123456789", ""), row("TC-2", "S223456789", ""), "TC-3,crd,S323456789,12,95810,");
        SuitePreview preview = service.preview("cases.csv", file);
        SuiteRunView run = service.start("cases.csv", file, null, null, null);
        String everything = JsonSafe.mapper().writeValueAsString(preview) + JsonSafe.mapper().writeValueAsString(run)
                + JsonSafe.mapper().writeValueAsString(service.list()) + service.resultsCsv(run.suiteRunId()) + preview + run;
        assertThat(everything).doesNotContain("S123456789").doesNotContain("S223456789").doesNotContain("S323456789")
                .doesNotContain(SuiteTestBed.SURNAME).doesNotContain("1970-01-31");
        // The request did carry the member ID: the row points to the ledger, which shows it as the tracking policy allows.
        assertThat(bed.ledger.records()).hasSize(2);
    }

    @Test
    void aRowWithAProblemIsReportedAsNotValidAndTheRestStillRun() {
        SuiteRunView run = bed.service().start("cases.csv", suite(
                row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                "TC-2,crd,P32H-NO-AUTH,1234567890,95810,NO_AUTH_REQUIRED",
                row("TC-3", "P32H-NO-AUTH", "NO_AUTH_REQUIRED")), null, null, null);
        assertThat(run.state()).isEqualTo(SuiteState.COMPLETED);
        assertThat(run.headline()).isEqualTo("Finished: 3 cases. 2 passed, 1 not valid.");
        SuiteCaseResult invalid = run.results().get(1);
        assertThat(invalid.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(invalid.statusLine()).isEqualTo("Not sent: the row is not valid.");
        assertThat(invalid.reason()).startsWith("The NPI is not valid");
        // Not a pass, not a fail: nothing was sent, so there is no result and no outcome.
        assertThat(invalid.result()).isNull();
        assertThat(invalid.outcome()).isNull();
        assertThat(invalid.runId()).isNull();
        assertThat(invalid.elapsedMillis()).isNull();
        assertThat(bed.ledger.records()).extracting(RunRecord::caseId).containsExactly("TC-1", "TC-3");
        assertThat(run.counts().invalid()).isEqualTo(1);
        assertThat(run.counts().done()).isEqualTo(3);
        // Each row names the count it is in, and that count gives its words.
        assertThat(run.results()).extracting(SuiteCaseResult::countedIn).containsExactly(
                org.p32h.interop.epa.pave.model.suite.SuiteCountGroup.PASSED, org.p32h.interop.epa.pave.model.suite.SuiteCountGroup.INVALID,
                org.p32h.interop.epa.pave.model.suite.SuiteCountGroup.PASSED);
        assertThat(run.countGroups()).filteredOn(group -> group.group().equals("invalid")).singleElement()
                .satisfies(group -> {
                    assertThat(group.label()).isEqualTo("Not valid: nothing was sent");
                    assertThat(group.count()).isEqualTo(1);
                });
    }

    @Test
    void aProblemOnlyTheServiceCanSeeIsReportedTheSameWay() {
        // The row is well formed, and the service would refuse it before sending: no procedure code. The preview finds that
        // too, so the row is reported as not valid and counted so before anything runs.
        SuiteRunView run = bed.service().start("cases.csv", suite("TC-1,crd,P32H-NO-AUTH," + NPI + ",,", row("TC-2", "P32H-NO-AUTH", "")),
                null, null, null);
        SuiteCaseResult invalid = run.results().get(0);
        assertThat(invalid.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(invalid.reason()).isEqualTo("procedureCodes: Choose at least one procedure code.");
        assertThat(invalid.result()).isNull();
        assertThat(bed.ledger.records()).extracting(RunRecord::caseId).containsExactly("TC-2");
    }

    @Test
    void anExpectationTheServiceRefusesForTheDateOfBirthOrANameIsNotRepeatedInTheResults() {
        // Found by the last check of the ledger fixes (NF4): the service refuses such an expectation
        // once the member is looked up, and the results and their file repeated it as written.
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "1970-01-31: covered=covered"),
                row("TC-2", "P32H-NO-AUTH", SuiteTestBed.SURNAME + ": covered=covered"), row("TC-3", "P32H-NO-AUTH", "NO_AUTH_REQUIRED")),
                null, null, null);
        SuiteCaseResult birth = run.results().get(0);
        assertThat(birth.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(birth.expected()).isEqualTo("The expectation holds the member's date of birth. The ledger keeps it as written and may "
                + "not hold member data, so leave it out.");
        assertThat(run.results().get(1).expected()).startsWith("The expectation holds the member's last name.");
        assertThat(run.results().get(2).expected()).isEqualTo("NO_AUTH_REQUIRED");
        String file = SuiteResultsCsv.of(run);
        assertThat(file).doesNotContain("1970-01-31").doesNotContain(SuiteTestBed.SURNAME);
    }

    @Test
    void aWriteTheGuardrailRefusesIsCountedAsBlockedAndNeverAsFailed() {
        bed.gateway.environment("Onyx Production", EnvironmentKind.PROD);
        bed.gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment."));
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED")), null, null, null);
        SuiteCaseResult blocked = run.results().get(0);
        assertThat(blocked.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(blocked.statusLine()).isEqualTo("Blocked: the production guardrail refused this write. Nothing was sent. This is not a failure.");
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.outcomeLabel()).isEqualTo("Blocked: nothing was sent");
        assertThat(blocked.tone()).isEqualTo(Tone.BLOCKED);
        // No test result: the expectation was neither met nor missed.
        assertThat(blocked.result()).isNull();
        assertThat(blocked.elapsedMillis()).isNull();
        assertThat(blocked.reason()).contains("Nothing was sent");
        // The attempt still has its ledger line.
        assertThat(blocked.runId()).isEqualTo(bed.ledger.only().runId());
        assertThat(run.counts().blocked()).isEqualTo(1);
        assertThat(run.counts().failed()).isZero();
        assertThat(run.counts().unverified()).isZero();
        assertThat(run.headline()).isEqualTo("Finished: 1 case. 1 blocked.");
        assertThat(run.notes()).containsExactly("1 case was blocked by the production guardrail. The write that was blocked was not sent; the steps of a chain before it were, "
                + "and its row says which. "
                + "A blocked case is not a failed case.");
        assertThat(run.environment()).isEqualTo(new EnvironmentRef("Onyx Production", EnvironmentKind.PROD));
        // A suite never carries the unlock for production writes.
        assertThat(bed.gateway.lastIntent().unlock()).isNull();
    }

    @Test
    void theRunIsPinnedToTheConfigurationItStartedUnderAndStopsWhenThatChanges() {
        AtomicInteger calls = new AtomicInteger();
        bed.wrap = runner -> spec -> {
            var outcome = runner.run(spec);
            if (calls.incrementAndGet() == 2) {
                // Someone activates another environment while the suite is on its second case.
                bed.gateway.changeConfiguration();
            }
            return outcome;
        };
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", ""),
                row("TC-3", "P32H-NO-AUTH", ""), row("TC-4", "P32H-NO-AUTH", "")), null, null, null);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        // What changed is not known here, so the reason does not say where the cases left would have gone.
        assertThat(run.stopReason()).isEqualTo(SuiteService.configurationChanged()).doesNotContain("target");
        assertThat(run.stopReason()).startsWith("The configuration was changed while the suite was running. The suite stopped so that "
                + "every case of it ran under one configuration");
        assertThat(run.headline()).isEqualTo("Stopped after 2 of 4 cases. 2 unverified, 2 not run.");
        assertThat(run.results()).extracting(SuiteCaseResult::state)
                .containsExactly(SuiteRowState.DONE, SuiteRowState.DONE, SuiteRowState.NOT_RUN, SuiteRowState.NOT_RUN);
        assertThat(run.results().get(2).statusLine()).isEqualTo("Not run: the suite stopped before this case.");
        assertThat(run.results().get(2).status()).isNull();
        // Nothing was sent for the cases left, and every case that ran was sent under the pinned configuration.
        assertThat(calls.get()).isEqualTo(2);
        assertThat(bed.ledger.records()).hasSize(2);
        assertThat(bed.gateway.intents()).allSatisfy(intent -> assertThat(intent.expectedEpoch()).isEqualTo(run.epoch()));
        assertThat(run.counts().notRun()).isEqualTo(2);
        assertThat(run.counts().waiting()).isZero();
    }

    @Test
    void aStopTakesEffectAfterTheCaseTheSuiteIsOn() {
        AtomicReference<SuiteService> service = new AtomicReference<>();
        AtomicReference<String> id = new AtomicReference<>();
        bed.hold = true;
        bed.wrap = runner -> spec -> {
            // The tester presses Stop while the first case is running.
            service.get().stop(id.get());
            return runner.run(spec);
        };
        service.set(bed.service());
        SuiteRunView started = service.get().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", "")),
                null, null, null);
        id.set(started.suiteRunId());
        bed.queued.remove().run();

        SuiteRunView run = service.get().get(started.suiteRunId(), true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopReason()).isEqualTo("Stopped on request.");
        assertThat(run.results().get(0).state()).isEqualTo(SuiteRowState.DONE);
        assertThat(run.results().get(1).state()).isEqualTo(SuiteRowState.NOT_RUN);
        assertThat(bed.ledger.records()).hasSize(1);
        // Stopping a run that has ended changes nothing.
        assertThat(service.get().stop(started.suiteRunId()).stopReason()).isEqualTo("Stopped on request.");
    }

    @Test
    void aStopDuringTheLastCaseLeavesACompletedRun() {
        AtomicReference<SuiteService> service = new AtomicReference<>();
        AtomicReference<String> id = new AtomicReference<>();
        bed.hold = true;
        bed.wrap = runner -> spec -> {
            service.get().stop(id.get());
            return runner.run(spec);
        };
        service.set(bed.service());
        id.set(service.get().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, null).suiteRunId());
        bed.queued.remove().run();
        SuiteRunView run = service.get().get(id.get(), true);
        // Every case was attempted, so nothing was left out and the run is complete.
        assertThat(run.state()).isEqualTo(SuiteState.COMPLETED);
        assertThat(run.stopReason()).isNull();
    }

    @Test
    void whileASuiteRunsItsProgressCanBeReadAndASecondSuiteIsRefused() {
        bed.hold = true;
        SuiteService service = bed.service();
        SuiteRunView started = service.start("first.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", "")),
                null, null, null);
        assertThat(started.state()).isEqualTo(SuiteState.RUNNING);
        assertThat(started.headline()).isEqualTo("Running: 0 of 2 cases done.");
        assertThat(started.finishedAt()).isNull();
        assertThat(started.results()).extracting(SuiteCaseResult::state).containsOnly(SuiteRowState.PENDING);
        assertThat(started.results()).extracting(SuiteCaseResult::statusLine).containsOnly("Waiting.");
        assertThat(started.counts().waiting()).isEqualTo(2);
        assertThat(started.timing().answered()).isZero();

        assertThatThrownBy(() -> service.start("second.csv", suite(row("TC-9", "P32H-NO-AUTH", "")), null, null, null))
                .isInstanceOf(BusyException.class)
                .hasMessage("A suite is already running: first.csv, 0 of 2 cases done. This tool runs one suite at a time.");
        assertThat(bed.queued).hasSize(1);
        // A summary leaves the rows out, for a screen that only wants the progress count.
        assertThat(service.get(started.suiteRunId(), false).results()).isNull();
        assertThat(service.get(started.suiteRunId(), false).counts().total()).isEqualTo(2);

        bed.queued.remove().run();
        assertThat(service.get(started.suiteRunId(), true).state()).isEqualTo(SuiteState.COMPLETED);
        // With the first one finished, another can start.
        bed.hold = false;
        assertThat(service.start("second.csv", suite(row("TC-9", "P32H-NO-AUTH", "")), null, null, null).state())
                .isEqualTo(SuiteState.COMPLETED);
        assertThat(service.list()).extracting(SuiteRunView::name).containsExactly("second.csv", "first.csv");
        assertThat(service.list()).allSatisfy(view -> assertThat(view.results()).isNull());
    }

    @Test
    void aRunTheLedgerDidNotKeepIsStillInTheTableAndTheViewSaysSo() {
        bed.ledger.refusing("This run was NOT recorded: the ledger file could not be written.");
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED")), null, null, null);
        SuiteCaseResult row = run.results().get(0);
        // The row does not go blank because a write failed.
        assertThat(row.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(row.result()).isEqualTo(TestResult.PASS);
        assertThat(row.notRecorded()).containsExactly("This run was NOT recorded: the ledger file could not be written.");
        assertThat(run.counts().notRecorded()).isEqualTo(1);
        assertThat(run.notes()).singleElement().asString().startsWith("1 run was NOT recorded in the ledger.");
    }

    @Test
    void aSuiteCanBeComparedWithAnEarlierRunOfTheSameCases() {
        SuiteService service = bed.service();
        SuiteRunView first = service.start("cases.csv", suite(
                row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                row("TC-2", "P32H-PA-REQ", "AUTH_REQUIRED"),
                row("TC-3", "P32H-PA-REQ", "AUTH_REQUIRED")), null, null, null);
        assertThat(first.comparedWith()).isNull();
        assertThat(first.results()).allSatisfy(row -> assertThat(row.changed()).isNull());

        // The next build of the service answers TC-2 differently. TC-3 has gone from the file and TC-4 is new.
        SuiteRunView second = service.start("cases.csv", suite(
                row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                row("tc-2", "P32H-NO-AUTH", "AUTH_REQUIRED"),
                row("TC-4", "P32H-NO-AUTH", "")), null, " " + first.suiteRunId() + " ", null);
        assertThat(second.comparedWith()).isEqualTo(first.suiteRunId());
        SuiteCaseResult same = second.results().get(0);
        assertThat(same.changed()).isFalse();
        assertThat(same.previousOutcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(same.previousResult()).isEqualTo(TestResult.PASS);
        SuiteCaseResult different = second.results().get(1);
        assertThat(different.changed()).isTrue();
        assertThat(different.previousOutcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(different.previousOutcomeLabel()).isEqualTo("Prior authorization is required");
        assertThat(different.previousResult()).isEqualTo(TestResult.PASS);
        assertThat(different.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(different.result()).isEqualTo(TestResult.FAIL);
        // A case the earlier run did not have is not "changed". There is nothing to compare it with.
        assertThat(second.results().get(2).changed()).isNull();
        assertThat(second.results().get(2).previousOutcome()).isNull();
        assertThat(second.counts().changed()).isEqualTo(1);
        assertThat(second.notes()).containsExactly("1 case of the earlier run is not in this file: TC-3.");

        assertThatThrownBy(() -> service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, "suite-unknown", null))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("nothing to compare with");
        bed.withLedger = false;
        assertThatThrownBy(() -> bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, first.suiteRunId(), null))
                .isInstanceOf(BadInputException.class);
    }

    @Test
    void aRowThatIsNoLongerValidCountsAsChangedAgainstAnEarlierRunThatRanIt() {
        SuiteService service = bed.service();
        SuiteRunView first = service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "NO_AUTH_REQUIRED"),
                row("TC-2", "P32H-NO-AUTH", "")), null, null, null);
        SuiteRunView second = service.start("cases.csv", suite("TC-1,crd,P32H-NO-AUTH,12,95810,NO_AUTH_REQUIRED",
                row("TC-2", "P32H-NO-AUTH", "")), null, first.suiteRunId(), null);
        assertThat(second.results().get(0).status()).isEqualTo(CaseStatus.INVALID);
        assertThat(second.results().get(0).changed()).isTrue();
        assertThat(second.results().get(0).previousOutcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(second.results().get(1).changed()).isFalse();
    }

    @Test
    void aFileThatCannotBeRunIsRefusedBeforeAnythingIsSent() {
        SuiteService service = bed.service();
        assertThatThrownBy(() -> service.start("cases.csv", "caseId,protocol,memberId,expectd\nTC-1,crd,P32H-NO-AUTH,X", null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("The suite file cannot be run. Nothing was sent.")
                .satisfies(e -> assertThat(((ValidationException) e).fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("file");
                    assertThat(error.message()).startsWith("This tool does not know the column \"expectd\".");
                }));
        assertThatThrownBy(() -> service.start("cases.csv", suite("TC-1,crd,,,,", "TC-2,x12,S123456789,,,"), null, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("No case in this file can be run. Nothing was sent.")
                .satisfies(e -> assertThat(((ValidationException) e).fieldErrors()).extracting(error -> error.message())
                        .containsExactly("Case 1: The row has no member ID.", "Case 2: The protocol must be crd, dtr, pas or chain."));
        assertThatThrownBy(() -> service.start("cases.csv", "", null, null, null)).isInstanceOf(ValidationException.class);
        assertThat(service.list()).isEmpty();
        assertThat(bed.ledger.records()).isEmpty();
        assertThat(bed.gateway.intents()).isEmpty();
    }

    @Test
    void aSuiteStartedFromAScreenDrawnUnderAnEarlierConfigurationIsRefused() {
        SuiteService service = bed.service();
        bed.gateway.changeConfiguration();
        assertThatThrownBy(() -> service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, 0L))
                .isInstanceOf(ConfigurationChangedException.class);
        assertThat(bed.ledger.records()).isEmpty();
        assertThat(service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, 1L).epoch()).isEqualTo(1L);
    }

    @Test
    void withNoCaseRunnerTheFeatureSaysItIsUnavailable() {
        bed.withRunner = false;
        assertThatThrownBy(() -> bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, null))
                .isInstanceOf(BadInputException.class).hasMessage("Suites cannot be run here: this copy of the tool was started without what runs a case. Nothing was sent.");
        // A preview needs no runner: it sends nothing.
        assertThat(bed.service().preview("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""))).runnable()).isTrue();
    }

    @Test
    void thePreviewSaysWhatWouldRunWithoutSendingAnything() {
        SuitePreview preview = bed.service().preview("C:\\work\\suites\\sleep.csv", "caseId,protocol,memberId,npi,codes,expected\n"
                + "TC-1,crd,P32H-NO-AUTH,1234567893,95810,NO_AUTH_REQUIRED\n"
                + "TC-2,crd,P32H-PA-REQ,1234567893,95810,\n"
                + "TC-3,pas,P32H-PA-REQ,1234567893,95810,\n"
                + "TC-4,chain,P32H-PA-REQ,1234567893,95810,\n"
                + "TC-5,crd,,1234567890,95810,\n");
        assertThat(preview.name()).isEqualTo("sleep.csv");
        assertThat(preview.format()).isEqualTo("CSV");
        assertThat(preview.runnable()).isTrue();
        assertThat(preview.headline()).isEqualTo("5 cases: 4 can be run, and 1 has a problem and will be reported as not valid.");
        assertThat(preview.total()).isEqualTo(5);
        assertThat(preview.valid()).isEqualTo(4);
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.rowProblemCount()).isEqualTo(1);
        assertThat(preview.rowProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.row()).isEqualTo(5);
            assertThat(problem.caseId()).isEqualTo("TC-5");
            assertThat(problem.problems()).hasSize(2);
        });
        assertThat(preview.kinds()).containsExactly(new SuitePreview.Kind("CRD order-sign", 2), new SuitePreview.Kind("PAS submit", 1),
                new SuitePreview.Kind("Chain, starting with CRD order-sign", 1));
        assertThat(preview.writes()).isEqualTo(2);
        assertThat(preview.writeNote()).isEqualTo("2 cases can create or change something on the far side. On the mock profile nothing "
                + "leaves this machine.");
        assertThat(preview.withExpectation()).isEqualTo(1);
        assertThat(preview.columns()).containsExactly("caseId", "protocol", "memberId", "npi", "codes", "expected");
        assertThat(preview.mode()).isEqualTo("mock");
        assertThat(bed.ledger.records()).isEmpty();
        assertThat(bed.gateway.intents()).isEmpty();
    }

    @Test
    void thePreviewSaysWhatHappensToWritesAgainstProduction() {
        bed.gateway.environment("Onyx Production", EnvironmentKind.PROD);
        SuitePreview preview = bed.service().preview("cases.csv", suite("TC-1,pas,P32H-PA-REQ," + NPI + ",95810,"));
        assertThat(preview.writeNote()).isEqualTo("1 case can create or change something on the far side. Production writes are locked. "
                + "A suite never unlocks production writes, so its submission will be blocked and reported as blocked, not as failed, "
                + "and will not be sent.");
        // A chain sends its reads before its submission is blocked, and the note says so instead of promising nothing is sent.
        assertThat(bed.service().preview("cases.csv", suite("TC-1,chain,P32H-PA-REQ," + NPI + ",95810,")).writeNote())
                .endsWith("A suite never unlocks production writes, so a submission it reaches will be blocked and reported as blocked, "
                        + "not as failed, and will not be sent. A chain sends its CRD step, and its DTR step when CRD calls for one, before "
                        + "it reaches a submission: those are reads. A chain that ends before a submission is reported by what its steps gave, "
                        + "not as blocked.");
        assertThat(preview.environment().kind()).isEqualTo(EnvironmentKind.PROD);
        assertThat(bed.service().preview("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""))).writeNote()).isNull();
    }

    @Test
    void thePreviewOfAFileThatCannotBeRunSaysWhy() {
        SuitePreview broken = bed.service().preview("cases.csv", "caseId,memberId,expectd\nTC-1,S123456789,X");
        assertThat(broken.runnable()).isFalse();
        assertThat(broken.headline()).isEqualTo("This file cannot be run. Nothing was sent.");
        assertThat(broken.problems()).hasSize(1);
        SuitePreview nothing = bed.service().preview("cases.csv", suite("TC-1,crd,,,,"));
        assertThat(nothing.runnable()).isFalse();
        assertThat(nothing.headline()).isEqualTo("No case in this file can be run. Nothing was sent.");
        assertThat(bed.service().preview("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""))).headline())
                .isEqualTo("1 case, and every one can be run.");
        assertThat(bed.service().preview(null, suite(row("TC-1", "P32H-NO-AUTH", ""))).name()).isEqualTo("suite");
    }

    @Test
    void onLiveARowThatChoosesAScenarioIsNotRun() {
        bed.mode = "live";
        SuitePreview preview = bed.service().preview("cases.csv", "caseId,protocol,memberId,scenario\nTC-1,crd,S123456789,crd-02-no-auth");
        assertThat(preview.runnable()).isFalse();
        assertThat(preview.rowProblems().get(0).problems()).singleElement().asString().contains("mock profile only");
    }

    @Test
    void onLiveThePreviewSaysItHasNotAskedWhetherEachCaseHasATarget() {
        bed.mode = "live";
        assertThat(bed.service().preview("cases.csv", suite(row("TC-1", "S123456789", ""))).headline()).isEqualTo("1 case, and every one "
                + "can be run. Whether the active configuration has a target for each is found when the case is sent: one with none is "
                + "reported as not configured.");
    }

    @Test
    void responseTimesAreSummarisedOverTheCallsTheServiceAnswered() {
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-TIMEOUT", ""),
                row("TC-3", "P32H-ERR500", "")), null, null, null);
        // The 200 and the 500 were answered. The timeout was not, and is counted on its own.
        assertThat(run.timing().answered()).isEqualTo(2);
        assertThat(run.timing().timedOut()).isEqualTo(1);
        assertThat(run.timing().thresholdMillis()).isEqualTo(15000);
        assertThat(run.timing().minMillis()).isNotNull();
        assertThat(run.timing().buckets()).hasSize(7);
        assertThat(run.results().get(1).outcome()).isEqualTo("TIMEOUT");
        assertThat(run.results().get(1).tone()).isEqualTo(Tone.FAULT);
    }

    @Test
    void onlyTheLastFewRunsAreHeldAndTheLedgerKeepsTheRest() {
        SuiteService service = bed.service();
        String firstId = null;
        for (int i = 0; i <= SuiteService.KEPT_RUNS; i++) {
            String id = service.start("cases-" + i + ".csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, null).suiteRunId();
            firstId = firstId == null ? id : firstId;
        }
        assertThat(service.list()).hasSize(SuiteService.KEPT_RUNS);
        assertThat(service.list().get(0).name()).isEqualTo("cases-" + SuiteService.KEPT_RUNS + ".csv");
        String dropped = firstId;
        assertThatThrownBy(() -> service.get(dropped, true)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("History tab");
        // The dropped run is still in the ledger, under its suite run id.
        assertThat(bed.ledger.query(RunQuery.forSuite(dropped))).hasSize(1);
        assertThatThrownBy(() -> service.get(null, true)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resultsCsv("suite-nope")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.stop(" ")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void asTheApplicationBuildsItTheSuiteRunsInTheBackgroundAndStartReturnsAtOnce() throws Exception {
        java.util.concurrent.CountDownLatch firstCaseReached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        bed.wrap = runner -> spec -> {
            thread.set(Thread.currentThread().getName());
            firstCaseReached.countDown();
            try {
                // Held until the test has looked at the run while it is on its first case.
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return runner.run(spec);
        };
        try (SuiteService service = bed.serviceOnItsOwnThread()) {
            SuiteRunView started = service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", "")),
                    null, null, null);
            // The answer came back while the first case was still to finish.
            assertThat(started.state()).isEqualTo(SuiteState.RUNNING);
            assertThat(firstCaseReached.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            SuiteRunView during = service.get(started.suiteRunId(), true);
            assertThat(during.state()).isEqualTo(SuiteState.RUNNING);
            assertThat(during.results().get(0).state()).isEqualTo(SuiteRowState.RUNNING);
            assertThat(during.results().get(0).statusLine()).isEqualTo("Running now.");
            assertThat(during.results().get(1).state()).isEqualTo(SuiteRowState.PENDING);
            assertThat(during.headline()).isEqualTo("Running: 0 of 2 cases done.");
            release.countDown();
            SuiteRunView finished = during;
            for (int i = 0; i < 400 && finished.state() == SuiteState.RUNNING; i++) {
                Thread.sleep(25);
                finished = service.get(started.suiteRunId(), true);
            }
            assertThat(finished.state()).isEqualTo(SuiteState.COMPLETED);
            assertThat(finished.counts().done()).isEqualTo(2);
            assertThat(thread.get()).isEqualTo("suite-runner");
            assertThat(bed.ledger.records()).hasSize(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingTheServiceStopsARunThatHasNotFinished() {
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", "")), null, null, null)
                .suiteRunId();
        service.close();
        bed.queued.remove().run();
        SuiteRunView run = service.get(id, true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopReason()).isEqualTo("The tool was shut down while the suite was running.");
        assertThat(run.results()).extracting(SuiteCaseResult::state).containsOnly(SuiteRowState.NOT_RUN);
        assertThat(bed.ledger.records()).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aRunnerThatThrowsStillLeavesARowAndNothingAboutTheCaseIsLogged(CapturedOutput output) {
        bed.wrap = runner -> spec -> {
            throw new IllegalStateException("exploded for " + spec.memberId());
        };
        SuiteRunView run = bed.service().start("cases.csv", suite(row("TC-1", "S123456789", ""), row("TC-2", "P32H-NO-AUTH", "")),
                null, null, null);
        assertThat(run.state()).isEqualTo(SuiteState.COMPLETED);
        assertThat(run.results()).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
            // A failure of the tool is not a case that cannot be run, and it never says nothing was sent.
            assertThat(row.faultReference()).isNotBlank();
            assertThat(row.faultCause()).isEqualTo("java.lang.IllegalStateException");
            assertThat(row.reason()).startsWith("This tool failed while running the case. Whether a request left for it is not known")
                    .endsWith("Reference " + row.faultReference() + ".");
            assertThat(row.statusLine()).isEqualTo("Tool failure: this tool failed while running the case, so whether a request left is "
                    + "not known. The reason gives the reference.");
        });
        assertThat(run.headline()).isEqualTo("Finished: 2 cases. 2 tool failures.");
        // Each row carries its own fault. The suite itself did not stop, so it has none.
        assertThat(run.stopReference()).isNull();
        assertThat(run.stopCause()).isNull();
        assertThat(JsonSafe.mapper().writeValueAsString(run)).doesNotContain("S123456789").doesNotContain("exploded");
        assertThat(run.counts().toolFailed()).isEqualTo(2);
        assertThat(run.counts().notRunnable()).isZero();
        assertThat(output.getAll()).contains("A suite case failed inside this tool").doesNotContain("S123456789").doesNotContain("exploded");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aMissingClassThatEndsTheSuiteNamesItsCauseOnTheRunAndNowhereThatIsKept(CapturedOutput output) {
        // A LinkageError is not caught per case, so it ends the whole suite: the place a missing class surfaces.
        String missing = "org/p32h/interop/epa/pave/service/crd/CrdRunRequest$Builder";
        bed.wrap = runner -> spec -> {
            throw new NoClassDefFoundError(missing);
        };
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "S123456789", ""), row("TC-2", "P32H-NO-AUTH", "")), null, null, null)
                .suiteRunId();
        // The run ends on it and is not thrown on, so no default handler prints its message.
        bed.queued.remove().run();

        SuiteRunView run = service.get(id, true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopReference()).isNotBlank();
        assertThat(run.stopReason()).isEqualTo("This tool failed while running the suite. Reference " + run.stopReference() + ".");
        assertThat(run.stopCause()).isEqualTo("java.lang.NoClassDefFoundError; java.lang.NoClassDefFoundError says: " + missing);
        // The list of runs gives the summary of each, and the stop is part of it.
        assertThat(service.list()).singleElement().satisfies(summary -> {
            assertThat(summary.stopReference()).isEqualTo(run.stopReference());
            assertThat(summary.stopCause()).isEqualTo(run.stopCause());
        });
        String json = JsonSafe.mapper().writeValueAsString(run);
        assertThat(json).contains("\"stopCause\"").doesNotContain("S123456789").doesNotContain("\tat ").doesNotContain("    at ");

        // The results CSV keeps the stop sentence with its reference, as before, and has no column for the cause.
        String csv = service.resultsCsv(id);
        assertThat(csv).contains("Reference " + run.stopReference() + ".").doesNotContain(missing).doesNotContain("NoClassDefFoundError")
                .startsWith("\uFEFF" + String.join(",", SuiteResultsCsv.HEADER) + "\r\n");
        assertThat(bed.ledger.records()).isEmpty();
        // The first line of the log entry names the class, beside the same reference.
        assertThat(output.getAll()).contains("A suite run failed inside this tool, reference " + run.stopReference() + ": "
                + run.stopCause() + System.lineSeparator()).doesNotContain("S123456789");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureWhoseMessageCouldQuoteMemberDataEndsTheSuiteWithItsTypeOnly(CapturedOutput output) {
        // An Error that is not about the program, wrapping a RuntimeException whose message quotes a member ID.
        bed.wrap = runner -> spec -> {
            throw new ExceptionInInitializerError(new IllegalStateException("no plan for " + spec.memberId()));
        };
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "S123456789", "")), null, null, null).suiteRunId();
        // The run ends on it and is not thrown on, so no default handler prints its message.
        bed.queued.remove().run();

        SuiteRunView run = service.get(id, true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopCause()).isEqualTo("java.lang.ExceptionInInitializerError <- java.lang.IllegalStateException");
        assertThat(JsonSafe.mapper().writeValueAsString(run)).doesNotContain("S123456789").doesNotContain("no plan");
        assertThat(service.resultsCsv(id)).doesNotContain("S123456789").doesNotContain("IllegalStateException");
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("no plan");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anErrorOnTheSuitesOwnThreadIsLoggedOnceUnderItsReferenceAndNeverWithItsMessage(CapturedOutput output) throws Exception {
        // On the application's own thread nothing above the run catches an Error, so whatever
        // escapes the run reaches the JVM's default handler, which prints every message and frame.
        AtomicReference<Thread> thread = new AtomicReference<>();
        bed.wrap = runner -> spec -> {
            thread.set(Thread.currentThread());
            throw new ExceptionInInitializerError(new IllegalStateException("no plan for " + spec.memberId()));
        };
        SuiteService service = bed.serviceOnItsOwnThread();
        String id = service.start("cases.csv", suite(row("TC-1", "S123456789", ""), row("TC-2", "P32H-NO-AUTH", "")), null, null, null)
                .suiteRunId();
        SuiteRunView run = service.get(id, true);
        for (int i = 0; i < 400 && run.state() == SuiteState.RUNNING; i++) {
            Thread.sleep(25);
            run = service.get(id, true);
        }
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        // Closing ends the thread, so anything it would still print has been printed when the join returns.
        service.close();
        thread.get().join(20_000);
        assertThat(thread.get().isAlive()).isFalse();

        assertThat(run.stopCause()).isEqualTo("java.lang.ExceptionInInitializerError <- java.lang.IllegalStateException");
        String all = output.getAll();
        assertThat(all).contains("A suite run failed inside this tool, reference " + run.stopReference() + ": " + run.stopCause())
                .doesNotContain("S123456789").doesNotContain("no plan").doesNotContain("Caused by:")
                .doesNotContain("Exception in thread");
    }

    @Test
    void theCaseThatWasRunningWhenThisToolEndedTheSuiteIsAToolFailureAndNeverReadsAsNotRun() {
        // A missing class can surface after the request of the case has left. Its row must not tell
        // the tester that nothing was sent for it.
        String missing = "org/p32h/interop/epa/pave/service/crd/CrdRequestBuilder$Built";
        bed.wrap = runner -> spec -> {
            throw new NoClassDefFoundError(missing);
        };
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "S123456789", ""), row("TC-2", "P32H-NO-AUTH", "")), null, null, null)
                .suiteRunId();
        bed.queued.remove().run();

        SuiteRunView run = service.get(id, true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        SuiteCaseResult cut = run.results().get(0);
        assertThat(cut.state()).isEqualTo(SuiteRowState.DONE);
        assertThat(cut.statusLine()).isEqualTo("Tool failure: this tool failed while running the case, so whether a request left is "
                + "not known. The reason gives the reference.");
        assertThat(cut.reason()).startsWith("This tool failed while running the case. Whether a request left for it is not known")
                .endsWith("Reference " + run.stopReference() + ".");
        // The same failure as the stop: one reference and one cause, not a second log entry.
        assertThat(cut.faultReference()).isEqualTo(run.stopReference());
        assertThat(cut.faultCause()).isEqualTo(run.stopCause());
        assertThat(cut.countedIn()).isEqualTo(org.p32h.interop.epa.pave.model.suite.SuiteCountGroup.TOOL_FAILED);
        assertThat(cut.status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        assertThat(cut.result()).isNull();
        // The case after it really was not run.
        assertThat(run.results().get(1).state()).isEqualTo(SuiteRowState.NOT_RUN);
        assertThat(run.results().get(1).statusLine()).isEqualTo("Not run: the suite stopped before this case.");
        assertThat(run.counts().toolFailed()).isEqualTo(1);
        assertThat(run.counts().notRun()).isEqualTo(1);
        assertThat(run.headline()).isEqualTo("Stopped after 1 of 2 cases. 1 tool failure, 1 not run.");
        assertThat(JsonSafe.mapper().writeValueAsString(run)).doesNotContain("S123456789");
        // The results file says the same of the row, with the reference in its sentence and no cause.
        String csv = service.resultsCsv(id);
        assertThat(csv).contains("This tool failed while running the case. Whether a request left for it is not known")
                .doesNotContain(missing).doesNotContain("NoClassDefFoundError");
        assertThat(bed.ledger.records()).isEmpty();
    }

    @Test
    void aSuiteThatThisToolEndedOnItsLastCaseStillReadsAsStoppedWithItsCause() {
        bed.wrap = runner -> spec -> {
            throw new NoClassDefFoundError("org/p32h/interop/epa/pave/service/crd/CrdRequestBuilder$Built");
        };
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", "")), null, null, null).suiteRunId();
        bed.queued.remove().run();
        SuiteRunView run = service.get(id, true);
        // Every case was attempted, and still the suite did not finish: this tool ended it.
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopReason()).isEqualTo("This tool failed while running the suite. Reference " + run.stopReference() + ".");
        assertThat(run.stopCause()).contains("CrdRequestBuilder$Built");
        assertThat(run.results().get(0).faultReference()).isEqualTo(run.stopReference());
        assertThat(run.headline()).isEqualTo("Stopped after 1 of 1 case. 1 tool failure.");
    }

    @Test
    void aRunThatEndsAnyOtherWayGivesNoStopReferenceOrCause() {
        bed.hold = true;
        SuiteService service = bed.service();
        String id = service.start("cases.csv", suite(row("TC-1", "P32H-NO-AUTH", ""), row("TC-2", "P32H-NO-AUTH", "")), null, null, null)
                .suiteRunId();
        service.stop(id);
        bed.queued.remove().run();
        SuiteRunView run = service.get(id, true);
        assertThat(run.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(run.stopReason()).isNotBlank();
        assertThat(run.stopReference()).isNull();
        assertThat(run.stopCause()).isNull();
        assertThat(JsonSafe.mapper().writeValueAsString(run)).doesNotContain("stopReference").doesNotContain("stopCause");
    }

    @Test
    void aCasesOwnTesterReferenceWinsOverTheSuites() {
        bed.service().start("cases.csv", "caseId,protocol,memberId,npi,codes,testerRef\nTC-1,crd,P32H-NO-AUTH," + NPI + ",95810,ROW-REF\n"
                + "TC-2,crd,P32H-NO-AUTH," + NPI + ",95810,", " SUITE-REF ", null, null);
        assertThat(bed.ledger.records()).extracting(RunRecord::testerRef).containsExactly("ROW-REF", "SUITE-REF");
    }
}
