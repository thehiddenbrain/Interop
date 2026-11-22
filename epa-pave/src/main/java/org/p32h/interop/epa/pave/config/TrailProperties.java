package org.p32h.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The trail of the last runs' requests and responses, kept beside the ledger under
 * {@code <tracking.directory>/trail/}.
 *
 * @param enabled    false keeps nothing and answers every run as not kept: one switch for an
 *                   installation where no wire JSON may be kept on disk
 * @param maxEntries how many runs are kept; past it the oldest go first
 */
@ConfigurationProperties("trail")
public record TrailProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") int maxEntries) {

    public static TrailProperties defaults() {
        return of(Map.of());
    }

    public static TrailProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(TrailProperties.class, "trail", overrides);
    }
}
