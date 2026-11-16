package org.p32h.interop.vendor;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * One configured vendor: its code, the id format it stores, and the payer Onyx puts on its requests. A vendor that keys
 * the payer on the heritage company has an entry per company (THP, HPHC). An entry keyed company and line of business
 * applies before the company's own, and is looked for twice: with the line of business of the answer (THP_D-SNP; the
 * derived value, SCO, MA-HMO, D-SNP and so on), then with the one on the member record as the member lookup reports
 * it (THP_PP; COM, MCR or PP). Every other member, and every vendor without entries, gets the default payer.
 */
public record Vendor(String code, String displayName, VendorIdFormat format, Payer defaultPayer, Map<String, Payer> payerByCompany) {

    /**
     * The payer for a member of {@code company} (as the member lookup reports it): the vendor's entry for the company
     * and {@code lineOfBusiness}, the line of business of the answer; else for the company and
     * {@code recordLineOfBusiness}, the one on the member record; else for the company; else the default.
     */
    public Payer payerFor(String company, String lineOfBusiness, String recordLineOfBusiness) {
        String key = ownPayerKey(company, lineOfBusiness, recordLineOfBusiness);
        return key == null ? defaultPayer : payerByCompany.get(key);
    }

    /** True when this vendor has its own payer for {@code company}, with or without either line of business. */
    public boolean hasOwnPayerFor(String company, String lineOfBusiness, String recordLineOfBusiness) {
        return ownPayerKey(company, lineOfBusiness, recordLineOfBusiness) != null;
    }

    private String ownPayerKey(String company, String lineOfBusiness, String recordLineOfBusiness) {
        if (company == null) {
            return null;
        }
        String companyKey = key(company);
        for (String line : Arrays.asList(lineOfBusiness, recordLineOfBusiness)) {
            if (line != null && !line.isBlank()) {
                String refined = companyKey + "_" + key(line);
                if (payerByCompany.containsKey(refined)) {
                    return refined;
                }
            }
        }
        return payerByCompany.containsKey(companyKey) ? companyKey : null;
    }

    private static String key(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }
}
