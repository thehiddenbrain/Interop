package com.thehiddenbrain.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Identity of this tool and how it is running.
 *
 * @param name    shown in the tab title, the masthead and /api/mode
 * @param version must match the build version; a test asserts it
 * @param target  onyx or nehen. Recorded on every run and shown on screen. It switches no behaviour
 * @param mode    mock or live. Must agree with the active Spring profile; startup fails otherwise
 */
@ConfigurationProperties("epa")
public record EpaProperties(
        @DefaultValue("PAVE") String name,
        @DefaultValue("1.0.0") String version,
        @DefaultValue("onyx") String target,
        @DefaultValue("mock") String mode) {

    public static final String MOCK = "mock";
    public static final String LIVE = "live";

    public static EpaProperties defaults() {
        return of(Map.of());
    }

    public static EpaProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(EpaProperties.class, "epa", overrides);
    }

    public boolean mock() {
        return MOCK.equalsIgnoreCase(mode);
    }

    public boolean live() {
        return LIVE.equalsIgnoreCase(mode);
    }
}
