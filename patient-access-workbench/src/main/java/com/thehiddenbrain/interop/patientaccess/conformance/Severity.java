package com.thehiddenbrain.interop.patientaccess.conformance;

/** IG conformance strength of a check: a failed SHALL is FAIL, a failed SHOULD is WARN, a failed MAY is INFO. */
public enum Severity {
    SHALL, SHOULD, MAY;

    public CheckStatus onFailure() {
        return switch (this) {
            case SHALL -> CheckStatus.FAIL;
            case SHOULD -> CheckStatus.WARN;
            case MAY -> CheckStatus.INFO;
        };
    }
}
