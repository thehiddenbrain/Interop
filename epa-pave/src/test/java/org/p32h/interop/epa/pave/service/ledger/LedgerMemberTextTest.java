package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.MEMBER_ID;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.lines;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.recordingIds;

import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The three ways in the second member data audit found for a member ID, a name or a date of birth
 * to reach a line on the live profile with the default settings: the free text typed with a run,
 * the run's own member ID typed where a procedure code goes, and an identifier a service supplies.
 * Each test writes through the real ledger with member IDs tokenised and reads the file back.
 */
class LedgerMemberTextTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), LedgerFixtures.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final String UUID = "0d1e2f3a-4b5c-4d6e-8f70-123456789abc";

    @TempDir
    Path home;

    private JsonNode onlyLine() {
        List<String> written = lines(dayFile(home, TODAY));
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).doesNotContain("S123456789").doesNotContainIgnoringCase("s12345").doesNotContain("1961")
                .doesNotContain("Zzlastname");
        return JsonSafe.parse(written.get(0)).node();
    }

    private static String text(JsonNode line, String field) {
        return JsonSafe.text(line, field);
    }

    @Test
    void aNoteATesterReferenceOrAnExpectationThatHoldsTheRunsOwnMemberIdIsWrittenAsItsLength() {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        String note = "Called about member s12345 6789 today";
        ledger.record(draft("run-1", CLOCK).note(note).testerRef("QA-S123456789").expected("S-123-456-789: covered=covered")
                .build());

        JsonNode line = onlyLine();
        assertThat(text(line, "note")).isEqualTo("(note not recorded, " + note.length() + " characters)");
        assertThat(text(line, "testerRef")).isEqualTo("(tester reference not recorded, 13 characters)");
        assertThat(text(line, "expected")).isEqualTo("(expectation not recorded, 30 characters)");
        assertThat(text(line, "memberToken")).matches("[0-9a-f]{64}");
        // Read back, the statements stay as they were written.
        RunRecord read = ledger.find("run-1").orElseThrow();
        assertThat(read.note()).isEqualTo("(note not recorded, " + note.length() + " characters)");
        assertThat(read.testerRef()).isEqualTo("(tester reference not recorded, 13 characters)");
    }

    @Test
    void freeTextThatDoesNotHoldTheMemberIdIsKeptAsTyped() {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        ledger.record(draft("run-1", CLOCK).note("second try after the 6789 fix, ticket 12345").testerRef("QA-17").build());

        List<String> written = lines(dayFile(home, TODAY));
        JsonNode line = JsonSafe.parse(written.get(0)).node();
        assertThat(text(line, "note")).isEqualTo("second try after the 6789 fix, ticket 12345");
        assertThat(text(line, "testerRef")).isEqualTo("QA-17");
    }

    @Test
    void theRunsOwnMemberIdTypedWhereAProcedureCodeGoesIsNotKeptAsACodeNorAsTheSubjectOfOne() {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        ledger.record(draft("run-1", CLOCK).procedureCodes(List.of(MEMBER_ID, "95810", "SUBZZ9988776", "E0601"))
                .coverageCodes(List.of(MEMBER_ID + ": covered=covered", MEMBER_ID + ", 95810: pa-needed=auth-needed",
                        MEMBER_ID + ": (no answer)", "ServiceRequest/Zzlastname-1961-07-23: (no answer)", "95810: (no answer)",
                        "ServiceRequest/" + UUID + ": covered=covered", MEMBER_ID + ": doc-needed=words in place of a code"))
                .build());

        JsonNode line = onlyLine();
        assertThat(JsonSafe.elements(line.get("procedureCodes")).stream().map(JsonNode::asString).toList())
                .containsExactly("(not a code, 10 characters)", "95810", "(not a code, 12 characters)", "E0601");
        assertThat(JsonSafe.elements(line.get("coverageCodes")).stream().map(JsonNode::asString).toList()).containsExactly(
                "(not a code, 10 characters): covered=covered",
                "(not a code, 10 characters), 95810: pa-needed=auth-needed",
                "(not a code, 10 characters): (no answer)",
                "(not a code, 36 characters): (no answer)",
                "95810: (no answer)",
                "ServiceRequest/" + UUID + ": covered=covered",
                "(not a code, 10 characters): doc-needed=(not a code, 24 characters)");
        RunRecord read = ledger.find("run-1").orElseThrow();
        assertThat(read.coverageCodes()).first().isEqualTo("(not a code, 10 characters): covered=covered");
    }

    @Test
    void theMemberIdTypedAsAProcedureCodeIsNotKeptEvenWhereMemberIdsAreRecorded() {
        JsonlRunLedger ledger = recordingIds(home, CLOCK);
        ledger.record(draft("run-1", CLOCK).memberId("S000000222").procedureCodes(List.of("S000000222")).build());
        RunRecord read = ledger.find("run-1").orElseThrow();
        assertThat(read.procedureCodes()).containsExactly("(not a code, 10 characters)");
    }

    @Test
    void anIdentifierAServiceSuppliesIsWrittenAsATokenUnlessItCannotBeAnybodysData() {
        // The notification the auditor used, and the neighbours the independent check found kept in
        // clear (N2: a member ID whose digits a separator splits) or no longer kept at all (N4: a
        // number a FHIR server mints). Shape cannot tell these apart, so each is a token.
        JsonlRunLedger ledger = ledger(home, CLOCK);
        List<String> given = List.of("sub-S123456789", "Zzlastname-19610723", "S123456789", "S123-456-789", "S12345.6789", "s12345_6789",
                "ZZQ12345-01", "1052344", "cr-1052344");
        for (int i = 0; i < given.size(); i++) {
            Map<String, String> correlation = new LinkedHashMap<>();
            correlation.put(RunRecord.CORR_SUBSCRIPTION_ID, given.get(i));
            correlation.put(RunRecord.CORR_CLAIM_ID, given.get(i));
            correlation.put(RunRecord.CORR_CLAIM_RESPONSE_ID, given.get(i));
            correlation.put(RunRecord.CORR_RUN_ID, "run-" + UUID);
            ledger.record(draft("run-" + i, CLOCK).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_NOTIFICATION)
                    .memberId(null).correlation(correlation).build());
        }
        List<String> written = lines(dayFile(home, TODAY));
        assertThat(written).hasSize(given.size());
        for (int i = 0; i < given.size(); i++) {
            JsonNode kept = JsonSafe.parse(written.get(i)).node().get("correlation");
            String token = ledger.tokenFor(given.get(i));
            assertThat(text(kept, "subscriptionId")).as(given.get(i)).isEqualTo(token);
            assertThat(text(kept, "claimId")).as(given.get(i)).isEqualTo(token);
            assertThat(text(kept, "claimResponseId")).as(given.get(i)).isEqualTo(token);
            assertThat(text(kept, "correlatedRunId")).isEqualTo("run-" + UUID);
            assertThat(written.get(i)).doesNotContain(given.get(i)).doesNotContain("Zzlastname");
        }
        // A token is matched as the identifier is, so a notification still finds its run, a numeric id included.
        RunRecord numeric = ledger.query(org.p32h.interop.epa.pave.model.RunQuery.builder()
                .correlation(RunRecord.CORR_SUBSCRIPTION_ID, "1052344").build()).get(0);
        assertThat(numeric.runId()).isEqualTo("run-7");
    }

    @Test
    void theIdentifiersThisToolMintsAUuidAndAnOidAreKeptInClear() {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        Map<String, String> correlation = new LinkedHashMap<>();
        correlation.put(RunRecord.CORR_CLAIM_ID, "claim-" + UUID);
        correlation.put(RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-claim-" + UUID);
        correlation.put(RunRecord.CORR_SUBSCRIPTION_ID, "urn:oid:2.16.840.1.113883.4.642");
        correlation.put(RunRecord.CORR_SUBSCRIPTION_RUN_ID, "run-" + UUID);
        ledger.record(draft("run-1", CLOCK).protocol(Protocol.SUBSCRIPTION).operation(EpaOperation.SUBSCRIPTION_NOTIFICATION).memberId(null)
                .correlation(correlation).build());

        JsonNode kept = JsonSafe.parse(lines(dayFile(home, TODAY)).get(0)).node().get("correlation");
        assertThat(text(kept, "claimId")).isEqualTo("claim-" + UUID);
        assertThat(text(kept, "claimResponseId")).isEqualTo("claimresponse-claim-" + UUID);
        assertThat(text(kept, "subscriptionId")).isEqualTo("urn:oid:2.16.840.1.113883.4.642");
        assertThat(text(kept, "subscriptionRunId")).isEqualTo("run-" + UUID);
        assertThat(RunRecord.nobodysData(UUID)).isTrue();
        assertThat(RunRecord.nobodysData("2.16.840.1.113883.4.642")).isTrue();
        assertThat(RunRecord.nobodysData("sub-" + UUID)).isFalse();
        assertThat(RunRecord.nobodysData("subscription-placeholder-0001")).isFalse();
        assertThat(RunRecord.keepsIdentifier("sub-S123456789")).isTrue();
        assertThat(RunRecord.keepsIdentifier("two words")).isFalse();
    }

    @Test
    void anIdentifierALineWrittenEarlierKeptInClearIsReadAsItsTokenAndStillMatched() throws java.io.IOException {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        ledger.record(draft("run-0", CLOCK).build());
        // As the first fix wrote it: a member ID with a separator, kept in clear by its shape.
        String older = lines(dayFile(home, TODAY)).get(0).replace("\"run-0\"", "\"run-1\"").replace("\"hook\"",
                "\"correlation\":{\"subscriptionId\":\"S123-456-789\"},\"hook\"");
        java.nio.file.Files.writeString(dayFile(home, TODAY), older + "\n", java.nio.file.StandardOpenOption.APPEND);

        RunRecord read = ledger.find("run-1").orElseThrow();
        assertThat(read.correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, ledger.tokenFor("S123-456-789"));
        assertThat(ledger.query(org.p32h.interop.epa.pave.model.RunQuery.builder().correlation(RunRecord.CORR_SUBSCRIPTION_ID, "S123-456-789")
                .build())).extracting(RunRecord::runId).containsExactly("run-1");
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.query(org.p32h.interop.epa.pave.model.RunQuery.all()))).doesNotContain("S123-456-789");
    }

    @Test
    void aNoteAddedLaterThatHoldsTheMemberIdIsRefusedWhetherTheLineHoldsTheIdOrItsToken() {
        JsonlRunLedger tokenised = ledger(home, CLOCK);
        tokenised.record(draft("run-1", CLOCK).build());
        for (String note : List.of("member S123456789 again", "member s123456789", "member S12345 6789 called", "xxS123456789yy")) {
            ValidationException refused = Assertions.catchThrowableOfType(ValidationException.class,
                    () -> tokenised.addNote("run-1", note, null));
            assertThat(refused).as(note).isNotNull();
            assertThat(refused.fieldErrors()).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo("note");
                assertThat(error.message()).contains("member ID").doesNotContain("S123456789");
            });
        }
        ValidationException ref = Assertions.catchThrowableOfType(ValidationException.class,
                () -> tokenised.addNote("run-1", null, "S123456789"));
        assertThat(ref.fieldErrors()).singleElement().satisfies(error -> assertThat(error.field()).isEqualTo("testerRef"));
        assertThat(tokenised.addNote("run-1", "checked with the payer, ticket 6789", "QA-18").recorded()).isTrue();
        assertThat(lines(dayFile(home, TODAY))).noneMatch(written -> written.contains("S123456789") || written.contains("s123456789"));

        JsonlRunLedger recording = recordingIds(home.resolve("recording"), CLOCK);
        recording.record(draft("run-2", CLOCK).build());
        Assertions.assertThatThrownBy(() -> recording.addNote("run-2", "about S-1234-56789", null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aNoteAddedLaterWithTheMemberIdInFullwidthOrAsItsDigitsAloneIsRefusedOnATokenLine() {
        // Found by the independent check of the member data fixes (N3).
        JsonlRunLedger tokenised = ledger(home, CLOCK);
        tokenised.record(draft("run-1", CLOCK).build());
        for (String note : List.of("member S\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19", "member 123456789",
                "member 123 456 789")) {
            ValidationException refused = Assertions.catchThrowableOfType(ValidationException.class,
                    () -> tokenised.addNote("run-1", note, null));
            assertThat(refused).as(note).isNotNull();
            assertThat(refused.fieldErrors()).singleElement().satisfies(error -> assertThat(error.field()).isEqualTo("note"));
        }
        assertThat(tokenised.addNote("run-1", "member 223456789 is another one", null).recorded()).isTrue();
        assertThat(lines(dayFile(home, TODAY))).hasSize(2).noneMatch(written -> written.contains("member 123")
                || written.contains("\uFF11\uFF12\uFF13"));
    }

    @Test
    void theRecordDoesNotKeepFreeTextHoldingTheMemberIdInFullwidthOrAsItsDigitsAndAUuidsDigitsAreNotTheIds() {
        // Found by the independent check of the member data fixes (N3): the record is the last line.
        RunRecord record = draft("run-1", CLOCK).note("about S\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19")
                .testerRef("QA 123-456-789").correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-" + UUID)).build();
        assertThat(record.note()).isEqualTo("(note not recorded, 16 characters)");
        assertThat(record.testerRef()).isEqualTo("(tester reference not recorded, 14 characters)");
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_CLAIM_ID, "claim-" + UUID);
    }

    @Test
    void aNoteAnOlderVersionAppendedWithTheMemberIdIsReplacedWhenTheLineIsReadAndExported() throws java.io.IOException {
        JsonlRunLedger ledger = ledger(home, CLOCK);
        ledger.record(draft("run-1", CLOCK).build());
        // As a version before the second member data audit would have appended it: kept as typed.
        java.nio.file.Files.writeString(dayFile(home, TODAY), "{\"recordType\":\"note\",\"runId\":\"run-1\",\"timestamp\":"
                + "\"2026-03-10T11:05:00-04:00\",\"note\":\"old note about s12345 6789\",\"testerRef\":\"QA-S123456789\"}\n",
                java.nio.file.StandardOpenOption.APPEND);

        RunRecord read = ledger.find("run-1").orElseThrow();
        assertThat(read.note()).isEqualTo("(note not recorded, 26 characters)");
        assertThat(read.testerRef()).isEqualTo("(tester reference not recorded, 13 characters)");
        String exported = new String(RunCsv.bytes(ledger.query(org.p32h.interop.epa.pave.model.RunQuery.builder().build())),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(exported).doesNotContainIgnoringCase("s12345").contains("(note not recorded, 26 characters)");
    }
}
