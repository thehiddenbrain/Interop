package com.thehiddenbrain.interop.memberid.mmi;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mmi.*}: where MMI is and how we identify ourselves to it. Timeouts come from the standard
 * {@code spring.http.client.connect-timeout} / {@code read-timeout} properties.
 */
@ConfigurationProperties("mmi")
@Validated
public record MmiProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("/master/member/v1") String path,
        @NotBlank String clientId,
        @DefaultValue("INT") String clientType,
        @DefaultValue("N") String voidCoverageRecord,
        @DefaultValue("ERROR") List<String> errorMessageTypes,
        @DefaultValue Stub stub) {

    /** The in-process stub used for local runs and tests. Allowed only with the local or test profile. */
    public record Stub(@DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:mmi-stub/members.json") String fixtures) {
    }
}
