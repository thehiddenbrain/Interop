package org.p32h.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Saved test members and providers.
 *
 * @param enabled false keeps the lists empty and leaves their folder untouched: one switch for an
 *                installation where no member data may be kept at all
 */
@ConfigurationProperties("test-lists")
public record TestListProperties(@DefaultValue("true") boolean enabled) {

    public static TestListProperties defaults() {
        return of(Map.of());
    }

    public static TestListProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(TestListProperties.class, "test-lists", overrides);
    }
}
