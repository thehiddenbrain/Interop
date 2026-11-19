package com.thehiddenbrain.interop.epa.pave.model;

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
}
