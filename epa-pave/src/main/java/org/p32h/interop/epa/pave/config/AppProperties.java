package org.p32h.interop.epa.pave.config;

import java.util.List;
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
 * @param allowedHosts       host names, besides localhost and the loopback addresses, under which
 *                           this tool answers API requests. Needed only when the tool is opened
 *                           under another name. A single {@code *} switches the check off
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue("") String scenarioDir,
        @DefaultValue("25") int autocompleteLimit,
        @DefaultValue("true") boolean mockLatencyEnabled,
        @DefaultValue({}) List<String> allowedHosts) {

    public AppProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().filter(h -> h != null && !h.isBlank())
                .map(String::strip).toList();
    }

    public static AppProperties defaults() {
        return of(Map.of());
    }

    public static AppProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(AppProperties.class, "app", overrides);
    }
}
