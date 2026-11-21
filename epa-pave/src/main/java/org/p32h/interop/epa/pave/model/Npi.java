package org.p32h.interop.epa.pave.model;

/**
 * National Provider Identifier check digit.
 *
 * <p>An NPI is ten digits. The last is a Luhn check digit computed over the constant prefix 80840
 * followed by the first nine digits. This class is the only implementation of that rule. The
 * application validates with it and every synthetic NPI in the fixtures is generated with it, so a
 * fixture cannot fail the check the application itself applies.
 */
public final class Npi {

    private static final String PREFIX = "80840";

    private Npi() {
    }

    /** True when the text is exactly ten digits with a correct check digit. */
    public static boolean isValid(String npi) {
        if (npi == null || npi.length() != 10 || !allDigits(npi)) {
            return false;
        }
        return checkDigit(npi.substring(0, 9)) == npi.charAt(9) - '0';
    }

    /**
     * What is wrong with a typed NPI, in the one sentence the provider lookup and a send both say,
     * or null when it passes. Spaces at its ends are not part of it. The text typed is never
     * repeated: the sentence names the rule that was broken.
     */
    public static String problem(String text) {
        String npi = text == null ? "" : text.strip();
        if (npi.isEmpty()) {
            return "Enter the ordering provider's NPI.";
        }
        if (npi.length() != 10 || !allDigits(npi)) {
            return "An NPI is 10 digits. Check the number for a typing mistake.";
        }
        if (!isValid(npi)) {
            return "The last digit of this NPI does not match the other nine. Check the number for a typing mistake.";
        }
        return null;
    }

    /** The complete ten digit NPI for a nine digit base. */
    public static String withCheckDigit(String nineDigits) {
        return nineDigits + checkDigit(nineDigits);
    }

    /** The check digit for a nine digit base. */
    public static int checkDigit(String nineDigits) {
        if (nineDigits == null || nineDigits.length() != 9 || !allDigits(nineDigits)) {
            throw new IllegalArgumentException("An NPI base is exactly nine digits.");
        }
        String payload = PREFIX + nineDigits;
        int sum = 0;
        boolean doubleIt = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int digit = payload.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }

    private static boolean allDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
