package org.point32health.memberprofile.segmentation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The member attributes the segmentation rules are evaluated against, normalized once per request.
 * <p>
 * Text comparisons are case-insensitive and trimmed, so every value is upper-cased once here instead of
 * once per condition. Numbers are normalized to their plain form ({@code 2001.0} and {@code 2.001E3} both
 * read as {@code 2001}) so a rule written as {@code sourceSystemId EQUALS 2001} matches whether MemberDomain
 * sends a string, an integer or a float. Booleans accept the spellings the source systems use ({@code true},
 * {@code Y}, {@code 1}, ...). A fact that is absent, null or blank never satisfies any condition, not even a
 * negative one such as NOT_EQUALS.
 */
public final class MemberFacts {

    private final Map<String, Object> raw;
    private final Map<String, String> text;

    private MemberFacts(Map<String, Object> raw) {
        this.raw = raw;
        this.text = new HashMap<>(Math.max(16, raw.size() * 2));
        raw.forEach((k, v) -> {
            String normalized = normalizeValue(v);
            if (normalized != null) text.put(k, normalized);
        });
    }

    public static MemberFacts of(Map<String, Object> attributes) {
        return new MemberFacts(attributes == null ? Map.of() : attributes);
    }

    /** Upper-cased, trimmed text of the fact, or null when absent. */
    public String text(String field) {
        return text.get(field);
    }

    public Object raw(String field) {
        return raw.get(field);
    }

    /** {@code TRUE}, {@code FALSE}, or null when absent or not a recognizable boolean. */
    public Boolean bool(String field) {
        Object value = raw.get(field);
        if (value instanceof Boolean b) return b;
        String s = text.get(field);
        if (s == null) return null;
        return switch (s) {
            case "TRUE", "Y", "YES", "1" -> Boolean.TRUE;
            case "FALSE", "N", "NO", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Numeric value of the fact, or null when absent or not numeric. */
    public BigDecimal number(String field) {
        Object value = raw.get(field);
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        // Double/Float NaN and infinities are not numbers for our purposes; parseNumber turns them into null.
        return parseNumber(value.toString());
    }

    public Map<String, Object> raw() {
        return raw;
    }

    static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** Text form of a fact for comparisons, or null when the fact carries no value. */
    static String normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n && !(value instanceof BigDecimal)) {
            BigDecimal number = parseNumber(n.toString());
            if (number == null) return null;                       // NaN, infinities
            return number.stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        String text = normalize(value.toString());
        return text.isEmpty() ? null : text;
    }

    static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
