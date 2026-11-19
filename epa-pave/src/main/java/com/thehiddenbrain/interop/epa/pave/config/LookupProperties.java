package com.thehiddenbrain.interop.epa.pave.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Member and provider lookup.
 *
 * <p>The timeouts are deliberately shorter than the protocol timeouts. A lookup sits in front of a
 * tester finishing a field, so it gives up quickly and lets them type.
 */
@ConfigurationProperties("lookup")
public record LookupProperties(@DefaultValue Endpoint member, @DefaultValue Endpoint provider) {

    /**
     * @param url     a template containing {@code {id}}, in the path or in the query string. It may
     *                also contain {@code {{placeholders}}} resolved from the active environment.
     *                Blank on the live profile means the lookup is disabled
     * @param enabled false switches the lookup off; the tester then types the details by hand
     * @param headers extra request headers, for an API that wants a key. Values may be placeholders
     */
    public record Endpoint(
            @DefaultValue("") String url,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs,
            Map<String, String> headers) {

        public Endpoint {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public static LookupProperties defaults() {
        return of(Map.of());
    }

    public static LookupProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(LookupProperties.class, "lookup", overrides);
    }
}
