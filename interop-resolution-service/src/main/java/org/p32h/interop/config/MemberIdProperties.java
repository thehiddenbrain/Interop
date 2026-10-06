package org.p32h.interop.config;

import org.p32h.interop.vendor.VendorIdFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

    /** No upper limit on the date of service: a future date is judged against the coverage on record (feedback 20). */
    public record DateOfService(
            @DefaultValue("10") @Positive int maxPastYears,
            @DefaultValue("America/New_York") @NotBlank String zone) {
    }

    /** One vendor entry. The key of the map is the vendor code, as it appears in {@code vendorMemberIds}. */
    public record VendorConfig(
            String displayName,
            @NotNull VendorIdFormat format) {
    }
}
