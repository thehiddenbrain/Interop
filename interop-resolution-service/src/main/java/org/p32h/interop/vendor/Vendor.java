package org.p32h.interop.vendor;

import java.util.Locale;
import java.util.Map;

/**
 * One configured vendor: its code, the id format it stores, and the payer Onyx puts on its requests. A vendor that keys
 * the payer on the heritage company has an entry per company (THP, HPHC); every other member, and every vendor without
 * entries, gets the default payer.
 */
public record Vendor(String code, String displayName, VendorIdFormat format, Payer defaultPayer, Map<String, Payer> payerByCompany) {

    /** The payer for a member of {@code company} as the member lookup reports it: the vendor's own entry, else the default. */
    public Payer payerFor(String company) {
        return hasOwnPayerFor(company) ? payerByCompany.get(key(company)) : defaultPayer;
    }

    /** True when this vendor has its own payer for {@code company}. */
    public boolean hasOwnPayerFor(String company) {
        return company != null && payerByCompany.containsKey(key(company));
    }

    private static String key(String company) {
        return company.strip().toUpperCase(Locale.ROOT);
    }
}
