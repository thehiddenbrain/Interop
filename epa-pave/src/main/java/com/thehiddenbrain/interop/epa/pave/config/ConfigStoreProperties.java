package com.thehiddenbrain.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where uploaded Postman environments and collections are kept, and the production write floor.
 *
 * @param directory         created at startup. Can hold credentials, so it is never committed
 * @param activeEnvironment the environment to activate at startup. Blank keeps the last one chosen
 * @param allowProdWrites   the hard floor. While false, no in-session unlock can enable a write
 *                          against a production environment
 */
@ConfigurationProperties("config-store")
public record ConfigStoreProperties(
        @DefaultValue("./config-store") String directory,
        @DefaultValue("") String activeEnvironment,
        @DefaultValue("false") boolean allowProdWrites) {

    public static ConfigStoreProperties defaults() {
        return of(Map.of());
    }

    public static ConfigStoreProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(ConfigStoreProperties.class, "config-store", overrides);
    }
}
