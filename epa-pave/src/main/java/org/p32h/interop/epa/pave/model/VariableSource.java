package org.p32h.interop.epa.pave.model;

/**
 * Where a placeholder value came from, in resolution order. The first layer holding a value wins.
 *
 * <p>Each value carries its words. Every answer that serves a source code serves this label beside
 * it, so no screen keeps a copy of them.
 */
public enum VariableSource {
    /** Typed on the form for this run. */
    RUN_VALUE("Typed for this run"),
    /** The active Postman environment. */
    ENVIRONMENT("The active environment"),
    /** A variable defined on the collection, or a credential typed into the text of the request. */
    COLLECTION("The collection"),
    /** The fallback layer in application.yml. */
    APPLICATION_YML("application.yml"),
    /**
     * Computed by the tool for a Postman dynamic variable that needs no script, such as a GUID or a
     * timestamp. The value differs on every resolution, so a preview and the request that is sent
     * do not show the same one.
     */
    GENERATED("Made up by the tool for this call"),
    /** Read from an operating system environment variable that the configuration pointed at. Always secret. */
    OPERATING_SYSTEM("An operating system variable"),
    /** No layer holds a value. The request is not sent. */
    UNRESOLVED("No value anywhere");

    private final String label;

    VariableSource(String label) {
        this.label = label;
    }

    /** The source in words, as every screen shows it. */
    public String label() {
        return label;
    }
}
