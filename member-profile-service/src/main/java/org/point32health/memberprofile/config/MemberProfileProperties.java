package org.point32health.memberprofile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Everything under {@code member-profile.*} in application.yaml. Records with defaults so the app also starts without a yaml. */
@ConfigurationProperties(prefix = "member-profile")
public record MemberProfileProperties(@DefaultValue MemberDomain memberDomain) {

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
}
