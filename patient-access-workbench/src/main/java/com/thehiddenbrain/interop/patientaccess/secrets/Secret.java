package com.thehiddenbrain.interop.patientaccess.secrets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A value stored encrypted. On disk and in memory it only ever holds the ciphertext
 * ({@code {"enc": "..."}}); {@link SecretCrypto#reveal(Secret)} yields the plain text.
 */
public final class Secret {

    private final String enc;

    @JsonCreator
    public Secret(@JsonProperty("enc") String enc) {
        this.enc = enc;
    }

    @JsonProperty("enc")
    public String enc() {
        return enc;
    }

    public boolean isSet() {
        return enc != null && !enc.isBlank();
    }

    @Override
    public String toString() {
        return "Secret[***]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret s && java.util.Objects.equals(enc, s.enc);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(enc);
    }
}
