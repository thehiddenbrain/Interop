package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The checks every run shares, for the free text a run writes to the ledger as typed. Found by the
 * member data audit (M5): the limit of a note was applied by the note endpoint only, so a pasted
 * body in the note of a run reached the ledger whole.
 */
class RunInputsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);
    private static final String PASTED = "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Zzlastname\"}],\"birthDate\":\"1961-07-23\"}";

    private static List<FieldError> errors(CrdRunRequest request) {
        return new RunInputs(CLOCK).common(request).errors();
    }

    @Test
    void aNoteATesterReferenceAndAnExpectationOverTheirLimitsAreEachRefusedByNameWithoutBeingRepeated() {
        String note = "n".repeat(RunRecord.LONGEST_NOTE - PASTED.length() + 1) + PASTED;
        String testerRef = "Zzlastname 1961-07-23 " + "r".repeat(RunRecord.LONGEST_TESTER_REF);
        String expected = "Zzlastname Zzfirstname 1961-07-23 " + "z".repeat(RunRecord.LONGEST_EXPECTED) + ": pa-needed=auth-needed";

        List<FieldError> errors = errors(CrdRunRequest.builder().note(note).testerRef(testerRef).expected(expected).build());

        assertThat(errors).extracting(FieldError::field).containsExactly("note", "testerRef", "expected");
        assertThat(errors.get(0).message()).isEqualTo("A note can be at most 4000 characters, and this one has 4001. A note is kept as "
                + "written, so do not paste a request or a response into it.");
        assertThat(errors.get(1).message()).isEqualTo("A tester reference can be at most 200 characters, and this one has "
                + testerRef.length() + ". It names a ticket or a test step, and is kept as written.");
        assertThat(errors.get(2).message()).isEqualTo("An expectation can be at most 1000 characters, and this one has " + expected.length()
                + ". It holds outcome codes and name=code terms, and is kept as written.");
        assertThat(errors.stream().map(FieldError::message).collect(Collectors.joining())).doesNotContain("Zzlastname").doesNotContain("1961");
        assertThatThrownBy(() -> new RunInputs(CLOCK).common(CrdRunRequest.builder().note(note).build()).throwIfInvalid())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anExpectationOverItsLimitIsReportedByItsLengthAloneAndItsTermsAreNotQuoted() {
        // Found by the verifier (V-4): the vocabulary check ran as well, and quoted the whole text back.
        String expected = "NO_AUTH_REQUIRED;" + "Zzlastname Zzfirstname 1961-07-23 ".repeat(30);
        CrdRunRequest request = CrdRunRequest.builder().expected(expected).build();

        List<FieldError> errors = new RunInputs(CLOCK).common(request)
                .expectation(expected, org.p32h.interop.epa.pave.model.CrdOutcome.values(),
                        org.p32h.interop.epa.pave.service.crd.CrdCodes.EXPECTATION_ELEMENTS)
                .errors();

        assertThat(errors).extracting(FieldError::field).containsExactly("expected");
        assertThat(errors.get(0).message()).startsWith("An expectation can be at most 1000 characters").doesNotContain("Zzlastname");
        // One within the limit is still read, and a misspelt outcome still refused.
        assertThat(new RunInputs(CLOCK).expectation("NO_AUTH_REQUIRD", org.p32h.interop.epa.pave.model.CrdOutcome.values(),
                org.p32h.interop.epa.pave.service.crd.CrdCodes.EXPECTATION_ELEMENTS).errors()).hasSize(1);
    }

    @Test
    void atTheirLimitsTheyAreAccepted() {
        assertThat(errors(CrdRunRequest.builder().note("n".repeat(RunRecord.LONGEST_NOTE)).testerRef("r".repeat(RunRecord.LONGEST_TESTER_REF))
                .expected("NO_AUTH_REQUIRED;" + "95810: pa-needed=auth-needed;".repeat(30)).build())).isEmpty();
    }

    @Test
    void anExpectationIsOutcomesAndCodeTermsAndAPersonCannotHideInOne() {
        // The subject of a term is a code that was asked about or a place in the response. Neither holds a space.
        assertThat(errors(CrdRunRequest.builder().expected("Zzlastname Zzfirstname 1961-07-23: pa-needed=auth-needed").build()))
                .extracting(FieldError::message).containsExactly("Term 1 of the expectation names what it is about with a space in it. "
                        + "That part, before the colon, is a procedure code that was asked about or a place in the response, such as 95810 "
                        + "or ServiceRequest/sr-1, and never holds a space.");
        assertThat(errors(CrdRunRequest.builder().expected("NO_AUTH_REQUIRED; pa-needed=Zzlastname Zzfirstname").build()))
                .extracting(FieldError::message).containsExactly("Term 2 of the expectation expects a code with a space in it. A code is "
                        + "one word, such as auth-needed.");
        // What the tool itself names a subject with is accepted, a PAS item with no code included.
        for (String fine : List.of("NO_AUTH_REQUIRED", "pa-needed=auth-needed", "95810: pa-needed=auth-needed",
                "95810, 95811: doc-needed=clinical", "ServiceRequest/sr-1: covered=covered", "item 2: review-action=A1",
                "https://example.org/Questionnaire/q1|1.0: questionnaire-status=active", "cards[0].extension[0]: pa-needed=no-auth")) {
            assertThat(errors(CrdRunRequest.builder().expected(fine).build())).as(fine).isEmpty();
        }
    }
}
