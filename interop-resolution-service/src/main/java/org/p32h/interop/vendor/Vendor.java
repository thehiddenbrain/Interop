package org.p32h.interop.vendor;

import java.util.Locale;
import java.util.Map;

/**
 * One configured vendor: its code, the id format it stores, and the payer Onyx puts on its requests. A vendor that keys
 * the payer on the heritage company has an entry per company (THP, HPHC); an entry keyed company and line of business
 * (THP_PP) applies to the members of that company whose member record carries that line of business (the member lookup's
 * own value, COM, MCR or PP, not the derived line of business of the answer), before the company's own. Every other
 * member, and every vendor without entries, gets the default payer.
 */
public record Vendor(String code, String displayName, VendorIdFormat format, Payer defaultPayer, Map<String, Payer> payerByCompany) {

    /**
     * The payer for a member of {@code company} whose member record carries {@code lineOfBusiness}, both as the member
     * lookup reports them: the vendor's entry for the company and line of business, else for the company, else the default.
     */
    public Payer payerFor(String company, String lineOfBusiness) {
        String key = ownPayerKey(company, lineOfBusiness);
        return key == null ? defaultPayer : payerByCompany.get(key);
    }

    /** True when this vendor has its own payer for {@code company}, with or without {@code lineOfBusiness}. */
    public boolean hasOwnPayerFor(String company, String lineOfBusiness) {
        return ownPayerKey(company, lineOfBusiness) != null;
    }

    private String ownPayerKey(String company, String lineOfBusiness) {
        if (company == null) {
            return null;
        }
        String companyKey = key(company);
        if (lineOfBusiness != null && !lineOfBusiness.isBlank()) {
            String refined = companyKey + "_" + key(lineOfBusiness);
            if (payerByCompany.containsKey(refined)) {
                return refined;
            }
        }
        return payerByCompany.containsKey(companyKey) ? companyKey : null;
    }

    private static String key(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }
}
