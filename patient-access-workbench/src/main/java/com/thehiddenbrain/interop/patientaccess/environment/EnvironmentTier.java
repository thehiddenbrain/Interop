package com.thehiddenbrain.interop.patientaccess.environment;

/** Which stage of the vendor's platform an environment points at. PROD refuses insecure options. */
public enum EnvironmentTier {
    SANDBOX, UAT, PROD, OTHER
}
