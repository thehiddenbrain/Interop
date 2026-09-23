package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@link ComparisonOperator}: every operator of the Operators tab against one fact. Text compares are
 * case-insensitive and trimmed on both sides, booleans accept the source-system spellings, GREATER_THAN is
 * numeric, and a missing or null fact never satisfies any operator, not even the negated ones.
 */
class ComparisonOperatorTest {

    private static final String FIELD = "f";
    private static final MemberFacts ABSENT = MemberFacts.of(Map.of());
    private static final MemberFacts NULL_VALUE = facts(null);

    private static SegmentRule rule(ComparisonOperator operator, String ruleValue) {
        return SegmentRule.of(1L, Segment.ONLINE_BILL_PAY, "THP", 1, 1, FIELD, operator, ruleValue);
    }

    private static MemberFacts facts(Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(FIELD, value);
        return MemberFacts.of(m);
    }

    private static boolean test(ComparisonOperator operator, String ruleValue, Object factValue) {
        return operator.test(facts(factValue), rule(operator, ruleValue));
    }

    private static boolean test(ComparisonOperator operator, String ruleValue, MemberFacts facts) {
        return operator.test(facts, rule(operator, ruleValue));
    }

    /** A rule value that is valid for the operator (IS_TRUE / IS_FALSE take none). */
    private static String anyValueFor(ComparisonOperator operator) {
        if (!operator.needsRuleValue()) return null;
        return operator == GREATER_THAN ? "1" : "X";
    }

    @Nested
    class Catalog {

        @Test
        void theNineOperatorsMatchTheDatabaseCheckConstraint() {
            // ck_comparison_operator in V1__schema.sql
            assertThat(Arrays.stream(ComparisonOperator.values()).map(Enum::name)).containsExactly(
                    "EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "CONTAINS", "NOT_CONTAINS", "IS_TRUE", "IS_FALSE", "GREATER_THAN");
        }

        @ParameterizedTest
        @EnumSource(value = ComparisonOperator.class, names = {"IS_TRUE", "IS_FALSE"})
        void booleanOperatorsNeedNoRuleValue(ComparisonOperator operator) {
            assertThat(operator.needsRuleValue()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = ComparisonOperator.class, mode = EnumSource.Mode.EXCLUDE, names = {"IS_TRUE", "IS_FALSE"})
        void everyOtherOperatorNeedsARuleValue(ComparisonOperator operator) {
            assertThat(operator.needsRuleValue()).isTrue();
        }

        @ParameterizedTest(name = "{0}: absent fact never matches")
        @EnumSource(ComparisonOperator.class)
        void absentFactNeverMatches(ComparisonOperator operator) {
            assertThat(test(operator, anyValueFor(operator), ABSENT)).isFalse();
        }

        @ParameterizedTest(name = "{0}: null fact never matches")
        @EnumSource(ComparisonOperator.class)
        void nullFactNeverMatches(ComparisonOperator operator) {
            assertThat(test(operator, anyValueFor(operator), NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class Equals {

        @ParameterizedTest
        @ValueSource(strings = {"THPPMA", "thppma", "ThPpMa", " THPPMA ", "\tthppma\n"})
        void matchesIgnoringCaseAndSurroundingWhitespace(String actual) {
            assertThat(test(EQUALS, "THPPMA", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"thppma", " Thppma ", "THPPMA"})
        void ruleValueIsNormalizedToo(String ruleValue) {
            assertThat(test(EQUALS, ruleValue, "THPPMA")).isTrue();
        }

        @Test
        void numericFactsCompareAsText() {
            assertThat(test(EQUALS, "2001", 2001)).isTrue();
            assertThat(test(EQUALS, "2001", 2001L)).isTrue();
            assertThat(test(EQUALS, "2001", "2001")).isTrue();
            assertThat(test(EQUALS, "2001", 2002)).isFalse();
        }

        @Test
        void booleanFactsCompareAsText() {
            assertThat(test(EQUALS, "true", Boolean.TRUE)).isTrue();
            assertThat(test(EQUALS, "false", Boolean.TRUE)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"THPPRI", "THPPMA1", "THPPM", "", " ", "THPP MA"})
        void doesNotMatchDifferentOrPartialValues(String actual) {
            assertThat(test(EQUALS, "THPPMA", actual)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatch() {
            assertThat(test(EQUALS, "THPPMA", ABSENT)).isFalse();
            assertThat(test(EQUALS, "THPPMA", NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class NotEquals {

        @ParameterizedTest
        @ValueSource(strings = {"GROUP", "nh_39_weeks", "NH_39", " OTHER "})
        void matchesWhenValuesDiffer(String actual) {
            assertThat(test(NOT_EQUALS, "NH_39_WEEK", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"NH_39_WEEK", "nh_39_week", " NH_39_WEEK ", "Nh_39_Week"})
        void doesNotMatchTheSameValueInAnyCaseOrSpacing(String actual) {
            assertThat(test(NOT_EQUALS, "NH_39_WEEK", actual)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatchEvenThoughItIsNotEqual() {
            assertThat(test(NOT_EQUALS, "NH_39_WEEK", ABSENT)).isFalse();
            assertThat(test(NOT_EQUALS, "NH_39_WEEK", NULL_VALUE)).isFalse();
        }

        @Test
        void numericFactsCompareAsText() {
            assertThat(test(NOT_EQUALS, "2001", 2026)).isTrue();
            assertThat(test(NOT_EQUALS, "2001", 2001)).isFalse();
        }
    }

    @Nested
    class In {

        @ParameterizedTest
        @ValueSource(strings = {"SCO", "PDP", "sco", "pdp", " SCO ", "Pdp"})
        void matchesAMemberOfTheListIgnoringCaseAndWhitespace(String actual) {
            assertThat(test(IN, "SCO,PDP", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"SCO,PDP", " SCO , PDP ", "sco,pdp", "SCO, PDP", "SCO ,PDP", ",SCO,,PDP,"})
        void listSpacingAndCaseDoNotMatter(String ruleValue) {
            assertThat(test(IN, ruleValue, "pdp")).isTrue();
            assertThat(test(IN, ruleValue, "sco")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"MR", "SC", "SCO1", "SCO,PDP", "SCOPDP", "", "MAP"})
        void doesNotMatchValuesOutsideTheListNorPartialMembers(String actual) {
            assertThat(test(IN, "SCO,PDP", actual)).isFalse();
        }

        @Test
        void singleElementListWorks() {
            assertThat(test(IN, "PDP", "pdp")).isTrue();
            assertThat(test(IN, "PDP", "npdp")).isFalse();
        }

        @Test
        void numericFactsCompareAsText() {
            assertThat(test(IN, "2001,2026", 2026)).isTrue();
            assertThat(test(IN, "2001,2026", 2064)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatch() {
            assertThat(test(IN, "SCO,PDP", ABSENT)).isFalse();
            assertThat(test(IN, "SCO,PDP", NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class NotIn {

        @ParameterizedTest
        @ValueSource(strings = {"MR", "CTH", "MAP", "SC", "SCO1", "", "SCO,PDP"})
        void matchesValuesOutsideTheList(String actual) {
            assertThat(test(NOT_IN, "SCO,PDP", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"SCO", "PDP", "sco", " pdp ", "Sco"})
        void doesNotMatchAMemberOfTheListInAnyCase(String actual) {
            assertThat(test(NOT_IN, "SCO,PDP", actual)).isFalse();
        }

        @Test
        void exactMembershipMeansPdpIsNotInNpdpRpdp() {
            // Seed rows 13 and 16: product NOT_IN 'NPDP,RPDP'. IN is list membership, not a substring test.
            assertThat(test(NOT_IN, "NPDP,RPDP", "PDP")).isTrue();
            assertThat(test(NOT_IN, "NPDP,RPDP", "NPDP")).isFalse();
            assertThat(test(NOT_IN, "NPDP,RPDP", "rpdp")).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatchEvenThoughItIsNotInTheList() {
            assertThat(test(NOT_IN, "SCO,PDP", ABSENT)).isFalse();
            assertThat(test(NOT_IN, "SCO,PDP", NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class Contains {

        @ParameterizedTest
        @ValueSource(strings = {"10EG1234", "EG1234", "1234EG", "EG", "eg", "10eg1234", " 10EG1234 ", "EGEG"})
        void matchesWhenTheFactContainsTheRuleTextAnywhere(String actual) {
            assertThat(test(CONTAINS, "EG", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"eg", " EG ", "Eg"})
        void ruleValueIsNormalizedToo(String ruleValue) {
            assertThat(test(CONTAINS, ruleValue, "10EG1234")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"10AB1234", "E G", "GE", "", "1234"})
        void doesNotMatchWhenTheTextIsAbsent(String actual) {
            assertThat(test(CONTAINS, "EG", actual)).isFalse();
        }

        @Test
        void multiCharacterRuleTextMustAppearContiguously() {
            assertThat(test(CONTAINS, "SMV", "10SMV001")).isTrue();
            assertThat(test(CONTAINS, "SMV", "10S-M-V001")).isFalse();
            assertThat(test(CONTAINS, "STD SAVER", "MY STD SAVER PLAN")).isTrue();
        }

        @Test
        void numericFactsCompareAsText() {
            assertThat(test(CONTAINS, "00", 2001)).isTrue();
            assertThat(test(CONTAINS, "9", 2001)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatch() {
            assertThat(test(CONTAINS, "EG", ABSENT)).isFalse();
            assertThat(test(CONTAINS, "EG", NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class NotContains {

        @ParameterizedTest
        @ValueSource(strings = {"10EG1234", "PD P", "DPP", "", "MR"})
        void matchesWhenTheTextIsAbsent(String actual) {
            assertThat(test(NOT_CONTAINS, "PDP", actual)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"PDP", "pdp", "10PDP1", "NPDP", "RPDP", " pdp "})
        void doesNotMatchWhenTheTextIsPresentInAnyCase(String actual) {
            assertThat(test(NOT_CONTAINS, "PDP", actual)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatchEvenThoughItContainsNothing() {
            assertThat(test(NOT_CONTAINS, "PDP", ABSENT)).isFalse();
            assertThat(test(NOT_CONTAINS, "PDP", NULL_VALUE)).isFalse();
        }
    }

    @Nested
    class IsTrue {

        static Stream<Arguments> truthy() {
            return Stream.of(arguments(Boolean.TRUE), arguments("true"), arguments("TRUE"), arguments("True"),
                    arguments("Y"), arguments("y"), arguments("YES"), arguments("yes"), arguments("Yes"),
                    arguments("1"), arguments(1), arguments(1L), arguments(" true "), arguments(" Y "));
        }

        static Stream<Arguments> falsy() {
            return Stream.of(arguments(Boolean.FALSE), arguments("false"), arguments("FALSE"), arguments("False"),
                    arguments("N"), arguments("n"), arguments("NO"), arguments("no"), arguments("No"),
                    arguments("0"), arguments(0), arguments(0L), arguments(" false "), arguments(" N "));
        }

        static Stream<Arguments> rejected() {
            return Stream.of(arguments("maybe"), arguments(""), arguments("  "), arguments(2), arguments("2"),
                    arguments("T"), arguments("F"), arguments("on"), arguments("off"), arguments(-1), arguments("yes!"));
        }

        @ParameterizedTest(name = "IS_TRUE {0}")
        @MethodSource("truthy")
        void matchesEveryAcceptedTrueSpelling(Object actual) {
            assertThat(test(IS_TRUE, null, actual)).isTrue();
        }

        @ParameterizedTest(name = "IS_TRUE {0} is false")
        @MethodSource("falsy")
        void doesNotMatchFalseSpellings(Object actual) {
            assertThat(test(IS_TRUE, null, actual)).isFalse();
        }

        @ParameterizedTest(name = "IS_TRUE {0} is not a boolean")
        @MethodSource("rejected")
        void doesNotMatchValuesThatAreNotBooleans(Object actual) {
            assertThat(test(IS_TRUE, null, actual)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatch() {
            assertThat(test(IS_TRUE, null, ABSENT)).isFalse();
            assertThat(test(IS_TRUE, null, NULL_VALUE)).isFalse();
        }

        @Test
        void ignoresAnyRuleValue() {
            assertThat(test(IS_TRUE, "false", Boolean.TRUE)).isTrue();
            assertThat(test(IS_TRUE, "true", Boolean.FALSE)).isFalse();
        }
    }

    @Nested
    class IsFalse {

        @ParameterizedTest(name = "IS_FALSE {0}")
        @MethodSource("org.point32health.memberprofile.segmentation.ComparisonOperatorTest$IsTrue#falsy")
        void matchesEveryAcceptedFalseSpelling(Object actual) {
            assertThat(test(IS_FALSE, null, actual)).isTrue();
        }

        @ParameterizedTest(name = "IS_FALSE {0} is true")
        @MethodSource("org.point32health.memberprofile.segmentation.ComparisonOperatorTest$IsTrue#truthy")
        void doesNotMatchTrueSpellings(Object actual) {
            assertThat(test(IS_FALSE, null, actual)).isFalse();
        }

        @ParameterizedTest(name = "IS_FALSE {0} is not a boolean")
        @MethodSource("org.point32health.memberprofile.segmentation.ComparisonOperatorTest$IsTrue#rejected")
        void doesNotMatchValuesThatAreNotBooleans(Object actual) {
            assertThat(test(IS_FALSE, null, actual)).isFalse();
        }

        @Test
        void absentOrNullFactDoesNotMatchEvenThoughItIsNotTrue() {
            assertThat(test(IS_FALSE, null, ABSENT)).isFalse();
            assertThat(test(IS_FALSE, null, NULL_VALUE)).isFalse();
        }

        @Test
        void ignoresAnyRuleValue() {
            assertThat(test(IS_FALSE, "true", Boolean.FALSE)).isTrue();
            assertThat(test(IS_FALSE, "false", Boolean.TRUE)).isFalse();
        }
    }

    @Nested
    class GreaterThan {

        static Stream<Arguments> greater() {
            return Stream.of(arguments(19), arguments(19L), arguments((short) 19), arguments(18.5), arguments(18.001f),
                    arguments(new BigDecimal("18.01")), arguments("19"), arguments(" 19 "), arguments("18.5"),
                    arguments("1e2"), arguments("100"), arguments(Long.MAX_VALUE));
        }

        static Stream<Arguments> equalOrSmaller() {
            return Stream.of(arguments(18), arguments(18L), arguments(18.0), arguments(new BigDecimal("18.00")),
                    arguments("18"), arguments(" 18 "), arguments("18.000"), arguments(17), arguments(0), arguments(-1),
                    arguments("17.99"), arguments(-100.5), arguments(Long.MIN_VALUE));
        }

        static Stream<Arguments> nonNumeric() {
            return Stream.of(arguments("abc"), arguments(""), arguments("  "), arguments("18abc"), arguments("1,000"),
                    arguments(Boolean.TRUE), arguments("nineteen"), arguments("19 years"));
        }

        @ParameterizedTest(name = "{0} > 18")
        @MethodSource("greater")
        void matchesNumbersAboveTheRuleValueWhateverTheirJavaType(Object actual) {
            assertThat(test(GREATER_THAN, "18", actual)).isTrue();
        }

        @ParameterizedTest(name = "{0} > 18 is false")
        @MethodSource("equalOrSmaller")
        void doesNotMatchEqualOrSmallerNumbers(Object actual) {
            assertThat(test(GREATER_THAN, "18", actual)).isFalse();
        }

        @ParameterizedTest(name = "{0} is not numeric")
        @MethodSource("nonNumeric")
        void nonNumericFactIsFalse(Object actual) {
            assertThat(test(GREATER_THAN, "18", actual)).isFalse();
        }

        @Test
        void nonNumericRuleValueNeverMatches() {
            assertThat(test(GREATER_THAN, "abc", 19)).isFalse();
            assertThat(test(GREATER_THAN, "", 19)).isFalse();
        }

        @Test
        void comparesNumericallyNotLexically() {
            assertThat(test(GREATER_THAN, "9", "10")).isTrue();      // "10" < "9" as text, 10 > 9 as a number
            assertThat(test(GREATER_THAN, "10", "9")).isFalse();
            assertThat(test(GREATER_THAN, "-2", -1)).isTrue();
            assertThat(test(GREATER_THAN, "0.1", 0.2)).isTrue();
            assertThat(test(GREATER_THAN, "1e3", 1001)).isTrue();
            assertThat(test(GREATER_THAN, "1e3", 1000)).isFalse();
        }

        @Test
        void ruleValueWhitespaceIsIgnored() {
            assertThat(test(GREATER_THAN, " 18 ", 19)).isTrue();
        }

        @Test
        void absentOrNullFactDoesNotMatch() {
            assertThat(test(GREATER_THAN, "18", ABSENT)).isFalse();
            assertThat(test(GREATER_THAN, "18", NULL_VALUE)).isFalse();
        }

        /**
         * Catalog section 3: "GREATER_THAN (numeric; non-numeric fact is false)". A NaN or infinite double is
         * not a number that can be compared, so the condition must be false rather than blow up the request.
         */
        @ParameterizedTest(name = "{0} is not numeric")
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void nanAndInfiniteDoublesAreNonNumericFacts(double actual) {
            assertThat(test(GREATER_THAN, "18", actual)).isFalse();
        }
    }
}
