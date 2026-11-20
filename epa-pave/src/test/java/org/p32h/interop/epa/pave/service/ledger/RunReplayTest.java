package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.NPI;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;

import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MovableClock;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * Replay, driven against the real ledger with a runner that answers what each test tells it to.
 * The runner here stands in for the one a later wave writes; what is under test is what replay
 * selects, what it refuses to send, what it hands the runner and what it makes of the answer.
 */
class RunReplayTest {

    private static final LocalDate TENTH = LocalDate.of(2026, 3, 10);
    private static final LocalDate ELEVENTH = LocalDate.of(2026, 3, 11);

    @TempDir
    Path home;

    private final MovableClock clock = LedgerFixtures.clock();

    /** A runner that records what it was asked and answers as told. By default it runs the case and gets the same answer as the original. */
    private final class FakeRunner implements CaseRunner {

        private final RunLedger writesTo;
        private final List<CaseSpec> asked = new ArrayList<>();
        private Function<CaseSpec, CaseOutcome> answer;
        private int minted;

        FakeRunner(RunLedger writesTo) {
            this.writesTo = writesTo;
            this.answer = spec -> completed(spec, same -> same);
        }

        @Override
        public CaseOutcome run(CaseSpec spec) {
            asked.add(spec);
            return answer.apply(spec);
        }

        /** Runs the case as the real runner would: a new record, marked as a replay, written to the ledger. */
        CaseOutcome completed(CaseSpec spec, UnaryOperator<RunRecord.Builder> change) {
            RunRecord original = writesTo.find(spec.replayOfRunId()).orElseThrow();
            RunRecord.Builder again = original.toBuilder().runId("replay-" + (++minted)).timestamp(RunStamps.timestamp(clock))
                    .replayOfRunId(spec.replayOfRunId()).chainId(spec.chainId()).suiteRunId(spec.suiteRunId()).caseId(spec.caseId())
                    .note(spec.note()).elapsedMillis(original.elapsedMillis() + 7);
            LedgerReceipt receipt = writesTo.record(change.apply(again).build());
            return new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(receipt.record()),
                    receipt.recorded() ? List.of() : List.of(receipt.message()));
        }
    }

    private JsonlRunLedger ledger;
    private FakeRunner runner;

    private RunReplay replayOver(RunLedger over, CaseRunner with, OperationGateway gateway) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (with != null) {
            beans.addBean("runner", with);
        }
        if (gateway != null) {
            beans.addBean("gateway", gateway);
        }
        return new RunReplay(over, beans.getBeanProvider(CaseRunner.class), beans.getBeanProvider(OperationGateway.class), clock);
    }

    /** A ledger that records member IDs, as mock does, so its runs can be run again. */
    private RunReplay replay() {
        ledger = recordingIds(home, clock);
        runner = new FakeRunner(ledger);
        return replayOver(ledger, runner, null);
    }

    private RunRecord recorded(RunRecord.Builder draft) {
        return ledger.record(draft.build()).record();
    }

    private static ReplayEntry only(ReplayReport report) {
        assertThat(report.results()).hasSize(1);
        assertThat(report.selected()).isEqualTo(1);
        return report.results().get(0);
    }

    // ---- same ----

    @Test
    void aRunThatAnswersAsItDidBeforeIsReportedSame() {
        RunReplay replay = replay();
        RunRecord original = recorded(draft("run-1", clock).delegatedVendor("Evolent").testerRef("QA-17"));

        ReplayReport report = replay.replay(null, null, List.of("run-1"));

        ReplayEntry entry = only(report);
        assertThat(entry.status()).isEqualTo(ReplayStatus.SAME);
        assertThat(entry.runId()).isEqualTo("run-1");
        assertThat(entry.replayRunId()).isEqualTo("replay-1");
        assertThat(entry.differences()).isEmpty();
        assertThat(entry.reason()).isNull();
        assertThat(entry.notRecorded()).isEmpty();
        assertThat(entry.original()).isEqualTo(original);
        // A different run id, timestamp and elapsed time do not make it a different answer.
        assertThat(entry.replayed().runId()).isEqualTo("replay-1");
        assertThat(entry.replayed().elapsedMillis()).isNotEqualTo(original.elapsedMillis());
        assertThat(report.runnerAvailable()).isTrue();
        assertThat(report.notes()).isEmpty();
        assertThat(report.startedAt()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(report.summary()).containsEntry("same", 1).containsEntry("changed", 0);
        // The replay wrote its own ledger record, marked as a replay and part of no chain or suite.
        RunRecord kept = ledger.find("replay-1").orElseThrow();
        assertThat(kept.replayOfRunId()).isEqualTo("run-1");
        assertThat(kept.chainId()).isNull();
        assertThat(kept.suiteRunId()).isNull();
    }

    @Test
    void theSameCodesInAnotherOrderAreTheSameAnswer() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock).coverageCodes(List.of("covered=covered", "pa-needed=no-auth", "doc-needed=no-doc")));
        runner.answer = spec -> runner.completed(spec, again -> again.coverageCodes(List.of("doc-needed=no-doc", "covered=covered", "pa-needed=no-auth")));

        assertThat(only(replay.replay(null, null, List.of("run-1"))).status()).isEqualTo(ReplayStatus.SAME);
    }

    // ---- what the runner is handed ----

    @Test
    void theCaseIsBuiltFromTheLineAloneAndIsNotPartOfTheOriginalChainOrSuite() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock).operation(EpaOperation.CRD_ORDER_SELECT).procedureCodes(List.of("95810", "E0601")).testerRef("QA-17")
                .note("a remark about the original").chainId("chain-00000000-0000-4000-8000-000000000001").suiteRunId("suite-1")
                .caseId("case-7").correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1")));

        replay.replay(null, null, List.of("run-1"));

        assertThat(runner.asked).hasSize(1);
        CaseSpec spec = runner.asked.get(0);
        assertThat(spec.replayOfRunId()).isEqualTo("run-1");
        assertThat(spec.chain()).isFalse();
        assertThat(spec.operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(spec.memberId()).isEqualTo(MEMBER_ID);
        assertThat(spec.npi()).isEqualTo(NPI);
        assertThat(spec.procedureCodes()).containsExactly("95810", "E0601");
        assertThat(spec.dateOfService()).isEqualTo("2026-03-20");
        assertThat(spec.scenarioId()).isEqualTo("crd-02-no-auth");
        // The original expectation is carried over, so the replay is judged against what the tester declared then.
        assertThat(spec.expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(spec.testerRef()).isEqualTo("QA-17");
        assertThat(spec.correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, "claim-1"));
        // Not the original's chain, suite or case, and not its note, which was a remark about that run.
        assertThat(spec.chainId()).isNull();
        assertThat(spec.suiteRunId()).isNull();
        assertThat(spec.caseId()).isNull();
        assertThat(spec.note()).isNull();
        assertThat(spec.unrecognised()).isEmpty();
        // No gateway in this test, so there is no epoch to hold the runs to.
        assertThat(spec.configEpoch()).isNull();
    }

    @Test
    void correlationEntriesArePassedAlongExceptOneTheLedgerTokenisedOrTheRecordRefused() {
        String token = "0123456789abcdef".repeat(4);
        RunRecord tokenised = draft("run-1", clock).correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1", RunRecord.CORR_AUTHORIZATION_REF, token,
                RunRecord.CORR_SUBSCRIPTION_ID, "sub-1", RunRecord.CORR_QUESTIONNAIRE, "two\nlines")).build();
        assertThat(tokenised.correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, "(not recorded, 9 characters)");

        assertThat(RunReplay.specFor(tokenised, 4L).correlation()).containsOnlyKeys(RunRecord.CORR_CLAIM_ID, RunRecord.CORR_SUBSCRIPTION_ID)
                .containsEntry(RunRecord.CORR_CLAIM_ID, "claim-1").containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, "sub-1");
        assertThat(RunReplay.specFor(tokenised, 4L).configEpoch()).isEqualTo(4L);

        // An authorization number that was kept as it is, as on mock, is an identifier and goes along.
        RunRecord kept = draft("run-2", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001")).build();
        assertThat(RunReplay.specFor(kept, null).correlation()).containsExactly(Map.entry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001"));
        // Text shaped like a token under any other key is that entry's own value. This one has no run
        // of six digits, which the record would not keep under a claim id since the second member data audit.
        String tokenShaped = "a1b2c3d4".repeat(8);
        RunRecord claim = draft("run-3", clock).correlation(Map.of(RunRecord.CORR_CLAIM_ID, tokenShaped)).build();
        assertThat(RunReplay.specFor(claim, null).correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, tokenShaped));
        // A claim id that reads like a member identifier was written as its length, and is never sent as one.
        RunRecord refused = draft("run-4", clock).correlation(Map.of(RunRecord.CORR_CLAIM_ID, token)).build();
        assertThat(refused.correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, "(not recorded, 64 characters)");
        assertThat(RunReplay.specFor(refused, null).correlation()).isEmpty();
    }

    @Test
    void everyRunOfAReplayIsHeldToTheConfigurationItStartedUnder() {
        ledger = recordingIds(home, clock);
        runner = new FakeRunner(ledger);
        FakeGateway gateway = FakeGateway.unconfigured().changeConfiguration().changeConfiguration();
        RunReplay replay = replayOver(ledger, runner, gateway);
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));
        // The configuration changes while the first run is out. The second must not go out under the new one unnoticed.
        runner.answer = spec -> {
            gateway.changeConfiguration();
            return runner.completed(spec, same -> same);
        };

        ReplayReport report = replay.replay(TENTH, TENTH, null);

        assertThat(report.configEpoch()).isEqualTo(2L);
        assertThat(runner.asked).extracting(CaseSpec::configEpoch).containsExactly(2L, 2L);
        assertThat(gateway.epoch()).isEqualTo(4L);
    }

    // ---- changed, and how ----

    private ReplayEntry changedBy(UnaryOperator<RunRecord.Builder> change) {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        runner.answer = spec -> runner.completed(spec, change);
        ReplayReport report = replay.replay(null, null, List.of("run-1"));
        assertThat(report.summary()).containsEntry("changed", 1).containsEntry("same", 0);
        ReplayEntry entry = only(report);
        assertThat(entry.status()).isEqualTo(ReplayStatus.CHANGED);
        assertThat(entry.reason()).isNull();
        return entry;
    }

    @Test
    void aChangedOutcomeIsReportedAsItWasAndAsItIsNow() {
        assertThat(changedBy(again -> again.outcome("AUTH_REQUIRED")).differences())
                .containsExactly(new ReplayDifference("outcome", "NO_AUTH_REQUIRED", "AUTH_REQUIRED"));
    }

    @Test
    void changedCoverageCodesAreReportedAsTheyWereAndAsTheyAreNow() {
        assertThat(changedBy(again -> again.coverageCodes(List.of("covered=covered", "pa-needed=auth-needed"))).differences())
                .containsExactly(new ReplayDifference("coverageCodes", "covered=covered; pa-needed=no-auth", "covered=covered; pa-needed=auth-needed"));
    }

    @Test
    void aCodeThatIsNoLongerReturnedIsADifferenceAndNotAnOmission() {
        assertThat(changedBy(again -> again.coverageCodes(List.of())).differences())
                .containsExactly(new ReplayDifference("coverageCodes", "covered=covered; pa-needed=no-auth", null));
    }

    @Test
    void aChangedDelegatedVendorIsReportedAsItWasAndAsItIsNow() {
        // The original named no vendor, so there is nothing on the "was" side.
        assertThat(changedBy(again -> again.delegatedVendor("Evolent")).differences())
                .containsExactly(new ReplayDifference("delegatedVendor", null, "Evolent"));
    }

    @Test
    void aChangedHttpStatusIsReportedAsItWasAndAsItIsNow() {
        assertThat(changedBy(again -> again.httpStatus(500)).differences()).containsExactly(new ReplayDifference("httpStatus", "200", "500"));
        assertThat(changedBy(again -> again.httpStatus(null)).differences()).containsExactly(new ReplayDifference("httpStatus", "200", null));
    }

    @Test
    void aChangedErrorTypeIsReportedAsItWasAndAsItIsNow() {
        assertThat(changedBy(again -> again.errorType(OnyxFailureType.TIMEOUT)).differences())
                .containsExactly(new ReplayDifference("errorType", "NONE", "TIMEOUT"));
    }

    @Test
    void aChangedTestResultIsReportedAsItWasAndAsItIsNow() {
        assertThat(changedBy(again -> again.result(TestResult.FAIL)).differences()).containsExactly(new ReplayDifference("result", "pass", "fail"));
    }

    @Test
    void aChangedNextActionIsReportedAsItWasAndAsItIsNow() {
        assertThat(changedBy(again -> again.nextAction(NextAction.START_PAS)).differences())
                .containsExactly(new ReplayDifference("nextAction", "FLOW_ENDS", "START_PAS"));
    }

    @Test
    void everythingThatChangedIsListedTogether() {
        ReplayEntry entry = changedBy(again -> again.outcome("SERVICE_ERROR").coverageCodes(List.of()).delegatedVendor("Carelon").httpStatus(503)
                .errorType(OnyxFailureType.SERVER_ERROR).result(TestResult.FAIL).nextAction(NextAction.CHECK_ENVIRONMENT));

        assertThat(entry.differences()).extracting(ReplayDifference::field)
                .containsExactly("outcome", "coverageCodes", "delegatedVendor", "httpStatus", "errorType", "result", "nextAction");
        // What the browser is sent: a side with nothing on it is left out, never written as null.
        JsonNode sent = JsonSafe.mapper().valueToTree(entry);
        assertThat(sent.get("status").asString()).isEqualTo("changed");
        assertThat(sent.get("differences").get(1).has("now")).isFalse();
        assertThat(sent.get("differences").get(2).has("was")).isFalse();
        assertThat(sent.get("differences").get(0).get("was").asString()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(sent.has("reason")).isFalse();
    }

    @Test
    void anAnswerThatWasNotKeptAsTextIsNeverCalledTheSame() {
        RunReplay replay = replay();
        // A vendor this tool does not know: the line says a name was there and does not hold the name.
        String vendor = "Some New Vendor";
        String anotherVendor = "Some Old Vendor";
        // Of the same length, so the two statements read alike whether or not the record keeps the length in them.
        assertThat(anotherVendor).hasSameSizeAs(vendor).isNotEqualTo(vendor);
        RunRecord original = recorded(draft("run-1", clock).delegatedVendor(vendor));
        assertThat(original.delegatedVendor()).startsWith("(vendor not on the known list, ");
        runner.answer = spec -> runner.completed(spec, again -> again.delegatedVendor(anotherVendor));

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        assertThat(entry.replayed().delegatedVendor()).isEqualTo(original.delegatedVendor());
        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_COMPARABLE);
        assertThat(entry.differences()).isEmpty();
        assertThat(entry.reason()).contains("delegatedVendor").contains("cannot be shown to be the same");

        // The same holds for a sentence that came back where a code belongs.
        String sentenceThen = "a sentence, not a code";
        String sentenceNow = "another sentence, here";
        assertThat(sentenceNow).hasSameSizeAs(sentenceThen).isNotEqualTo(sentenceThen);
        recorded(draft("run-2", clock).coverageCodes(List.of("covered=covered", "pa-needed=" + sentenceThen)));
        runner.answer = spec -> runner.completed(spec, again -> again.coverageCodes(List.of("covered=covered", "pa-needed=" + sentenceNow)));
        ReplayEntry sentence = only(replay.replay(null, null, List.of("run-2")));
        assertThat(sentence.status()).isEqualTo(ReplayStatus.NOT_COMPARABLE);
        assertThat(sentence.reason()).contains("coverageCodes");
        // When a code comes back in its place, that is a difference like any other.
        runner.answer = spec -> runner.completed(spec, again -> again.coverageCodes(List.of("covered=covered", "pa-needed=auth-needed")));
        assertThat(only(replay.replay(null, null, List.of("run-2"))).status()).isEqualTo(ReplayStatus.CHANGED);
    }

    @Test
    void aRecordThatNeverWentThroughTheLedgerIsComparedLikeForLike() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock).delegatedVendor("Acme"));
        // The ledger did not keep the replay, so its record comes straight from the run and was
        // never read back from a line. The same unknown vendor must not be reported as a change.
        runner.answer = spec -> new CaseOutcome(spec, CaseStatus.COMPLETED, null,
                List.of(draft("replay-1", clock).delegatedVendor("Acme").replayOfRunId("run-1").build()),
                List.of("This run was NOT recorded: the ledger file could not be written."));

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        assertThat(entry.differences()).isEmpty();
        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_COMPARABLE);
        assertThat(entry.replayed().delegatedVendor()).isEqualTo(entry.original().delegatedVendor());
        assertThat(entry.notRecorded()).containsExactly("This run was NOT recorded: the ledger file could not be written.");
    }

    // ---- not replayable ----

    @Test
    void aRunWhoseMemberIdWasNotRecordedIsNotReplayableAndIsNotSent() {
        // The default on live: a token in place of the ID.
        JsonlRunLedger tokenising = ledger(home, clock);
        FakeRunner unused = new FakeRunner(tokenising);
        RunRecord original = tokenising.record(draft("run-1", clock).build()).record();
        assertThat(original.memberId()).isNull();

        ReplayReport report = replayOver(tokenising, unused, null).replay(null, null, List.of("run-1"));

        ReplayEntry entry = only(report);
        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_REPLAYABLE);
        assertThat(entry.reason()).startsWith("The member ID of this run was not recorded.").contains("tracking.record-member-id");
        assertThat(entry.replayed()).isNull();
        assertThat(entry.replayRunId()).isNull();
        assertThat(entry.original()).isEqualTo(original);
        // Never sent with something else in the place of the ID.
        assertThat(unused.asked).isEmpty();
        assertThat(report.summary()).containsEntry("not-replayable", 1);
    }

    @Test
    void aRunThatSentSomethingItsLineDoesNotHoldIsNotReplayableForEachKindOfInput() throws IllegalAccessException {
        RunReplay replay = replay();
        List<String> kinds = new ArrayList<>();
        for (Field field : RunRecord.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("INPUT_")) {
                kinds.add((String) field.get(null));
            }
        }
        // A kind of input added to the record must be given words here, or a tester would be shown its code name.
        assertThat(kinds).hasSize(7).containsExactlyInAnyOrderElementsOf(RunReplay.INPUTS_IN_WORDS.keySet());

        for (String kind : kinds) {
            recorded(draft("run-" + kind, clock).unrecordedInputs(List.of(kind)));
            ReplayEntry entry = only(replay.replay(null, null, List.of("run-" + kind)));
            assertThat(entry.status()).as(kind).isEqualTo(ReplayStatus.NOT_REPLAYABLE);
            assertThat(entry.reason()).as(kind).startsWith("This run sent something its ledger line does not hold: "
                    + RunReplay.INPUTS_IN_WORDS.get(kind) + ".").contains("would send a different request");
        }
        assertThat(runner.asked).isEmpty();
        assertThat(RunReplay.INPUTS_IN_WORDS).containsEntry(RunRecord.INPUT_BODY, "a request body that was edited by hand")
                .containsEntry(RunRecord.INPUT_MEMBER_DETAILS, "member details that were typed or changed by hand")
                .containsEntry(RunRecord.INPUT_RUN_VALUES, "values that were typed on the form for that run only")
                .containsEntry(RunRecord.INPUT_CHOSEN_REQUEST, "a collection request that was picked by hand")
                .containsEntry(RunRecord.INPUT_PRIOR_STEP, "answers or identifiers handed on by an earlier step");
    }

    @Test
    void everyInputTheLineDoesNotHoldIsQuotedIncludingOneThisVersionDoesNotKnow() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock).unrecordedInputs(List.of(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES, "signed-attachment")));

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        assertThat(entry.reason()).contains("a request body that was edited by hand; values that were typed on the form for that run only; "
                + "an input this version does not know (signed-attachment).");
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void aLineThatRecordsSomethingThatArrivedOrAStepInsideTheToolIsNotReplayable() {
        RunReplay replay = replay();
        recorded(draft("notification", clock).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_NOTIFICATION).outcome("RECEIVED"));
        recorded(draft("response", clock).protocol(Protocol.DTR).operation(EpaOperation.DTR_RESPONSE).outcome("COMPLETED"));

        ReplayReport report = replay.replay(null, null, List.of("notification", "response"));

        assertThat(report.results()).extracting(ReplayEntry::status).containsExactly(ReplayStatus.NOT_REPLAYABLE, ReplayStatus.NOT_REPLAYABLE);
        assertThat(report.results().get(0).reason()).contains("Subscription notification").contains("nothing to send again");
        assertThat(report.results().get(1).reason()).contains("DTR questionnaire response").contains("nothing to send again");
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void aRunWhoseProcedureCodeWasNotKeptIsNotReplayable() {
        RunReplay replay = replay();
        RunRecord original = recorded(draft("run-1", clock).procedureCodes(List.of("95810", "not a code at all")));
        assertThat(original.procedureCodes()).hasSize(2).last().asString().startsWith("(not a code, ");

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_REPLAYABLE);
        assertThat(entry.reason()).startsWith("A procedure code of this run was not recorded");
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void aRunWhoseClaimIdOrQuestionnaireWasSentAndNotKeptIsNotReplayableAndIsNotSent() {
        // Found by the verifier (V-6): a value the record refused was dropped from the case, which
        // then sent a different request and compared the two answers.
        RunReplay replay = replay();
        recorded(draft("run-1", clock).protocol(org.p32h.interop.epa.pave.model.Protocol.PAS)
                .operation(org.p32h.interop.epa.pave.model.EpaOperation.PAS_INQUIRE)
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim id with words")));
        recorded(draft("run-2", clock).protocol(org.p32h.interop.epa.pave.model.Protocol.DTR)
                .operation(org.p32h.interop.epa.pave.model.EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)
                .correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, "https://example.org/Questionnaire/q1?patient=x")));
        recorded(draft("run-3", clock).protocol(org.p32h.interop.epa.pave.model.Protocol.DTR)
                .operation(org.p32h.interop.epa.pave.model.EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)
                .correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, "https://example.org/Questionnaire/q1 (not a url, 12 characters)")));

        ReplayReport report = replay.replay(null, null, List.of("run-1", "run-2", "run-3"));

        assertThat(report.results()).extracting(ReplayEntry::status).containsOnly(ReplayStatus.NOT_REPLAYABLE);
        assertThat(report.results().get(0).reason()).startsWith("This run sent a claim id, and its line holds a statement");
        assertThat(report.results().get(1).reason()).startsWith("This run sent the questionnaire addresses it asked for");
        assertThat(report.results().get(2).reason()).startsWith("This run sent the questionnaire addresses it asked for");
        assertThat(runner.asked).isEmpty();

        // On a line where the value is what came back, a refused one is only not compared, and the run is sent.
        RunReplay again = replay();
        recorded(draft("run-4", clock).correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, "https://example.org/Questionnaire/q1?patient=x")));
        runner.answer = spec -> new CaseOutcome(spec, CaseStatus.INVALID, "stand-in", List.of());
        assertThat(only(again.replay(null, null, List.of("run-4"))).status()).isEqualTo(ReplayStatus.INVALID);
        assertThat(runner.asked).hasSize(1);
    }

    @Test
    void anAuthorizationNumberWrittenInSeveralGroupsIsStillAnIdentifierAndIsTokenisedNotDropped() {
        // Found by the verifier (V-6): two gaps of spaces made it "(not recorded, 12 characters)".
        JsonlRunLedger live = ledger(home, clock);
        RunRecord written = live.record(draft("run-1", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "AUTH ZZ 0002")).build()).record();
        assertThat(written.correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).matches("[0-9a-f]{64}");
        assertThat(live.find("run-1").orElseThrow().correlation().get(RunRecord.CORR_AUTHORIZATION_REF))
                .isEqualTo(written.correlation().get(RunRecord.CORR_AUTHORIZATION_REF));
        // A tab or a line break is not a gap in a number, and five groups is a sentence.
        assertThat(draft("run-2", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "AUTH\tZZ")).build().correlation())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 7 characters)");
        assertThat(draft("run-3", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "a b c d e")).build().correlation())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 9 characters)");
    }

    // ---- what the runner answers when it does not run the case ----

    private ReplayEntry answered(CaseStatus status, String reason, RunRecord.Builder original) {
        RunReplay replay = replay();
        recorded(original);
        runner.answer = spec -> new CaseOutcome(spec, status, reason, List.of());
        return only(replay.replay(null, null, List.of("run-1")));
    }

    @Test
    void aBlockedReplayIsReportedAsBlockedAndNotAsAFailureOfTheRun() {
        ReplayEntry entry = answered(CaseStatus.BLOCKED, "PAS submit is blocked: \"Onyx Production\" is a production environment and writes are "
                + "locked.", draft("run-1", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));

        assertThat(entry.status()).isEqualTo(ReplayStatus.BLOCKED);
        assertThat(entry.reason()).isEqualTo("PAS submit is blocked: \"Onyx Production\" is a production environment and writes are locked.");
        assertThat(entry.differences()).isEmpty();
        assertThat(entry.replayed()).isNull();
    }

    @Test
    void aBlockedReplayStillShowsTheRecordOfTheAttemptWhenThereIsOne() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));
        // A refused write is recorded as an attempt, and the runner hands that record back.
        runner.answer = spec -> {
            CaseOutcome attempt = runner.completed(spec, again -> again.outcome("BLOCKED").errorType(RunRecord.ERROR_BLOCKED).httpStatus(null));
            return new CaseOutcome(spec, CaseStatus.BLOCKED, "Writes to production are locked.", attempt.runs());
        };

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        assertThat(entry.status()).isEqualTo(ReplayStatus.BLOCKED);
        assertThat(entry.replayRunId()).isEqualTo("replay-1");
        assertThat(entry.replayed().outcome()).isEqualTo("BLOCKED");
        // Blocked is not a comparison, so nothing is listed as a difference.
        assertThat(entry.differences()).isEmpty();
    }

    @Test
    void aReplayTheToolIsNotConfiguredForIsReportedAsNotConfigured() {
        ReplayEntry entry = answered(CaseStatus.NOT_CONFIGURED, "There is no url for CRD order-sign in the active environment.",
                draft("run-1", clock));

        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_CONFIGURED);
        assertThat(entry.reason()).isEqualTo("There is no url for CRD order-sign in the active environment.");
    }

    @Test
    void invalidInputsAreReportedAsInvalidWithTheRunnersReason() {
        ReplayEntry entry = answered(CaseStatus.INVALID, "A scenario can be chosen on the mock profile only.", draft("run-1", clock));

        assertThat(entry.status()).isEqualTo(ReplayStatus.INVALID);
        assertThat(entry.reason()).isEqualTo("A scenario can be chosen on the mock profile only.");
    }

    @Test
    void aLineWithNoNpiSaysThatItMayNotHaveBeenRecorded() {
        ReplayEntry invalid = answered(CaseStatus.INVALID, "Enter the ordering provider's NPI.", draft("run-1", clock).npi(null));

        assertThat(invalid.status()).isEqualTo(ReplayStatus.INVALID);
        assertThat(invalid.reason()).startsWith("Enter the ordering provider's NPI. This line holds no NPI.").contains("tracking.record-npi");
    }

    @Test
    void aCaseTheRunnerCannotRunIsReportedAsNotReplayableWithItsReason() {
        ReplayEntry entry = answered(CaseStatus.NOT_RUNNABLE, "A raw request is not kept, so it cannot be sent again.",
                draft("run-1", clock).protocol(Protocol.RAW).operation(EpaOperation.UNMAPPED).outcome("SENT"));

        assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_REPLAYABLE);
        assertThat(entry.reason()).isEqualTo("A raw request is not kept, so it cannot be sent again.");
    }

    @Test
    void aStatusWithNoReasonStillSaysSomething() {
        assertThat(answered(CaseStatus.BLOCKED, " ", draft("run-1", clock)).reason())
                .isEqualTo("The production guardrail refused to send this run again. The runner gave no further reason.");
    }

    // ---- no runner ----

    @Test
    void withNoRunnerEverySelectedRunIsReportedNotReplayableAndSaysWhy() {
        ledger = recordingIds(home, clock);
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock).unrecordedInputs(List.of(RunRecord.INPUT_BODY)));

        ReplayReport report = replayOver(ledger, null, null).replay(TENTH, TENTH, null);

        assertThat(report.runnerAvailable()).isFalse();
        assertThat(report.notes()).containsExactly("Nothing in this build can run a case again, so no run was sent.");
        assertThat(report.selected()).isEqualTo(2);
        assertThat(report.results()).extracting(ReplayEntry::runId).containsExactly("run-1", "run-2");
        assertThat(report.results()).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo(ReplayStatus.NOT_REPLAYABLE);
            assertThat(entry.reason()).isEqualTo("Nothing in this build can run a case again, so no run was sent.");
        });
        assertThat(report.summary()).containsEntry("not-replayable", 2);
        assertThat(ledger.query(null)).hasSize(2);
    }

    // ---- selection ----

    @Test
    void aRequestWithNeitherARangeNorIdsIsRefused() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));

        for (List<String> noIds : new ArrayList<>(java.util.Arrays.asList(null, List.<String>of()))) {
            assertThatThrownBy(() -> replay.replay(null, null, noIds)).isInstanceOfSatisfying(ValidationException.class,
                    refused -> assertThat(refused.fieldErrors()).extracting(FieldError::field).containsExactly("runIds"));
        }
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void aRangeTheWrongWayRoundAndABlankIdAreRefused() {
        RunReplay replay = replay();

        assertThatThrownBy(() -> replay.replay(ELEVENTH, TENTH, null)).isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.fieldErrors()).containsExactly(new FieldError("from", "The from date is after the to date.")));
        assertThatThrownBy(() -> replay.replay(null, null, java.util.Arrays.asList("run-1", " "))).isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.fieldErrors()).extracting(FieldError::field).containsExactly("runIds[1]"));
        assertThatThrownBy(() -> replay.replay(null, null, java.util.Arrays.asList((String) null))).isInstanceOf(ValidationException.class);
    }

    @Test
    void anIdTheLedgerDoesNotHoldIsNotFoundAndNothingIsSent() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));

        assertThatThrownBy(() -> replay.replay(null, null, List.of("run-1", "run-404"))).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no run with that id. Nothing was sent.");
        assertThatThrownBy(() -> replay.replay(null, null, List.of("run-404", "run-405", "<b>not an id</b>"))).isInstanceOf(NotFoundException.class)
                .hasMessage("3 of the run ids given are not in the ledger. Nothing was sent.");
        // The run that does exist was not sent on its own either.
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void aDateRangeSelectsEveryRunInItOldestFirstAndANoteIsNotARun() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));
        ledger.addNote("run-1", "a note is a line in the same file", "QA-17");
        clock.advance(Duration.ofDays(1));
        recorded(draft("run-3", clock));

        ReplayReport tenth = replay.replay(TENTH, TENTH, null);
        assertThat(tenth.from()).isEqualTo("2026-03-10");
        assertThat(tenth.to()).isEqualTo("2026-03-10");
        assertThat(tenth.results()).extracting(ReplayEntry::runId).containsExactly("run-1", "run-2");
        assertThat(tenth.selected()).isEqualTo(2);
        // Sent again in the order they first happened.
        assertThat(runner.asked).extracting(CaseSpec::replayOfRunId).containsExactly("run-1", "run-2");
        assertThat(tenth.results().get(0).original().note()).isEqualTo("a note is a line in the same file");

        // "What changed since the eleventh": a range with no end.
        runner.asked.clear();
        ReplayReport since = replay.replay(ELEVENTH, null, null);
        assertThat(since.to()).isNull();
        assertThat(since.results()).extracting(ReplayEntry::runId).containsExactly("run-3");
    }

    @Test
    void aRangeThatReachesTodayDoesNotTakeInTheReplaysItMadeLastTime() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));

        // The same open-ended replay three times over. Each one writes its own records into the range.
        ReplayReport first = replay.replay(TENTH, null, null);
        ReplayReport second = replay.replay(TENTH, null, null);
        ReplayReport third = replay.replay(TENTH, null, null);

        assertThat(first.results()).extracting(ReplayEntry::runId).containsExactly("run-1", "run-2");
        assertThat(first.notes()).isEmpty();
        // Still the two original runs, not four and then eight.
        assertThat(second.results()).extracting(ReplayEntry::runId).containsExactly("run-1", "run-2");
        assertThat(third.results()).extracting(ReplayEntry::runId).containsExactly("run-1", "run-2");
        assertThat(runner.asked).hasSize(6);
        // And the report says what it left out, so nothing was skipped without a word.
        assertThat(second.notes()).hasSize(1).first().asString()
                .startsWith("2 runs in that date range are themselves replays and were not selected.");
        assertThat(third.notes()).hasSize(1).first().asString().startsWith("4 runs in that date range are themselves replays");
        // A replay can still be run again by naming it.
        ReplayReport named = replay.replay(null, null, List.of("replay-1"));
        assertThat(named.results()).extracting(ReplayEntry::runId).containsExactly("replay-1");
        assertThat(named.notes()).isEmpty();
        assertThat(runner.asked.get(6).replayOfRunId()).isEqualTo("replay-1");
    }

    @Test
    void aRangeThatHoldsOnlyReplaysSelectsNothingAndSaysWhy() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        // Yesterday's run is replayed today, so today holds one record and it is a replay.
        clock.advance(Duration.ofDays(1));
        replay.replay(TENTH, TENTH, null);

        ReplayReport report = replay.replay(ELEVENTH, ELEVENTH, null);

        assertThat(report.selected()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.notes()).hasSize(2);
        assertThat(report.notes().get(0)).startsWith("1 run in that date range is itself a replay and was not selected.");
        assertThat(report.notes().get(1)).isEqualTo("No run in that date range was selected, so nothing was run again.");
        assertThat(runner.asked).hasSize(1);
    }

    @Test
    void aRangeWithNoRunsInItSaysSo() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));

        ReplayReport report = replay.replay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(report.selected()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.notes()).containsExactly("No run in that date range was selected, so nothing was run again.");
        assertThat(report.summary().values()).containsOnly(0);
    }

    @Test
    void idsAreRunInTheOrderGivenAndEachOnce() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));
        recorded(draft("run-3", clock));

        ReplayReport report = replay.replay(null, null, List.of("run-3", " run-1 ", "run-3"));

        assertThat(report.results()).extracting(ReplayEntry::runId).containsExactly("run-3", "run-1");
        assertThat(runner.asked).hasSize(2);
    }

    @Test
    void withARangeAndIdsTheIdsAreTheSelectionAndTheRangeIsACheckOnIt() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));
        clock.advance(Duration.ofDays(1));
        recorded(draft("run-3", clock));

        // Not the whole range: only the run that was named.
        ReplayReport report = replay.replay(TENTH, TENTH, List.of("run-2"));
        assertThat(report.results()).extracting(ReplayEntry::runId).containsExactly("run-2");
        assertThat(runner.asked).hasSize(1);

        // An id outside the range is a contradiction, and nothing is sent until it is resolved.
        assertThatThrownBy(() -> replay.replay(TENTH, TENTH, List.of("run-2", "run-3"))).isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.fieldErrors()).extracting(FieldError::field).containsExactly("runIds[1]"));
        assertThatThrownBy(() -> replay.replay(TENTH, TENTH, List.of("run-404"))).isInstanceOf(NotFoundException.class);
        assertThat(runner.asked).hasSize(1);
    }

    @Test
    void moreRunsThanOneReplaySendsIsRefusedBeforeAnythingIsSent() {
        InMemoryLedger many = new InMemoryLedger();
        for (int i = 0; i <= RunReplay.MOST_RUNS; i++) {
            many.record(draft("run-" + i, clock).build());
        }
        FakeRunner unused = new FakeRunner(many);

        assertThatThrownBy(() -> replayOver(many, unused, null).replay(TENTH, TENTH, null)).isInstanceOfSatisfying(ValidationException.class,
                refused -> {
                    assertThat(refused).hasMessage("That would send 1001 runs again, and one replay sends at most 1000. Nothing was sent.");
                    assertThat(refused.fieldErrors()).extracting(FieldError::field).containsExactly("from");
                });
        assertThat(unused.asked).isEmpty();
    }

    @Test
    void theLimitIsOnWhatWouldBeSentAndNotOnWhatIsSelected() {
        // A day of chained runs: for each request that can be sent again there are lines that cannot,
        // such as a step that used what an earlier one handed on. Those are reported and cost the service nothing.
        InMemoryLedger many = new InMemoryLedger();
        for (int i = 0; i < RunReplay.MOST_RUNS; i++) {
            many.record(draft("run-" + i, clock).build());
            many.record(draft("step-" + i, clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED")
                    .unrecordedInputs(List.of(RunRecord.INPUT_PRIOR_STEP)).build());
        }
        runner = new FakeRunner(many);
        // The runner answers without writing, so the selection stays as it is while it is run.
        runner.answer = spec -> new CaseOutcome(spec, CaseStatus.COMPLETED, null,
                List.of(draft("replay-of-" + spec.replayOfRunId(), clock).replayOfRunId(spec.replayOfRunId()).build()));

        ReplayReport report = replayOver(many, runner, null).replay(TENTH, TENTH, null);

        assertThat(report.selected()).isEqualTo(2 * RunReplay.MOST_RUNS);
        assertThat(report.summary()).containsEntry("same", RunReplay.MOST_RUNS).containsEntry("not-replayable", RunReplay.MOST_RUNS);
        assertThat(runner.asked).hasSize(RunReplay.MOST_RUNS);
        // A step of a chain is pointed to the way its journey is run again, in the words the chain view uses.
        assertThat(report.results()).filteredOn(entry -> entry.runId().equals("step-0")).singleElement()
                .satisfies(entry -> assertThat(entry.reason()).isEqualTo("This run sent something its ledger line does not hold: answers "
                        + "or identifiers handed on by an earlier step. Running it again from the line would send a different request, and "
                        + "the two answers could not be compared. This run is one step of a chain, and cannot be replayed by itself. To run "
                        + "the journey again, run it as a suite row with the protocol chain."));
        // One more that can be sent, and the whole request is refused.
        many.record(draft("one-too-many", clock).build());
        runner.asked.clear();
        assertThatThrownBy(() -> replayOver(many, runner, null).replay(TENTH, TENTH, null)).isInstanceOf(ValidationException.class)
                .hasMessage("That would send 1001 runs again, and one replay sends at most 1000. Nothing was sent.");
        assertThat(runner.asked).isEmpty();
    }

    @Test
    void withNoRunnerNothingWouldBeSentSoNoSelectionIsTooLarge() {
        InMemoryLedger many = new InMemoryLedger();
        for (int i = 0; i <= RunReplay.MOST_RUNS; i++) {
            many.record(draft("run-" + i, clock).build());
        }

        ReplayReport report = replayOver(many, null, null).replay(TENTH, TENTH, null);

        assertThat(report.selected()).isEqualTo(RunReplay.MOST_RUNS + 1);
        assertThat(report.summary()).containsEntry("not-replayable", RunReplay.MOST_RUNS + 1);
        assertThat(report.runnerAvailable()).isFalse();
    }

    // ---- a replay the ledger did not keep, and a runner that fails ----

    @Test
    void aReplayedRunTheLedgerDidNotKeepIsStillReportedWithTheLedgersMessage() {
        // The originals are in a ledger that works. The replay's own record goes to one that cannot write.
        InMemoryLedger originals = new InMemoryLedger();
        RunRecord original = originals.record(draft("run-1", clock).build()).record();
        runner = new FakeRunner(originals);
        runner.answer = spec -> {
            RunRecord again = original.toBuilder().runId("replay-1").replayOfRunId("run-1").outcome("AUTH_REQUIRED").build();
            return new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(again),
                    List.of("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written."));
        };

        ReplayEntry entry = only(replayOver(originals, runner, null).replay(null, null, List.of("run-1")));

        // Not a blank row: the comparison was made against the record the run produced.
        assertThat(entry.status()).isEqualTo(ReplayStatus.CHANGED);
        assertThat(entry.replayRunId()).isEqualTo("replay-1");
        assertThat(entry.replayed().outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(entry.differences()).containsExactly(new ReplayDifference("outcome", "NO_AUTH_REQUIRED", "AUTH_REQUIRED"));
        assertThat(entry.notRecorded()).containsExactly("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written.");
        assertThat(originals.find("replay-1")).isEmpty();
    }

    @Test
    void aReplayThroughALedgerThatCannotWriteStillReportsEveryRun() throws java.io.IOException {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        // From here on today's file cannot be written: the next day's file is a folder.
        clock.advance(Duration.ofDays(1));
        java.nio.file.Files.createDirectory(LedgerFixtures.dayFile(home, ELEVENTH));

        ReplayEntry entry = only(replay.replay(TENTH, TENTH, null));

        assertThat(entry.status()).isEqualTo(ReplayStatus.SAME);
        assertThat(entry.replayed().runId()).isEqualTo("replay-1");
        assertThat(entry.notRecorded()).hasSize(1).first().asString().startsWith("This run was NOT recorded: the ledger file ");
        assertThat(ledger.find("replay-1")).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aRunnerThatFailsCostsThatRunAndNotTheRestAndItsMessageGoesNowhere(CapturedOutput output) {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        recorded(draft("run-2", clock));
        runner.answer = spec -> {
            if ("run-1".equals(spec.replayOfRunId())) {
                throw new IllegalStateException("response body for member " + spec.memberId());
            }
            return runner.completed(spec, same -> same);
        };

        ReplayReport report = replay.replay(null, null, List.of("run-1", "run-2"));

        assertThat(report.results()).extracting(ReplayEntry::status).containsExactly(ReplayStatus.ERROR, ReplayStatus.SAME);
        ReplayEntry failed = report.results().get(0);
        assertThat(failed.reason()).startsWith("This tool failed while running this again").contains("A request may have been sent")
                .doesNotContain(MEMBER_ID).doesNotContain("response body");
        assertThat(report.summary()).containsEntry("error", 1).containsEntry("same", 1);
        // The log has the kind of failure under the same reference, and not the message.
        String reference = failed.reason().substring(failed.reason().indexOf("reference ") + 10, failed.reason().length() - 1);
        assertThat(output.getAll()).contains(reference).contains("IllegalStateException").doesNotContain(MEMBER_ID).doesNotContain("response body");
    }

    @Test
    void anAnswerThatCannotBeComparedIsAnErrorAndNeverTheSame() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));

        // Completed, with no record of the run.
        runner.answer = spec -> new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of());
        ReplayEntry noRecord = only(replay.replay(null, null, List.of("run-1")));
        assertThat(noRecord.status()).isEqualTo(ReplayStatus.ERROR);
        assertThat(noRecord.reason()).contains("returned no record of it");

        // Completed, with the record of some other operation.
        runner.answer = spec -> runner.completed(spec, again -> again.operation(EpaOperation.CRD_ORDER_SELECT));
        ReplayEntry otherOperation = only(replay.replay(null, null, List.of("run-1")));
        assertThat(otherOperation.status()).isEqualTo(ReplayStatus.ERROR);
        assertThat(otherOperation.reason()).contains("CRD order-select").contains("cannot be compared");

        // No answer at all. Whether a request went out is not known, and the entry says so.
        runner.answer = spec -> null;
        ReplayEntry noAnswer = only(replay.replay(null, null, List.of("run-1")));
        assertThat(noAnswer.status()).isEqualTo(ReplayStatus.ERROR);
        assertThat(noAnswer.reason()).startsWith("The runner gave no answer for this run").endsWith("A request may have been sent.");
        runner.answer = spec -> new CaseOutcome(spec, null, null, List.of());
        ReplayEntry noStatus = only(replay.replay(null, null, List.of("run-1")));
        assertThat(noStatus.status()).isEqualTo(ReplayStatus.ERROR);
        assertThat(noStatus.reason()).startsWith("The runner did not say how this run ended").endsWith("A request may have been sent.");
        assertThat(noStatus.replayed()).isNull();
    }

    @Test
    void anAnswerWithNoStatusStillShowsTheRunThatWasMadeAndTheLedgersWordOnIt() {
        RunReplay replay = replay();
        recorded(draft("run-1", clock));
        // The runner ran the case, the ledger did not keep it, and the runner then failed to say how the attempt ended.
        runner.answer = spec -> new CaseOutcome(spec, null, null,
                List.of(draft("replay-1", clock).replayOfRunId("run-1").outcome("AUTH_REQUIRED").build()),
                List.of("This run was NOT recorded: the ledger file could not be written."));

        ReplayEntry entry = only(replay.replay(null, null, List.of("run-1")));

        // Not compared, because the runner did not say the run completed. Not hidden either.
        assertThat(entry.status()).isEqualTo(ReplayStatus.ERROR);
        assertThat(entry.differences()).isEmpty();
        assertThat(entry.replayRunId()).isEqualTo("replay-1");
        assertThat(entry.replayed().outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(entry.notRecorded()).containsExactly("This run was NOT recorded: the ledger file could not be written.");
    }

    // ---- runs that went somewhere else than the first time ----

    @Test
    void aReplayAgainstAnotherEnvironmentOrProfileSaysSoBecauseADifferenceCanComeFromTheMove() {
        RunReplay replay = replay();
        recorded(draft("stayed", clock));
        recorded(draft("other-environment", clock));
        recorded(draft("other-profile", clock));
        recorded(draft("no-environment-then", clock).environment(null, null));
        recorded(draft("blocked", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));
        runner.answer = spec -> switch (spec.replayOfRunId()) {
            case "other-environment" -> runner.completed(spec, again -> again.environment("Onyx UAT", EnvironmentKind.UAT).outcome("AUTH_REQUIRED"));
            case "other-profile" -> runner.completed(spec, again -> again.profile("live").scenarioId(null));
            case "no-environment-then" -> runner.completed(spec, again -> again.environment("Onyx Sandbox", EnvironmentKind.SANDBOX));
            // Not sent, so it went nowhere, whatever environment refused it.
            case "blocked" -> new CaseOutcome(spec, CaseStatus.BLOCKED, "Writes to production are locked.", List.of());
            default -> runner.completed(spec, same -> same);
        };

        ReplayReport report = replay.replay(TENTH, TENTH, null);

        assertThat(report.results()).extracting(entry -> entry.status().wire()).containsExactly("same", "changed", "same", "same", "blocked");
        assertThat(report.notes()).containsExactly("3 of the runs that were sent again ran against another environment or profile than the "
                + "first time. A difference in those can come from that move and not from the service. Each entry shows both.");
        // The move is not listed as a difference of the run: those say how the answer changed.
        assertThat(report.results().get(1).differences()).extracting(ReplayDifference::field).containsExactly("outcome");
        assertThat(report.results().get(2).differences()).isEmpty();
        assertThat(report.results().get(1).original().environment()).isEqualTo("Onyx Sandbox");
        assertThat(report.results().get(1).replayed().environment()).isEqualTo("Onyx UAT");

        // One on its own is worded as one, and a replay that stayed where it was says nothing.
        assertThat(replay.replay(null, null, List.of("other-environment")).notes()).containsExactly("1 of the runs that were sent again ran "
                + "against another environment or profile than the first time. A difference in it can come from that move and not from the "
                + "service. Its entry shows both.");
        assertThat(replay.replay(null, null, List.of("stayed", "blocked")).notes()).isEmpty();
    }

    // ---- writes that were sent again ----

    @Test
    void aReplayThatSentWritesAgainOnLiveSaysHowManyAndWhich() {
        RunReplay replay = replay();
        recorded(draft("submit-1", clock).profile("live").scenarioId(null).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));
        recorded(draft("submit-2", clock).profile("live").scenarioId(null).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("PENDED"));
        recorded(draft("create", clock).profile("live").scenarioId(null).protocol(Protocol.SUBSCRIPTION)
                .operation(EpaOperation.SUBSCRIPTION_CREATE).outcome("CREATED"));
        // A read, a write the guardrail refused and a write that cannot be sent again are not writes that were sent.
        recorded(draft("read", clock).profile("live").scenarioId(null));
        recorded(draft("blocked", clock).profile("live").scenarioId(null).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));
        recorded(draft("hand-edited", clock).profile("live").scenarioId(null).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT)
                .outcome("APPROVED").unrecordedInputs(List.of(RunRecord.INPUT_BODY)));
        runner.answer = spec -> switch (spec.replayOfRunId()) {
            case "blocked" -> new CaseOutcome(spec, CaseStatus.BLOCKED, "Writes to production are locked.", List.of());
            case "submit-2" -> runner.completed(spec, again -> again.outcome("DENIED"));
            default -> runner.completed(spec, same -> same);
        };

        ReplayReport report = replay.replay(TENTH, TENTH, null);

        assertThat(report.results()).extracting(entry -> entry.status().wire())
                .containsExactly("same", "changed", "same", "same", "blocked", "not-replayable");
        assertThat(report.notes()).containsExactly("3 of the runs that were sent again are writes (PAS submit, Subscription create). Each went "
                + "to the service as a real request, so each can have created or changed something there again.");

        // One on its own is worded as one.
        assertThat(replay.replay(null, null, List.of("create")).notes()).containsExactly("1 of the runs that were sent again is a write "
                + "(Subscription create). It went to the service as a real request, so it can have created or changed something there again.");
        assertThat(replay.replay(null, null, List.of("read", "blocked", "hand-edited")).notes()).isEmpty();
    }

    @Test
    void onMockNothingLeavesTheToolSoAReplayedWriteIsNotReportedAsOne() {
        RunReplay replay = replay();
        recorded(draft("submit-1", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));

        ReplayReport report = replay.replay(null, null, List.of("submit-1"));

        assertThat(only(report).status()).isEqualTo(ReplayStatus.SAME);
        assertThat(only(report).replayed().profile()).isEqualTo("mock");
        assertThat(report.notes()).isEmpty();
    }

    // ---- the summary ----

    @Test
    void theSummaryCountsEveryStatusAndEverySelectedRunIsInTheReport() {
        RunReplay replay = replay();
        recorded(draft("same", clock));
        recorded(draft("changed", clock));
        recorded(draft("hand-edited", clock).unrecordedInputs(List.of(RunRecord.INPUT_BODY)));
        recorded(draft("blocked", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED"));
        recorded(draft("not-configured", clock));
        recorded(draft("notification", clock).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_NOTIFICATION).outcome("RECEIVED"));
        runner.answer = spec -> switch (spec.replayOfRunId()) {
            case "changed" -> runner.completed(spec, again -> again.outcome("AUTH_REQUIRED"));
            case "blocked" -> new CaseOutcome(spec, CaseStatus.BLOCKED, "Writes to production are locked.", List.of());
            case "not-configured" -> new CaseOutcome(spec, CaseStatus.NOT_CONFIGURED, "The configuration changed.", List.of());
            default -> runner.completed(spec, same -> same);
        };

        ReplayReport report = replay.replay(TENTH, TENTH, null);

        assertThat(report.selected()).isEqualTo(6);
        assertThat(report.results()).extracting(ReplayEntry::runId)
                .containsExactly("same", "changed", "hand-edited", "blocked", "not-configured", "notification");
        assertThat(report.results()).extracting(entry -> entry.status().wire())
                .containsExactly("same", "changed", "not-replayable", "blocked", "not-configured", "not-replayable");
        Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put("same", 1);
        expected.put("changed", 1);
        expected.put("not-comparable", 0);
        expected.put("not-replayable", 2);
        expected.put("blocked", 1);
        expected.put("not-configured", 1);
        expected.put("invalid", 0);
        expected.put("error", 0);
        assertThat(report.summary()).containsExactlyEntriesOf(expected);
        assertThat(report.summary().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(report.selected());
        // Only the four that could be sent were sent.
        assertThat(runner.asked).extracting(CaseSpec::replayOfRunId).containsExactly("same", "changed", "blocked", "not-configured");
    }
}
