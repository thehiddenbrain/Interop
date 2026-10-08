package org.p32h.interop.memberinfo;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code member-info.*}: where the member information service is, and the two timeouts of its one HTTP call.
 * The service returns the member's plan, from which the line of business is derived.
 */
@ConfigurationProperties("member-info")
@Validated
public record MemberInfoProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("/members") String path,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("5s") @NotNull Duration readTimeout,
        @DefaultValue("false") boolean logPayloads,
        @DefaultValue Stub stub) {

    // logPayloads: print the full request and response bodies. For integration debugging in DEV / FQA / PQA; keep it off in PRD.

    /** The in-process stub used for developer runs and tests. Allowed only with the DEV or test profile. */
    public record Stub(@DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:member-info-stub/members.json") String fixtures) {
    }
}
