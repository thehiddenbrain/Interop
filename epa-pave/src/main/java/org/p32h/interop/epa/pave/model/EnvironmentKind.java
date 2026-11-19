package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What kind of environment a set of endpoints belongs to. Written as sandbox, uat or prod. */
public enum EnvironmentKind {
    SANDBOX,
    UAT,
    PROD;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses sandbox, uat or prod in any case. Anything else, including blank, is rejected. */
    @JsonCreator
    public static EnvironmentKind parse(String text) {
        if (Texts.present(text)) {
            for (EnvironmentKind kind : values()) {
                if (kind.name().equalsIgnoreCase(text.strip())) {
                    return kind;
                }
            }
        }
        throw new IllegalArgumentException("Environment kind must be sandbox, uat or prod.");
    }
}
