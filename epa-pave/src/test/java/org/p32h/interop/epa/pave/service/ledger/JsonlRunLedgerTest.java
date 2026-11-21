package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.NPI;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.bytes;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.codeList;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.configStore;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledgerDirectory;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.lines;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.saltFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.text;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.tracking;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MovableClock;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * The write side of the ledger: what a line holds under each tracking configuration, that nothing
 * already written ever changes, and that a run is either on disk or reported as not being there.
 * Every test reads the file back as it is on disk.
 */
class JsonlRunLedgerTest {

    @TempDir
    Path home;

    private final MovableClock clock = LedgerFixtures.clock();

    private Path todayFile() {
        return dayFile(home, clock.today());
    }

    private static JsonNode json(String line) {
        return JsonSafe.parse(line).node();
    }

    // ---- the line ----

    @Test
    void aLineIsTheRecordExactlyAsItsAnnotationsWriteItAndEndsInOneLineBreak() {
        JsonlRunLedger ledger = recordingIds(home, clock);

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());

        assertThat(receipt.recorded()).isTrue();
        assertThat(receipt.message()).isNull();
        assertThat(receipt.runsToday()).isEqualTo(1);
        assertThat(todayFile().getFileName().toString()).isEqualTo("runs-2026-03-10.jsonl");
        String written = text(todayFile());
        assertThat(written).isEqualTo(JsonSafe.mapper().writeValueAsString(receipt.record()) + "\n");
        assertThat(written).startsWith("{\"recordType\":\"run\",\"runId\":\"run-1\",\"timestamp\":\"2026-03-10T11:00:00-04:00\",\"protocol\":\"CRD\","
                + "\"operation\":\"CRD_ORDER_SIGN\",");
        // The fields that are always written are there with null where nothing applies.
        assertThat(written).contains("\"delegatedVendor\":null").contains("\"note\":null").contains("\"testerRef\":null");
        assertThat(JsonSafe.mapper().readValue(lines(todayFile()).get(0), RunRecord.class)).isEqualTo(receipt.record());
    }

    @Test
    void theReceiptHoldsTheRecordAsWrittenAndFindReturnsTheSame() {
        JsonlRunLedger ledger = ledger(home, clock);
        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());
        assertThat(ledger.find("run-1")).contains(receipt.record());
        assertThat(receipt.record().memberId()).isNull();
    }

    // ---- the tracking policy ----

    @Test
    void withMemberIdsNotRecordedTheLineHoldsATokenAndTheLastFourAndNeitherTheIdNorTheDateOfService() {
        JsonlRunLedger ledger = ledger(home, clock);

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());

        String line = text(todayFile());
        assertThat(line).doesNotContain(MEMBER_ID).doesNotContain("2026-03-20");
        JsonNode written = json(line);
        assertThat(written.has("memberId")).isFalse();
        assertThat(written.has("dateOfService")).isFalse();
        String token = written.get("memberToken").asString();
        assertThat(token).matches("[0-9a-f]{64}").isEqualTo(ledger.tokenFor(MEMBER_ID));
        assertThat(line).contains(token);
        assertThat(written.get("memberIdLast4").asString()).isEqualTo("6789");
        // An NPI is not member data, so by default it is kept.
        assertThat(written.get("npi").asString()).isEqualTo(NPI);
        assertThat(receipt.record().memberToken()).isEqualTo(token);
        assertThat(receipt.record().dateOfService()).isNull();
    }

    @Test
    void withMemberIdsRecordedTheIdAndTheDateOfServiceAreWrittenAndNoToken() {
        JsonlRunLedger ledger = recordingIds(home, clock);

        ledger.record(draft("run-1", clock).build());

        JsonNode written = json(text(todayFile()));
        assertThat(written.get("memberId").asString()).isEqualTo(MEMBER_ID);
        assertThat(written.get("dateOfService").asString()).isEqualTo("2026-03-20");
        assertThat(written.has("memberToken")).isFalse();
        assertThat(written.has("memberIdLast4")).isFalse();
    }

    @Test
    void withNpisNotRecordedNoNpiIsWritten() {
        JsonlRunLedger ledger = ledger(home, clock, Map.of("record-npi", "false", "record-member-id", "true"));

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());

        String line = text(todayFile());
        assertThat(line).doesNotContain(NPI);
        assertThat(json(line).has("npi")).isFalse();
        assertThat(receipt.record().npi()).isNull();
        // The member ID is governed by its own setting.
        assertThat(json(line).get("memberId").asString()).isEqualTo(MEMBER_ID);
    }

    @Test
    void aSpacedAndAnUnspacedSpellingOfOneIdAreStoredAlikeUnderBothPolicies() {
        JsonlRunLedger tokenising = ledger(home.resolve("a"), clock);
        RunRecord spaced = tokenising.record(draft("run-1", clock).memberId("S1234 567\t89").build()).record();
        RunRecord unspaced = tokenising.record(draft("run-2", clock).memberId(MEMBER_ID).build()).record();
        assertThat(spaced.memberToken()).isEqualTo(unspaced.memberToken());
        assertThat(spaced.memberIdLast4()).isEqualTo("6789").isEqualTo(unspaced.memberIdLast4());
        assertThat(text(dayFile(home.resolve("a"), clock.today()))).doesNotContain("S1234 567");

        JsonlRunLedger recording = recordingIds(home.resolve("b"), clock);
        assertThat(recording.record(draft("run-3", clock).memberId(" S1234 56789 ").build()).record().memberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void anIdOfFourCharactersOrFewerGetsNoLastFourBecauseThatWouldBeTheWholeId() {
        JsonlRunLedger ledger = ledger(home, clock);
        assertThat(ledger.record(draft("run-1", clock).memberId("A123").build()).record().memberIdLast4()).isNull();
        assertThat(ledger.record(draft("run-2", clock).memberId("A 1 2").build()).record().memberIdLast4()).isNull();
        assertThat(ledger.record(draft("run-3", clock).memberId("A1234").build()).record().memberIdLast4()).isEqualTo("1234");
        // The token is still written, so two runs of the short ID can be told to be the same member.
        assertThat(ledger.find("run-1").orElseThrow().memberToken()).matches("[0-9a-f]{64}");
        assertThat(text(todayFile())).doesNotContain("A123\"");
    }

    @Test
    void aRunWithNoMemberGetsNoTokenAndADraftTokenIsLeftAlone() {
        JsonlRunLedger ledger = ledger(home, clock);
        RunRecord none = ledger.record(draft("run-1", clock).memberId(null).build()).record();
        assertThat(none.memberToken()).isNull();
        assertThat(none.memberIdLast4()).isNull();
        // The policy replaces a member ID. Where a draft has none, it has nothing to replace.
        RunRecord carried = ledger.record(draft("run-2", clock).memberId(" ").memberToken("0f".repeat(32)).memberIdLast4("6789").build()).record();
        assertThat(carried.memberId()).isNull();
        assertThat(carried.memberToken()).isEqualTo("0f".repeat(32));
        // The date of service goes whenever member IDs are not recorded, whether or not this run had one.
        assertThat(none.dateOfService()).isNull();
    }

    @Test
    void theTokenIsStableForOneIdDiffersBetweenIdsAndDiffersBetweenInstallations() throws IOException {
        JsonlRunLedger first = ledger(home.resolve("one"), clock);
        JsonlRunLedger second = ledger(home.resolve("two"), clock);

        String token = first.tokenFor(MEMBER_ID);
        assertThat(token).matches("[0-9a-f]{64}").isEqualTo(first.tokenFor(MEMBER_ID));
        assertThat(first.record(draft("run-1", clock).build()).record().memberToken()).isEqualTo(token);
        assertThat(first.record(draft("run-2", clock).build()).record().memberToken()).isEqualTo(token);
        assertThat(first.tokenFor("S123456780")).isNotEqualTo(token);
        assertThat(second.tokenFor(MEMBER_ID)).isNotEqualTo(token);

        // Each installation made its own salt, outside its ledger directory.
        byte[] saltOne = Files.readAllBytes(saltFile(home.resolve("one")));
        byte[] saltTwo = Files.readAllBytes(saltFile(home.resolve("two")));
        assertThat(saltOne).hasSize(32).isNotEqualTo(saltTwo);
        // A restart reads the same salt, so a run next week still correlates with one from today.
        assertThat(ledger(home.resolve("one"), clock).tokenFor(MEMBER_ID)).isEqualTo(token);
        assertThat(Files.readAllBytes(saltFile(home.resolve("one")))).isEqualTo(saltOne);
    }

    @Test
    void anAuthorizationNumberFollowsTheMemberIdPolicyAndEveryOtherCorrelationEntryIsWrittenAsGiven() {
        String questionnaires = "https://example.org/Questionnaire/q1|1.0 https://example.org/Questionnaire/q2";
        Map<String, String> correlation = Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER 0001", RunRecord.CORR_CLAIM_ID, "claim-1",
                RunRecord.CORR_SUBSCRIPTION_ID, "sub-1", RunRecord.CORR_RUN_ID, "run-0", RunRecord.CORR_SUBSCRIPTION_RUN_ID, "run-s",
                RunRecord.CORR_CLAIM_RESPONSE_ID, "response-1", RunRecord.CORR_QUESTIONNAIRE, questionnaires);

        JsonlRunLedger tokenising = ledger(home.resolve("a"), clock);
        RunRecord tokenised = tokenising.record(draft("run-1", clock).correlation(correlation).build()).record();
        assertThat(tokenised.correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).matches("[0-9a-f]{64}")
                .isEqualTo(tokenising.tokenFor("PA-PLACEHOLDER0001"));
        assertThat(text(dayFile(home.resolve("a"), clock.today()))).doesNotContain("PA-PLACEHOLDER");
        // An identifier a service or a tester supplied follows the same policy, unless it cannot be anybody's data
        // (found by the independent check of the member data fixes, N2 and N4).
        assertThat(tokenised.correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, tokenising.tokenFor("claim-1"))
                .containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, tokenising.tokenFor("sub-1"))
                .containsEntry(RunRecord.CORR_RUN_ID, "run-0").containsEntry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, "run-s")
                .containsEntry(RunRecord.CORR_CLAIM_RESPONSE_ID, tokenising.tokenFor("response-1"))
                .containsEntry(RunRecord.CORR_QUESTIONNAIRE, questionnaires).hasSize(7);
        assertThat(tokenising.matches(tokenised.correlation().get(RunRecord.CORR_SUBSCRIPTION_ID), "sub-1")).isTrue();

        JsonlRunLedger recording = recordingIds(home.resolve("b"), clock);
        RunRecord kept = recording.record(draft("run-2", clock).correlation(correlation).build()).record();
        // Kept, in the same no-space form a member ID is kept in.
        assertThat(kept.correlation()).containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER0001")
                .containsEntry(RunRecord.CORR_CLAIM_ID, "claim-1").containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, "sub-1").hasSize(7);
    }

    @Test
    void aCorrelationValueTheRecordItselfRefusedStaysAsItsStatementAndIsNotTokenised() {
        // A response put two lines of text where the authorization number belongs. The record keeps
        // only the fact and the length. Tokenising that would turn it into something that reads as an identifier.
        RunRecord draft = draft("run-1", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "line one\nline two")).build();
        assertThat(draft.correlation()).containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 17 characters)");

        assertThat(ledger(home.resolve("a"), clock).record(draft).record().correlation())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 17 characters)");
        assertThat(recordingIds(home.resolve("b"), clock).record(draft).record().correlation())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 17 characters)");
        // An authorization number that is only whitespace identifies nothing, so nothing is written for it.
        assertThat(ledger(home.resolve("c"), clock).record(draft("run-2", clock).correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "  ",
                RunRecord.CORR_CLAIM_ID, "claim-1")).build()).record().correlation()).containsOnlyKeys(RunRecord.CORR_CLAIM_ID);
    }

    private RunRecord everyComponentSet() {
        return RunRecord.builder().runId("run-full").timestamp("2026-03-10T11:00:00-04:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).target("onyx").profile("live").environment("Onyx UAT", EnvironmentKind.UAT)
                .scenarioId("pas-01").procedureCodes(List.of("95810", "E0601")).lineOfBusiness("Commercial").outcome("PENDED")
                .coverageCodes(List.of("95810: review-action=A4")).delegatedVendor("Evolent").httpStatus(200).elapsedMillis(321)
                .errorType(OnyxFailureType.NONE).expected("PENDED").result(TestResult.PASS).note("first submission").testerRef("QA-17")
                .chainId("chain-00000000-0000-4000-8000-000000000001").suiteRunId("suite-1").caseId("case-7").replayOfRunId("run-earlier")
                .hook("order-sign").attachmentMode("fhir").nextAction(NextAction.CHECK_STATUS).targetSource("Claim submit")
                .memberId(MEMBER_ID).npi(NPI).dateOfService("2026-03-20")
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1", RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001"))
                .findings(List.of("SLOW_RESPONSE")).unrecordedInputs(List.of(RunRecord.INPUT_RUN_VALUES)).slow(true).build();
    }

    @Test
    void nothingButWhatThePolicyGovernsIsRewritten() throws ReflectiveOperationException {
        RunRecord draft = everyComponentSet();

        // With everything recorded and no whitespace to remove, the line is the draft.
        assertThat(recordingIds(home.resolve("a"), clock).record(draft).record()).isEqualTo(draft);

        RunRecord written = ledger(home.resolve("b"), clock, Map.of("record-npi", "false")).record(draft).record();
        Set<String> governed = Set.of("memberId", "memberToken", "memberIdLast4", "npi", "dateOfService", "correlation");
        for (RecordComponent component : RunRecord.class.getRecordComponents()) {
            if (!governed.contains(component.getName())) {
                assertThat(component.getAccessor().invoke(written)).as(component.getName()).isEqualTo(component.getAccessor().invoke(draft));
            }
        }
        assertThat(written.memberId()).isNull();
        assertThat(written.npi()).isNull();
        assertThat(written.dateOfService()).isNull();
        assertThat(written.correlation().get(RunRecord.CORR_CLAIM_ID)).matches("[0-9a-f]{64}");
        assertThat(written.correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).matches("[0-9a-f]{64}");
    }

    @Test
    void whatTheRecordDidNotKeepAsTextReadsBackExactlyAsTheReceiptHadIt() {
        // A vendor this tool does not know, a sentence where a code belongs and a code that is not
        // one. The record keeps a statement in place of each, and checks that statement again on
        // every copy. What the receipt carries, what is on disk and what is read back must agree.
        String sentence = "Authorization is needed, see the letter that was sent and the notes that follow it. ".repeat(2);
        JsonlRunLedger ledger = ledger(home, clock);

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).delegatedVendor("Acme")
                .coverageCodes(List.of("pa-needed=" + sentence, "doc-needed=x y", "free text")).procedureCodes(List.of("95810", "bad code!")).build());

        assertThat(receipt.recorded()).isTrue();
        assertThat(JsonSafe.mapper().readValue(lines(todayFile()).get(0), RunRecord.class)).isEqualTo(receipt.record());
        assertThat(ledger.find("run-1")).contains(receipt.record());
        assertThat(ledger.query(RunQuery.all())).containsExactly(receipt.record());
        // After a restart, and with a note applied, which copies the record once more.
        JsonlRunLedger restarted = ledger(home, clock);
        assertThat(restarted.find("run-1")).contains(receipt.record());
        restarted.addNote("run-1", "a note", null);
        assertThat(restarted.find("run-1").orElseThrow().toBuilder().note(null).build()).isEqualTo(receipt.record());
        // The line says that something was there, and holds none of it.
        assertThat(text(todayFile())).doesNotContain("Acme").doesNotContain("see the letter").doesNotContain("bad code!")
                .contains("(vendor not on the known list, ").contains("pa-needed=(not a code, ").contains("(not a code entry, ");
    }

    // ---- append only ----

    @Test
    void aSecondRunANoteAndANewDayLeaveEveryByteAlreadyWrittenUnchanged() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());
        byte[] afterFirst = bytes(todayFile());

        ledger.record(draft("run-2", clock).build());
        byte[] afterSecond = bytes(todayFile());
        assertThat(afterSecond.length).isGreaterThan(afterFirst.length);
        assertThat(Arrays.copyOf(afterSecond, afterFirst.length)).isEqualTo(afterFirst);

        assertThat(ledger.addNote("run-1", "retest after the fix", "QA-17").recorded()).isTrue();
        byte[] afterNote = bytes(todayFile());
        assertThat(afterNote.length).isGreaterThan(afterSecond.length);
        assertThat(Arrays.copyOf(afterNote, afterSecond.length)).isEqualTo(afterSecond);
        // The run's own line still says it has no note. The note is a line of its own.
        assertThat(lines(todayFile()).get(0)).contains("\"note\":null");
        assertThat(lines(todayFile()).get(2)).startsWith("{\"recordType\":\"note\",\"runId\":\"run-1\",");

        Path firstDay = todayFile();
        clock.advance(Duration.ofDays(1));
        ledger.record(draft("run-3", clock).build());
        ledger.addNote("run-1", "closed", null);
        // Yesterday's file is as it was when yesterday ended, a note on one of its runs included.
        assertThat(bytes(firstDay)).isEqualTo(afterNote);
        assertThat(lines(todayFile())).hasSize(2);
    }

    @Test
    void aNoteOrATesterReferenceOverItsLimitIsRefusedByTheLedgerItselfAndNothingIsAppended() {
        // Found by the member data audit (M5). The endpoint checks first; the ledger holds the same limit for any other caller.
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());
        byte[] before = bytes(todayFile());

        assertThatThrownBy(() -> ledger.addNote("run-1", "n".repeat(RunRecord.LONGEST_NOTE + 1), null))
                .isInstanceOf(org.p32h.interop.epa.pave.service.error.ValidationException.class);
        assertThatThrownBy(() -> ledger.addNote("run-1", null, "r".repeat(RunRecord.LONGEST_TESTER_REF + 1)))
                .isInstanceOf(org.p32h.interop.epa.pave.service.error.ValidationException.class);
        assertThat(bytes(todayFile())).isEqualTo(before);

        assertThat(ledger.addNote("run-1", "n".repeat(RunRecord.LONGEST_NOTE), "r".repeat(RunRecord.LONGEST_TESTER_REF)).recorded()).isTrue();
        assertThat(ledger.find("run-1").orElseThrow().note()).hasSize(RunRecord.LONGEST_NOTE);
    }

    @Test
    void aTornLastLineIsLeftBehindAndTheNextRunStartsOnItsOwnLine() throws IOException {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(draft("run-1", clock).build());
        byte[] intact = bytes(todayFile());
        // What a crash in the middle of a write leaves: part of a record and no line break.
        String torn = "{\"recordType\":\"run\",\"runId\":\"run-torn\",\"timest";
        Files.writeString(todayFile(), torn, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        LedgerReceipt receipt = ledger.record(draft("run-2", clock).build());

        assertThat(receipt.recorded()).isTrue();
        String written = text(todayFile());
        assertThat(written).isEqualTo(new String(intact, StandardCharsets.UTF_8) + torn + "\n"
                + JsonSafe.mapper().writeValueAsString(receipt.record()) + "\n");
        assertThat(Arrays.copyOf(bytes(todayFile()), intact.length)).isEqualTo(intact);
        // Both whole runs are read. The torn one is counted, and is never taken for a run.
        assertThat(ledger.query(RunQuery.all())).extracting(RunRecord::runId).containsExactly("run-2", "run-1");
        assertThat(receipt.runsToday()).isEqualTo(2);
        assertThat(ledger.health().unreadableLines()).isEqualTo(1);
        assertThat(ledger.health().runsToday()).isEqualTo(2);
        // The file that holds it is named, by the tool's own name for the day file.
        assertThat(ledger.health().unreadableFiles()).containsExactly(java.util.Map.entry(todayFile().getFileName().toString(), 1));
    }

    @Test
    void aLineTornBeforeTheLastStartIsReportedAsSoonAsTheLedgerStarts() throws IOException {
        ledger(home, clock).record(draft("run-1", clock).build());
        Files.writeString(todayFile(), "{\"recordType\":\"run\",\"runId\":\"run-to", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        LedgerHealth health = ledger(home, clock).health();

        assertThat(health.unreadableLines()).isEqualTo(1);
        assertThat(health.runsToday()).isEqualTo(1);
        assertThat(health.writable()).isTrue();
    }

    // ---- one file per day ----

    @Test
    void runsEitherSideOfMidnightLandInTwoFiles() {
        // A tenth of a second before midnight in New York, then a tenth after.
        clock.set("2026-03-11T03:59:59.900Z");
        JsonlRunLedger ledger = ledger(home, clock);
        LedgerReceipt before = ledger.record(draft("run-before", clock).build());
        Path firstFile = todayFile();

        clock.advance(Duration.ofMillis(200));
        LedgerReceipt after = ledger.record(draft("run-after", clock).build());
        Path secondFile = todayFile();

        assertThat(firstFile.getFileName().toString()).isEqualTo("runs-2026-03-10.jsonl");
        assertThat(secondFile.getFileName().toString()).isEqualTo("runs-2026-03-11.jsonl");
        assertThat(lines(firstFile)).hasSize(1).first().asString().contains("\"runId\":\"run-before\"")
                .contains("\"timestamp\":\"2026-03-10T23:59:59.9-04:00\"");
        assertThat(lines(secondFile)).hasSize(1).first().asString().contains("\"runId\":\"run-after\"")
                .contains("\"timestamp\":\"2026-03-11T00:00:00.1-04:00\"");
        // The count is of today's file, so it starts again with the day.
        assertThat(before.runsToday()).isEqualTo(1);
        assertThat(after.runsToday()).isEqualTo(1);
        assertThat(ledger.health().currentFile()).isEqualTo("runs-2026-03-11.jsonl");
        assertThat(ledger.query(RunQuery.all())).extracting(RunRecord::runId).containsExactly("run-after", "run-before");
    }

    @Test
    void theDayIsTakenInTheZoneOfTheClockAndNotInUtc() {
        // 03:30 UTC on the 11th is still the 10th in New York.
        clock.set("2026-03-11T03:30:00Z");
        ledger(home, clock).record(draft("run-1", clock).build());
        assertThat(Files.exists(ledgerDirectory(home).resolve("runs-2026-03-10.jsonl"))).isTrue();
        assertThat(Files.exists(ledgerDirectory(home).resolve("runs-2026-03-11.jsonl"))).isFalse();
    }

    // ---- a write that fails ----

    @Test
    void aWriteThatFailsIsAReceiptThatSaysSoNamesTheFileAndStillCarriesTheRecord() throws IOException {
        JsonlRunLedger ledger = ledger(home, clock);
        // A folder where today's file should be. This fails for an administrator too, which a permission would not.
        Files.createDirectory(todayFile());

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());

        assertThat(receipt.recorded()).isFalse();
        assertThat(receipt.message()).startsWith("This run was NOT recorded: the ledger file " + todayFile() + " could not be written");
        // The record is there, with the policy applied: a failed write is no reason to hand back the member ID.
        assertThat(receipt.record().runId()).isEqualTo("run-1");
        assertThat(receipt.record().outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(receipt.record().memberId()).isNull();
        assertThat(receipt.record().dateOfService()).isNull();
        assertThat(receipt.record().memberToken()).isEqualTo(ledger.tokenFor(MEMBER_ID));
        assertThat(JsonSafe.mapper().writeValueAsString(receipt)).doesNotContain(MEMBER_ID);
        // The ledger cannot say how many runs today's file holds, and does not say zero.
        assertThat(receipt.runsToday()).isNull();
        assertThat(ledger.find("run-1")).isEmpty();

        LedgerHealth health = ledger.health();
        assertThat(health.writable()).isFalse();
        assertThat(health.failedWrites()).isEqualTo(1);
        assertThat(health.lastError()).isEqualTo(receipt.message());
        assertThat(health.lastErrorAt()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(health.lastWriteAt()).isNull();
    }

    @Test
    void healthKeepsTheLastErrorItsTimeAndTheCountAfterALaterWriteSucceeds() throws IOException {
        JsonlRunLedger ledger = ledger(home, clock);
        Files.createDirectory(todayFile());
        LedgerReceipt failed = ledger.record(draft("run-1", clock).build());
        assertThat(failed.recorded()).isFalse();

        // The next day's file is an ordinary one, so the next write works.
        clock.advance(Duration.ofHours(25));
        LedgerReceipt kept = ledger.record(draft("run-2", clock).build());
        assertThat(kept.recorded()).isTrue();

        LedgerHealth health = ledger.health();
        assertThat(health.writable()).isTrue();
        assertThat(health.runsToday()).isEqualTo(1);
        assertThat(health.lastWriteAt()).isEqualTo("2026-03-11T12:00:00-04:00");
        // A run went unrecorded yesterday. That stays a fact after today's write worked.
        assertThat(health.lastError()).isEqualTo(failed.message()).contains("runs-2026-03-10.jsonl");
        assertThat(health.lastErrorAt()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(health.failedWrites()).isEqualTo(1);

        Files.createDirectory(dayFile(home, clock.today().plusDays(1)));
        clock.advance(Duration.ofDays(1));
        ledger.record(draft("run-3", clock).build());
        ledger.addNote("run-2", "a note that cannot be written either", null);
        LedgerHealth later = ledger.health();
        assertThat(later.failedWrites()).isEqualTo(3);
        assertThat(later.lastError()).startsWith("The note was NOT recorded: the ledger file ").contains("runs-2026-03-12.jsonl");
        assertThat(later.lastErrorAt()).isEqualTo("2026-03-12T12:00:00-04:00");
        assertThat(later.lastWriteAt()).isEqualTo("2026-03-11T12:00:00-04:00");
        assertThat(later.writable()).isFalse();
    }

    @Test
    void aLedgerThatCannotPrepareALineSaysSoAndDoesNotThrow() {
        JsonlRunLedger ledger = new JsonlRunLedger(tracking(home, Map.of()), configStore(home), EpaProperties.defaults(), clock, codeList()) {
            @Override
            RunRecord policyApplied(RunRecord draft) {
                throw new IllegalStateException("must not reach the tester: " + draft.memberId());
            }
        };

        LedgerReceipt receipt = ledger.record(draft("run-1", clock)
                .correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001")).build());

        assertThat(receipt.recorded()).isFalse();
        assertThat(receipt.message()).isEqualTo("This run was NOT recorded: the ledger failed while preparing its line (IllegalStateException).");
        // No policy was applied, so everything a policy governs is left out.
        assertThat(receipt.record().runId()).isEqualTo("run-1");
        assertThat(receipt.record().memberId()).isNull();
        assertThat(receipt.record().memberToken()).isNull();
        assertThat(receipt.record().npi()).isNull();
        assertThat(receipt.record().dateOfService()).isNull();
        assertThat(receipt.record().correlation()).isEmpty();
        assertThat(receipt.runsToday()).isNull();
        assertThat(Files.exists(todayFile())).isFalse();
        assertThat(ledger.health().failedWrites()).isEqualTo(1);
    }

    @Test
    void aDraftThatIsMissingOrIsNotARunIsTheOneThingThatIsThrown() {
        JsonlRunLedger ledger = ledger(home, clock);
        assertThatThrownBy(() -> ledger.record(null)).isInstanceOf(NullPointerException.class);

        // A note line as the ledger reads one back. It is a record of another kind, with a run id and nothing else of a run.
        ledger.record(draft("run-1", clock).build());
        ledger.addNote("run-1", "a note", "QA-17");
        RunRecord note = JsonSafe.mapper().readValue(lines(todayFile()).get(1), RunRecord.class);
        assertThat(note.recordType()).isEqualTo("note");
        byte[] before = bytes(todayFile());

        assertThatThrownBy(() -> ledger.record(note)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a run can be recorded, and this record is not one.");
        // A line of another kind that holds everything a run has. It could be copied into a run, and must not be written as one.
        RunRecord dressedAsARun = JsonSafe.mapper().readValue(
                JsonSafe.mapper().writeValueAsString(draft("run-2", clock).build()).replace("\"recordType\":\"run\"", "\"recordType\":\"audit\""),
                RunRecord.class);
        assertThat(dressedAsARun.recordType()).isEqualTo("audit");
        assertThat(dressedAsARun.runId()).isEqualTo("run-2");
        assertThatThrownBy(() -> ledger.record(dressedAsARun)).isInstanceOf(IllegalArgumentException.class);

        // Nothing was written and nothing is counted as a failed write: no run went unrecorded.
        assertThat(bytes(todayFile())).isEqualTo(before);
        assertThat(ledger.find("run-2")).isEmpty();
        assertThat(ledger.health().failedWrites()).isZero();
    }

    // ---- tracking switched off ----

    @Test
    void withTrackingOffNothingIsWrittenAndTheReceiptCarriesTheRecordAfterThePolicy() {
        JsonlRunLedger ledger = ledger(home, clock, Map.of("enabled", "false"));

        LedgerReceipt receipt = ledger.record(draft("run-1", clock).build());

        assertThat(receipt.recorded()).isFalse();
        assertThat(receipt.message()).isEqualTo("This run was NOT recorded: tracking is switched off in configuration (tracking.enabled is false).");
        assertThat(receipt.record().runId()).isEqualTo("run-1");
        assertThat(receipt.record().memberId()).isNull();
        assertThat(receipt.record().dateOfService()).isNull();
        assertThat(receipt.record().memberToken()).matches("[0-9a-f]{64}").isEqualTo(ledger.tokenFor(MEMBER_ID));
        assertThat(receipt.record().memberIdLast4()).isEqualTo("6789");
        assertThat(receipt.runsToday()).isNull();
        // Nothing at all was written: no directory, no file, and no salt file either.
        assertThat(Files.exists(ledgerDirectory(home))).isFalse();
        assertThat(Files.exists(saltFile(home))).isFalse();

        LedgerHealth health = ledger.health();
        assertThat(health.enabled()).isFalse();
        assertThat(health.writable()).isFalse();
        assertThat(health.runsToday()).isZero();
        assertThat(health.failedWrites()).isZero();
        assertThat(health.lastError()).isNull();
        assertThat(ledger.query(RunQuery.all())).isEmpty();
    }

    @Test
    void withTrackingOffAndMemberIdsRecordedTheReceiptCarriesTheId() {
        JsonlRunLedger ledger = ledger(home, clock, Map.of("enabled", "false", "record-member-id", "true"));
        RunRecord record = ledger.record(draft("run-1", clock).memberId("S1234 56789").build()).record();
        assertThat(record.memberId()).isEqualTo(MEMBER_ID);
        assertThat(record.dateOfService()).isEqualTo("2026-03-20");
    }

    @Test
    void withTrackingOffThereIsNoStartupCheck() throws IOException {
        // A place no directory can be made: underneath an ordinary file.
        Path file = Files.writeString(home.resolve("not-a-folder"), "x");
        Map<String, String> off = Map.of("enabled", "false", "directory", file.resolve("run-history").toString());

        JsonlRunLedger ledger = new JsonlRunLedger(tracking(home, off), configStore(home), EpaProperties.defaults(), clock, codeList());

        assertThat(ledger.record(draft("run-1", clock).build()).recorded()).isFalse();
        assertThat(ledger.query(RunQuery.all())).isEmpty();
        assertThat(ledger.find("run-1")).isEmpty();
        // The same place with tracking on is refused at startup.
        Map<String, String> on = Map.of("directory", file.resolve("run-history").toString());
        assertThatThrownBy(() -> new JsonlRunLedger(tracking(home, on), configStore(home), EpaProperties.defaults(), clock, codeList()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withTrackingOffAnExistingSaltIsUsedSoIdentifiersStillMatchWhatAnEarlierSessionWrote() {
        JsonlRunLedger on = ledger(home, clock);
        String token = on.record(draft("run-1", clock).build()).record().memberToken();

        JsonlRunLedger off = ledger(home, clock, Map.of("enabled", "false"));

        assertThat(off.tokenFor(MEMBER_ID)).isEqualTo(token);
        assertThat(off.matches(token, MEMBER_ID)).isTrue();
        // What was written before can still be read.
        assertThat(off.find("run-1")).isPresent();
        assertThat(off.health().runsToday()).isEqualTo(1);
        assertThat(off.addNote("run-1", "a note", null).recorded()).isFalse();
        assertThat(lines(todayFile())).hasSize(1);
    }

    // ---- startup ----

    @Test
    void startupFailsWhenTheDirectoryCannotBeCreatedAndNamesThePath() throws IOException {
        // An ordinary file where the directory should be.
        Files.writeString(ledgerDirectory(home), "not a directory");

        assertThatThrownBy(() -> ledger(home, clock)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The run history directory " + ledgerDirectory(home) + " could not be created")
                .hasMessageContaining("tracking.directory");
    }

    @Test
    void startupFailsWhenTheDirectoryExistsAndRefusesAFile() {
        // The entry a Linux process has under /proc is a folder that exists and takes no new file,
        // from an administrator either. Elsewhere this case is covered by the test below.
        Path refusing = Path.of("/proc/self");
        assumeTrue(Files.isDirectory(refusing), "needs a folder that refuses a new file even from an administrator");

        assertThatThrownBy(() -> ledger(home, clock, Map.of("directory", refusing.toString()))).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The run history directory " + refusing + " cannot be written to");
    }

    @Test
    void startupFailsWhenTheDirectoryIsReadOnly() throws IOException {
        Path directory = Files.createDirectories(ledgerDirectory(home));
        boolean changed = directory.toFile().setWritable(false);
        try {
            assumeTrue(changed && !Files.isWritable(directory),
                    "needs a user for whom a read-only folder is read-only, which an administrator is not");
            assertThatThrownBy(() -> ledger(home, clock)).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("The run history directory " + directory + " cannot be written to");
        } finally {
            directory.toFile().setWritable(true);
        }
    }

    @Test
    void startupFailsWhenTheProofByWritingFailsOnAnyMachineAndSaysNothingTheFailureSaid() {
        // The two tests before this one each need a folder that refuses a file, and not every machine
        // has one: an administrator is refused almost nowhere, and some systems ignore a read-only
        // folder. Here the proof itself is made to fail, so the refusal and its wording are checked everywhere.
        assertThatThrownBy(() -> new JsonlRunLedger(tracking(home, Map.of()), configStore(home), EpaProperties.defaults(), clock, codeList()) {
            @Override
            void proveWritable() throws IOException {
                throw new java.nio.file.AccessDeniedException("a path and a reason that must not be repeated");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessage("The run history directory " + ledgerDirectory(home) + " cannot be written to (AccessDeniedException). "
                        + "Set tracking.directory to a folder this tool can write to, or set tracking.enabled to false to run with no ledger.");
        // Refused before the salt was made: a tool that will not start leaves nothing new behind but the folder it tried.
        assertThat(Files.exists(saltFile(home))).isFalse();
    }

    @Test
    void startupFailsWhenTodaysFileCannotBeAppendedTo() throws IOException {
        Files.createDirectories(todayFile());

        assertThatThrownBy(() -> ledger(home, clock)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Today's ledger file " + todayFile() + " cannot be opened for appending");
    }

    @Test
    void startupProvesTheDirectoryByWritingToItAndLeavesNothingBehind() throws IOException {
        ledger(home, clock);
        try (Stream<Path> entries = Files.list(ledgerDirectory(home))) {
            assertThat(entries).isEmpty();
        }
        // A directory several levels down is created, not only its last part.
        Path deep = home.resolve("a").resolve("b").resolve("history");
        ledger(home, clock, Map.of("directory", deep.toString())).record(draft("run-1", clock).build());
        try (Stream<Path> entries = Files.list(deep)) {
            assertThat(entries.map(path -> path.getFileName().toString())).containsExactly("runs-2026-03-10.jsonl");
        }
    }

    @Test
    void startupRefusesASaltFileInsideTheLedgerDirectory() {
        Path inside = ledgerDirectory(home).resolve("keys").resolve("salt");

        assertThatThrownBy(() -> ledger(home, clock, Map.of("salt-file", inside.toString()))).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The salt file " + inside + " is inside the run history directory " + ledgerDirectory(home))
                .hasMessageContaining("tracking.salt-file");
        // Refused before anything was created.
        assertThat(Files.exists(ledgerDirectory(home))).isFalse();
        // A path that only looks different is the same place.
        String roundabout = ledgerDirectory(home).resolve("..").resolve("run-history").resolve("salt").toString();
        assertThatThrownBy(() -> ledger(home, clock, Map.of("salt-file", roundabout))).isInstanceOf(IllegalStateException.class);
        // Switching tracking off does not make it acceptable.
        assertThatThrownBy(() -> ledger(home, clock, Map.of("salt-file", inside.toString(), "enabled", "false")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupRefusesTheDefaultSaltFileWhenTheConfigStoreIsTheLedgerDirectory() {
        // With no salt file configured the salt goes into the config store, which here is the ledger directory itself.
        assertThatThrownBy(() -> ledger(home, clock, Map.of("directory", home.resolve("config-store").toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is inside the run history directory");
    }

    @Test
    void startupRefusesASaltFileThatReachesTheLedgerDirectoryThroughALink() throws IOException {
        Path directory = Files.createDirectories(ledgerDirectory(home));
        Path link = home.resolve("alias");
        try {
            Files.createSymbolicLink(link, directory);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "needs a file system and a user that can make a symbolic link");
        }

        assertThatThrownBy(() -> ledger(home, clock, Map.of("salt-file", link.resolve("salt").toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is inside the run history directory");
        assertThat(Files.exists(directory.resolve("salt"))).isFalse();
        // The same through a folder beneath the link that does not exist yet: creating the salt would create it inside the ledger directory.
        assertThatThrownBy(() -> ledger(home, clock, Map.of("salt-file", link.resolve("keys").resolve("deeper").resolve("salt").toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is inside the run history directory");
        assertThat(Files.exists(directory.resolve("keys"))).isFalse();
        // A link that leads somewhere else is an ordinary place for a salt.
        Path elsewhere = Files.createDirectories(home.resolve("secrets"));
        Path harmless = home.resolve("alias-to-secrets");
        Files.createSymbolicLink(harmless, elsewhere);
        ledger(home, clock, Map.of("salt-file", harmless.resolve("keys").resolve("salt").toString())).record(draft("run-1", clock).build());
        assertThat(Files.size(elsewhere.resolve("keys").resolve("salt"))).isEqualTo(32);
    }

    @Test
    void whenTheDirectoryVanishesWhileTheToolRunsEveryRunSaysItWasNotRecordedAndNothingIsPutBackInSilence() throws IOException {
        JsonlRunLedger ledger = ledger(home, clock);
        assertThat(ledger.record(draft("run-1", clock).build()).recorded()).isTrue();
        // What an unmounted share or a tidy-up script leaves: no file and no folder.
        Files.delete(todayFile());
        Files.delete(ledgerDirectory(home));

        assertThat(ledger.health().writable()).isFalse();
        LedgerReceipt receipt = ledger.record(draft("run-2", clock).build());

        assertThat(receipt.recorded()).isFalse();
        assertThat(receipt.message()).startsWith("This run was NOT recorded: the ledger file " + todayFile() + " could not be written")
                .contains("Check that the folder exists");
        assertThat(receipt.runsToday()).isNull();
        assertThat(receipt.record().runId()).isEqualTo("run-2");
        // The folder is not made again behind the tester's back: it may be a share that is coming back, and a run
        // written beneath its mount point would vanish when it did.
        assertThat(Files.exists(ledgerDirectory(home))).isFalse();
        assertThat(ledger.query(RunQuery.all())).isEmpty();
        assertThat(ledger.health().failedWrites()).isEqualTo(1);

        // Once the folder is back, the next run is kept. No restart is needed.
        Files.createDirectories(ledgerDirectory(home));
        assertThat(ledger.record(draft("run-3", clock).build()).recorded()).isTrue();
        assertThat(ledger.health().writable()).isTrue();
        assertThat(ledger.query(RunQuery.all())).extracting(RunRecord::runId).containsExactly("run-3");
    }

    @Test
    void theSaltIsCreatedOnceOutsideTheLedgerDirectoryAndOnlyItsOwnerMayReadIt() throws IOException {
        Path elsewhere = home.resolve("secrets").resolve("pave.salt");
        JsonlRunLedger ledger = ledger(home, clock, Map.of("salt-file", elsewhere.toString()));
        ledger.record(draft("run-1", clock).build());

        assertThat(Files.size(elsewhere)).isEqualTo(32);
        // The default location is not used when one is configured.
        assertThat(Files.exists(saltFile(home))).isFalse();
        try (Stream<Path> entries = Files.list(ledgerDirectory(home))) {
            assertThat(entries.map(path -> path.getFileName().toString())).containsExactly("runs-2026-03-10.jsonl");
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(elsewhere)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void startupRefusesASaltFileTooShortToProtectAnything() throws IOException {
        Files.createDirectories(saltFile(home).getParent());
        Files.writeString(saltFile(home), "short");

        assertThatThrownBy(() -> ledger(home, clock)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The salt file " + saltFile(home) + " holds 5 bytes");
    }

    @Test
    void aSaltFileAnOperatorSuppliesIsUsedAsItIs() throws IOException {
        Files.createDirectories(saltFile(home).getParent());
        Files.writeString(saltFile(home), "an operator's own passphrase, long enough");
        String token = ledger(home, clock).tokenFor(MEMBER_ID);

        Files.createDirectories(saltFile(home.resolve("other")).getParent());
        Files.writeString(saltFile(home.resolve("other")), "an operator's own passphrase, long enough");

        // The same salt on another installation gives the same token, which is how a ledger is moved.
        assertThat(ledger(home.resolve("other"), clock).tokenFor(MEMBER_ID)).isEqualTo(token);
        assertThat(Files.readString(saltFile(home))).isEqualTo("an operator's own passphrase, long enough");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void recordingMemberIdsOnLiveIsHonouredWithExactlyOneWarningThatNamesTheDirectory(CapturedOutput output) {
        String warning = "tracking.record-member-id is true on the live profile";
        EpaProperties live = EpaProperties.of(Map.of("mode", "live"));

        JsonlRunLedger ledger = new JsonlRunLedger(tracking(home, Map.of("record-member-id", "true")), configStore(home), live, clock, codeList());
        for (int i = 0; i < 3; i++) {
            ledger.record(draft("run-" + i, clock).build());
        }

        // Honoured: the ID is written.
        assertThat(text(todayFile())).contains("\"memberId\":\"" + MEMBER_ID + "\"");
        assertThat(output.getAll().split(java.util.regex.Pattern.quote(warning), -1)).hasSize(2);
        assertThat(output.getAll()).contains(ledgerDirectory(home).toString());
        // And nothing that was logged holds the member ID.
        assertThat(output.getAll()).doesNotContain(MEMBER_ID);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void thereIsNoWarningOnMockOrWhenMemberIdsAreNotRecorded(CapturedOutput output) {
        recordingIds(home.resolve("mock"), clock).record(draft("run-1", clock).build());
        new JsonlRunLedger(tracking(home.resolve("live"), Map.of()), configStore(home.resolve("live")), EpaProperties.of(Map.of("mode", "live")),
                clock, codeList()).record(draft("run-2", clock).build());

        assertThat(output.getAll()).doesNotContain("tracking.record-member-id");
    }

    // ---- counting and serialising ----

    @Test
    void theCountIsOfRunsInTodaysFileAndANoteIsNotARun() {
        JsonlRunLedger ledger = ledger(home, clock);
        assertThat(ledger.health().runsToday()).isZero();
        assertThat(ledger.record(draft("run-1", clock).build()).runsToday()).isEqualTo(1);
        assertThat(ledger.record(draft("run-2", clock).build()).runsToday()).isEqualTo(2);
        assertThat(ledger.addNote("run-1", "a note", "QA-17").runsToday()).isEqualTo(2);
        assertThat(ledger.health().runsToday()).isEqualTo(2);

        // The count comes from the file, so it survives a restart.
        JsonlRunLedger restarted = ledger(home, clock);
        assertThat(restarted.health().runsToday()).isEqualTo(2);
        assertThat(restarted.record(draft("run-3", clock).build()).runsToday()).isEqualTo(3);
        // A line the first instance did not write is still counted by it.
        assertThat(ledger.record(draft("run-4", clock).build()).runsToday()).isEqualTo(4);
        assertThat(ledger.health().lastWriteAt()).isEqualTo("2026-03-10T11:00:00-04:00");
    }

    @Test
    void runsThatFinishTogetherEachGetAWholeLine() throws Exception {
        JsonlRunLedger ledger = ledger(home, clock);
        int threads = 8;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<LedgerReceipt>>> pending = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                pending.add(pool.submit(() -> {
                    start.await();
                    List<LedgerReceipt> receipts = new ArrayList<>();
                    for (int i = 0; i < each; i++) {
                        receipts.add(ledger.record(draft("run-" + thread + "-" + i, clock).note("a note long enough to make a line that "
                                + "would show if two writes ran into each other, " + "x".repeat(400)).build()));
                    }
                    return receipts;
                }));
            }
            start.countDown();
            List<String> expected = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                List<LedgerReceipt> receipts = pending.get(t).get(60, TimeUnit.SECONDS);
                assertThat(receipts).allMatch(LedgerReceipt::recorded);
                for (int i = 0; i < each; i++) {
                    expected.add("run-" + t + "-" + i);
                }
            }
            List<String> written = lines(todayFile());
            assertThat(written).hasSize(threads * each);
            // Every line reads back as a whole record, and every run is there exactly once.
            assertThat(written.stream().map(line -> JsonSafe.mapper().readValue(line, RunRecord.class).runId()).toList())
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(ledger.health().runsToday()).isEqualTo(threads * each);
            assertThat(ledger.health().unreadableLines()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
