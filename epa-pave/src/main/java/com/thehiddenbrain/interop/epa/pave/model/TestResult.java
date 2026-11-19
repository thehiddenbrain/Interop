package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How a run compared with what the tester declared they expected. Written as pass, fail or
 * unverified.
 *
 * <p>This is the test result. It is a different fact from the authorization outcome: a run the
 * tester expected to be denied, and which was denied, has outcome Denied and result PASS.
 */
public enum TestResult {
    PASS("Passed"),
    FAIL("Failed"),
    /** No expectation was declared. A real state, not a failure. */
    UNVERIFIED("Unverified");

    private final String label;

    TestResult(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TestResult parse(String text) {
        if (Texts.present(text)) {
            for (TestResult result : values()) {
                if (result.name().equalsIgnoreCase(text.strip())) {
                    return result;
                }
            }
        }
        throw new IllegalArgumentException("Result must be pass, fail or unverified.");
    }
}
