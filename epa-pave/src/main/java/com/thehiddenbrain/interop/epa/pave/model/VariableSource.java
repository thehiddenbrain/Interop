package com.thehiddenbrain.interop.epa.pave.model;

/** Where a placeholder value came from, in resolution order. The first layer holding a value wins. */
public enum VariableSource {
    /** Typed on the form for this run. */
    RUN_VALUE,
    /** The active Postman environment. */
    ENVIRONMENT,
    /** A variable defined on the collection. */
    COLLECTION,
    /** The fallback layer in application.yml. */
    APPLICATION_YML,
    /**
     * Computed by the tool for a Postman dynamic variable that needs no script, such as a GUID or a
     * timestamp. The value differs on every resolution, so a preview and the request that is sent
     * do not show the same one.
     */
    GENERATED,
    /** Read from an operating system environment variable that the configuration pointed at. Always secret. */
    OPERATING_SYSTEM,
    /** No layer holds a value. The request is not sent. */
    UNRESOLVED
}
