package org.p32h.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The test run ledger.
 *
 * @param directory       one JSON Lines file per day is appended here
 * @param recordMemberId  false stores a salted token and the last four characters in place of the
 *                        member ID. The mock profile sets this to true because its IDs are synthetic
 * @param recordNpi       an NPI is not member data, so this defaults to true
 * @param saltFile        where the per-installation salt lives. Blank means a file named
 *                        installation-salt inside the config store directory. It must never sit
 *                        inside the ledger directory
 * @param slowThresholdMs a completed call slower than this is flagged. Zero means use the Onyx read timeout
 */
@ConfigurationProperties("tracking")
public record TrackingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./run-history") String directory,
        @DefaultValue("false") boolean recordMemberId,
        @DefaultValue("true") boolean recordNpi,
        @DefaultValue("") String saltFile,
        @DefaultValue("0") long slowThresholdMs) {

    public static TrackingProperties defaults() {
        return of(Map.of());
    }

    public static TrackingProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(TrackingProperties.class, "tracking", overrides);
    }
}
