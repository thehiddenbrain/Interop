package org.p32h.interop.epa.pave.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The fallback layer for Onyx endpoints, plus timeouts and authentication.
 *
 * <p>In normal use every url comes from the active Postman environment and an uploaded collection,
 * and nothing here needs editing. These urls are used only for an operation that has no mapped
 * collection request. Each is a base url, may contain {@code {{placeholders}}}, and the tool
 * appends the standard path for the operation.
 *
 * @param variables fallback values for placeholders, consulted after the environment and the
 *                  collection. Keys are placeholder names and are case sensitive, so they cannot be
 *                  supplied through operating system variables, which Spring lower-cases. To keep a
 *                  secret out of this file give the variable the value {@code {{env:PAVE_NAME}}}
 */
@ConfigurationProperties("onyx")
public record OnyxProperties(
        @DefaultValue Crd crd,
        @DefaultValue Dtr dtr,
        @DefaultValue Pas pas,
        @DefaultValue Subscription subscription,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("15000") int readTimeoutMs,
        @DefaultValue Auth auth,
        @JsonIgnore Map<String, String> variables) {

    public OnyxProperties {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** Prints variable names only. A value can be a secret. */
    @Override
    public String toString() {
        return "OnyxProperties[crd=" + crd + ", dtr=" + dtr + ", pas=" + pas + ", subscription=" + subscription
                + ", connectTimeoutMs=" + connectTimeoutMs + ", readTimeoutMs=" + readTimeoutMs + ", auth=" + auth
                + ", variables=" + variables.keySet() + "]";
    }

    /** @param url the CDS Hooks base. Services are addressed as {@code url/cds-services/service-id} */
    public record Crd(@DefaultValue("") String url) {
    }

    /** @param url the FHIR base the DTR operations hang off */
    public record Dtr(@DefaultValue("") String url) {
    }

    /**
     * @param url            the FHIR base the PAS operations hang off
     * @param attachmentMode how supporting documentation travels: fhir for FHIR-native attachment
     *                       references, x12-275 for X12 275 attachments
     */
    public record Pas(@DefaultValue("") String url, @DefaultValue("fhir") String attachmentMode) {
    }

    /**
     * @param url         the FHIR base subscriptions are managed on
     * @param callbackUrl the url Onyx should post notifications to. It must reach this tool's
     *                    receiver from where Onyx runs, which on a corporate workstation it may not
     */
    public record Subscription(@DefaultValue("") String url, @DefaultValue("") String callbackUrl) {
    }

    /** OAuth2 client credentials. Each value may be a {@code {{placeholder}}} resolved from the active environment. */
    public record Auth(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String tokenUrl,
            @DefaultValue("") String clientId,
            @JsonIgnore @DefaultValue("") String clientSecret,
            @DefaultValue("") String scopes) {

        /** Redacted, because a record's default toString would print the client secret. */
        @Override
        public String toString() {
            return "Auth[enabled=" + enabled + ", clientSecret redacted]";
        }
    }

    public static OnyxProperties defaults() {
        return of(Map.of());
    }

    public static OnyxProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(OnyxProperties.class, "onyx", overrides);
    }
}
