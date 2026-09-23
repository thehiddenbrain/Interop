package org.point32health.memberprofile.segmentation;

/**
 * The comparison operators a {@code segment_rule} row may use (Operators tab of the workbook).
 * Each operator tests one normalized member fact against one pre-parsed rule value. A missing fact never
 * matches, whatever the operator, so a rule cannot pass on absent data.
 */
public enum ComparisonOperator {
    /** Actual API value equals rule value. */
    EQUALS {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && actual.equals(rule.normalizedValue());
        }
    },
    /** Actual API value does not equal rule value. */
    NOT_EQUALS {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && !actual.equals(rule.normalizedValue());
        }
    },
    /** Actual API value is in a comma-separated list. */
    IN {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && rule.valueSet().contains(actual);
        }
    },
    /** Actual API value is not in a comma-separated list. */
    NOT_IN {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && !rule.valueSet().contains(actual);
        }
    },
    /** Actual API text contains rule value. */
    CONTAINS {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && actual.contains(rule.normalizedValue());
        }
    },
    /** Actual API text does not contain rule value. */
    NOT_CONTAINS {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            String actual = facts.text(rule.apiField());
            return actual != null && !actual.contains(rule.normalizedValue());
        }
    },
    /** Actual API value resolves to true. */
    IS_TRUE {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            return Boolean.TRUE.equals(facts.bool(rule.apiField()));
        }
    },
    /** Actual API value resolves to false. */
    IS_FALSE {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            return Boolean.FALSE.equals(facts.bool(rule.apiField()));
        }
    },
    /** Numeric API value is greater than rule value. */
    GREATER_THAN {
        @Override boolean test(MemberFacts facts, SegmentRule rule) {
            var actual = facts.number(rule.apiField());
            return actual != null && rule.numericValue() != null && actual.compareTo(rule.numericValue()) > 0;
        }
    };

    abstract boolean test(MemberFacts facts, SegmentRule rule);

    /** IS_TRUE and IS_FALSE have no rule value; every other operator needs one. */
    public boolean needsRuleValue() {
        return this != IS_TRUE && this != IS_FALSE;
    }
}
