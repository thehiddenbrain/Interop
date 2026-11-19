package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** What a mock scenario does when it is selected. Written as respond, timeout or unavailable. */
public enum ScenarioBehavior {
    /** Return the scenario's status and body. */
    RESPOND,
    /** Behave as a service that never answers inside the read timeout. */
    TIMEOUT,
    /** Behave as a service that cannot be reached. */
    UNAVAILABLE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Scenario files are edited by hand, so case and surrounding spaces are forgiven. Unknown text is not. */
    @JsonCreator
    public static ScenarioBehavior parse(String text) {
        if (Texts.present(text)) {
            for (ScenarioBehavior behavior : values()) {
                if (behavior.name().equalsIgnoreCase(text.strip())) {
                    return behavior;
                }
            }
        }
        throw new IllegalArgumentException("Scenario behavior must be respond, timeout or unavailable.");
    }
}
