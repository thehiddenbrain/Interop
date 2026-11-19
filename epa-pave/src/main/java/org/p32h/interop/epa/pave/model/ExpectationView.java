package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the tester expected, what happened, and whether the two match. This is the test result.
 *
 * @param expected      what the tester declared, as typed. Null when nothing was declared
 * @param expectedLabel the expectation in plain language. Null when nothing was declared
 * @param actual        the outcome code that was compared
 * @param actualLabel   the outcome in plain language
 * @param result        pass, fail or unverified
 * @param explanation   one sentence saying what was compared and why the result is what it is
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpectationView(String expected, String expectedLabel, String actual, String actualLabel, TestResult result,
                              String explanation) {

    public ExpectationView {
        if (Texts.blank(actual) || Texts.blank(actualLabel) || Texts.blank(explanation) || result == null) {
            throw new IllegalArgumentException("A test result needs what happened, a result and an explanation.");
        }
        // A pass or a fail is a comparison, and a comparison has two sides.
        if (result != TestResult.UNVERIFIED && Texts.blank(expected)) {
            throw new IllegalArgumentException("A run can pass or fail only against a declared expectation.");
        }
    }
}
