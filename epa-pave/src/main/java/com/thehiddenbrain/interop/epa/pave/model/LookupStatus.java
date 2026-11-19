package com.thehiddenbrain.interop.epa.pave.model;

/**
 * How a member or provider lookup ended.
 *
 * <p>NOT_FOUND and FAULT are never collapsed. An identifier the API does not recognise is a finding
 * about the test data. An API that is down is a finding about the environment.
 */
public enum LookupStatus {
    FOUND,
    NOT_FOUND,
    /** Not configured or switched off. The tester types the details by hand. Not an error. */
    DISABLED,
    FAULT
}
