package org.p32h.interop.epa.pave.model.suite;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The count groups of a suite run, from {@code passed} to {@code waiting} in {@link SuiteCounts}.
 * Every row is counted in exactly one of them.
 *
 * <p>Each group carries its words, so the suite answer can give a label beside each count and the
 * group beside each row, and the browser keeps no copy of either. On the wire a group is the name
 * of its component in {@link SuiteCounts}, which is what a screen filters the rows by.
 */
public enum SuiteCountGroup {
    PASSED("passed", "Passed"),
    FAILED("failed", "Failed"),
    UNVERIFIED("unverified", "Unverified: nothing was declared to compare with"),
    BLOCKED("blocked", "Blocked by the production guardrail, not failures"),
    INVALID("invalid", "Not valid: nothing was sent"),
    NOT_CONFIGURED("notConfigured", "Not configured: nothing was sent"),
    NOT_RUNNABLE("notRunnable", "Cannot be run from a suite row: nothing was sent"),
    TOOL_FAILED("toolFailed", "This tool failed: whether a request left is not known"),
    PARTLY("partly", "Stopped part way, after some of their steps were sent"),
    NOT_RUN("notRun", "Not run: the suite stopped before them"),
    WAITING("waiting", "Waiting to be run");

    private final String key;
    private final String label;

    SuiteCountGroup(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The name of the group's component in {@link SuiteCounts}, as the wire writes the group. */
    @JsonValue
    public String key() {
        return key;
    }

    /** The group in plain words, as every screen shows it. */
    public String label() {
        return label;
    }

    /** How many rows of the counts are in this group. */
    public int count(SuiteCounts counts) {
        return switch (this) {
            case PASSED -> counts.passed();
            case FAILED -> counts.failed();
            case UNVERIFIED -> counts.unverified();
            case BLOCKED -> counts.blocked();
            case INVALID -> counts.invalid();
            case NOT_CONFIGURED -> counts.notConfigured();
            case NOT_RUNNABLE -> counts.notRunnable();
            case TOOL_FAILED -> counts.toolFailed();
            case PARTLY -> counts.partly();
            case NOT_RUN -> counts.notRun();
            case WAITING -> counts.waiting();
        };
    }

    /**
     * One group as the suite answer gives it: its key, its words and how many rows it holds.
     *
     * @param group the name of the component in {@link SuiteCounts}
     */
    public record Labelled(String group, String label, int count) {
    }
}
