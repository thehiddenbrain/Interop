package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.segmentation.SegmentationResult.ConditionTrace;
import org.point32health.memberprofile.segmentation.SegmentationResult.GroupTrace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.EQUALS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IS_TRUE;
import static org.point32health.memberprofile.segmentation.Segment.ALL_PUBLIC_PLANS_MA;
import static org.point32health.memberprofile.segmentation.Segment.ALL_TUFTS_MEDICARE_PREFERRED;
import static org.point32health.memberprofile.segmentation.Segment.INTEROPERABILITY;
import static org.point32health.memberprofile.segmentation.Segment.ONLINE_BILL_PAY;
import static org.point32health.memberprofile.segmentation.Segment.OPTUM_RX_COVERAGE;
import static org.point32health.memberprofile.segmentation.Segment.PLAN_OF_CARE;
import static org.point32health.memberprofile.segmentation.Segment.TMP_OTC_MA;

/**
 * {@link SegmentationEvaluator}: AND inside a rule group, OR across the groups of a segment, false for a
 * segment without rules, short-circuiting without explain, and a complete trace with explain.
 * The rules here are synthetic; the seeded rules are covered by {@link SeededSegmentationRulesTest}.
 */
class SegmentationEvaluatorTest {

    static final List<String> CONTRACT_KEYS = List.of("onlineBillPay", "optumRxCoverage", "allPublicPlansMa",
            "allTuftsMedicarePreferred", "tmpOtcMa", "planOfCare", "interoperability");

    private final SegmentationEvaluator evaluator = new SegmentationEvaluator();

    /** A rule "field EQUALS value" for the given segment and group; the id doubles as a readable marker. */
    private static SegmentRule eq(long id, Segment segment, int group, String field, String value) {
        return SegmentRule.of(id, segment, "THP", group, (int) id, field, EQUALS, value);
    }

    private static MemberFacts facts(Object... keysAndValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return MemberFacts.of(m);
    }

    private static GroupTrace group(SegmentationResult result, Segment segment, int ruleGroup) {
        return result.trace().stream()
                .filter(g -> g.segment().equals(segment.key()) && g.ruleGroup() == ruleGroup)
                .findFirst().orElseThrow(() -> new AssertionError("no trace for " + segment.key() + " group " + ruleGroup));
    }

    @Nested
    class Contract {

        @Test
        void noRulesGiveSevenFalseFlagsInContractOrderAndNoTrace() {
            SegmentationResult result = evaluator.evaluate(List.of(), facts("anything", "x"), false);
            assertThat(result.flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
            assertThat(result.flags().values()).containsOnly(Boolean.FALSE);
            assertThat(result.trace()).isNull();
        }

        @Test
        void noRulesWithExplainGiveAnEmptyTraceNotNull() {
            SegmentationResult result = evaluator.evaluate(List.of(), facts(), true);
            assertThat(result.trace()).isNotNull().isEmpty();
            assertThat(result.flags().values()).containsOnly(Boolean.FALSE);
        }

        @Test
        void flagsAlwaysHoldExactlyTheSevenKeysInContractOrderWhateverTheRowOrder() {
            List<SegmentRule> rules = List.of(
                    eq(1, INTEROPERABILITY, 1, "a", "1"),
                    eq(2, ONLINE_BILL_PAY, 1, "a", "1"),
                    eq(3, PLAN_OF_CARE, 1, "a", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "1"), false);
            assertThat(result.flags()).containsExactly(
                    entry("onlineBillPay", true), entry("optumRxCoverage", false), entry("allPublicPlansMa", false),
                    entry("allTuftsMedicarePreferred", false), entry("tmpOtcMa", false), entry("planOfCare", true),
                    entry("interoperability", true));
        }

        @Test
        void flagsMapIsUnmodifiable() {
            Map<String, Boolean> flags = evaluator.evaluate(List.of(), facts(), false).flags();
            assertThatThrownBy(() -> flags.put("onlineBillPay", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> flags.remove("onlineBillPay")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void traceListIsUnmodifiable() {
            List<GroupTrace> trace = evaluator.evaluate(List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1")), facts("a", "1"), true).trace();
            assertThatThrownBy(() -> trace.add(null)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void traceIsNullWithoutExplainEvenWhenRulesExist() {
            SegmentationResult result = evaluator.evaluate(List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1")), facts("a", "1"), false);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.trace()).isNull();
        }

        @Test
        void isTrueReflectsTheFlagOfEachSegment() {
            SegmentationResult result = evaluator.evaluate(List.of(eq(1, TMP_OTC_MA, 1, "a", "1")), facts("a", "1"), false);
            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            for (Segment other : Segment.values()) {
                if (other != TMP_OTC_MA) assertThat(result.isTrue(other)).as(other.key()).isFalse();
            }
        }

        @Test
        void nullAttributesGiveAllFalse() {
            SegmentationResult result = evaluator.evaluate(List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1")), MemberFacts.of(null), true);
            assertThat(result.flags().values()).containsOnly(Boolean.FALSE);
            assertThat(group(result, ONLINE_BILL_PAY, 1).matched()).isFalse();
            assertThat(group(result, ONLINE_BILL_PAY, 1).conditions()).singleElement()
                    .satisfies(c -> {
                        assertThat(c.passed()).isFalse();
                        assertThat(c.actualValue()).isNull();
                    });
        }

        @Test
        void evaluatorIsStatelessAcrossCalls() {
            List<SegmentRule> rules = List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1"));
            assertThat(evaluator.evaluate(rules, facts("a", "1"), false).isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(evaluator.evaluate(rules, facts("a", "0"), false).isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(evaluator.evaluate(rules, facts("a", "1"), true).isTrue(ONLINE_BILL_PAY)).isTrue();
        }
    }

    @Nested
    class AndWithinGroup {

        private final List<SegmentRule> threeConditions = List.of(
                eq(1, ONLINE_BILL_PAY, 1, "a", "1"),
                eq(2, ONLINE_BILL_PAY, 1, "b", "1"),
                eq(3, ONLINE_BILL_PAY, 1, "c", "1"));

        @Test
        void trueWhenEveryConditionPasses() {
            assertThat(evaluator.evaluate(threeConditions, facts("a", "1", "b", "1", "c", "1"), false).isTrue(ONLINE_BILL_PAY)).isTrue();
        }

        @ParameterizedTest(name = "a={0} b={1} c={2}")
        @CsvSource({"0,1,1", "1,0,1", "1,1,0", "0,0,1", "0,1,0", "1,0,0", "0,0,0"})
        void falseWhenAnyConditionFails(String a, String b, String c) {
            assertThat(evaluator.evaluate(threeConditions, facts("a", a, "b", b, "c", c), false).isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(evaluator.evaluate(threeConditions, facts("a", a, "b", b, "c", c), true).isTrue(ONLINE_BILL_PAY)).isFalse();
        }

        @Test
        void falseWhenAConditionFactIsMissing() {
            assertThat(evaluator.evaluate(threeConditions, facts("a", "1", "c", "1"), false).isTrue(ONLINE_BILL_PAY)).isFalse();
        }

        @Test
        void singleConditionGroupIsTrueWhenItPasses() {
            assertThat(evaluator.evaluate(List.of(eq(1, OPTUM_RX_COVERAGE, 1, "a", "1")), facts("a", "1"), false).isTrue(OPTUM_RX_COVERAGE)).isTrue();
            assertThat(evaluator.evaluate(List.of(eq(1, OPTUM_RX_COVERAGE, 1, "a", "1")), facts("a", "2"), false).isTrue(OPTUM_RX_COVERAGE)).isFalse();
        }
    }

    @Nested
    class OrAcrossGroups {

        private final List<SegmentRule> twoGroups = List.of(
                eq(1, ONLINE_BILL_PAY, 1, "g1", "1"),
                eq(2, ONLINE_BILL_PAY, 1, "shared", "1"),
                eq(3, ONLINE_BILL_PAY, 2, "g2", "1"),
                eq(4, ONLINE_BILL_PAY, 2, "shared", "1"));

        @ParameterizedTest(name = "group1={0} group2={1} -> {2}")
        @CsvSource({"1,1,true", "1,0,true", "0,1,true", "0,0,false"})
        void segmentIsTrueWhenAtLeastOneGroupMatches(String g1, String g2, boolean expected) {
            MemberFacts facts = facts("g1", g1, "g2", g2, "shared", "1");
            assertThat(evaluator.evaluate(twoGroups, facts, false).isTrue(ONLINE_BILL_PAY)).isEqualTo(expected);
            assertThat(evaluator.evaluate(twoGroups, facts, true).isTrue(ONLINE_BILL_PAY)).isEqualTo(expected);
        }

        @Test
        void aConditionSharedByBothGroupsFailsBoth() {
            assertThat(evaluator.evaluate(twoGroups, facts("g1", "1", "g2", "1", "shared", "0"), false).isTrue(ONLINE_BILL_PAY)).isFalse();
        }

        @Test
        void theLastOfManyGroupsCanCarryTheSegment() {
            List<SegmentRule> rules = new ArrayList<>();
            for (int g = 1; g <= 5; g++) rules.add(eq(g, PLAN_OF_CARE, g, "group" + g, "1"));
            assertThat(evaluator.evaluate(rules, facts("group5", "1"), false).isTrue(PLAN_OF_CARE)).isTrue();
            assertThat(evaluator.evaluate(rules, facts("group3", "1"), false).isTrue(PLAN_OF_CARE)).isTrue();
            assertThat(evaluator.evaluate(rules, facts("group6", "1"), false).isTrue(PLAN_OF_CARE)).isFalse();
        }

        @Test
        void ruleGroupNumbersNeedNotBeConsecutive() {
            List<SegmentRule> rules = List.of(eq(1, TMP_OTC_MA, 3, "a", "1"), eq(2, TMP_OTC_MA, 7, "b", "1"));
            assertThat(evaluator.evaluate(rules, facts("b", "1"), false).isTrue(TMP_OTC_MA)).isTrue();
            SegmentationResult explained = evaluator.evaluate(rules, facts("b", "1"), true);
            assertThat(explained.trace()).extracting(GroupTrace::ruleGroup).containsExactly(3, 7);
        }
    }

    @Nested
    class Grouping {

        @Test
        void segmentWithoutRulesStaysFalseWhenOthersMatch() {
            List<SegmentRule> rules = List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1"), eq(2, INTEROPERABILITY, 1, "a", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "1"), true);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            assertThat(result.isTrue(TMP_OTC_MA)).isFalse();
            assertThat(result.trace()).extracting(GroupTrace::segment).containsExactly("onlineBillPay", "interoperability");
        }

        @Test
        void sameGroupNumberUnderDifferentSegmentsAreSeparateGroups() {
            List<SegmentRule> rules = List.of(eq(1, ONLINE_BILL_PAY, 1, "a", "1"), eq(2, TMP_OTC_MA, 1, "b", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "1", "b", "0"), true);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(TMP_OTC_MA)).isFalse();
            assertThat(result.trace()).hasSize(2);
            assertThat(group(result, ONLINE_BILL_PAY, 1).matched()).isTrue();
            assertThat(group(result, TMP_OTC_MA, 1).matched()).isFalse();

            SegmentationResult reverse = evaluator.evaluate(rules, facts("a", "0", "b", "1"), false);
            assertThat(reverse.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(reverse.isTrue(TMP_OTC_MA)).isTrue();
        }

        @Test
        void segmentsMayArriveInAnyOrderAsLongAsTheirRowsAreContiguous() {
            // the repository orders by segment_name (alphabetical), not by enum order
            List<SegmentRule> rules = List.of(
                    eq(1, INTEROPERABILITY, 1, "i", "1"),
                    eq(2, INTEROPERABILITY, 2, "i2", "1"),
                    eq(3, ONLINE_BILL_PAY, 1, "o", "1"),
                    eq(4, ALL_PUBLIC_PLANS_MA, 1, "p", "1"),
                    eq(5, ALL_PUBLIC_PLANS_MA, 1, "p2", "1"),
                    eq(6, ALL_TUFTS_MEDICARE_PREFERRED, 1, "t", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("i2", "1", "o", "1", "p", "1", "p2", "1", "t", "0"), true);
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(ALL_PUBLIC_PLANS_MA)).isTrue();
            assertThat(result.isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
            assertThat(result.flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
            assertThat(result.trace()).extracting(GroupTrace::segment, GroupTrace::ruleGroup).containsExactly(
                    tuple("interoperability", 1),
                    tuple("interoperability", 2),
                    tuple("onlineBillPay", 1),
                    tuple("allPublicPlansMa", 1),
                    tuple("allTuftsMedicarePreferred", 1));
        }

        @Test
        void allSevenSegmentsCanBeTrueAtOnce() {
            List<SegmentRule> rules = new ArrayList<>();
            long id = 1;
            for (Segment s : Segment.values()) rules.add(eq(id++, s, 1, "a", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "1"), false);
            assertThat(result.flags().values()).containsOnly(Boolean.TRUE);
        }

        @ParameterizedTest
        @EnumSource(Segment.class)
        void eachSegmentIsDrivenOnlyByItsOwnRows(Segment segment) {
            List<SegmentRule> rules = new ArrayList<>();
            long id = 1;
            for (Segment s : Segment.values()) rules.add(eq(id++, s, 1, s.key(), "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts(segment.key(), "1"), false);
            for (Segment s : Segment.values()) {
                assertThat(result.isTrue(s)).as(s.key()).isEqualTo(s == segment);
            }
        }

        @Test
        void aLargeGroupEvaluatesCorrectly() {
            List<SegmentRule> rules = new ArrayList<>();
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (int i = 1; i <= 500; i++) {
                rules.add(eq(i, PLAN_OF_CARE, 1, "f" + i, "v" + i));
                attributes.put("f" + i, "v" + i);
            }
            assertThat(evaluator.evaluate(rules, MemberFacts.of(attributes), false).isTrue(PLAN_OF_CARE)).isTrue();
            attributes.put("f500", "other");
            assertThat(evaluator.evaluate(rules, MemberFacts.of(attributes), false).isTrue(PLAN_OF_CARE)).isFalse();
        }
    }

    @Nested
    class MissingFacts {

        @ParameterizedTest(name = "{0} on a missing fact")
        @EnumSource(ComparisonOperator.class)
        void aMissingFactNeverSatisfiesAConditionWhateverTheOperator(ComparisonOperator operator) {
            String value = operator.needsRuleValue() ? (operator == ComparisonOperator.GREATER_THAN ? "1" : "X") : null;
            SegmentRule rule = SegmentRule.of(1L, ONLINE_BILL_PAY, "THP", 1, 1, "missing", operator, value);
            assertThat(evaluator.evaluate(List.of(rule), facts("other", "value"), false).isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(evaluator.evaluate(List.of(rule), facts("missing", null), false).isTrue(ONLINE_BILL_PAY)).isFalse();
        }
    }

    @Nested
    class Explain {

        @Test
        void everyConditionIsRecordedEvenAfterOneFails() {
            List<SegmentRule> rules = List.of(
                    eq(1, ONLINE_BILL_PAY, 1, "a", "1"),
                    eq(2, ONLINE_BILL_PAY, 1, "b", "1"),
                    eq(3, ONLINE_BILL_PAY, 1, "c", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "0", "b", "1", "c", "0"), true);
            GroupTrace group = group(result, ONLINE_BILL_PAY, 1);
            assertThat(group.matched()).isFalse();
            assertThat(group.conditions()).extracting(ConditionTrace::ruleId).containsExactly(1L, 2L, 3L);
            assertThat(group.conditions()).extracting(ConditionTrace::passed).containsExactly(false, true, false);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
        }

        @Test
        void everyGroupIsRecordedEvenAfterOneMatches() {
            List<SegmentRule> rules = List.of(
                    eq(1, TMP_OTC_MA, 1, "a", "1"),
                    eq(2, TMP_OTC_MA, 2, "b", "1"),
                    eq(3, TMP_OTC_MA, 3, "c", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts("a", "1", "b", "0", "c", "1"), true);
            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            assertThat(result.trace()).extracting(GroupTrace::ruleGroup).containsExactly(1, 2, 3);
            assertThat(result.trace()).extracting(GroupTrace::matched).containsExactly(true, false, true);
        }

        @Test
        void groupTraceCarriesSegmentKeyAndRuleGroupNumber() {
            SegmentationResult result = evaluator.evaluate(List.of(eq(9, PLAN_OF_CARE, 4, "a", "1")), facts("a", "1"), true);
            assertThat(result.trace()).singleElement().satisfies(g -> {
                assertThat(g.segment()).isEqualTo("planOfCare");
                assertThat(g.ruleGroup()).isEqualTo(4);
                assertThat(g.matched()).isTrue();
                assertThat(g.conditions()).hasSize(1);
            });
        }

        @Test
        void conditionTraceCarriesTheRuleTheRawActualValueAndTheOutcome() {
            SegmentRule rule = SegmentRule.of(17L, INTEROPERABILITY, "THP", 4, 14, "planTypeCode", ComparisonOperator.IN, " mr , sco ");
            SegmentationResult result = evaluator.evaluate(List.of(rule), facts("planTypeCode", " Sco "), true);
            ConditionTrace c = group(result, INTEROPERABILITY, 4).conditions().get(0);
            assertThat(c.ruleId()).isEqualTo(17L);
            assertThat(c.apiField()).isEqualTo("planTypeCode");
            assertThat(c.operator()).isEqualTo(ComparisonOperator.IN);
            assertThat(c.ruleValue()).isEqualTo(" mr , sco ");      // verbatim, as stored in the table
            assertThat(c.actualValue()).isEqualTo(" Sco ");          // verbatim, as MemberDomain sent it
            assertThat(c.passed()).isTrue();
        }

        @Test
        void conditionTraceKeepsTheJavaTypeOfTheActualValue() {
            List<SegmentRule> rules = List.of(
                    eq(1, ONLINE_BILL_PAY, 1, "sourceSystemId", "2001"),
                    SegmentRule.of(2L, ONLINE_BILL_PAY, "THP", 1, 2, "hasActivePdp", ComparisonOperator.IS_FALSE, null));
            SegmentationResult result = evaluator.evaluate(rules, facts("sourceSystemId", 2001, "hasActivePdp", Boolean.FALSE), true);
            List<ConditionTrace> conditions = group(result, ONLINE_BILL_PAY, 1).conditions();
            assertThat(conditions.get(0).actualValue()).isEqualTo(2001);
            assertThat(conditions.get(1).actualValue()).isEqualTo(Boolean.FALSE);
            assertThat(conditions.get(1).ruleValue()).isNull();
            assertThat(conditions).extracting(ConditionTrace::passed).containsOnly(true);
        }

        @Test
        void conditionTraceShowsNullForAnAbsentFact() {
            SegmentationResult result = evaluator.evaluate(List.of(eq(1, ONLINE_BILL_PAY, 1, "absent", "1")), facts("other", "1"), true);
            ConditionTrace c = group(result, ONLINE_BILL_PAY, 1).conditions().get(0);
            assertThat(c.actualValue()).isNull();
            assertThat(c.passed()).isFalse();
        }

        @Test
        void traceFollowsRowOrderAndHasOneEntryPerGroup() {
            List<SegmentRule> rules = List.of(
                    eq(1, ONLINE_BILL_PAY, 1, "a", "1"), eq(2, ONLINE_BILL_PAY, 1, "b", "1"),
                    eq(3, ONLINE_BILL_PAY, 2, "c", "1"),
                    eq(4, OPTUM_RX_COVERAGE, 1, "d", "1"));
            SegmentationResult result = evaluator.evaluate(rules, facts(), true);
            assertThat(result.trace()).hasSize(3);
            assertThat(result.trace()).extracting(GroupTrace::segment).containsExactly("onlineBillPay", "onlineBillPay", "optumRxCoverage");
            assertThat(result.trace().get(0).conditions()).hasSize(2);
            assertThat(result.trace().get(1).conditions()).hasSize(1);
            assertThat(result.trace().get(2).conditions()).hasSize(1);
        }

        @ParameterizedTest(name = "facts a={0} b={1} c={2}")
        @CsvSource({"1,1,1", "0,1,1", "1,0,1", "1,1,0", "0,0,0"})
        void explainAndPlainEvaluationAgreeOnTheFlags(String a, String b, String c) {
            List<SegmentRule> rules = List.of(
                    eq(1, ONLINE_BILL_PAY, 1, "a", "1"), eq(2, ONLINE_BILL_PAY, 1, "b", "1"),
                    eq(3, ONLINE_BILL_PAY, 2, "c", "1"),
                    eq(4, TMP_OTC_MA, 1, "b", "1"), eq(5, TMP_OTC_MA, 2, "c", "1"),
                    eq(6, INTEROPERABILITY, 1, "a", "1"));
            MemberFacts facts = facts("a", a, "b", b, "c", c);
            assertThat(evaluator.evaluate(rules, facts, false).flags()).isEqualTo(evaluator.evaluate(rules, facts, true).flags());
        }
    }

    /**
     * Without explain the evaluator must stop reading facts as soon as a group has failed or a segment has
     * been decided (catalog section 6). The facts object is spied to count which fields were actually read.
     */
    @Nested
    class ShortCircuit {

        private final List<SegmentRule> failingGroup = List.of(
                eq(1, ONLINE_BILL_PAY, 1, "a", "1"),
                eq(2, ONLINE_BILL_PAY, 1, "b", "1"),
                eq(3, ONLINE_BILL_PAY, 1, "c", "1"));

        private final List<SegmentRule> twoGroupsThenAnotherSegment = List.of(
                eq(1, ONLINE_BILL_PAY, 1, "a", "1"),
                eq(2, ONLINE_BILL_PAY, 2, "b", "1"),
                eq(3, ONLINE_BILL_PAY, 3, "c", "1"),
                eq(4, OPTUM_RX_COVERAGE, 1, "d", "1"));

        @Test
        void withoutExplainAFailedConditionSkipsTheRestOfItsGroup() {
            MemberFacts facts = spy(facts("a", "0", "b", "1", "c", "1"));
            SegmentationResult result = evaluator.evaluate(failingGroup, facts, false);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            verify(facts, times(1)).text("a");
            verify(facts, never()).text("b");
            verify(facts, never()).text("c");
        }

        @Test
        void withExplainEveryConditionOfAFailedGroupIsStillEvaluated() {
            MemberFacts facts = spy(facts("a", "0", "b", "1", "c", "1"));
            SegmentationResult result = evaluator.evaluate(failingGroup, facts, true);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            verify(facts, times(1)).text("a");
            verify(facts, times(1)).text("b");
            verify(facts, times(1)).text("c");
            assertThat(group(result, ONLINE_BILL_PAY, 1).conditions()).extracting(ConditionTrace::passed).containsExactly(false, true, true);
        }

        @Test
        void withoutExplainAMatchedGroupSkipsTheRemainingGroupsOfTheSegmentButNotTheNextSegment() {
            MemberFacts facts = spy(facts("a", "1", "b", "1", "c", "1", "d", "1"));
            SegmentationResult result = evaluator.evaluate(twoGroupsThenAnotherSegment, facts, false);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
            verify(facts, times(1)).text("a");
            verify(facts, never()).text("b");
            verify(facts, never()).text("c");
            verify(facts, times(1)).text("d");
        }

        @Test
        void withExplainEveryGroupOfAMatchedSegmentIsStillEvaluated() {
            MemberFacts facts = spy(facts("a", "1", "b", "0", "c", "1", "d", "1"));
            SegmentationResult result = evaluator.evaluate(twoGroupsThenAnotherSegment, facts, true);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            verify(facts, times(1)).text("a");
            verify(facts, times(1)).text("b");
            verify(facts, times(1)).text("c");
            verify(facts, times(1)).text("d");
            assertThat(result.trace()).extracting(GroupTrace::matched).containsExactly(true, false, true, true);
        }

        @Test
        void withoutExplainAFailedGroupStillTriesTheNextGroup() {
            MemberFacts facts = spy(facts("a", "0", "b", "1", "c", "1", "d", "0"));
            SegmentationResult result = evaluator.evaluate(twoGroupsThenAnotherSegment, facts, false);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            verify(facts, times(1)).text("a");
            verify(facts, times(1)).text("b");
            verify(facts, never()).text("c");     // group 2 decided the segment
            verify(facts, times(1)).text("d");
        }

        @Test
        void booleanConditionsShortCircuitTheSameWay() {
            List<SegmentRule> rules = List.of(
                    SegmentRule.of(1L, TMP_OTC_MA, "THP", 1, 1, "coverageActive", IS_TRUE, null),
                    SegmentRule.of(2L, TMP_OTC_MA, "THP", 1, 2, "coverageStarted", IS_TRUE, null));
            MemberFacts facts = spy(facts("coverageActive", "N", "coverageStarted", "Y"));
            evaluator.evaluate(rules, facts, false);
            verify(facts, times(1)).bool("coverageActive");
            verify(facts, never()).bool("coverageStarted");
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void shortCircuitNeverChangesTheOutcome(boolean explain) {
            MemberFacts facts = facts("a", "0", "b", "1", "c", "1", "d", "1");
            SegmentationResult result = evaluator.evaluate(twoGroupsThenAnotherSegment, facts, explain);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isTrue();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
        }
    }
}
