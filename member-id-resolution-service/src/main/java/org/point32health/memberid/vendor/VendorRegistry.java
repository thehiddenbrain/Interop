package org.point32health.memberid.vendor;

import org.point32health.memberid.config.MemberIdProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The vendor table from application.yml, indexed by code and alias (case-insensitive). Built once at
 * startup; a duplicate alias or a malformed key stops the application with a message naming it.
 */
public final class VendorRegistry {

    private static final Pattern KEY = Pattern.compile("^[A-Z0-9_]{2,32}$");

    private final Map<String, Vendor> byCodeOrAlias = new HashMap<>();
    private final List<Vendor> vendors = new ArrayList<>();

    public VendorRegistry(Map<String, MemberIdProperties.VendorConfig> config) {
        if (config == null || config.isEmpty()) {
            throw new IllegalStateException("member-id.vendors must list at least one vendor");
        }
        config.forEach((rawKey, cfg) -> {
            String code = normalise(rawKey);
            if (!KEY.matcher(code).matches()) {
                throw new IllegalStateException("member-id.vendors: key '" + rawKey + "' must match " + KEY.pattern());
            }
            if (cfg == null || cfg.format() == null) {
                throw new IllegalStateException("member-id.vendors." + rawKey + ".format is required (one of "
                        + List.of(VendorIdFormat.values()) + ")");
            }
            Vendor vendor = new Vendor(code, cfg.displayName() == null ? code : cfg.displayName(), cfg.format());
            register(code, vendor, rawKey);
            vendors.add(vendor);
            for (String alias : cfg.aliases() == null ? List.<String>of() : cfg.aliases()) {
                register(normalise(alias), vendor, rawKey);
            }
        });
        vendors.sort((a, b) -> a.code().compareTo(b.code()));
    }

    private void register(String key, Vendor vendor, String rawKey) {
        Vendor previous = byCodeOrAlias.putIfAbsent(key, vendor);
        if (previous != null && !previous.equals(vendor)) {
            throw new IllegalStateException("member-id.vendors: '" + key + "' is used by both " + previous.code()
                    + " and " + rawKey + "; codes and aliases must be unique");
        }
    }

    public Optional<Vendor> find(String codeOrAlias) {
        if (codeOrAlias == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCodeOrAlias.get(normalise(codeOrAlias)));
    }

    public List<Vendor> all() {
        return Collections.unmodifiableList(vendors);
    }

    public List<String> knownCodes() {
        return vendors.stream().map(Vendor::code).toList();
    }

    private static String normalise(String s) {
        return s.strip().toUpperCase(Locale.ROOT);
    }
}
