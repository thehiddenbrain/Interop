package com.thehiddenbrain.interop.memberprofile.segmentation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.thehiddenbrain.interop.memberprofile.segmentation.ComparisonOperator.*;
import static org.assertj.core.api.Assertions.assertThat;

class SegmentationEvaluatorTest {

    private final SegmentationEvaluator evaluator = new SegmentationEvaluator();
    private final List<String> segments = List.of("onlineBillPay", "optumRxCoverage", "tmpOtcMa");

    /** THP onlineBillPay as loaded: group 1 (4 ANDed conditions) OR group 2 (CTH). */
    private final List<SegmentRule> thpOnlineBillPay = List.of(
            rule("onlineBillPay", 1, 1, "sourceSystemId", EQUALS, "2001"),
            rule("onlineBillPay", 1, 2, "planTypeCode", NOT_IN, "SCO,PDP"),
            rule("onlineBillPay", 1, 3, "coverageGroupTypeCode", EQUALS, "2"),
            rule("onlineBillPay", 1, 4, "hasActivePdp", IS_FALSE, null),
            rule("onlineBillPay", 2, 5, "sourceSystemId", EQUALS, "2001"),
            rule("onlineBillPay", 2, 6, "planTypeCode", EQUALS, "CTH"));

    @Test
    void allConditionsOfOneGroupMustPass() {
        Map<String, Object> facts = facts("sourceSystemId", 2001, "planTypeCode", "HMO",
                "coverageGroupTypeCode", "2", "hasActivePdp", false);

        SegmentationResult result = evaluator.evaluate(segments, thpOnlineBillPay, facts);

        assertThat(result.flags()).containsEntry("onlineBillPay", true);
        assertThat(result.trace()).anySatisfy(g -> {
            assertThat(g.ruleGroup()).isEqualTo(1);
            assertThat(g.matched()).isTrue();
        });
    }

    @Test
    void oneFailingConditionFailsTheGroupButAnotherGroupCanStillMatch() {
        Map<String, Object> facts = facts("sourceSystemId", "2001", "planTypeCode", "CTH",
                "coverageGroupTypeCode", "9", "hasActivePdp", false);

        assertThat(evaluator.evaluate(segments, thpOnlineBillPay, facts).flags())
                .containsEntry("onlineBillPay", true);   // group 2 matched
    }

    @Test
    void noGroupMatchingGivesFalse() {
        Map<String, Object> facts = facts("sourceSystemId", "2001", "planTypeCode", "SCO",
                "coverageGroupTypeCode", "2", "hasActivePdp", false);

        assertThat(evaluator.evaluate(segments, thpOnlineBillPay, facts).flags())
                .containsEntry("onlineBillPay", false);
    }

    @Test
    void everyActiveSegmentIsReturnedEvenWithoutRules() {
        Map<String, Boolean> flags = evaluator.evaluate(segments, thpOnlineBillPay, Map.of()).flags();

        assertThat(flags).containsKeys("onlineBillPay", "optumRxCoverage", "tmpOtcMa");
        assertThat(flags.values()).containsOnly(false);
    }

    @Test
    void missingFactNeverMatchesEvenForNegativeOperators() {
        List<SegmentRule> rules = List.of(
                rule("optumRxCoverage", 1, 1, "product", NOT_IN, "NPDP,RPDP"),
                rule("tmpOtcMa", 1, 1, "customerCategory", NOT_EQUALS, "NH_39_WEEK"));

        Map<String, Boolean> flags = evaluator.evaluate(segments, rules, Map.of()).flags();

        assertThat(flags).containsEntry("optumRxCoverage", false).containsEntry("tmpOtcMa", false);
    }

    @Test
    void comparisonsAreCaseInsensitiveAndTrimmed() {
        List<SegmentRule> rules = List.of(
                rule("optumRxCoverage", 1, 1, "planCode", CONTAINS, "eg"),
                rule("optumRxCoverage", 1, 2, "planCode", NOT_CONTAINS, "PDP"),
                rule("optumRxCoverage", 1, 3, "product", IN, " pl , gt "));

        assertThat(evaluator.evaluate(segments, rules, facts("planCode", " 10EG44 ", "product", "GT")).flags())
                .containsEntry("optumRxCoverage", true);
    }

    @Test
    void booleanFactsAcceptCommonSourceSystemSpellings() {
        List<SegmentRule> rules = List.of(rule("tmpOtcMa", 1, 1, "coverageActive", IS_TRUE, null));

        for (Object truthy : List.of(true, "true", "Y", "yes", 1, "1")) {
            assertThat(evaluator.evaluate(segments, rules, facts("coverageActive", truthy)).flags())
                    .as("value %s", truthy).containsEntry("tmpOtcMa", true);
        }
        for (Object falsy : List.of(false, "N", "0", "maybe")) {
            assertThat(evaluator.evaluate(segments, rules, facts("coverageActive", falsy)).flags())
                    .as("value %s", falsy).containsEntry("tmpOtcMa", false);
        }
    }

    @Test
    void greaterThanComparesNumerically() {
        List<SegmentRule> rules = List.of(rule("tmpOtcMa", 1, 1, "amount", GREATER_THAN, "10"));

        assertThat(evaluator.evaluate(segments, rules, facts("amount", "9.5")).flags()).containsEntry("tmpOtcMa", false);
        assertThat(evaluator.evaluate(segments, rules, facts("amount", 10.01)).flags()).containsEntry("tmpOtcMa", true);
        assertThat(evaluator.evaluate(segments, rules, facts("amount", "n/a")).flags()).containsEntry("tmpOtcMa", false);
    }

    private static SegmentRule rule(String segment, int group, int order, String field, ComparisonOperator op, String value) {
        return new SegmentRule(order, segment, "THP", group, order, field, op, value);
    }

    private static Map<String, Object> facts(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
