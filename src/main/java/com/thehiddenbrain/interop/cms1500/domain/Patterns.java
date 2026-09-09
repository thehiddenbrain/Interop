package com.thehiddenbrain.interop.cms1500.domain;

import java.util.regex.Pattern;

/** Value patterns shared by the validator and the XSD (keep both in sync). */
public final class Patterns {

    public static final Pattern CLAIM_NUMBER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    public static final Pattern STATE = Pattern.compile("[A-Za-z]{2}");
    public static final Pattern ZIP = Pattern.compile("[0-9]{5}(-?[0-9]{4})?");
    public static final Pattern AREA_CODE = Pattern.compile("[0-9]{3}");
    public static final Pattern PHONE = Pattern.compile("[0-9]{7}");
    public static final Pattern NPI = Pattern.compile("[0-9]{10}");
    public static final Pattern TAX_ID = Pattern.compile("[0-9]{9}");
    public static final Pattern PLACE_OF_SERVICE = Pattern.compile("[0-9]{2}");
    public static final Pattern PROCEDURE_CODE = Pattern.compile("[A-Za-z0-9]{5}");
    public static final Pattern MODIFIER = Pattern.compile("[A-Za-z0-9]{2}");
    public static final Pattern DIAGNOSIS_POINTERS = Pattern.compile("[A-La-l]{1,4}");
    public static final Pattern DIAGNOSIS_CODE = Pattern.compile("[A-Za-z0-9]{3}[A-Za-z0-9.]{0,5}");
    public static final Pattern ICD10_CODE = Pattern.compile("[A-Za-z][0-9][A-Za-z0-9](\\.?[A-Za-z0-9]{1,4})?");
    public static final Pattern ICD9_CODE = Pattern.compile(
            "[0-9]{3}(\\.?[0-9]{1,2})?|[Vv][0-9]{2}(\\.?[0-9]{1,2})?|[Ee][0-9]{3}(\\.?[0-9])?");
    public static final Pattern ITEM14_QUALIFIER = Pattern.compile("431|484");
    public static final Pattern ITEM15_QUALIFIER = Pattern.compile("454|304|453|439|455|471|090|091|444|050|054");
    public static final Pattern REFERRING_QUALIFIER = Pattern.compile("DN|DK|DQ");
    public static final Pattern OTHER_ID_QUALIFIER = Pattern.compile("0B|1G|G2|LU");
    public static final Pattern RENDERING_ID_QUALIFIER = Pattern.compile("0B|1G|G2|LU|ZZ");
    public static final Pattern OTHER_CLAIM_ID_QUALIFIER = Pattern.compile("Y4");
    public static final Pattern EPSDT_CODE = Pattern.compile("AV|S2|ST|NU");

    private Patterns() {
    }

    /** NPI check digit: Luhn over the 15-digit number formed by prefixing 80840. */
    public static boolean validNpi(String npi) {
        if (npi == null || !NPI.matcher(npi).matches()) {
            return false;
        }
        String full = "80840" + npi;
        int sum = 0;
        boolean doubleIt = false;
        for (int i = full.length() - 1; i >= 0; i--) {
            int d = full.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
