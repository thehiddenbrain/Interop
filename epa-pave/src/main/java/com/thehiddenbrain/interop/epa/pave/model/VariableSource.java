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
    /** No layer holds a value. The request is not sent. */
    UNRESOLVED
}
