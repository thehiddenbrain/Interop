package org.p32h.interop.epa.pave.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The shared stand-in for the ledger behaves as the real one does wherever a caller can tell the
 * difference. A fake that is kinder than the real thing lets a caller pass its tests and fail in
 * the running tool, so each rule here is one the real ledger has and its own tests pin.
 */
class InMemoryLedgerTest {

    private static final String NUMBER = "PA-PLACEHOLDER 0001";

    private final InMemoryLedger ledger = new InMemoryLedger();

    private static RunRecord.Builder run(String runId, String outcome) {
        return RunRecord.builder().runId(runId).timestamp("2026-03-10T10:15:30.000-04:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome(outcome).result(TestResult.UNVERIFIED);
    }

    // ---- a ledger that cannot be asked ----

    @Test
    void aQuestionCanBeMadeToFailWhileAWriteStillWorks() {
        ledger.record(run("run-1", "APPROVED").build());
        IllegalStateException gone = new IllegalStateException("the ledger directory is gone");

        assertThat(ledger.throwingOnRead(gone)).isSameAs(ledger);

        assertThatThrownBy(() -> ledger.query(RunQuery.all())).isSameAs(gone);
        assertThatThrownBy(() -> ledger.query(null)).isSameAs(gone);
        assertThatThrownBy(() -> ledger.find("run-1")).isSameAs(gone);
        // Writing is another matter, and a test can still look at what was written.
        assertThat(ledger.record(run("run-2", "DENIED").build()).recorded()).isTrue();
        assertThat(ledger.records()).hasSize(2);
        assertThat(ledger.last().runId()).isEqualTo("run-2");

        // And the two failures are separate: a ledger that cannot be written can still be asked.
        InMemoryLedger other = new InMemoryLedger();
        other.record(run("run-1", "APPROVED").build());
        other.throwing(new IllegalStateException("disk full"));
        assertThat(other.query(RunQuery.all())).hasSize(1);
        assertThat(other.find("run-1")).isPresent();

        // Given nothing to throw, it answers again.
        ledger.throwingOnRead(null);
        assertThat(ledger.query(RunQuery.all())).hasSize(2);
    }

    // ---- the outcome filter ----

    @Test
    void theOutcomeFilterIgnoresCaseAndTheSpaceAroundIt() {
        ledger.record(run("run-1", "APPROVED").build());
        ledger.record(run("run-2", "DENIED").build());
        for (String asked : new String[] {"APPROVED", "approved", "Approved", " approved "}) {
            assertThat(ledger.query(RunQuery.builder().outcome(asked).build())).as("[" + asked + "]").extracting(RunRecord::runId)
                    .containsExactly("run-1");
        }
        assertThat(ledger.query(RunQuery.builder().outcome("PENDED").build())).isEmpty();
        assertThat(ledger.query(RunQuery.builder().outcome("APPROVE").build())).isEmpty();
    }

    // ---- notes ----

    @Test
    void aNoteThatIsLeftOutIsKeptABlankOneIsClearedAndTheTesterReferenceIsRecorded() {
        ledger.record(run("run-1", "APPROVED").note("first reading").testerRef("QA-1").build());

        // Only the tester reference is given: the note stays as it was.
        LedgerReceipt reference = ledger.addNote("run-1", null, "QA-2");
        assertThat(reference.recorded()).isTrue();
        assertThat(reference.record().note()).isEqualTo("first reading");
        assertThat(reference.record().testerRef()).isEqualTo("QA-2");
        // Only the note is given: the tester reference stays as it was. What is given is trimmed.
        LedgerReceipt note = ledger.addNote("run-1", "  second reading ", null);
        assertThat(note.record().note()).isEqualTo("second reading");
        assertThat(note.record().testerRef()).isEqualTo("QA-2");
        // A blank value is a value: it clears.
        assertThat(ledger.addNote("run-1", "   ", null).record().note()).isNull();
        assertThat(ledger.addNote("run-1", null, "").record().testerRef()).isNull();

        // What a later read returns is what the receipt said.
        RunRecord kept = ledger.find("run-1").orElseThrow();
        assertThat(kept.note()).isNull();
        assertThat(kept.testerRef()).isNull();
        assertThat(ledger.addNote("run-1", "third", "QA-3").record()).isEqualTo(ledger.find("run-1").orElseThrow());
        assertThat(ledger.query(RunQuery.all())).singleElement().satisfies(run -> {
            assertThat(run.note()).isEqualTo("third");
            assertThat(run.testerRef()).isEqualTo("QA-3");
        });
        // Nothing else of the run is touched.
        assertThat(ledger.only().outcome()).isEqualTo("APPROVED");
    }

    @Test
    void aNoteOnARunTheLedgerDoesNotHoldIsNotFoundAndOneWithNothingInItIsRefused() {
        ledger.record(run("run-1", "APPROVED").note("first reading").build());
        assertThatThrownBy(() -> ledger.addNote("run-unknown", "a note", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no run with that id.");
        assertThatThrownBy(() -> ledger.addNote(null, "a note", null)).isInstanceOf(NotFoundException.class);
        // Neither a note nor a tester reference: nothing to change, and the caller is told.
        assertThatThrownBy(() -> ledger.addNote("run-1", null, null)).isInstanceOf(ValidationException.class);
        assertThat(ledger.only().note()).isEqualTo("first reading");
    }

    // ---- an authorization number stored as a token ----

    @Test
    void anAuthorizationNumberCanBeStoredAsATokenAndIsThenMatchedByTheNumberItself() {
        InMemoryLedger tokenising = new InMemoryLedger().tokenisingAuthorizationRefs();
        Map<String, String> correlation = new LinkedHashMap<>();
        correlation.put(RunRecord.CORR_CLAIM_ID, "claim-test-0001");
        correlation.put(RunRecord.CORR_AUTHORIZATION_REF, NUMBER);

        LedgerReceipt receipt = tokenising.record(run("run-1", "APPROVED").correlation(correlation).build());

        // The receipt carries the record as written, and what is written is a token: the same every time, whatever the spacing.
        String stored = receipt.record().correlation().get(RunRecord.CORR_AUTHORIZATION_REF);
        assertThat(stored).matches("[0-9a-f]{64}").isEqualTo(tokenising.tokenFor(NUMBER)).isEqualTo(tokenising.tokenFor("PA-PLACEHOLDER0001"));
        assertThat(tokenising.tokenFor("PA-PLACEHOLDER-0002")).isNotEqualTo(stored);
        assertThat(tokenising.only()).isEqualTo(receipt.record());
        // Every other identifier is written as given.
        assertThat(receipt.record().correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, "claim-test-0001");

        // A caller asks with the number it holds, never with a token, and the ledger does the comparing.
        assertThat(tokenising.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, NUMBER))).hasSize(1);
        assertThat(tokenising.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER0001"))).hasSize(1);
        assertThat(tokenising.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0002"))).isEmpty();
        assertThat(tokenising.query(RunQuery.correlated(RunRecord.CORR_CLAIM_ID, "claim-test-0001"))).hasSize(1);
        assertThat(tokenising.matches(stored, NUMBER)).isTrue();
        assertThat(tokenising.matches(stored, "PA-PLACEHOLDER-0002")).isFalse();
        // A line written before the policy changed holds the number itself, and still matches.
        assertThat(tokenising.matches("PA-PLACEHOLDER0001", NUMBER)).isTrue();
        // Comparing the two as text, which is what a caller must never do, would find nothing.
        assertThat(stored).isNotEqualTo(NUMBER).doesNotContain("PLACEHOLDER");
    }

    @Test
    void whatTheRecordWroteInPlaceOfAValueItRefusedIsNotTokenisedAndANumberThatIsBlankIsNotWritten() {
        InMemoryLedger tokenising = new InMemoryLedger().tokenisingAuthorizationRefs();
        // The record replaces a value that is not one line of plain text with a statement of its length.
        LedgerReceipt refused = tokenising.record(run("run-1", "APPROVED")
                .correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "{\"number\":\"PA-PLACEHOLDER-0001\"}")).build());
        assertThat(refused.record().correlation().get(RunRecord.CORR_AUTHORIZATION_REF)).startsWith("(not recorded, ").endsWith(" characters)");

        LedgerReceipt blank = tokenising.record(run("run-2", "APPROVED").correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, "   ")).build());
        assertThat(blank.record().correlation()).doesNotContainKey(RunRecord.CORR_AUTHORIZATION_REF);
        assertThat(tokenising.tokenFor("  ")).isNull();
        assertThat(tokenising.tokenFor(null)).isNull();
    }

    @Test
    void withoutThatOptionADraftIsStoredExactlyAsGivenAndAnIdentifierIsMatchedWhateverItsSpacing() {
        RunRecord draft = run("run-1", "APPROVED").memberId("S12345678   01").correlation(Map.of(RunRecord.CORR_AUTHORIZATION_REF, NUMBER)).build();
        assertThat(ledger.record(draft).record()).isSameAs(draft);
        assertThat(ledger.only()).isSameAs(draft);
        assertThat(ledger.query(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER0001"))).hasSize(1);
        assertThat(ledger.tokenFor(NUMBER)).isEqualTo("PA-PLACEHOLDER0001");
        assertThat(ledger.tokenFor(" ")).isNull();
        assertThat(ledger.matches(NUMBER, "PA-PLACEHOLDER0001")).isTrue();
        assertThat(ledger.matches(NUMBER, "PA-PLACEHOLDER-0002")).isFalse();
    }

    @Test
    void twoIdentifiersThatAreNotThereNeverMatch() {
        for (InMemoryLedger each : new InMemoryLedger[] {ledger, new InMemoryLedger().tokenisingAuthorizationRefs()}) {
            assertThat(each.matches("", "")).isFalse();
            assertThat(each.matches("  ", " ")).isFalse();
            assertThat(each.matches(null, null)).isFalse();
            assertThat(each.matches(NUMBER, null)).isFalse();
            assertThat(each.matches(null, NUMBER)).isFalse();
            assertThat(each.matches(" ", NUMBER)).isFalse();
        }
        // So a run with a blank identifier under a key is not found by a blank question.
        ledger.record(run("run-1", "APPROVED").correlation(Map.of(RunRecord.CORR_CLAIM_ID, " ")).build());
        assertThat(ledger.query(RunQuery.correlated(RunRecord.CORR_CLAIM_ID, " "))).isEmpty();
    }
}
