package org.p32h.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Behaviour that is not about an external system.
 *
 * @param scenarioDir        a directory of scenario files that replaces the bundled ones. Blank
 *                           uses the bundled scenarios
 * @param autocompleteLimit  the most procedure codes the picker returns for one query
 * @param mockLatencyEnabled when true a mock scenario waits for its configured delay
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue("") String scenarioDir,
        @DefaultValue("25") int autocompleteLimit,
        @DefaultValue("true") boolean mockLatencyEnabled) {

    public static AppProperties defaults() {
        return of(Map.of());
    }

    public static AppProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(AppProperties.class, "app", overrides);
    }
}
