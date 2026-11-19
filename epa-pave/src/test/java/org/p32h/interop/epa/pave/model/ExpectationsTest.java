package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Expectations.Answer;

class ExpectationsTest {

    private static final OutcomeCode[] CRD = CrdOutcome.values();
    private static final List<String> ELEMENTS =
            List.of("covered", "pa-needed", "doc-needed", "doc-purpose", "info-needed", "satisfied-pa-id", "delegated-vendor");

    /** One answer about the whole request, with nothing known about what was asked. */
    private static ExpectationView assess(String expected, CrdOutcome actual, Map<String, List<String>> codes) {
        return Expectations.assess(expected, actual, null, codes == null ? null : List.of(Answer.of(null, codes)), CRD, ELEMENTS);
    }

    private static ExpectationView assessAnswers(String expected, CrdOutcome actual, Answer... answers) {
        return Expectations.assess(expected, actual, null, List.of(answers), CRD, ELEMENTS);
    }

    /** As a mapper calls it: what was asked about, and the answers that came back. */
    private static ExpectationView assessAsked(String expected, CrdOutcome actual, List<String> asked, Answer... answers) {
        return Expectations.assess(expected, actual, asked, List.of(answers), CRD, ELEMENTS);
    }

    private static List<String> problems(String expected) {
        return Expectations.problems(expected, CRD, ELEMENTS);
    }

    @Test
    void noDeclaredExpectationIsUnverifiedAndNeverAPass() {
        for (String blank : new String[] {null, "", "   ", ";", " ; ; "}) {
            ExpectationView view = assess(blank, CrdOutcome.NO_AUTH_REQUIRED, Map.of());
            assertThat(view.result()).as("expected=[" + blank + "]").isEqualTo(TestResult.UNVERIFIED);
            assertThat(view.expected()).isNull();
            assertThat(view.actual()).isEqualTo("NO_AUTH_REQUIRED");
        }
    }

    @Test
    void blankExpectedAgainstNoDeterminationDoesNotPass() {
        // Both sides "empty" must never be read as a match.
        ExpectationView view = assess("", CrdOutcome.NO_DETERMINATION, Map.of());
        assertThat(view.result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void noAuthAndNoDeterminationNeverSatisfyEachOther() {
        assertThat(assess("NO_AUTH_REQUIRED", CrdOutcome.NO_DETERMINATION, Map.of()).result()).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_DETERMINATION", CrdOutcome.NO_AUTH_REQUIRED, Map.of("pa-needed", List.of("no-auth"))).result())
                .isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_AUTH_REQUIRED", CrdOutcome.NO_AUTH_REQUIRED, Map.of("pa-needed", List.of("no-auth"))).result())
                .isEqualTo(TestResult.PASS);
        assertThat(assess("NO_DETERMINATION", CrdOutcome.NO_DETERMINATION, Map.of()).result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void aCodeAssertionFailsWhenTheResponseDidNotCarryThatElement() {
        ExpectationView emptyAnswer = assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, Map.of());
        assertThat(emptyAnswer.result()).isEqualTo(TestResult.FAIL);
        assertThat(emptyAnswer.explanation()).contains("did not carry pa-needed");
        // No answers at all, given as null and as an empty list.
        assertThat(assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, null).result()).isEqualTo(TestResult.FAIL);
        ExpectationView noAnswers = assessAnswers("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION);
        assertThat(noAnswers.result()).isEqualTo(TestResult.FAIL);
        assertThat(noAnswers.explanation()).isEqualTo("Expected pa-needed=no-auth but the response did not carry pa-needed.");
        assertThat(assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, Map.of("pa-needed", List.of())).result())
                .isEqualTo(TestResult.FAIL);
    }

    @Test
    void aCodeAssertionComparesTheStandardCode() {
        Map<String, List<String>> codes = Map.of("pa-needed", List.of("auth-needed"), "doc-needed", List.of("clinical"));
        ExpectationView right = assess("pa-needed=auth-needed", CrdOutcome.AUTH_REQUIRED, codes);
        assertThat(right.result()).isEqualTo(TestResult.PASS);
        // The element name keeps its own spelling: it is not capitalised into something the response never said.
        assertThat(right.explanation()).isEqualTo("The response carried pa-needed=auth-needed.");
        ExpectationView wrong = assess("pa-needed=no-auth", CrdOutcome.AUTH_REQUIRED, codes);
        assertThat(wrong.result()).isEqualTo(TestResult.FAIL);
        assertThat(wrong.explanation()).isEqualTo("Expected pa-needed=no-auth but the response carried pa-needed=auth-needed.");
        // Codes are the contract and are case sensitive.
        assertThat(assess("pa-needed=Auth-Needed", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void anyOfSeveralValuesInsideOneAnswerSatisfiesATerm() {
        // doc-purpose and info-needed repeat inside one answer, and each value was returned.
        Map<String, List<String>> codes = Map.of("doc-purpose", List.of("withpa", "retain"));
        assertThat(assess("doc-purpose=retain", CrdOutcome.DOCUMENTATION_REQUIRED, codes).result()).isEqualTo(TestResult.PASS);
        assertThat(assess("doc-purpose=withpa; doc-purpose=retain", CrdOutcome.DOCUMENTATION_REQUIRED, codes).result())
                .isEqualTo(TestResult.PASS);
        assertThat(assess("doc-purpose=withclaim", CrdOutcome.DOCUMENTATION_REQUIRED, codes).result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void theCodesOfDifferentAnswersAreNeverPooled() {
        Answer needsAuth = Answer.of("95810", Map.of("pa-needed", List.of("auth-needed")));
        Answer noAuth = Answer.of("95811", Map.of("pa-needed", List.of("no-auth")));

        // One answer matching does not speak for the other.
        ExpectationView oneOfTwo = assessAnswers("pa-needed=no-auth", CrdOutcome.MIXED, needsAuth, noAuth);
        assertThat(oneOfTwo.result()).isEqualTo(TestResult.FAIL);
        assertThat(oneOfTwo.explanation())
                .isEqualTo("Expected pa-needed=no-auth but the answer for 95810 carried pa-needed=auth-needed.");

        // Declaring both codes without saying which code gets which cannot pass either, whichever way round they are.
        assertThat(assessAnswers("MIXED; pa-needed=no-auth; pa-needed=auth-needed", CrdOutcome.MIXED, needsAuth, noAuth).result())
                .isEqualTo(TestResult.FAIL);

        // When every answer carries the code, the term holds and the explanation names them.
        Answer alsoNoAuth = Answer.of("95810", Map.of("pa-needed", List.of("no-auth")));
        ExpectationView both = assessAnswers("pa-needed=no-auth", CrdOutcome.NO_AUTH_REQUIRED, alsoNoAuth, noAuth);
        assertThat(both.result()).isEqualTo(TestResult.PASS);
        assertThat(both.explanation()).isEqualTo("Every answer carried pa-needed=no-auth (95810, 95811).");
    }

    @Test
    void aTermCanNameTheAnswerItIsAbout() {
        Answer needsAuth = Answer.of("95810", Map.of("pa-needed", List.of("auth-needed")));
        Answer noAuth = Answer.of("95811", Map.of("pa-needed", List.of("no-auth")));

        ExpectationView declared = assessAnswers("MIXED; 95810: pa-needed=auth-needed; 95811: pa-needed=no-auth", CrdOutcome.MIXED,
                needsAuth, noAuth);
        assertThat(declared.result()).isEqualTo(TestResult.PASS);
        assertThat(declared.expectedLabel()).contains("95810: pa-needed=auth-needed").contains("95811: pa-needed=no-auth");

        // The same declaration fails when the two codes swap answers.
        ExpectationView swapped = assessAnswers("95810: pa-needed=auth-needed; 95811: pa-needed=no-auth", CrdOutcome.MIXED,
                Answer.of("95810", Map.of("pa-needed", List.of("no-auth"))), Answer.of("95811", Map.of("pa-needed", List.of("auth-needed"))));
        assertThat(swapped.result()).isEqualTo(TestResult.FAIL);
        assertThat(swapped.explanation()).contains("the answer for 95810 carried pa-needed=no-auth")
                .contains("the answer for 95811 carried pa-needed=auth-needed");

        // A subject the response has no answer for is unmet, and says so.
        ExpectationView unknown = assessAnswers("95899: pa-needed=no-auth", CrdOutcome.MIXED, needsAuth, noAuth);
        assertThat(unknown.result()).isEqualTo(TestResult.FAIL);
        assertThat(unknown.explanation()).isEqualTo("Expected 95899: pa-needed=no-auth but the response has no answer for 95899.");
    }

    @Test
    void somethingAskedAboutAndLeftUnansweredSatisfiesNoCodeAssertion() {
        Answer noAuth = Answer.of("95810", Map.of("pa-needed", List.of("no-auth")));
        List<String> asked = List.of("95810", "95811");

        // The caller passes only the answer that exists. It cannot make this pass by saying
        // nothing about 95811, because what was asked is given and the gap is worked out here.
        ExpectationView view = assessAsked("pa-needed=no-auth", CrdOutcome.MIXED, asked, noAuth);
        assertThat(view.result()).isEqualTo(TestResult.FAIL);
        assertThat(view.explanation()).isEqualTo("Expected pa-needed=no-auth but no answer was returned for 95811.");
        ExpectationView scoped = assessAsked("95811: pa-needed=no-auth", CrdOutcome.MIXED, asked, noAuth);
        assertThat(scoped.result()).isEqualTo(TestResult.FAIL);
        assertThat(scoped.explanation()).isEqualTo("Expected 95811: pa-needed=no-auth but no answer was returned for 95811.");
        // The answered code can still be asserted on its own.
        assertThat(assessAsked("95810: pa-needed=no-auth", CrdOutcome.MIXED, asked, noAuth).result()).isEqualTo(TestResult.PASS);

        // With nothing answered at all, every asked subject is unanswered.
        ExpectationView nothing = assessAsked("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, asked);
        assertThat(nothing.result()).isEqualTo(TestResult.FAIL);
        assertThat(nothing.explanation()).contains("no answer was returned for 95810").contains("no answer was returned for 95811");
        // When every asked subject has its answer, the same call passes.
        assertThat(assessAsked("pa-needed=no-auth", CrdOutcome.NO_AUTH_REQUIRED, asked, noAuth,
                Answer.of("95811", Map.of("pa-needed", List.of("no-auth")))).result()).isEqualTo(TestResult.PASS);
        // An asked subject is matched to its answer whatever its case.
        assertThat(assessAsked("pa-needed=no-auth", CrdOutcome.NO_AUTH_REQUIRED, List.of("e0601"),
                Answer.of("E0601", Map.of("pa-needed", List.of("no-auth")))).result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void everyTermMustHold() {
        Map<String, List<String>> codes = Map.of("pa-needed", List.of("auth-needed"), "doc-needed", List.of("clinical"));
        assertThat(assess("AUTH_REQUIRED; doc-needed=clinical", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.PASS);
        assertThat(assess("AUTH_REQUIRED; doc-needed=admin", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_AUTH_REQUIRED; doc-needed=clinical", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void outcomeNamesAreReadForgivingly() {
        assertThat(assess("no auth required", CrdOutcome.NO_AUTH_REQUIRED, Map.of()).result()).isEqualTo(TestResult.PASS);
        assertThat(assess("no-auth-required", CrdOutcome.NO_AUTH_REQUIRED, Map.of()).result()).isEqualTo(TestResult.PASS);
        assertThat(assess("  No_Auth_Required  ", CrdOutcome.NO_AUTH_REQUIRED, Map.of()).result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void aFaultCanBeTheExpectedOutcome() {
        assertThat(assess("TIMEOUT", CrdOutcome.TIMEOUT, Map.of()).result()).isEqualTo(TestResult.PASS);
        assertThat(assess("AUTH_REQUIRED", CrdOutcome.TIMEOUT, Map.of()).result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void anExpectationThatCannotBeReadIsAProblemAndNeverAPass() {
        assertThat(problems("APPROVED")).hasSize(1);
        assertThat(problems("pa-needed=")).hasSize(1);
        assertThat(problems("=no-auth")).hasSize(1);
        assertThat(problems(": pa-needed=no-auth")).hasSize(1);
        assertThat(problems("pa-needed=no-auth; NO_AUTH_REQUIRED")).isEmpty();
        assertThat(problems("95810: pa-needed=no-auth")).isEmpty();
        assertThat(problems("")).isEmpty();
        assertThat(problems(null)).isEmpty();

        ExpectationView unreadable = assess("APPROVED", CrdOutcome.AUTH_REQUIRED, Map.of());
        assertThat(unreadable.result()).isEqualTo(TestResult.FAIL);
        assertThat(unreadable.explanation()).startsWith("The expectation could not be checked.");
        // A blank code on both sides must not be a match either.
        assertThat(assess("pa-needed=", CrdOutcome.NO_DETERMINATION, Map.of("pa-needed", List.of(""))).result())
                .isEqualTo(TestResult.FAIL);
    }

    @Test
    void aMisspeltElementNameIsRefusedAndNeverReportedAsMissingFromTheResponse() {
        // Each of these would otherwise be sent and come back as a failure saying the response did
        // not carry the element, which would be false.
        for (String misspelt : new String[] {"pa_needed=no-auth", "paneeded=no-auth", "NO_AUTH_REQUIRED, pa-needed=no-auth"}) {
            List<String> found = problems(misspelt);
            assertThat(found).as(misspelt).hasSize(1);
            assertThat(found.get(0)).as(misspelt).contains("is not an element this operation returns").contains("pa-needed");
        }
        assertThat(assess("pa_needed=no-auth", CrdOutcome.NO_AUTH_REQUIRED, Map.of("pa-needed", List.of("no-auth"))).result())
                .isEqualTo(TestResult.FAIL);

        // A difference in case only is the same element, and is compared under the response's spelling.
        assertThat(problems("PA-Needed=no-auth")).isEmpty();
        ExpectationView cased = assess("PA-Needed=no-auth", CrdOutcome.NO_AUTH_REQUIRED, Map.of("pa-needed", List.of("no-auth")));
        assertThat(cased.result()).isEqualTo(TestResult.PASS);
        assertThat(cased.expectedLabel()).isEqualTo("pa-needed=no-auth");
    }

    @Test
    void anOperationWithNoCodedElementsAcceptsOutcomesOnly() {
        assertThat(Expectations.problems("pa-needed=no-auth", CRD, List.of())).hasSize(1);
        assertThat(Expectations.problems("pa-needed=no-auth", CRD, null)).hasSize(1);
        assertThat(Expectations.problems("NO_AUTH_REQUIRED", CRD, null)).isEmpty();
    }

    @Test
    void theExplanationSaysWhatWasCompared() {
        ExpectationView pass = assess("NO_AUTH_REQUIRED", CrdOutcome.NO_AUTH_REQUIRED, Map.of());
        assertThat(pass.explanation()).isEqualTo("The outcome was \"Prior authorization is not required\" as expected.");
        assertThat(pass.expectedLabel()).isEqualTo("Prior authorization is not required");
        ExpectationView fail = assess("NO_AUTH_REQUIRED", CrdOutcome.NO_DETERMINATION, Map.of());
        assertThat(fail.explanation()).isEqualTo(
                "Expected \"Prior authorization is not required\" but the outcome was \"The service returned no determination\".");
        assertThat(fail.actualLabel()).isEqualTo("The service returned no determination");
    }

    @Test
    void aRunThatWasNotSentIsUnverifiedWhateverWasDeclared() {
        ExpectationView declared = Expectations.notCompared(" NO_AUTH_REQUIRED ", RunOutcome.BLOCKED, "Nothing was sent.");
        assertThat(declared.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(declared.expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(declared.actual()).isEqualTo("BLOCKED");
        assertThat(declared.explanation()).isEqualTo("Nothing was sent.");
        assertThat(Expectations.notCompared(null, RunOutcome.BLOCKED, "Nothing was sent.").expected()).isNull();
    }

    @Test
    void anOutcomeIsAlwaysRequired() {
        assertThatThrownBy(() -> Expectations.assess("x", null, null, List.of(), CRD, ELEMENTS)).isInstanceOf(IllegalArgumentException.class);
    }
}
