package org.p32h.interop.epa.pave.model;

/**
 * How a member or provider lookup ended.
 *
 * <p>NOT_FOUND and FAULT are never collapsed. An identifier the API does not recognise is a finding
 * about the test data. An API that is down is a finding about the environment. NOT_ANSWERING is
 * kept apart from FAULT too: a service that never answered (a timeout, a refused connection) is
 * fixed somewhere else than one that answered with an error.
 */
public enum LookupStatus {
    FOUND("Found"),
    /** More than one record matched. None is picked, because picking one would be guessing which member was meant. */
    SEVERAL("Several matched"),
    NOT_FOUND("Not found"),
    /** No answer came: the call timed out or the service could not be reached. */
    NOT_ANSWERING("Not answering"),
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
