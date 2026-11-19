package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.bytes;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledgerDirectory;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.lines;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;

import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MovableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read side of the ledger: what a query returns and in what order, how a note reaches the run
 * it is about, and that a line nobody can read costs that line and nothing else.
 */
class JsonlRunLedgerQueryTest {

    private static final LocalDate TENTH = LocalDate.of(2026, 3, 10);
    private static final LocalDate ELEVENTH = LocalDate.of(2026, 3, 11);
    private static final LocalDate TWELFTH = LocalDate.of(2026, 3, 12);
    private static final String CHAIN = "chain-00000000-0000-4000-8000-000000000001";

    @TempDir
    Path home;

    private final MovableClock clock = LedgerFixtures.clock();

    private static List<String> ids(List<RunRecord> runs) {
        return runs.stream().map(RunRecord::runId).toList();
    }

    /** Two runs on the 10th, one on the 11th and two on the 12th. */
    private JsonlRunLedger fiveRunsOverThreeDays() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());
        ledger.record(draft("run-2", clock).build());
        clock.advance(Duration.ofDays(1));
        ledger.record(draft("run-3", clock).build());
        clock.advance(Duration.ofDays(1));
        ledger.record(draft("run-4", clock).build());
        ledger.record(draft("run-5", clock).build());
        return ledger;
    }

    // ---- order and limit ----

    @Test
    void runsComeBackNewestFirstAcrossFilesAndTheLimitKeepsTheNewest() {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();

        assertThat(ids(ledger.query(RunQuery.all()))).containsExactly("run-5", "run-4", "run-3", "run-2", "run-1");
        assertThat(ids(ledger.query(null))).containsExactly("run-5", "run-4", "run-3", "run-2", "run-1");
        assertThat(ids(ledger.query(RunQuery.builder().limit(2).build()))).containsExactly("run-5", "run-4");
        assertThat(ids(ledger.query(RunQuery.builder().limit(3).build()))).containsExactly("run-5", "run-4", "run-3");
        assertThat(ids(ledger.query(RunQuery.builder().limit(50).build()))).hasSize(5);
        // Zero and null both mean no limit.
        assertThat(ledger.query(RunQuery.builder().limit(0).build())).hasSize(5);
        assertThat(ledger.query(RunQuery.builder().limit(null).build())).hasSize(5);
        // The limit is applied after the filters, not before them.
        assertThat(ids(ledger.query(RunQuery.builder().to(ELEVENTH).limit(2).build()))).containsExactly("run-3", "run-2");
    }

    @Test
    void theListReturnedBelongsToTheCaller() {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();
        List<RunRecord> runs = ledger.query(RunQuery.all());
        Collections.reverse(runs);
        runs.clear();
        assertThat(ledger.query(RunQuery.all())).hasSize(5);
    }

    // ---- the date range ----

    @Test
    void theDateRangeIncludesBothItsEnds() {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();

        assertThat(ids(ledger.query(RunQuery.between(ELEVENTH, ELEVENTH)))).containsExactly("run-3");
        assertThat(ids(ledger.query(RunQuery.between(TENTH, ELEVENTH)))).containsExactly("run-3", "run-2", "run-1");
        assertThat(ids(ledger.query(RunQuery.between(ELEVENTH, TWELFTH)))).containsExactly("run-5", "run-4", "run-3");
        assertThat(ids(ledger.query(RunQuery.builder().from(TWELFTH).build()))).containsExactly("run-5", "run-4");
        assertThat(ids(ledger.query(RunQuery.builder().to(TENTH).build()))).containsExactly("run-2", "run-1");
        assertThat(ledger.query(RunQuery.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))).isEmpty();
        assertThat(ledger.query(RunQuery.between(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))).isEmpty();
    }

    @Test
    void aRunStampedJustBeforeMidnightAndWrittenJustAfterIsFoundUnderTheDayItsTimestampNames() {
        // A service stamps the run, then hands it to the ledger. Midnight can fall between the two.
        clock.set("2026-03-11T03:59:59.900Z");
        JsonlRunLedger ledger = ledger(home, clock);
        RunRecord stampedOnTheTenth = draft("run-late", clock).build();
        clock.advance(Duration.ofMillis(200));
        ledger.record(stampedOnTheTenth);
        ledger.record(draft("run-next-day", clock).build());

        assertThat(lines(dayFile(home, ELEVENTH))).hasSize(2);
        assertThat(Files.exists(dayFile(home, TENTH))).isFalse();
        // Asked for by day, it is under the day the tester saw on its timestamp, and under no other.
        assertThat(ids(ledger.query(RunQuery.between(TENTH, TENTH)))).containsExactly("run-late");
        assertThat(ids(ledger.query(RunQuery.between(ELEVENTH, ELEVENTH)))).containsExactly("run-next-day");
        assertThat(ids(ledger.query(RunQuery.between(TENTH, ELEVENTH)))).containsExactly("run-next-day", "run-late");
    }

    // ---- the other filters ----

    private JsonlRunLedger variedRuns() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("crd-sign", clock).chainId(CHAIN).build());
        ledger.record(draft("crd-select", clock).operation(EpaOperation.CRD_ORDER_SELECT).outcome("AUTH_REQUIRED").result(TestResult.FAIL)
                .chainId(CHAIN).build());
        ledger.record(draft("pas-submit", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("PENDED").expected(null)
                .result(TestResult.UNVERIFIED).suiteRunId("suite-1").caseId("case-1").build());
        ledger.record(draft("pas-inquire", clock).protocol(Protocol.PAS).operation(EpaOperation.PAS_INQUIRE).outcome("APPROVED")
                .result(TestResult.PASS).suiteRunId("suite-1").caseId("case-2").build());
        ledger.record(draft("subscription-create", clock).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_CREATE)
                .outcome("CREATED").expected(null).result(TestResult.UNVERIFIED).build());
        ledger.record(draft("subscription-get", clock).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_GET)
                .outcome("FOUND").expected(null).result(TestResult.UNVERIFIED).build());
        return ledger;
    }

    @Test
    void eachFilterNarrowsTheAnswer() {
        JsonlRunLedger ledger = variedRuns();

        assertThat(ids(ledger.query(RunQuery.builder().protocol(Protocol.CRD).build()))).containsExactly("crd-select", "crd-sign");
        assertThat(ids(ledger.query(RunQuery.builder().protocol(Protocol.DTR).build()))).isEmpty();
        assertThat(ids(ledger.query(RunQuery.builder().result(TestResult.PASS).build()))).containsExactly("pas-inquire", "crd-sign");
        assertThat(ids(ledger.query(RunQuery.builder().result(TestResult.FAIL).build()))).containsExactly("crd-select");
        assertThat(ids(ledger.query(RunQuery.builder().result(TestResult.UNVERIFIED).build())))
                .containsExactly("subscription-get", "subscription-create", "pas-submit");
        assertThat(ids(ledger.query(RunQuery.builder().outcome("APPROVED").build()))).containsExactly("pas-inquire");
        assertThat(ids(ledger.query(RunQuery.builder().outcome(" approved ").build()))).containsExactly("pas-inquire");
        assertThat(ids(ledger.query(RunQuery.builder().outcome("APPROVE").build()))).isEmpty();
        assertThat(ids(ledger.query(RunQuery.forChain(CHAIN)))).containsExactly("crd-select", "crd-sign");
        assertThat(ids(ledger.query(RunQuery.forSuite("suite-1")))).containsExactly("pas-inquire", "pas-submit");
        assertThat(ids(ledger.query(RunQuery.forSuite("suite-2")))).isEmpty();
    }

    @Test
    void theOperationFilterTellsTheRunThatCreatedASubscriptionFromEveryReadOfIt() {
        JsonlRunLedger ledger = variedRuns();

        assertThat(ids(ledger.query(RunQuery.builder().operation(EpaOperation.SUBSCRIPTION_CREATE).build()))).containsExactly("subscription-create");
        assertThat(ids(ledger.query(RunQuery.builder().operation(EpaOperation.SUBSCRIPTION_GET).build()))).containsExactly("subscription-get");
        assertThat(ids(ledger.query(RunQuery.builder().protocol(Protocol.SUBSCRIPTION).build())))
                .containsExactly("subscription-get", "subscription-create");
        assertThat(ids(ledger.query(RunQuery.builder().operation(EpaOperation.CRD_ORDER_SELECT).build()))).containsExactly("crd-select");
        assertThat(ids(ledger.query(RunQuery.builder().operation(EpaOperation.CRD_DISCOVERY).build()))).isEmpty();
    }

    @Test
    void filtersCombineAndABlankFilterNeverWidensTheAnswer() {
        JsonlRunLedger ledger = variedRuns();

        assertThat(ids(ledger.query(RunQuery.builder().protocol(Protocol.PAS).result(TestResult.PASS).build()))).containsExactly("pas-inquire");
        assertThat(ids(ledger.query(RunQuery.builder().protocol(Protocol.CRD).operation(EpaOperation.PAS_SUBMIT).build()))).isEmpty();
        assertThat(ids(ledger.query(RunQuery.builder().from(TENTH).to(TENTH).protocol(Protocol.CRD).result(TestResult.FAIL)
                .outcome("AUTH_REQUIRED").chainId(CHAIN).limit(5).build()))).containsExactly("crd-select");
        // A caller that lost its chain id must not be handed every run in the ledger as that chain.
        assertThat(ledger.query(RunQuery.forChain(""))).isEmpty();
        assertThat(ledger.query(RunQuery.forSuite(" "))).isEmpty();
        assertThat(ledger.query(RunQuery.builder().outcome("").build())).isEmpty();
    }

    // ---- correlation, and the two forms an identifier can be stored in ----

    @Test
    void theCorrelationFilterTakesTheRawIdentifierAndMatchesALineWrittenUnderEitherPolicy() {
        String authorization = "PA-PLACEHOLDER-0001";
        JsonlRunLedger tokenising = ledger(home, clock);
        tokenising.record(draft("written-as-token", clock)
                .correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, authorization, RunRecord.CORR_CLAIM_ID, "claim-1")).build());
        // The policy changes at a restart. The line already written stays as it is.
        JsonlRunLedger recording = recordingIds(home, clock);
        recording.record(draft("written-as-is", clock)
                .correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, authorization, RunRecord.CORR_CLAIM_ID, "claim-2")).build());
        recording.record(draft("another-authorization", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0002")).build());
        recording.record(draft("no-authorization", clock).build());

        List<String> stored = lines(dayFile(home, TENTH));
        assertThat(stored.get(0)).doesNotContain(authorization);
        assertThat(stored.get(1)).contains("\"authorizationRef\":\"" + authorization + "\"");

        for (JsonlRunLedger either : List.of(tokenising, recording)) {
            assertThat(ids(either.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, authorization))))
                    .containsExactly("written-as-is", "written-as-token");
            // A notification can spell the number with a space the submission did not have.
            assertThat(ids(either.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER- 0001"))))
                    .containsExactly("written-as-is", "written-as-token");
            assertThat(ids(either.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0002"))))
                    .containsExactly("another-authorization");
            assertThat(either.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0009"))).isEmpty();
            assertThat(ids(either.query(RunQuery.correlated(RunRecord.CORR_CLAIM_ID, "claim-1")))).containsExactly("written-as-token");
            assertThat(either.query(RunQuery.correlated(RunRecord.CORR_SUBSCRIPTION_ID, "claim-1"))).isEmpty();
            // A key with nothing to look for matches nothing. It is not "any run that has the key".
            assertThat(either.query(RunQuery.correlated(RunRecord.CORR_CLAIM_ID, null))).isEmpty();
            assertThat(either.query(RunQuery.correlated(RunRecord.CORR_CLAIM_ID, " "))).isEmpty();
            // Combined with another filter.
            assertThat(ids(either.query(RunQuery.builder().correlation(RunRecord.CORR_AUTHORIZATION_REF, authorization).limit(1).build())))
                    .containsExactly("written-as-is");
        }
    }

    @Test
    void anEntryThatIsWrittenAsGivenIsMatchedAsGiven() {
        String questionnaires = "https://example.org/Questionnaire/q1 https://example.org/Questionnaire/q2";
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, questionnaires, RunRecord.CORR_RUN_ID, "run-0")).build());

        assertThat(ids(ledger.query(RunQuery.correlated(RunRecord.CORR_QUESTIONNAIRE, questionnaires)))).containsExactly("run-1");
        assertThat(ids(ledger.query(RunQuery.correlated(RunRecord.CORR_RUN_ID, "run-0")))).containsExactly("run-1");
        assertThat(ledger.query(RunQuery.correlated(RunRecord.CORR_RUN_ID, "run-1"))).isEmpty();
    }

    @Test
    void tokenForReturnsTheFormTheLedgerWritesUnderTheCurrentPolicy() {
        JsonlRunLedger tokenising = ledger(home, clock);
        JsonlRunLedger recording = recordingIds(home, clock);

        String token = tokenising.tokenFor("S1234 56789");
        assertThat(token).matches("[0-9a-f]{64}").isEqualTo(tokenising.tokenFor(MEMBER_ID));
        assertThat(tokenising.record(draft("run-1", clock).build()).record().memberToken()).isEqualTo(token);
        assertThat(recording.tokenFor("S1234 56789")).isEqualTo(MEMBER_ID);
        assertThat(recording.record(draft("run-2", clock).memberId("S1234 56789").build()).record().memberId()).isEqualTo(MEMBER_ID);
        // Nothing is written for an identifier that is not there, so there is no form to return.
        for (JsonlRunLedger either : List.of(tokenising, recording)) {
            assertThat(either.tokenFor(null)).isNull();
            assertThat(either.tokenFor("  ")).isNull();
        }
    }

    @Test
    void matchesHoldsForALineWrittenUnderTheOtherPolicy() {
        JsonlRunLedger tokenising = ledger(home, clock);
        JsonlRunLedger recording = recordingIds(home, clock);
        String asToken = tokenising.tokenFor(MEMBER_ID);
        String asIs = recording.tokenFor(MEMBER_ID);
        assertThat(asToken).isNotEqualTo(asIs);

        for (JsonlRunLedger either : List.of(tokenising, recording)) {
            assertThat(either.matches(asToken, MEMBER_ID)).isTrue();
            assertThat(either.matches(asIs, MEMBER_ID)).isTrue();
            assertThat(either.matches(asToken, "S1234 567 89")).isTrue();
            assertThat(either.matches(asIs, " S123456789 ")).isTrue();
            assertThat(either.matches(asToken, "S123456780")).isFalse();
            assertThat(either.matches(asIs, "S123456780")).isFalse();
            assertThat(either.matches(asIs, "s123456789")).isFalse();
            // Two blanks are not the same identifier.
            assertThat(either.matches(null, null)).isFalse();
            assertThat(either.matches("", " ")).isFalse();
            assertThat(either.matches(asToken, null)).isFalse();
            assertThat(either.matches(null, MEMBER_ID)).isFalse();
        }
        // A token is bound to the installation that made it.
        assertThat(ledger(home.resolve("another-installation"), clock).matches(asToken, MEMBER_ID)).isFalse();
    }

    // ---- notes ----

    @Test
    void aNoteIsItsOwnLineAndIsAppliedWhenTheRunIsRead() {
        JsonlRunLedger ledger = ledger(home, clock);
        RunRecord asWritten = ledger.record(draft("run-1", clock).note("typed on the form").testerRef("QA-1").build()).record();
        ledger.record(draft("run-2", clock).build());
        clock.advance(Duration.ofMinutes(5));

        LedgerReceipt receipt = ledger.addNote("run-1", "  retest after the fix  ", "QA-17");

        assertThat(receipt.recorded()).isTrue();
        assertThat(receipt.message()).isNull();
        assertThat(receipt.runsToday()).isEqualTo(2);
        assertThat(receipt.record().runId()).isEqualTo("run-1");
        assertThat(receipt.record().note()).isEqualTo("retest after the fix");
        assertThat(receipt.record().testerRef()).isEqualTo("QA-17");
        assertThat(lines(dayFile(home, TENTH)).get(2)).isEqualTo("{\"recordType\":\"note\",\"runId\":\"run-1\","
                + "\"timestamp\":\"2026-03-10T11:05:00-04:00\",\"note\":\"retest after the fix\",\"testerRef\":\"QA-17\"}");
        assertThat(ledger.find("run-1")).contains(receipt.record());
        // A note is not a run: there are still two, and the other one is untouched.
        List<RunRecord> runs = ledger.query(RunQuery.all());
        assertThat(ids(runs)).containsExactly("run-2", "run-1");
        assertThat(runs.get(1).note()).isEqualTo("retest after the fix");
        assertThat(runs.get(0).note()).isNull();
        assertThat(runs.get(0).testerRef()).isNull();
        // Everything else about the run is as it was written.
        assertThat(runs.get(1).toBuilder().note("typed on the form").testerRef("QA-1").build()).isEqualTo(asWritten);
    }

    @Test
    void theLatestNoteWinsAValueNotGivenIsLeftAndABlankOneClears() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());

        ledger.addNote("run-1", "first", "QA-1");
        assertThat(ledger.addNote("run-1", "second", null).record().testerRef()).isEqualTo("QA-1");
        assertThat(ledger.find("run-1").orElseThrow().note()).isEqualTo("second");
        assertThat(ledger.addNote("run-1", null, "QA-2").record().note()).isEqualTo("second");
        assertThat(ledger.find("run-1").orElseThrow().testerRef()).isEqualTo("QA-2");
        // A query reads the same lines by another path, and must apply the last note line too, not the first.
        assertThat(ledger.query(RunQuery.all())).singleElement().satisfies(run -> {
            assertThat(run.note()).isEqualTo("second");
            assertThat(run.testerRef()).isEqualTo("QA-2");
        });
        assertThat(ledger.query(RunQuery.all())).containsExactly(ledger.find("run-1").orElseThrow());

        RunRecord cleared = ledger.addNote("run-1", " ", null).record();
        assertThat(cleared.note()).isNull();
        assertThat(cleared.testerRef()).isEqualTo("QA-2");
        assertThat(ledger.find("run-1").orElseThrow().note()).isNull();
        // Each note line states both values as they stand, so the last one is the whole answer.
        assertThat(lines(dayFile(home, TENTH)).get(4)).contains("\"note\":null,\"testerRef\":\"QA-2\"");
        assertThat(ledger.query(RunQuery.all())).hasSize(1);
    }

    @Test
    void aNoteOnAnOlderRunGoesIntoTodaysFileAndIsStillAppliedWhenOnlyTheOldDayIsAsked() {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();
        byte[] firstDay = bytes(dayFile(home, TENTH));

        ledger.addNote("run-1", "signed off", "QA-17");

        assertThat(bytes(dayFile(home, TENTH))).isEqualTo(firstDay);
        assertThat(lines(dayFile(home, TWELFTH))).hasSize(3).last().asString().startsWith("{\"recordType\":\"note\",\"runId\":\"run-1\",");
        List<RunRecord> tenth = ledger.query(RunQuery.between(TENTH, TENTH));
        assertThat(ids(tenth)).containsExactly("run-2", "run-1");
        assertThat(tenth.get(1).note()).isEqualTo("signed off");
        assertThat(tenth.get(0).note()).isNull();
        assertThat(ledger.find("run-1").orElseThrow().testerRef()).isEqualTo("QA-17");
        // A restart reads the same files and gives the same answer.
        assertThat(ledger(home, clock).query(RunQuery.between(TENTH, TENTH))).isEqualTo(tenth);
        // The note line is no run, on the day it was written or any other.
        assertThat(ids(ledger.query(RunQuery.between(TWELFTH, TWELFTH)))).containsExactly("run-5", "run-4");
    }

    @Test
    void aNoteForARunThatDoesNotExistIsNotFoundAndWritesNothing() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());
        byte[] before = bytes(dayFile(home, TENTH));

        assertThatThrownBy(() -> ledger.addNote("run-404", "a note", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no run with id run-404.");
        // Text that is not an id this tool minted is not repeated back.
        assertThatThrownBy(() -> ledger.addNote("<script>alert(1)</script>", "a note", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no run with that id.");
        assertThatThrownBy(() -> ledger.addNote(null, "a note", null)).isInstanceOf(NotFoundException.class);
        assertThat(bytes(dayFile(home, TENTH))).isEqualTo(before);
        assertThat(ledger.health().failedWrites()).isZero();
    }

    @Test
    void aNoteWithNothingInItIsRefusedAndWritesNothing() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());

        assertThatThrownBy(() -> ledger.addNote("run-1", null, null)).isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.fieldErrors()).extracting(FieldError::field).containsExactly("note"));
        assertThat(lines(dayFile(home, TENTH))).hasSize(1);
    }

    @Test
    void aNoteThatCouldNotBeWrittenIsNotShownAsIfItHadBeen() throws IOException {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).note("as recorded").build());
        clock.advance(Duration.ofDays(1));
        Files.createDirectory(dayFile(home, ELEVENTH));

        LedgerReceipt receipt = ledger.addNote("run-1", "this one is lost", "QA-17");

        assertThat(receipt.recorded()).isFalse();
        assertThat(receipt.message()).startsWith("The note was NOT recorded: the ledger file " + dayFile(home, ELEVENTH));
        assertThat(receipt.record().runId()).isEqualTo("run-1");
        assertThat(receipt.record().note()).isEqualTo("as recorded");
        assertThat(ledger.find("run-1").orElseThrow().note()).isEqualTo("as recorded");
    }

    // ---- find ----

    @Test
    void aRunIsFoundByItsIdWhicheverDayItIsOn() {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();

        assertThat(ledger.find("run-1").orElseThrow().timestamp()).startsWith("2026-03-10");
        assertThat(ledger.find("run-3").orElseThrow().timestamp()).startsWith("2026-03-11");
        assertThat(ledger.find(" run-5 ").orElseThrow().runId()).isEqualTo("run-5");
        assertThat(ledger.find("run-6")).isEmpty();
        assertThat(ledger.find("run-")).isEmpty();
        assertThat(ledger.find("")).isEmpty();
        assertThat(ledger.find(null)).isEmpty();
    }

    // ---- lines and files that cannot be read ----

    @Test
    void aLineThatCannotBeReadIsSkippedAndCountedAndNeverStopsTheQuery() throws IOException {
        Files.createDirectories(ledgerDirectory(home));
        String first = JsonSafe.mapper().writeValueAsString(draft("run-1", clock).memberId(null).build());
        String second = JsonSafe.mapper().writeValueAsString(draft("run-2", clock).memberId(null).build());
        List<String> unreadable = List.of(
                "this is not JSON",
                "{\"recordType\":\"run\",\"runId\":\"run-torn\",\"timest",
                // A run line without what makes it a run.
                "{\"timestamp\":\"2026-03-10T11:00:00-04:00\"}",
                // A kind of line this version does not know.
                "{\"recordType\":\"audit\",\"runId\":\"run-1\"}",
                // A note that names no run.
                "{\"recordType\":\"note\",\"note\":\"about nothing\"}",
                "[1,2,3]",
                "null",
                "42",
                "\"only a string\"",
                // Two records run together on one line. Reading the first and dropping the second would lose a run without a word.
                first + first.replace("run-1", "run-glued"),
                // A number where the protocol belongs must not be read as a position in the list of protocols.
                first.replace("\"protocol\":\"CRD\"", "\"protocol\":2").replace("run-1", "run-number"),
                // A correlation key the record does not have.
                first.replace("run-1", "run-key").replace("}", ",\"correlation\":{\"rawResponse\":\"x\"}}"));
        String content = first + "\n" + String.join("\n", unreadable) + "\n\n   \n" + second + "\r\n";
        Files.writeString(dayFile(home, TENTH), content, StandardCharsets.UTF_8);

        JsonlRunLedger ledger = ledger(home, clock);

        assertThat(ids(ledger.query(RunQuery.all()))).containsExactly("run-2", "run-1");
        // Empty lines hold nothing and are not counted. Every other line that is not a run or a note is.
        assertThat(ledger.health().unreadableLines()).isEqualTo(unreadable.size());
        assertThat(ledger.health().runsToday()).isEqualTo(2);
        assertThat(ledger.find("run-torn")).isEmpty();
        assertThat(ledger.find("run-glued")).isEmpty();
        assertThat(ledger.find("run-number")).isEmpty();
        // Reading the file again counts the same lines again, not more of them.
        ledger.query(RunQuery.all());
        ledger.find("run-1");
        assertThat(ledger.health().unreadableLines()).isEqualTo(unreadable.size());
        // And the file is as it was: nothing is repaired, removed or rewritten.
        assertThat(Files.readString(dayFile(home, TENTH))).isEqualTo(content);
    }

    @Test
    void unreadableLinesAreCountedAcrossEveryFileThatHasBeenRead() throws IOException {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();
        Files.writeString(dayFile(home, LocalDate.of(2026, 3, 1)), "torn\n", StandardCharsets.UTF_8);
        Files.writeString(dayFile(home, LocalDate.of(2026, 3, 2)), "torn\nand torn again\n", StandardCharsets.UTF_8);
        assertThat(ledger.health().unreadableLines()).isZero();

        // Only the files a query has to read are read.
        ledger.query(RunQuery.between(TENTH, TWELFTH));
        assertThat(ledger.health().unreadableLines()).isZero();
        assertThat(ledger.query(RunQuery.all())).hasSize(5);
        assertThat(ledger.health().unreadableLines()).isEqualTo(3);
    }

    @Test
    void aFileThatCannotBeReadAtAllIsCountedAndTheOtherFilesAreStillRead() throws IOException {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();
        // A folder with the name of a day's file.
        Files.createDirectory(dayFile(home, LocalDate.of(2026, 3, 9)));

        assertThat(ids(ledger.query(RunQuery.all()))).containsExactly("run-5", "run-4", "run-3", "run-2", "run-1");
        assertThat(ledger.health().unreadableLines()).isEqualTo(1);
        ledger.query(RunQuery.all());
        assertThat(ledger.health().unreadableLines()).isEqualTo(1);
    }

    @Test
    void whatIsNotALedgerFileIsNeverRead() throws IOException {
        JsonlRunLedger ledger = fiveRunsOverThreeDays();
        for (String name : List.of("notes.txt", "runs-2026-03-10.jsonl.bak", "runs-2026-02-30.jsonl", "runs-20260310.jsonl", "RUNS-2026-03-10.JSONL.old",
                ".write-check-left-behind.tmp")) {
            Files.writeString(ledgerDirectory(home).resolve(name), "{\"recordType\":\"run\",\"runId\":\"run-from-" + name + "\"\n");
        }

        assertThat(ledger.query(RunQuery.all())).hasSize(5);
        assertThat(ledger.health().unreadableLines()).isZero();
    }

    @Test
    void aFileSavedWithAByteOrderMarkStillGivesUpItsFirstLine() throws IOException {
        Files.createDirectories(ledgerDirectory(home));
        String line = JsonSafe.mapper().writeValueAsString(draft("run-1", clock).memberId(null).build());
        Files.writeString(dayFile(home, TENTH), "\uFEFF" + line + "\n" + line.replace("run-1", "run-2"), StandardCharsets.UTF_8);

        JsonlRunLedger ledger = ledger(home, clock);

        // The last line has no line break after it and is whole, so it is a run like any other.
        assertThat(ids(ledger.query(RunQuery.all()))).containsExactly("run-2", "run-1");
        assertThat(ledger.health().unreadableLines()).isZero();
        // The next run still starts on a line of its own.
        ledger.record(draft("run-3", clock).build());
        assertThat(ids(ledger.query(RunQuery.all()))).containsExactly("run-3", "run-2", "run-1");
    }
}
