package org.point32health.memberid.config;

import org.point32health.memberid.vendor.VendorIdFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Everything under {@code member-id.*} in application.yaml: the date-of-service window, the DOB sanity
 * limit and the vendor -> format map. Nothing here describes the shape of a member id: the id is passed to MMI as received. Bound once at startup and validated; a bad value stops the
 * application with a message naming the property.
 */
@ConfigurationProperties("member-id")
@Validated
public record MemberIdProperties(
        @Valid @NotNull @DefaultValue DateOfService dateOfService,
        @DefaultValue("125") @Positive int dateOfBirthMaxAgeYears,
        @Valid @NotEmpty Map<String, VendorConfig> vendors) {

    public record DateOfService(
            @DefaultValue("10") @Positive int maxPastYears,
            @DefaultValue("366") @Positive int maxFutureDays,
            @DefaultValue("America/New_York") @NotBlank String zone) {
    }

    /** One vendor entry. The key of the map is the code Onyx sends (compared case-insensitively). */
    public record VendorConfig(
            String displayName,
            @DefaultValue List<String> aliases,
            @NotNull VendorIdFormat format) {
    }
}
