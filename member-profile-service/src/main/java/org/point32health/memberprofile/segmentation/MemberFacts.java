package org.point32health.memberprofile.segmentation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The member attributes the segmentation rules are evaluated against, normalized once per request.
 * <p>
 * Text comparisons are case-insensitive and trimmed, so every value is upper-cased once here instead of
 * once per condition. Booleans accept the spellings the source systems use ({@code true}, {@code Y},
 * {@code 1}, ...). A fact that is absent or null never satisfies any condition.
 */
public final class MemberFacts {

    private final Map<String, Object> raw;
    private final Map<String, String> text;

    private MemberFacts(Map<String, Object> raw) {
        this.raw = raw;
        this.text = new HashMap<>(Math.max(16, raw.size() * 2));
        raw.forEach((k, v) -> {
            if (v != null) text.put(k, normalize(v.toString()));
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
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return parseNumber(value.toString());
    }

    public Map<String, Object> raw() {
        return raw;
    }

    static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
