package com.thehiddenbrain.interop.extract.transform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The small typed value model every rule works on: null, TEXT (String), DECIMAL (BigDecimal), DATE (LocalDate),
 * TIMESTAMP (LocalDateTime) and BOOLEAN. Conversions here are the only place engine quirks are normalised.
 */
public final class Values {

    private Values() {}

    public static String typeOf(Object v) {
        if (v == null) return "NULL";
        if (v instanceof String) return "TEXT";
        if (v instanceof BigDecimal || v instanceof Number) return "DECIMAL";
        if (v instanceof LocalDate) return "DATE";
        if (v instanceof LocalDateTime) return "TIMESTAMP";
        if (v instanceof Boolean) return "BOOLEAN";
        return "TEXT";
    }

    public static boolean isBlank(Object v) {
        return v == null || (v instanceof String s && s.trim().isEmpty());
    }

    /** Render any value as text using neutral defaults: ISO dates, plain decimals, true/false. */
    public static String text(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).toPlainString();
        if (v instanceof LocalDate d) return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (v instanceof LocalDateTime t) return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (v instanceof Boolean b) return b ? "true" : "false";
        return v.toString();
    }

    public static BigDecimal decimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        String s = v.toString().trim().replace(",", "");
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + v);
        }
    }

    public static LocalDate date(Object v, String inputPattern) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof LocalDateTime t) return t.toLocalDate();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (inputPattern != null && !inputPattern.isBlank()) {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(inputPattern));
        }
        if (s.length() >= 10 && s.charAt(4) == '-') return LocalDate.parse(s.substring(0, 10));
        if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) return LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd"));
        if (s.length() == 10 && s.charAt(2) == '/') return LocalDate.parse(s, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        throw new IllegalArgumentException("Not a date: " + v);
    }

    public static LocalDateTime timestamp(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof LocalDate d) return d.atStartOfDay();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
        return LocalDateTime.parse(s);
    }

    public static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("y") || s.equals("yes") || s.equals("1") || s.equals("t");
    }

    public static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof LocalDate || b instanceof LocalDate) return date(a, null).compareTo(date(b, null));
        if (a instanceof LocalDateTime || b instanceof LocalDateTime) return timestamp(a).compareTo(timestamp(b));
        if (a instanceof Number || b instanceof Number) {
            try {
                return decimal(a).compareTo(decimal(b));
            } catch (IllegalArgumentException e) {
                return text(a).compareTo(text(b));
            }
        }
        if (a instanceof Boolean || b instanceof Boolean) return Boolean.compare(bool(a), bool(b));
        return text(a).compareToIgnoreCase(text(b));
    }

    public static boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Boolean || b instanceof Boolean) return bool(a).equals(bool(b));
        try {
            return compare(a, b) == 0;
        } catch (RuntimeException e) {
            return text(a).equalsIgnoreCase(text(b));
        }
    }

    public static BigDecimal scale(BigDecimal b, int scale) {
        return b.setScale(scale, RoundingMode.HALF_UP);
    }
}
