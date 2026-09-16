package com.thehiddenbrain.interop.patientaccess.conformance;

/** Outcome of one check. ERROR means the check itself could not run (transport failure, exception). */
public enum CheckStatus {
    PASS, FAIL, WARN, INFO, SKIP, ERROR
}
