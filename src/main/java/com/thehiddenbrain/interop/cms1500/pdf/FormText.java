package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.contract.PersonName;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Turns request values into the strings printed on the form, following the NUCC 1500
 * instruction manual: no punctuation in phones/ZIPs/tax IDs, money as dollars and cents
 * without a decimal point, dates split into MM DD YY(YY), names as LAST, FIRST M.
 * Everything is reduced to printable ASCII so the template's WinAnsi fonts can render it.
 */
public final class FormText {

    public static final String SIGNATURE_ON_FILE = "SIGNATURE ON FILE";

    /** Latin letters that have no combining-mark decomposition and would otherwise become '?'. */
    private static final Map<Character, String> LIGATURES = Map.ofEntries(
            Map.entry('\u00C6', "AE"), Map.entry('\u00E6', "ae"), Map.entry('\u0152', "OE"), Map.entry('\u0153', "oe"),
            Map.entry('\u00D8', "O"), Map.entry('\u00F8', "o"), Map.entry('\u00DF', "ss"), Map.entry('\u1E9E', "SS"),
            Map.entry('\u00D0', "D"), Map.entry('\u00F0', "d"), Map.entry('\u0110', "D"), Map.entry('\u0111', "d"),
            Map.entry('\u00DE', "TH"), Map.entry('\u00FE', "th"), Map.entry('\u0141', "L"), Map.entry('\u0142', "l"),
            Map.entry('\u2018', "'"), Map.entry('\u2019', "'"), Map.entry('\u201C', "\""), Map.entry('\u201D', "\""),
            Map.entry('\u2013', "-"), Map.entry('\u2014', "-"), Map.entry('\u00A0', " "));

    private final boolean uppercase;
    private final boolean stripDiagnosisPeriods;

    public FormText(boolean uppercase, boolean stripDiagnosisPeriods) {
        this.uppercase = uppercase;
        this.stripDiagnosisPeriods = stripDiagnosisPeriods;
    }

    public boolean uppercase() {
        return uppercase;
    }

    /** Trims, folds accents to ASCII, replaces anything unprintable with '?', collapses whitespace. Blank gives null. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        StringBuilder mapped = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char ch = collapsed.charAt(i);
            String replacement = LIGATURES.get(ch);
            mapped.append(replacement != null ? replacement : ch);
        }
        String folded = Normalizer.normalize(mapped, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String ascii = folded.replaceAll("[^\\x20-\\x7E]", "?").trim();
        return ascii.isEmpty() ? null : ascii;
    }

    /** Free text for a box, upper-cased when configured. */
    public String text(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : (uppercase ? cleaned.toUpperCase(Locale.ROOT) : cleaned);
    }

    /** Items 2, 4, 9: LAST, FIRST M. */
    public String personName(PersonName name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String last = clean(name.getLastName());
        String first = clean(name.getFirstName());
        String middle = clean(name.getMiddleInitial());
        if (last != null) {
            sb.append(last);
        }
        if (first != null) {
            sb.append(sb.isEmpty() ? "" : ", ").append(first);
        }
        if (middle != null) {
            sb.append(sb.isEmpty() ? "" : " ").append(middle.charAt(0));
        }
        return text(sb.toString());
    }

    /** CITY ST 12345 on a single line (items 32, 33, carrier block). */
    public String cityStateZip(String city, String state, String zip) {
        StringJoiner joiner = new StringJoiner(" ");
        addIfPresent(joiner, clean(city));
        addIfPresent(joiner, clean(state));
        addIfPresent(joiner, zip(zip));
        return joiner.length() == 0 ? null : text(joiner.toString());
    }

    private static void addIfPresent(StringJoiner joiner, String value) {
        if (value != null) {
            joiner.add(value);
        }
    }

    /** Digits only (NUCC: no hyphens in ZIP codes). */
    public static String zip(String zip) {
        return digits(zip);
    }

    public static String digits(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    public static String twoDigitMonth(LocalDate d) {
        return d == null ? null : String.format("%02d", d.getMonthValue());
    }

    public static String twoDigitDay(LocalDate d) {
        return d == null ? null : String.format("%02d", d.getDayOfMonth());
    }

    public static String twoDigitYear(LocalDate d) {
        return d == null ? null : String.format("%02d", d.getYear() % 100);
    }

    public static String fourDigitYear(LocalDate d) {
        return d == null ? null : String.format("%04d", d.getYear());
    }

    /** Single-field dates (items 12, 31): MM DD YYYY. */
    public static String dateText(LocalDate d) {
        return d == null ? null : twoDigitMonth(d) + " " + twoDigitDay(d) + " " + fourDigitYear(d);
    }

    /** Money without symbols or decimal point, cents separated by a space: 1234.5 becomes "1234 50". */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        String plain = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return plain.replace(".", " ");
    }

    /** Units without trailing zeros: 1.00 becomes "1", 1.50 becomes "1.5". */
    public static String units(BigDecimal units) {
        if (units == null) {
            return null;
        }
        BigDecimal stripped = units.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    /** Item 21 codes: upper case, period removed when configured. */
    public String diagnosis(String code) {
        String cleaned = clean(code);
        if (cleaned == null) {
            return null;
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        return stripDiagnosisPeriods ? upper.replace(".", "") : upper;
    }

    /** Item 24E pointers, upper case letters only. */
    public static String pointers(String pointers) {
        String cleaned = clean(pointers);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    /** Codes and qualifiers are always upper case regardless of configuration. */
    public static String code(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }
}
