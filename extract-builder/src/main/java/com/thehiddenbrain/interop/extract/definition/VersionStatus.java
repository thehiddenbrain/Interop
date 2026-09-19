package com.thehiddenbrain.interop.extract.definition;

public enum VersionStatus {
    DRAFT, SAMPLED, PENDING_APPROVAL, APPROVED, PRODUCTION, RETIRED;

    public static VersionStatus of(String s) {
        return valueOf(s);
    }

    public boolean editable() {
        return this == DRAFT;
    }
}
