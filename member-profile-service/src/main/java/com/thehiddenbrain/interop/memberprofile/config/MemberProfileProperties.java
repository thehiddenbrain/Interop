package com.thehiddenbrain.interop.memberprofile.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Settings under {@code member-profile.*} in application.yaml. */
@Validated
@ConfigurationProperties(prefix = "member-profile")
public record MemberProfileProperties(MemberDomain memberDomain) {

    public record MemberDomain(
            /** Base URL of the MemberDomain service, e.g. https://memberdomain.internal/api/v1 */
            @NotBlank String baseUrl,
            /** Path template appended to baseUrl; {memberId} is substituted. */
            @NotBlank String memberPath,
            Duration connectTimeout,
            Duration readTimeout) {

        public MemberDomain {
            if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
            if (readTimeout == null) readTimeout = Duration.ofSeconds(3);
        }
    }
}
