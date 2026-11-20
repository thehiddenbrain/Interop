package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrNextQuestionRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.pas.PasInquireRequest;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;

/**
 * A run that sends a body of the tester's own. The screen sends no member with it, so its note,
 * tester reference and expectation are checked against the identifiers the body states. Found by
 * the independent check of the member data fixes (N1): such a note was checked against nothing and
 * reached the ledger in clear, beside a line with no member token at all.
 */
class OwnBodyMemberTextTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);

    /** The checker's own body: the member ID is the CDS Hooks patientId, and nowhere else. */
    private static final String CRD_BODY = "{\"hook\":\"order-sign\",\"hookInstance\":\"x\",\"context\":{\"patientId\":\"S123456789\","
            + "\"userId\":\"PractitionerRole/1\",\"draftOrders\":{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":"
            + "\"Patient\",\"id\":\"p1\"}}]}}}";
    private static final String COVERAGE_BODY = "{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Coverage\","
            + "\"id\":\"coverage-1\",\"subscriberId\":\"SUBZZ-9988-776\",\"identifier\":[{\"value\":\"S123456789\"}]}},"
            + "{\"resource\":{\"resourceType\":\"Claim\",\"patient\":{\"reference\":\"Patient/patient-1\"}}}]}";
    private static final String REFERENCE_BODY = "{\"resourceType\":\"Claim\",\"patient\":{\"reference\":\"Patient/S123456789\"}}";

    private static Map<String, String> errors(RunRequest request) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (FieldError error : new RunInputs(CLOCK).common(request).errors()) {
            out.put(error.field(), error.message());
        }
        return out;
    }

    @Test
    void aCrdRunWithABodyOfItsOwnAndNoMemberIsRefusedOnTheNoteThatNamesThePatientTheBodyNames() {
        Map<String, String> refused = errors(CrdRunRequest.builder().rawBody(CRD_BODY).note("own body for S123456789")
                .testerRef("QA s12345-6789").expected("S123456789: covered=covered").build());

        assertThat(refused).containsOnlyKeys("note", "testerRef", "expected");
        assertThat(refused.get("note")).isEqualTo("The note holds a member ID the request body carries. The ledger keeps it as written "
                + "and may not hold member data, so leave it out. Nothing was sent.");
        assertThat(String.join(" ", refused.values())).doesNotContain("S123456789").doesNotContain("6789");
    }

    @Test
    void dtrAndPasRunsWithABodyOfTheirOwnAreHeldToTheSameCheck() {
        List<RunRequest> requests = List.of(
                DtrPackageRequest.builder().rawBody(COVERAGE_BODY).note("pkg S123456789").build(),
                DtrNextQuestionRequest.builder().rawBody(COVERAGE_BODY).note("next S 1234 56789").build(),
                PasSubmitRequest.builder().rawBody(REFERENCE_BODY).note("claim for s123456789").build(),
                PasInquireRequest.builder().rawBody(COVERAGE_BODY).note("subscriber SUBZZ9988776").build());
        for (RunRequest request : requests) {
            assertThat(errors(request)).as(request.getClass().getSimpleName()).containsKey("note");
        }
        assertThat(errors(PasInquireRequest.builder().rawBody(COVERAGE_BODY).note("subscriber SUBZZ9988776").build()).get("note"))
                .contains("a subscriber ID the request body carries");
    }

    @Test
    void anHonestNoteABodyWithNoIdentifierAndTheIdsTheToolGivesThePatientAreNotRefused() {
        assertThat(errors(CrdRunRequest.builder().rawBody(CRD_BODY).note("ticket 4471, retry after 5xx").build())).isEmpty();
        // No identifier can be read from a body that is not JSON, so nothing is refused.
        assertThat(errors(CrdRunRequest.builder().rawBody("{\"hook\": \"order-sign\", S123456789").note("own body for S123456789")
                .build())).isEmpty();
        // patient-1 is the id this tool's own builders give the patient, and names nobody.
        assertThat(errors(PasSubmitRequest.builder().rawBody(COVERAGE_BODY).note("sent for Patient/patient-1").build())).isEmpty();
    }

    @Test
    void aFieldTheFormsMemberAlreadyRefusesIsRefusedOnceAndTheBodysMemberIsCheckedAsWell() {
        MemberDetail member = MemberDetail.builder().memberId("S000000500").build();
        List<FieldError> both = new RunInputs(CLOCK).common(CrdRunRequest.builder().member(member).rawBody(CRD_BODY)
                .note("S000000500 and S123456789").testerRef("about S123456789").build()).errors();

        assertThat(both).extracting(FieldError::field).containsExactly("note", "testerRef");
        assertThat(both.get(0).message()).startsWith("The note holds this run's member ID.");
        assertThat(both.get(1).message()).startsWith("The tester reference holds a member ID the request body carries.");
    }

    /** A body loaded from a collection, naming its member through placeholders. */
    private static final String PLACEHOLDER_BODY = "{\"hook\":\"order-sign\",\"hookInstance\":\"x\",\"context\":{\"patientId\":"
            + "\"{{memberId}}\",\"userId\":\"PractitionerRole/1\",\"draftOrders\":{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":"
            + "{\"resourceType\":\"Coverage\",\"subscriberId\":\"{{subscriber}}\"}}]}}}";

    @Test
    void aBodyThatNamesTheMemberThroughAPlaceholderIsCheckedAsItWillBeSentWithRunValuesAndTheActiveEnvironment() {
        // Found by the last check of the ledger fixes (NF2): the body was read as typed, so a
        // {{memberId}} placeholder named nobody and the note went into the ledger in clear.
        OwnBodyPlaceholders filling = new OwnBodyPlaceholders(new FakeGateway().variable("subscriber", "SUBZZ-9988-776"));
        try {
            Map<String, String> refused = errors(CrdRunRequest.builder().rawBody(PLACEHOLDER_BODY).variables(Map.of("memberId", "S123456789"))
                    .note("placeholder S123456789").testerRef("sub SUBZZ9988776").build());

            assertThat(refused).containsOnlyKeys("note", "testerRef");
            assertThat(refused.get("note")).isEqualTo("The note holds a member ID the request body carries. The ledger keeps it as "
                    + "written and may not hold member data, so leave it out. Nothing was sent.");
            assertThat(refused.get("testerRef")).contains("a subscriber ID the request body carries");
            assertThat(String.join(" ", refused.values())).doesNotContain("S123456789").doesNotContain("9988");
            // The same body with an honest note is not refused.
            assertThat(errors(CrdRunRequest.builder().rawBody(PLACEHOLDER_BODY).variables(Map.of("memberId", "S123456789"))
                    .note("ticket 4471").build())).isEmpty();
        } finally {
            filling.close();
        }
    }

    @Test
    void aPlaceholderWithNoValueChangesNothingAndTheBodyIsReadAsTyped() {
        OwnBodyPlaceholders filling = new OwnBodyPlaceholders(new FakeGateway());
        try {
            // {{subscriber}} has no value: the call is refused for it where it is prepared, and here
            // the body is read as typed, as it was before.
            assertThat(errors(CrdRunRequest.builder().rawBody(PLACEHOLDER_BODY).variables(Map.of("memberId", "S123456789"))
                    .note("placeholder S123456789").build())).isEmpty();
            assertThat(errors(CrdRunRequest.builder().rawBody(PLACEHOLDER_BODY.replace("{{subscriber}}", "SUBZZ-9988-776"))
                    .note("sub SUBZZ9988776").build())).containsOnlyKeys("note");
        } finally {
            filling.close();
        }
        // With the filling handed back, nothing is resolved and nothing throws.
        assertThat(errors(CrdRunRequest.builder().rawBody(PLACEHOLDER_BODY).variables(Map.of("memberId", "S123456789"))
                .note("placeholder S123456789").build())).isEmpty();
    }
}
