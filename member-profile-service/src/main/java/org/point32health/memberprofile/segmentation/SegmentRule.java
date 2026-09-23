package org.point32health.memberprofile.segmentation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One active row of {@code league_segmentation.segment_rule}, with its rule value parsed once at read time
 * so the evaluator does no string work per condition.
 * <p>
 * Conditions sharing {@code (segment, company, ruleGroup)} are ANDed; a segment is true when at least one
 * of its groups matches (OR). The workbook's {@code logical_operator} column is not needed for that and is
 * not read.
 *
 * @param normalizedValue upper-cased, trimmed {@code rule_value} (EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS)
 * @param valueSet        the comma-separated {@code rule_value} as a set (IN, NOT_IN)
 * @param numericValue    {@code rule_value} as a number (GREATER_THAN), null when not numeric
 */
public record SegmentRule(
        long id,
        Segment segment,
        String company,
        int ruleGroup,
        int evaluationOrder,
        String apiField,
        ComparisonOperator operator,
        String ruleValue,
        String normalizedValue,
        Set<String> valueSet,
        BigDecimal numericValue) {

    public static SegmentRule of(long id, Segment segment, String company, int ruleGroup, int evaluationOrder,
                                 String apiField, ComparisonOperator operator, String ruleValue) {
        String normalized = ruleValue == null ? "" : MemberFacts.normalize(ruleValue);
        Set<String> set = switch (operator) {
            case IN, NOT_IN -> Arrays.stream(normalized.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
            default -> Set.of();
        };
        BigDecimal number = operator == ComparisonOperator.GREATER_THAN ? MemberFacts.parseNumber(normalized) : null;
        return new SegmentRule(id, segment, company, ruleGroup, evaluationOrder, apiField, operator, ruleValue,
                normalized, set, number);
    }

    boolean matches(MemberFacts facts) {
        return operator.test(facts, this);
    }
}
