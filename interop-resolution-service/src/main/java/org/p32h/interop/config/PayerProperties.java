package org.p32h.interop.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The payer identity Onyx puts on every request it sends a UM vendor, returned as {@code payerId} and
 * {@code payerName} whenever a member was identified. One value covers THP and HPHC. If a vendor ever needs a
 * payer code here, or the heritage company instead of the parent, the values change in configuration; the
 * resolved record already carries the company.
 */
@ConfigurationProperties("payer")
@Validated
public record PayerProperties(
        @DefaultValue("Point32Health") @NotBlank String id,
        @DefaultValue("Point32Health") @NotBlank String name) {
}
