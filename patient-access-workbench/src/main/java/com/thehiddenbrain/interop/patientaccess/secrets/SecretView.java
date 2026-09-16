package com.thehiddenbrain.interop.patientaccess.secrets;

/** What the API shows for a secret: whether it is set and a short hint (last characters) to recognise it. */
public record SecretView(boolean set, String hint) {

    public static SecretView unset() {
        return new SecretView(false, null);
    }
}
