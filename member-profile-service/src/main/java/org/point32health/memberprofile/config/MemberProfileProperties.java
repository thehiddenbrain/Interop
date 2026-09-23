package org.point32health.memberprofile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Everything under {@code member-profile.*} in application.yaml. Records with defaults so the app also starts without a yaml. */
@ConfigurationProperties(prefix = "member-profile")
public record MemberProfileProperties(
        @DefaultValue MemberDomain memberDomain,
        @DefaultValue Security security,
        @DefaultValue Explain explain,
        @DefaultValue Http http,
        /** Zone in which ages are computed from dates of birth (the plan's business day, not UTC). */
        @DefaultValue("America/New_York") String timeZone) {

    /**
     * The MemberDomain service that owns the member, their rule facts and their family roster.
     *
     * @param baseUrl        e.g. {@code https://memberdomain.internal/api/v1}
     * @param memberPath     appended to the base URL; {@code {memberId}} is substituted
     * @param connectTimeout TCP connect budget
     * @param readTimeout    response budget; the login page waits for this at most
     */
    public record MemberDomain(@DefaultValue("http://localhost:8090/api/v1") String baseUrl,
                               @DefaultValue("/members/{memberId}") String memberPath,
                               @DefaultValue("2s") Duration connectTimeout,
                               @DefaultValue("3s") Duration readTimeout) {
    }

    /**
     * Service-to-service authentication: every {@code /api/**} call must carry the configured key in the
     * {@code X-Api-Key} header. Enabled in prod; the service refuses to start enabled without a key.
     */
    public record Security(@DefaultValue ApiKey apiKey) {
        public record ApiKey(@DefaultValue("false") boolean enabled,
                             @DefaultValue("X-Api-Key") String header,
                             @DefaultValue("") String value) {
        }
    }

    /** Whether callers may ask for the evaluation trace. Off in prod: it returns raw member facts and rule internals. */
    public record Explain(@DefaultValue("true") boolean enabled) {
    }

    /**
     * @param maxBodyBytes largest accepted request body; the real body is under 200 bytes
     * @param fanOutTimeout upper bound for waiting on a parallel rule read (the JDBC query timeout is the real limit)
     */
    public record Http(@DefaultValue("8192") long maxBodyBytes,
                       @DefaultValue("5s") Duration fanOutTimeout) {
    }
}
