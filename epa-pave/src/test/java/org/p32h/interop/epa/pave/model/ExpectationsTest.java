package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpectationsTest {

    private static final OutcomeCode[] CRD = CrdOutcome.values();

    private static ExpectationView assess(String expected, CrdOutcome actual, Map<String, List<String>> codes) {
        return Expectations.assess(expected, actual, codes, CRD);
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
        ExpectationView emptyResponse = assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, Map.of());
        assertThat(emptyResponse.result()).isEqualTo(TestResult.FAIL);
        assertThat(emptyResponse.explanation()).contains("did not carry pa-needed");
        assertThat(assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, null).result()).isEqualTo(TestResult.FAIL);
        assertThat(assess("pa-needed=no-auth", CrdOutcome.NO_DETERMINATION, Map.of("pa-needed", List.of())).result())
                .isEqualTo(TestResult.FAIL);
    }

    @Test
    void aCodeAssertionComparesTheStandardCode() {
        Map<String, List<String>> codes = Map.of("pa-needed", List.of("auth-needed"), "doc-needed", List.of("clinical"));
        assertThat(assess("pa-needed=auth-needed", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.PASS);
        ExpectationView wrong = assess("pa-needed=no-auth", CrdOutcome.AUTH_REQUIRED, codes);
        assertThat(wrong.result()).isEqualTo(TestResult.FAIL);
        assertThat(wrong.explanation()).contains("pa-needed was auth-needed");
        // Codes are the contract and are case sensitive.
        assertThat(assess("pa-needed=Auth-Needed", CrdOutcome.AUTH_REQUIRED, codes).result()).isEqualTo(TestResult.FAIL);
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
        assertThat(Expectations.problems("APPROVED", CRD)).hasSize(1);
        assertThat(Expectations.problems("pa-needed=", CRD)).hasSize(1);
        assertThat(Expectations.problems("=no-auth", CRD)).hasSize(1);
        assertThat(Expectations.problems("pa-needed=no-auth; NO_AUTH_REQUIRED", CRD)).isEmpty();
        assertThat(Expectations.problems("", CRD)).isEmpty();
        assertThat(Expectations.problems(null, CRD)).isEmpty();

        ExpectationView unreadable = assess("APPROVED", CrdOutcome.AUTH_REQUIRED, Map.of());
        assertThat(unreadable.result()).isEqualTo(TestResult.FAIL);
        assertThat(unreadable.explanation()).startsWith("The expectation could not be checked.");
        // A blank code on both sides must not be a match either.
        assertThat(assess("pa-needed=", CrdOutcome.NO_DETERMINATION, Map.of("pa-needed", List.of(""))).result())
                .isEqualTo(TestResult.FAIL);
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
    void anOutcomeIsAlwaysRequired() {
        assertThatThrownBy(() -> Expectations.assess("x", null, Map.of(), CRD)).isInstanceOf(IllegalArgumentException.class);
    }
}
