package org.p32h.interop.epa.pave.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * @param headers extra request headers, for an API that wants a key. Values may be placeholders.
     *                The gateway resolves them and returns them on the prepared call. Header names
     *                cannot be supplied through operating system variables; to keep a key out of
     *                this file give the header the value {@code {{env:PAVE_NAME}}}
     * @param useOnyxAuth true to send the same access token the Onyx calls use. False sends only
     *                the configured headers
     */
    public record Endpoint(
            @DefaultValue("") String url,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs,
            @JsonIgnore Map<String, String> headers,
            @DefaultValue("false") boolean useOnyxAuth) {

        public Endpoint {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /** Prints header names only. A header value can be an API key typed literally. */
        @Override
        public String toString() {
            return "Endpoint[url=" + url + ", enabled=" + enabled + ", headers=" + headers.keySet() + ", useOnyxAuth=" + useOnyxAuth + "]";
        }
    }

    public static LookupProperties defaults() {
        return of(Map.of());
    }

    public static LookupProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(LookupProperties.class, "lookup", overrides);
    }
}
