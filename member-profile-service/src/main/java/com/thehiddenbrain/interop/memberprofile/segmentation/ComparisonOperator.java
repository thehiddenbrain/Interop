package com.thehiddenbrain.interop.memberprofile.segmentation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The comparison operators a segment_rule row may use (see the Operators tab of the workbook).
 * String comparisons are case-insensitive and trimmed. A null member fact never matches,
 * whatever the operator, so a rule cannot pass on missing data.
 */
public enum ComparisonOperator {
    EQUALS {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && text(actual).equals(text(ruleValue));
        }
    },
    NOT_EQUALS {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && !text(actual).equals(text(ruleValue));
        }
    },
    IN {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && list(ruleValue).contains(text(actual));
        }
    },
    NOT_IN {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && !list(ruleValue).contains(text(actual));
        }
    },
    CONTAINS {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && text(actual).contains(text(ruleValue));
        }
    },
    NOT_CONTAINS {
        @Override boolean test(Object actual, String ruleValue) {
            return actual != null && !text(actual).contains(text(ruleValue));
        }
    },
    IS_TRUE {
        @Override boolean test(Object actual, String ruleValue) {
            return Boolean.TRUE.equals(bool(actual));
        }
    },
    IS_FALSE {
        @Override boolean test(Object actual, String ruleValue) {
            return Boolean.FALSE.equals(bool(actual));
        }
    },
    GREATER_THAN {
        @Override boolean test(Object actual, String ruleValue) {
            BigDecimal left = number(actual);
            BigDecimal right = number(ruleValue);
            return left != null && right != null && left.compareTo(right) > 0;
        }
    };

    abstract boolean test(Object actual, String ruleValue);

    public boolean needsRuleValue() {
        return this != IS_TRUE && this != IS_FALSE;
    }

    static String text(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    static Set<String> list(String ruleValue) {
        return Arrays.stream(text(ruleValue).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Booleans arrive as JSON booleans, "true"/"false", "Y"/"N" or 1/0 depending on the source system. */
    static Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        String s = text(value);
        return switch (s) {
            case "TRUE", "Y", "YES", "1" -> Boolean.TRUE;
            case "FALSE", "N", "NO", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    static BigDecimal number(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
