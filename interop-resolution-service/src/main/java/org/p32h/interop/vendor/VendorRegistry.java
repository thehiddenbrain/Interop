package org.p32h.interop.vendor;

import org.p32h.interop.config.MemberIdProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The vendor table from application.yaml, sorted by code: one {@code memberId.forVendors} entry per vendor. Built once at
 * startup; a malformed key, a missing format or two keys that differ only in case stop the application with a message
 * naming them.
 */
public final class VendorRegistry {

    private static final Pattern KEY = Pattern.compile("^[A-Z0-9_]{2,32}$");

    private final List<Vendor> vendors = new ArrayList<>();

    public VendorRegistry(Map<String, MemberIdProperties.VendorConfig> config) {
        if (config == null || config.isEmpty()) {
            throw new IllegalStateException("member-id.vendors must list at least one vendor");
        }
        Set<String> codes = new HashSet<>();
        config.forEach((rawKey, cfg) -> {
            String code = rawKey.strip().toUpperCase(Locale.ROOT);
            if (!KEY.matcher(code).matches()) {
                throw new IllegalStateException("member-id.vendors: key '" + rawKey + "' must match " + KEY.pattern());
            }
            if (cfg == null || cfg.format() == null) {
                throw new IllegalStateException("member-id.vendors." + rawKey + ".format is required (one of "
                        + List.of(VendorIdFormat.values()) + ")");
            }
            if (!codes.add(code)) {
                throw new IllegalStateException("member-id.vendors: '" + code + "' is listed twice");
            }
            vendors.add(new Vendor(code, cfg.displayName() == null ? code : cfg.displayName(), cfg.format()));
        });
        vendors.sort(Comparator.comparing(Vendor::code));
    }

    /** Every configured vendor, sorted by code. */
    public List<Vendor> all() {
        return Collections.unmodifiableList(vendors);
    }
}
