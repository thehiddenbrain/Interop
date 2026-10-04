package org.point32health.memberid.mmi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mmi.*}: where MMI is, how we identify ourselves to it, and the two timeouts of the one HTTP call.
 */
@ConfigurationProperties("mmi")
@Validated
public record MmiProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("/master/member/v1") String path,
        @NotBlank String clientId,
        @DefaultValue("INT") String clientType,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("5s") @NotNull Duration readTimeout,
        @DefaultValue("N") String voidCoverageRecord,
        @DefaultValue("ERROR") List<String> errorMessageTypes,
        @DefaultValue("false") boolean logPayloads,
        @DefaultValue Stub stub) {

    // MMI's HTTP contract is fixed (200 success, 404 no member, 400 bad request, 500 internal error) and mapped in
    // RestMmiClient; nothing about it is configurable.
    // logPayloads: print the full MMI request and response bodies (they contain member PHI). For integration
    // debugging in dev / FQA / PQA; keep it off in prod.

    /** The in-process stub used for developer runs and tests. Allowed only with the dev or test profile. */
    public record Stub(@DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:mmi-stub/members.json") String fixtures) {
    }
}
