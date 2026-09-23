package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.CONTAINS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.EQUALS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.GREATER_THAN;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IN;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IS_FALSE;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IS_TRUE;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_CONTAINS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_EQUALS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_IN;

/**
 * {@link SegmentRule}: one {@code segment_rule} row with its rule value parsed once at read time
 * (normalized text for the text operators, a set for IN / NOT_IN, a number for GREATER_THAN).
 */
class SegmentRuleTest {

    private static SegmentRule rule(ComparisonOperator operator, String ruleValue) {
        return SegmentRule.of(7L, Segment.TMP_OTC_MA, "THP", 3, 8, "planCode", operator, ruleValue);
    }

    @Nested
    class Fields {

        @Test
        void ofKeepsEveryColumnOfTheRow() {
            SegmentRule rule = SegmentRule.of(42L, Segment.ONLINE_BILL_PAY, "HPHC", 1, 3, "customerCategory", NOT_EQUALS, "NH_39_WEEK");
            assertThat(rule.id()).isEqualTo(42L);
            assertThat(rule.segment()).isEqualTo(Segment.ONLINE_BILL_PAY);
            assertThat(rule.company()).isEqualTo("HPHC");
            assertThat(rule.ruleGroup()).isEqualTo(1);
            assertThat(rule.evaluationOrder()).isEqualTo(3);
            assertThat(rule.apiField()).isEqualTo("customerCategory");
            assertThat(rule.operator()).isEqualTo(NOT_EQUALS);
            assertThat(rule.ruleValue()).isEqualTo("NH_39_WEEK");
        }

        @Test
        void ruleValueIsKeptVerbatimForTheExplainTrace() {
            SegmentRule rule = rule(EQUALS, "  std saver ");
            assertThat(rule.ruleValue()).isEqualTo("  std saver ");
            assertThat(rule.normalizedValue()).isEqualTo("STD SAVER");
        }

        @Test
        void rulesWithTheSameColumnsAreEqual() {
            assertThat(rule(IN, "SCO,PDP")).isEqualTo(rule(IN, "SCO,PDP"));
            assertThat(rule(IN, "SCO,PDP")).isNotEqualTo(rule(NOT_IN, "SCO,PDP"));
            assertThat(rule(IN, "SCO,PDP")).isNotEqualTo(rule(IN, "SCO"));
        }

        @ParameterizedTest
        @EnumSource(ComparisonOperator.class)
        void everyOperatorCanBeParsedWithAValue(ComparisonOperator operator) {
            SegmentRule rule = rule(operator, "1");
            assertThat(rule.operator()).isEqualTo(operator);
            assertThat(rule.normalizedValue()).isEqualTo("1");
        }

        @ParameterizedTest
        @EnumSource(value = ComparisonOperator.class, names = {"IS_TRUE", "IS_FALSE"})
        void booleanOperatorsHaveNoRuleValue(ComparisonOperator operator) {
            SegmentRule rule = rule(operator, null);
            assertThat(rule.ruleValue()).isNull();
            assertThat(rule.normalizedValue()).isEmpty();
            assertThat(rule.valueSet()).isEmpty();
            assertThat(rule.numericValue()).isNull();
        }
    }

    @Nested
    class NormalizedValue {

        static Stream<Arguments> normalized() {
            return Stream.of(
                    arguments("sco", "SCO"),
                    arguments("SCO", "SCO"),
                    arguments(" sco ", "SCO"),
                    arguments("Std Saver", "STD SAVER"),
                    arguments("nh_39_week", "NH_39_WEEK"),
                    arguments("10gt100000", "10GT100000"),
                    arguments("eg", "EG"));
        }

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @MethodSource("normalized")
        void ruleValueIsTrimmedAndUpperCasedOnce(String ruleValue, String expected) {
            assertThat(rule(EQUALS, ruleValue).normalizedValue()).isEqualTo(expected);
            assertThat(rule(NOT_EQUALS, ruleValue).normalizedValue()).isEqualTo(expected);
            assertThat(rule(CONTAINS, ruleValue).normalizedValue()).isEqualTo(expected);
            assertThat(rule(NOT_CONTAINS, ruleValue).normalizedValue()).isEqualTo(expected);
        }

        @Test
        void normalizationMatchesMemberFactsNormalization() {
            String value = "  Mixed Case  ";
            assertThat(rule(EQUALS, value).normalizedValue()).isEqualTo(MemberFacts.normalize(value));
        }
    }

    @Nested
    class ValueSet {

        static Stream<Arguments> parsedSets() {
            return Stream.of(
                    arguments("SCO,PDP", Set.of("SCO", "PDP")),
                    arguments("sco,pdp", Set.of("SCO", "PDP")),
                    arguments(" sco , pdp ", Set.of("SCO", "PDP")),
                    arguments("SCO ,PDP", Set.of("SCO", "PDP")),
                    arguments("SCO, PDP", Set.of("SCO", "PDP")),
                    arguments("a,,b", Set.of("A", "B")),                 // empty items are dropped
                    arguments("a, ,b", Set.of("A", "B")),
                    arguments(",a,b,", Set.of("A", "B")),
                    arguments("a,A, a ", Set.of("A")),                   // duplicates collapse
                    arguments("PDP", Set.of("PDP")),                     // single value list
                    arguments("MR,SCO,MAP", Set.of("MR", "SCO", "MAP")),
                    arguments("std saver,std plus", Set.of("STD SAVER", "STD PLUS")),   // inner spaces kept
                    arguments(", ,", Set.of()));
        }

        @ParameterizedTest(name = "IN \"{0}\" -> {1}")
        @MethodSource("parsedSets")
        void inParsesTheCommaListOnce(String ruleValue, Set<String> expected) {
            assertThat(rule(IN, ruleValue).valueSet()).containsExactlyInAnyOrderElementsOf(expected);
        }

        @ParameterizedTest(name = "NOT_IN \"{0}\" -> {1}")
        @MethodSource("parsedSets")
        void notInParsesTheCommaListOnce(String ruleValue, Set<String> expected) {
            assertThat(rule(NOT_IN, ruleValue).valueSet()).containsExactlyInAnyOrderElementsOf(expected);
        }

        @ParameterizedTest
        @EnumSource(value = ComparisonOperator.class, mode = EnumSource.Mode.EXCLUDE, names = {"IN", "NOT_IN"})
        void otherOperatorsDoNotSplitOnCommas(ComparisonOperator operator) {
            SegmentRule rule = rule(operator, "SCO,PDP");
            assertThat(rule.valueSet()).isEmpty();
            assertThat(rule.normalizedValue()).isEqualTo("SCO,PDP");
        }

        @Test
        void valueSetIsUnmodifiable() {
            Set<String> set = rule(IN, "SCO,PDP").valueSet();
            assertThatThrownBy(() -> set.add("MAP")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> set.remove("SCO")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void theTwentyPlanOfCareCodesOfSeedGroup3ParseToTwentyEntries() {
            String codes = "10GT100004,10GT100005,10GT100006,10GT100007,10GT100008,10GT100009,10GT100010,10GT100011,"
                    + "10GT100012,10GT100013,10GT100014,10GT100015,10PL100002,10PL100003,10PL100004,10PL100005,10PL100006,"
                    + "10GT100016,10GT100017,10GT100018";
            SegmentRule rule = rule(IN, codes);
            assertThat(rule.valueSet()).hasSize(20)
                    .contains("10GT100004", "10GT100018", "10PL100006")
                    .doesNotContain("10GT100003", "10GT100000", "10PL100001");
        }
    }

    @Nested
    class NumericValue {

        static Stream<Arguments> numeric() {
            return Stream.of(
                    arguments("18", "18"),
                    arguments(" 18.5 ", "18.5"),
                    arguments("-2", "-2"),
                    arguments("1e2", "100"),
                    arguments("0", "0"),
                    arguments("007", "7"));
        }

        @ParameterizedTest(name = "GREATER_THAN \"{0}\" -> {1}")
        @MethodSource("numeric")
        void greaterThanParsesTheNumberOnce(String ruleValue, String expected) {
            assertThat(rule(GREATER_THAN, ruleValue).numericValue()).isEqualByComparingTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "", " ", "1,000", "18abc", "eighteen"})
        void greaterThanWithNonNumericValueHasNoNumber(String ruleValue) {
            assertThat(rule(GREATER_THAN, ruleValue).numericValue()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = ComparisonOperator.class, mode = EnumSource.Mode.EXCLUDE, names = "GREATER_THAN")
        void otherOperatorsDoNotParseNumbers(ComparisonOperator operator) {
            assertThat(rule(operator, "18").numericValue()).isNull();
        }

        @Test
        void numericValueIsABigDecimalSoFractionsAndLargeNumbersCompareExactly() {
            assertThat(rule(GREATER_THAN, "9223372036854775808").numericValue())
                    .isEqualByComparingTo(new BigDecimal("9223372036854775808"));
            assertThat(rule(GREATER_THAN, "0.1").numericValue()).isEqualByComparingTo("0.1");
        }
    }

    @Nested
    class Matches {

        @Test
        void matchesDelegatesToTheOperator() {
            SegmentRule equals = rule(EQUALS, "std saver");
            assertThat(equals.matches(MemberFacts.of(Map.of("planCode", "STD SAVER")))).isTrue();
            assertThat(equals.matches(MemberFacts.of(Map.of("planCode", "STD PLUS")))).isFalse();
            assertThat(equals.matches(MemberFacts.of(Map.of()))).isFalse();
        }

        @Test
        void matchesUsesTheParsedSetForIn() {
            SegmentRule in = rule(IN, " sco , pdp ");
            assertThat(in.matches(MemberFacts.of(Map.of("planCode", "pdp")))).isTrue();
            assertThat(in.matches(MemberFacts.of(Map.of("planCode", "mr")))).isFalse();
        }

        @Test
        void matchesUsesTheParsedNumberForGreaterThan() {
            SegmentRule gt = rule(GREATER_THAN, "18");
            assertThat(gt.matches(MemberFacts.of(Map.of("planCode", 19)))).isTrue();
            assertThat(gt.matches(MemberFacts.of(Map.of("planCode", 18)))).isFalse();
        }

        @Test
        void matchesReadsOnlyItsOwnApiField() {
            SegmentRule rule = SegmentRule.of(1L, Segment.TMP_OTC_MA, "THP", 1, 1, "coverageActive", IS_TRUE, null);
            assertThat(rule.matches(MemberFacts.of(Map.of("coverageActive", true, "coverageStarted", false)))).isTrue();
            assertThat(rule.matches(MemberFacts.of(Map.of("coverageStarted", true)))).isFalse();
        }

        @Test
        void booleanOperatorsIgnoreAnyRuleValue() {
            MemberFacts facts = MemberFacts.of(Map.of("planCode", "Y"));
            assertThat(rule(IS_TRUE, null).matches(facts)).isTrue();
            assertThat(rule(IS_TRUE, "ignored").matches(facts)).isTrue();
            assertThat(rule(IS_FALSE, null).matches(facts)).isFalse();
            assertThat(rule(IS_FALSE, "ignored").matches(facts)).isFalse();
        }
    }
}
