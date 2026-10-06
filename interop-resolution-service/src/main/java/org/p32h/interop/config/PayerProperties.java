package org.p32h.interop.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The default payer identity Onyx puts on its requests to a UM vendor, returned in every {@code memberId.forVendors}
 * entry as {@code payerId} and {@code payerName}. One value covers THP and HPHC. A vendor that keys the payer on the
 * heritage company sets its own per company in its {@code member-id.vendors} entry.
 */
@ConfigurationProperties("payer")
@Validated
public record PayerProperties(
        @DefaultValue("Point32Health") @NotBlank String id,
        @DefaultValue("Point32Health") @NotBlank String name) {
}
