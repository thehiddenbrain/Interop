package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ledger line must never hold member data or response JSON, under any configuration. Having no
 * component named for them is not enough: several components are filled from what a service
 * returned, and a service can put anything there. These tests push a surname, a date of birth, an
 * address and a JSON fragment through every such component and read the serialised line back.
 */
class LedgerLineSafetyTest {

    private static final String SURNAME = "Placeholder-Surname";
    private static final String BIRTH = "1970-01-01";
    private static final String SENTENCE = "Auth needed for " + SURNAME + " born " + BIRTH;
    private static final String JSON = "{\"patient\":\"" + SURNAME + "\",\"subscriberId\":\"SUB-1\"}";
    private static final String ADDRESS = SURNAME + ", authorized representative, 1 Placeholder Street";

    private static RunRecord.Builder minimal() {
        return RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.UNRECOGNISED_CODE).result(TestResult.UNVERIFIED);
    }

    private static String line(RunRecord record) {
        return JsonSafe.mapper().writeValueAsString(record);
    }

    private static void assertHoldsNoMemberData(String line) {
        assertThat(line).doesNotContain(SURNAME).doesNotContain(BIRTH).doesNotContain("SUB-1").doesNotContain("Placeholder Street")
                .doesNotContain("subscriberId").doesNotContain("authorized representative");
    }

    @Test
    void textWhereACodeBelongsIsNotWrittenAndTheFactThatItWasThereIs() {
        RunRecord record = minimal().coverageCodes(List.of("pa-needed=" + SENTENCE, "doc-needed=" + JSON, "95810: covered=" + SENTENCE,
                SENTENCE, "covered=covered")).build();
        assertThat(record.coverageCodes()).containsExactly(
                "pa-needed=(not a code, " + SENTENCE.length() + " characters)",
                "doc-needed=(not a code, " + JSON.length() + " characters)",
                "95810: covered=(not a code, " + SENTENCE.length() + " characters)",
                "(not a code entry, " + SENTENCE.length() + " characters)",
                "covered=covered");
        assertHoldsNoMemberData(line(record));
    }

    @Test
    void whatHasTheShapeOfACodeIsKeptIncludingOneTheToolDoesNotRecognise() {
        // An unrecognised code is the evidence a tester needs, so it must survive.
        List<String> codes = List.of("covered=covered", "pa-needed=auth-maybe", "pa-needed=No-Auth", "95810: pa-needed=auth-needed",
                "95810, E0601: doc-needed=no-doc", "cards[0].extension[0]: pa-needed=no-auth", "ServiceRequest/service-request-2: (no answer)",
                "95811: (no answer)", "billing=http://www.ama-assn.org/go/cpt|95810");
        assertThat(minimal().coverageCodes(codes).build().coverageCodes()).isEqualTo(codes);
    }

    @Test
    void onlyAVendorThisToolKnowsIsWrittenAsAVendor() {
        assertThat(minimal().delegatedVendor("EVICORE").build().delegatedVendor()).isEqualTo("eviCore");
        assertThat(minimal().delegatedVendor("Evolent; optum post-acute").build().delegatedVendor()).isEqualTo("Evolent; Optum Post Acute");
        // Under a vendor code a service can return any text. It is shown on screen and not stored.
        RunRecord record = minimal().delegatedVendor(ADDRESS).build();
        assertThat(record.delegatedVendor()).isEqualTo("(vendor not on the known list, " + ADDRESS.length() + " characters)");
        assertHoldsNoMemberData(line(record));
        assertThat(minimal().delegatedVendor("Some New Vendor").build().delegatedVendor())
                .isEqualTo("(vendor not on the known list, 15 characters)");
        assertThat(minimal().delegatedVendor(" ").build().delegatedVendor()).isNull();
    }

    @Test
    void aCorrelationValueIsOneLineWithNoQuotesOrBracesAndOnlyUnderAKnownKey() {
        RunRecord record = minimal().correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, JSON, RunRecord.CORR_CLAIM_ID, "claim-1",
                RunRecord.CORR_AUTHORIZATION_REF, "line one\nline two")).build();
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_QUESTIONNAIRE, "(not recorded, " + JSON.length() + " characters)")
                .containsEntry(RunRecord.CORR_CLAIM_ID, "claim-1")
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "(not recorded, 17 characters)");
        assertHoldsNoMemberData(line(record));
        // Canonical urls, several joined by a space, are what the questionnaire key is for.
        String canonicals = "https://example.org/Questionnaire/q1|1.0 https://example.org/Questionnaire/q2";
        assertThat(minimal().correlation(Map.of(RunRecord.CORR_QUESTIONNAIRE, canonicals)).build().correlation())
                .containsEntry(RunRecord.CORR_QUESTIONNAIRE, canonicals);
        // An open map would be somewhere to put a response. A key that is not one of the record's own is a defect.
        assertThatThrownBy(() -> minimal().correlation(Map.of("rawResponse", "x")).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProcedureCodeAndALineOfBusinessAreShortLabels() {
        RunRecord record = minimal().procedureCodes(List.of("95810", "E0601", "95810-26", SENTENCE)).lineOfBusiness(ADDRESS + " " + JSON).build();
        assertThat(record.procedureCodes()).containsExactly("95810", "E0601", "95810-26", "(not a code, " + SENTENCE.length() + " characters)");
        assertThat(record.lineOfBusiness()).startsWith("(not a line of business, ");
        assertHoldsNoMemberData(line(record));
        assertThat(minimal().lineOfBusiness("THP Medicare").build().lineOfBusiness()).isEqualTo("THP Medicare");
        assertThat(minimal().lineOfBusiness("Medicaid/CHIP (MassHealth)").build().lineOfBusiness()).isEqualTo("Medicaid/CHIP (MassHealth)");
    }

    @Test
    void theSameChecksApplyToALineThatIsReadAndToACopy() {
        // Whoever constructs the record: the builder, a copy, or the JSON reader.
        RunRecord built = minimal().coverageCodes(List.of("covered=covered")).build();
        String tampered = line(built).replace("\"covered=covered\"", "\"pa-needed=" + SENTENCE + "\"");
        RunRecord read = JsonSafe.mapper().readValue(tampered, RunRecord.class);
        assertThat(read.coverageCodes()).containsExactly("pa-needed=(not a code, " + SENTENCE.length() + " characters)");
        assertThat(built.toBuilder().delegatedVendor(ADDRESS).build().delegatedVendor()).startsWith("(vendor not on the known list");
    }

    @Test
    void aRunLineThatLacksWhatMakesItARunIsRefusedAndNeverReadAsOne() {
        // A line holding only a timestamp must fail to read, so the ledger counts it as unreadable.
        assertThatThrownBy(() -> JsonSafe.mapper().readValue("{\"timestamp\":\"2026-01-05T10:15:30.000-05:00\"}", RunRecord.class))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().runId(" ").build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().outcome((String) null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().result(null).build()).isInstanceOf(IllegalStateException.class);
        // A number is not read as a position in an enum: 6 must not come back as some operation.
        String ordinals = line(minimal().build()).replace("\"protocol\":\"CRD\"", "\"protocol\":2").replace("\"operation\":\"CRD_ORDER_SIGN\"", "\"operation\":6");
        assertThatThrownBy(() -> JsonSafe.mapper().readValue(ordinals, RunRecord.class)).isInstanceOf(RuntimeException.class);
    }
}
