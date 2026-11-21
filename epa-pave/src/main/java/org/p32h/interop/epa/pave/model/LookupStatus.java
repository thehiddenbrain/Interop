package org.p32h.interop.epa.pave.model;

/**
 * How a member or provider lookup ended.
 *
 * <p>NOT_FOUND and FAULT are never collapsed. An identifier the API does not recognise is a finding
 * about the test data. An API that is down is a finding about the environment.
 */
public enum LookupStatus {
    FOUND("Found"),
    NOT_FOUND("Not found"),
    /** Not configured or switched off. The tester types the details by hand. Not an error. */
    DISABLED("No lookup is configured"),
    FAULT("The lookup failed");

    private final String label;

    LookupStatus(String label) {
        this.label = label;
    }

    /**
     * The status in plain words. Every lookup answer serves it beside the code, so the browser
     * keeps no copy of these words.
     */
    public String label() {
        return label;
    }
}
