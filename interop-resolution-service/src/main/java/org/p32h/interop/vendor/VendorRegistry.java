package org.p32h.interop.vendor;

import org.p32h.interop.config.MemberIdProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The vendor table from application.yaml, sorted by code: one {@code memberId.forVendors} entry per vendor, each with
 * its payer (the vendor's own per company, else the default). Built once at startup; a malformed key, a missing format,
 * two keys that differ only in case or a payer without an id or a name stop the application with a message naming them.
 */
public final class VendorRegistry {

    private static final Pattern KEY = Pattern.compile("^[A-Z0-9_]{2,32}$");

    private final List<Vendor> vendors = new ArrayList<>();

    public VendorRegistry(Map<String, MemberIdProperties.VendorConfig> config, Payer defaultPayer) {
        if (defaultPayer == null || isBlank(defaultPayer.id()) || isBlank(defaultPayer.name())) {
            throw new IllegalStateException("payer.id and payer.name are required");
        }
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
            Map<String, Payer> payerByCompany = new LinkedHashMap<>();
            if (cfg.payer() != null) {
                cfg.payer().forEach((rawCompany, p) -> {
                    String company = rawCompany.strip().toUpperCase(Locale.ROOT);
                    String where = "member-id.vendors." + rawKey + ".payer." + rawCompany;
                    if (!KEY.matcher(company).matches()) {
                        throw new IllegalStateException(where + ": the company must match " + KEY.pattern() + " (THP, HPHC)");
                    }
                    if (p == null || isBlank(p.id()) || isBlank(p.name())) {
                        throw new IllegalStateException(where + " needs an id and a name");
                    }
                    if (payerByCompany.put(company, new Payer(p.id().strip(), p.name().strip())) != null) {
                        throw new IllegalStateException(where + ": '" + company + "' is listed twice");
                    }
                });
            }
            vendors.add(new Vendor(code, cfg.displayName() == null ? code : cfg.displayName(), cfg.format(), defaultPayer,
                    Map.copyOf(payerByCompany)));
        });
        vendors.sort(Comparator.comparing(Vendor::code));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Every configured vendor, sorted by code. */
    public List<Vendor> all() {
        return Collections.unmodifiableList(vendors);
    }
}
