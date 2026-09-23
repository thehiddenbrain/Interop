package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.segmentation.SegmentationResult.ConditionTrace;
import org.point32health.memberprofile.segmentation.SegmentationResult.GroupTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.CONTAINS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.EQUALS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IN;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IS_FALSE;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.IS_TRUE;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_CONTAINS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_EQUALS;
import static org.point32health.memberprofile.segmentation.ComparisonOperator.NOT_IN;
import static org.point32health.memberprofile.segmentation.Segment.ALL_PUBLIC_PLANS_MA;
import static org.point32health.memberprofile.segmentation.Segment.ALL_TUFTS_MEDICARE_PREFERRED;
import static org.point32health.memberprofile.segmentation.Segment.INTEROPERABILITY;
import static org.point32health.memberprofile.segmentation.Segment.ONLINE_BILL_PAY;
import static org.point32health.memberprofile.segmentation.Segment.OPTUM_RX_COVERAGE;
import static org.point32health.memberprofile.segmentation.Segment.PLAN_OF_CARE;
import static org.point32health.memberprofile.segmentation.Segment.TMP_OTC_MA;

/**
 * The seeded rules of {@code V2__seed_segmentation.sql} (catalog section 3), rebuilt in memory exactly as
 * {@code SegmentRuleRepository} would read them, evaluated by the real {@link SegmentationEvaluator}.
 * <p>
 * For every rule group of both companies: one fact set that matches, and for each condition of the group
 * one fact set that fails only that condition. Plus the HPHC segments that have no rules, the cross-checks
 * of the catalog, the contract keys, and the empty-attributes member.
 */
class SeededSegmentationRulesTest {

    static final String HPHC = "HPHC";
    static final String THP = "THP";

    static final List<String> CONTRACT_KEYS = List.of("onlineBillPay", "optumRxCoverage", "allPublicPlansMa",
            "allTuftsMedicarePreferred", "tmpOtcMa", "planOfCare", "interoperability");

    static final String PLAN_OF_CARE_GROUP_3_PLAN_CODES =
            "10GT100004,10GT100005,10GT100006,10GT100007,10GT100008,10GT100009,10GT100010,10GT100011,10GT100012,"
            + "10GT100013,10GT100014,10GT100015,10PL100002,10PL100003,10PL100004,10PL100005,10PL100006,10GT100016,"
            + "10GT100017,10GT100018";

    /** Builds the rows in seed order; ids 1..75 are what the identity column assigns on a fresh database. */
    private static final class Seed {
        private final List<SegmentRule> rows = new ArrayList<>(75);

        Seed row(Segment segment, String company, int group, int order, String field, ComparisonOperator op, String value) {
            rows.add(SegmentRule.of(rows.size() + 1, segment, company, group, order, field, op, value));
            return this;
        }

        List<SegmentRule> rows() {
            return List.copyOf(rows);
        }
    }

    /** Every row of V2__seed_segmentation.sql, copied verbatim and in seed order. */
    static final List<SegmentRule> SEED = new Seed()
            // onlineBillPay
            .row(ONLINE_BILL_PAY, HPHC, 1, 1, "dependentType", EQUALS, "01")
            .row(ONLINE_BILL_PAY, HPHC, 1, 2, "memberCategory", EQUALS, "B2I")
            .row(ONLINE_BILL_PAY, HPHC, 1, 3, "customerCategory", NOT_EQUALS, "NH_39_WEEK")
            .row(ONLINE_BILL_PAY, THP, 1, 1, "sourceSystemId", EQUALS, "2001")
            .row(ONLINE_BILL_PAY, THP, 1, 2, "planTypeCode", NOT_IN, "SCO,PDP")
            .row(ONLINE_BILL_PAY, THP, 1, 3, "coverageGroupTypeCode", EQUALS, "2")
            .row(ONLINE_BILL_PAY, THP, 1, 4, "hasActivePdp", IS_FALSE, null)
            .row(ONLINE_BILL_PAY, THP, 2, 5, "sourceSystemId", EQUALS, "2001")
            .row(ONLINE_BILL_PAY, THP, 2, 6, "planTypeCode", EQUALS, "CTH")
            // optumRxCoverage
            .row(OPTUM_RX_COVERAGE, HPHC, 1, 1, "basicMedicalDrugCoverageIndicator", IS_TRUE, null)
            .row(OPTUM_RX_COVERAGE, THP, 1, 1, "sourceSystemId", EQUALS, "2001")
            .row(OPTUM_RX_COVERAGE, THP, 1, 2, "hasPharmacyRider", IS_TRUE, null)
            .row(OPTUM_RX_COVERAGE, THP, 1, 3, "product", NOT_IN, "NPDP,RPDP")
            .row(OPTUM_RX_COVERAGE, THP, 2, 4, "hasTmpMedicalCoverage", IS_TRUE, null)
            .row(OPTUM_RX_COVERAGE, THP, 2, 5, "hasActivePdp", IS_TRUE, null)
            .row(OPTUM_RX_COVERAGE, THP, 2, 6, "product", NOT_IN, "NPDP,RPDP")
            .row(OPTUM_RX_COVERAGE, THP, 3, 7, "sourceSystemId", EQUALS, "2001")
            .row(OPTUM_RX_COVERAGE, THP, 3, 8, "product", EQUALS, "PDP")
            // allPublicPlansMa
            .row(ALL_PUBLIC_PLANS_MA, THP, 1, 1, "sourceSystemId", EQUALS, "2026")
            .row(ALL_PUBLIC_PLANS_MA, THP, 1, 2, "subsidiary", EQUALS, "THPPMA")
            // allTuftsMedicarePreferred
            .row(ALL_TUFTS_MEDICARE_PREFERRED, THP, 1, 1, "sourceSystemId", EQUALS, "2001")
            .row(ALL_TUFTS_MEDICARE_PREFERRED, THP, 1, 2, "planCode", CONTAINS, "EG")
            .row(ALL_TUFTS_MEDICARE_PREFERRED, THP, 1, 3, "planCode", NOT_CONTAINS, "PDP")
            // tmpOtcMa
            .row(TMP_OTC_MA, THP, 1, 1, "sourceSystemId", EQUALS, "2001")
            .row(TMP_OTC_MA, THP, 1, 2, "planTypeCode", EQUALS, "SCO")
            .row(TMP_OTC_MA, THP, 1, 3, "coverageActive", IS_TRUE, null)
            .row(TMP_OTC_MA, THP, 2, 4, "sourceSystemId", EQUALS, "2001")
            .row(TMP_OTC_MA, THP, 2, 5, "planTypeCode", EQUALS, "MAP")
            .row(TMP_OTC_MA, THP, 2, 6, "coverageActive", IS_TRUE, null)
            .row(TMP_OTC_MA, THP, 3, 7, "sourceSystemId", EQUALS, "2001")
            .row(TMP_OTC_MA, THP, 3, 8, "planCode", EQUALS, "STD SAVER")
            .row(TMP_OTC_MA, THP, 3, 9, "coverageActive", IS_TRUE, null)
            .row(TMP_OTC_MA, THP, 4, 10, "sourceSystemId", EQUALS, "2001")
            .row(TMP_OTC_MA, THP, 4, 11, "planCode", CONTAINS, "SMV")
            .row(TMP_OTC_MA, THP, 4, 12, "coverageActive", IS_TRUE, null)
            .row(TMP_OTC_MA, THP, 5, 13, "sourceSystemId", EQUALS, "2064")
            .row(TMP_OTC_MA, THP, 5, 14, "product", EQUALS, "DMA")
            .row(TMP_OTC_MA, THP, 5, 15, "coverageActive", IS_TRUE, null)
            // planOfCare
            .row(PLAN_OF_CARE, THP, 1, 1, "subsidiary", EQUALS, "THPPMA")
            .row(PLAN_OF_CARE, THP, 1, 2, "sourceSystemId", EQUALS, "2026")
            .row(PLAN_OF_CARE, THP, 1, 3, "product", EQUALS, "MC")
            .row(PLAN_OF_CARE, THP, 1, 4, "planOfCareCoverageEligible", IS_TRUE, null)
            .row(PLAN_OF_CARE, THP, 2, 5, "subsidiary", EQUALS, "THPPMA")
            .row(PLAN_OF_CARE, THP, 2, 6, "sourceSystemId", EQUALS, "2026")
            .row(PLAN_OF_CARE, THP, 2, 7, "product", IN, "PL,GT")
            .row(PLAN_OF_CARE, THP, 2, 8, "planCode", IN, "10GT100000,10GT100001,10GT100002,10PL100001")
            .row(PLAN_OF_CARE, THP, 2, 9, "planOfCareCoverageEligible", IS_TRUE, null)
            .row(PLAN_OF_CARE, THP, 3, 10, "subsidiary", EQUALS, "THPPMA")
            .row(PLAN_OF_CARE, THP, 3, 11, "sourceSystemId", EQUALS, "2026")
            .row(PLAN_OF_CARE, THP, 3, 12, "product", IN, "PL,GT")
            .row(PLAN_OF_CARE, THP, 3, 13, "planCode", IN, PLAN_OF_CARE_GROUP_3_PLAN_CODES)
            .row(PLAN_OF_CARE, THP, 3, 14, "planOfCareCoverageEligible", IS_TRUE, null)
            .row(PLAN_OF_CARE, THP, 4, 15, "subsidiary", EQUALS, "THPPMA")
            .row(PLAN_OF_CARE, THP, 4, 16, "sourceSystemId", EQUALS, "2064")
            .row(PLAN_OF_CARE, THP, 4, 17, "product", EQUALS, "DMA")
            .row(PLAN_OF_CARE, THP, 4, 18, "planOfCareCoverageEligible", IS_TRUE, null)
            .row(PLAN_OF_CARE, THP, 5, 19, "sourceSystemId", EQUALS, "2001")
            .row(PLAN_OF_CARE, THP, 5, 20, "planTypeCode", EQUALS, "SCO")
            .row(PLAN_OF_CARE, THP, 5, 21, "planOfCareCoverageEligible", IS_TRUE, null)
            // interoperability
            .row(INTEROPERABILITY, THP, 1, 1, "subsidiary", EQUALS, "THPPRI")
            .row(INTEROPERABILITY, THP, 1, 2, "sourceSystemId", EQUALS, "2048")
            .row(INTEROPERABILITY, THP, 1, 3, "coverageStarted", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 1, 4, "groupProductEffectiveForCoverage", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 2, 5, "subsidiary", EQUALS, "THPPMA")
            .row(INTEROPERABILITY, THP, 2, 6, "sourceSystemId", EQUALS, "2026")
            .row(INTEROPERABILITY, THP, 2, 7, "product", IN, "PL,GT,MC")
            .row(INTEROPERABILITY, THP, 2, 8, "coverageStarted", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 2, 9, "groupProductEffectiveForCoverage", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 3, 10, "sourceSystemId", EQUALS, "2064")
            .row(INTEROPERABILITY, THP, 3, 11, "product", EQUALS, "DMA")
            .row(INTEROPERABILITY, THP, 3, 12, "coverageActive", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 4, 13, "sourceSystemId", EQUALS, "2001")
            .row(INTEROPERABILITY, THP, 4, 14, "planTypeCode", IN, "MR,SCO,MAP")
            .row(INTEROPERABILITY, THP, 4, 15, "coverageStarted", IS_TRUE, null)
            .row(INTEROPERABILITY, THP, 4, 16, "groupProductEffectiveForCoverage", IS_TRUE, null)
            .rows();

    /**
     * One rule group of the catalog table: a fact set that satisfies every condition, and for each condition
     * (in evaluation order) the field to change and the value that fails that condition alone.
     */
    record GroupCase(Segment segment, String company, int group, Map<String, Object> matching, List<Map.Entry<String, Object>> breakers) {
        String label() {
            return segment.key() + " " + company + " group " + group;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private static GroupCase group(Segment segment, String company, int group, Map<String, Object> matching, List<Map.Entry<String, Object>> breakers) {
        return new GroupCase(segment, company, group, matching, breakers);
    }

    /** Attribute map as MemberDomain would send it: values may be strings, numbers or booleans. */
    private static Map<String, Object> facts(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    /** (field, value) pairs, one per condition of the group, in evaluation order. */
    private static List<Map.Entry<String, Object>> breaks(Object... fieldsAndValues) {
        List<Map.Entry<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < fieldsAndValues.length; i += 2) list.add(Map.entry((String) fieldsAndValues[i], fieldsAndValues[i + 1]));
        return list;
    }

    /** Catalog section 3, one entry per row of the table: the matching facts, then a breaker per condition. */
    static final List<GroupCase> GROUPS = List.of(
            group(ONLINE_BILL_PAY, HPHC, 1,
                    facts("dependentType", "01", "memberCategory", "B2I", "customerCategory", "GROUP"),
                    breaks("dependentType", "02", "memberCategory", "B2C", "customerCategory", "NH_39_WEEK")),
            group(ONLINE_BILL_PAY, THP, 1,
                    facts("sourceSystemId", 2001, "planTypeCode", "MR", "coverageGroupTypeCode", "2", "hasActivePdp", false),
                    breaks("sourceSystemId", 2026, "planTypeCode", "SCO", "coverageGroupTypeCode", "1", "hasActivePdp", true)),
            group(ONLINE_BILL_PAY, THP, 2,
                    facts("sourceSystemId", 2001, "planTypeCode", "CTH"),
                    breaks("sourceSystemId", 2064, "planTypeCode", "MR")),
            group(OPTUM_RX_COVERAGE, HPHC, 1,
                    facts("basicMedicalDrugCoverageIndicator", "Y"),
                    breaks("basicMedicalDrugCoverageIndicator", "N")),
            group(OPTUM_RX_COVERAGE, THP, 1,
                    facts("sourceSystemId", 2001, "hasPharmacyRider", true, "product", "MAPD"),
                    breaks("sourceSystemId", 2026, "hasPharmacyRider", false, "product", "NPDP")),
            group(OPTUM_RX_COVERAGE, THP, 2,
                    facts("hasTmpMedicalCoverage", true, "hasActivePdp", true, "product", "MC"),
                    breaks("hasTmpMedicalCoverage", false, "hasActivePdp", false, "product", "RPDP")),
            group(OPTUM_RX_COVERAGE, THP, 3,
                    facts("sourceSystemId", 2001, "product", "PDP"),
                    breaks("sourceSystemId", 2048, "product", "MAPD")),
            group(ALL_PUBLIC_PLANS_MA, THP, 1,
                    facts("sourceSystemId", 2026, "subsidiary", "THPPMA"),
                    breaks("sourceSystemId", 2001, "subsidiary", "THPPRI")),
            group(ALL_TUFTS_MEDICARE_PREFERRED, THP, 1,
                    facts("sourceSystemId", 2001, "planCode", "10EG1234"),
                    // both planCode conditions: no EG at all, then EG plus PDP
                    breaks("sourceSystemId", 2026, "planCode", "10AB1234", "planCode", "10EGPDP1")),
            group(TMP_OTC_MA, THP, 1,
                    facts("sourceSystemId", 2001, "planTypeCode", "SCO", "coverageActive", true),
                    breaks("sourceSystemId", 2064, "planTypeCode", "MR", "coverageActive", false)),
            group(TMP_OTC_MA, THP, 2,
                    facts("sourceSystemId", 2001, "planTypeCode", "MAP", "coverageActive", "Y"),
                    breaks("sourceSystemId", 2026, "planTypeCode", "MR", "coverageActive", "N")),
            group(TMP_OTC_MA, THP, 3,
                    facts("sourceSystemId", "2001", "planCode", "Std Saver", "coverageActive", "yes"),
                    breaks("sourceSystemId", "2064", "planCode", "Std Plus", "coverageActive", "no")),
            group(TMP_OTC_MA, THP, 4,
                    facts("sourceSystemId", 2001, "planCode", "10SMV001", "coverageActive", 1),
                    breaks("sourceSystemId", 2026, "planCode", "10STD001", "coverageActive", 0)),
            group(TMP_OTC_MA, THP, 5,
                    facts("sourceSystemId", 2064, "product", "DMA", "coverageActive", true),
                    breaks("sourceSystemId", 2001, "product", "MC", "coverageActive", false)),
            group(PLAN_OF_CARE, THP, 1,
                    facts("subsidiary", "THPPMA", "sourceSystemId", 2026, "product", "MC", "planOfCareCoverageEligible", true),
                    breaks("subsidiary", "THPPRI", "sourceSystemId", 2001, "product", "PL", "planOfCareCoverageEligible", false)),
            group(PLAN_OF_CARE, THP, 2,
                    facts("subsidiary", "THPPMA", "sourceSystemId", 2026, "product", "PL", "planCode", "10PL100001", "planOfCareCoverageEligible", true),
                    breaks("subsidiary", "THPPRI", "sourceSystemId", 2064, "product", "MC", "planCode", "10PL100002", "planOfCareCoverageEligible", false)),
            group(PLAN_OF_CARE, THP, 3,
                    facts("subsidiary", "THPPMA", "sourceSystemId", 2026, "product", "GT", "planCode", "10GT100018", "planOfCareCoverageEligible", "Y"),
                    breaks("subsidiary", "THPPRI", "sourceSystemId", 2001, "product", "DMA", "planCode", "10GT100003", "planOfCareCoverageEligible", "N")),
            group(PLAN_OF_CARE, THP, 4,
                    facts("subsidiary", "THPPMA", "sourceSystemId", 2064, "product", "DMA", "planOfCareCoverageEligible", true),
                    breaks("subsidiary", "THPPRI", "sourceSystemId", 2026, "product", "MC", "planOfCareCoverageEligible", false)),
            group(PLAN_OF_CARE, THP, 5,
                    facts("sourceSystemId", 2001, "planTypeCode", "SCO", "planOfCareCoverageEligible", true),
                    breaks("sourceSystemId", 2026, "planTypeCode", "MAP", "planOfCareCoverageEligible", false)),
            group(INTEROPERABILITY, THP, 1,
                    facts("subsidiary", "THPPRI", "sourceSystemId", 2048, "coverageStarted", true, "groupProductEffectiveForCoverage", true),
                    breaks("subsidiary", "THPPMA", "sourceSystemId", 2026, "coverageStarted", false, "groupProductEffectiveForCoverage", false)),
            group(INTEROPERABILITY, THP, 2,
                    facts("subsidiary", "THPPMA", "sourceSystemId", 2026, "product", "GT", "coverageStarted", true, "groupProductEffectiveForCoverage", true),
                    breaks("subsidiary", "THPPRI", "sourceSystemId", 2048, "product", "DMA", "coverageStarted", false, "groupProductEffectiveForCoverage", false)),
            group(INTEROPERABILITY, THP, 3,
                    facts("sourceSystemId", 2064, "product", "DMA", "coverageActive", true),
                    breaks("sourceSystemId", 2001, "product", "MC", "coverageActive", false)),
            group(INTEROPERABILITY, THP, 4,
                    facts("sourceSystemId", 2001, "planTypeCode", "MAP", "coverageStarted", true, "groupProductEffectiveForCoverage", true),
                    breaks("sourceSystemId", 2026, "planTypeCode", "CTH", "coverageStarted", "N", "groupProductEffectiveForCoverage", "0")));

    private static final SegmentationEvaluator EVALUATOR = new SegmentationEvaluator();

    /** What {@code SegmentRuleRepository.activeRulesFor(company)} returns, in seed order. */
    static List<SegmentRule> rulesFor(String company) {
        return SEED.stream().filter(r -> r.company().equals(company)).toList();
    }

    static List<SegmentRule> rulesOf(GroupCase g) {
        return SEED.stream()
                .filter(r -> r.segment() == g.segment() && r.company().equals(g.company()) && r.ruleGroup() == g.group())
                .toList();
    }

    /** Seed row 51: the twenty-code planCode IN list of planOfCare THP group 3. */
    static SegmentRule planOfCareGroup3PlanCodes() {
        return SEED.stream()
                .filter(r -> r.segment() == PLAN_OF_CARE && r.ruleGroup() == 3 && r.apiField().equals("planCode"))
                .findFirst().orElseThrow();
    }

    static SegmentationResult evaluate(String company, Map<String, Object> attributes, boolean explain) {
        return EVALUATOR.evaluate(rulesFor(company), MemberFacts.of(attributes), explain);
    }

    static GroupTrace traceOf(SegmentationResult result, Segment segment, int group) {
        return result.trace().stream()
                .filter(t -> t.segment().equals(segment.key()) && t.ruleGroup() == group)
                .findFirst().orElseThrow(() -> new AssertionError("no trace for " + segment.key() + " group " + group));
    }

    static Stream<Arguments> groups() {
        return GROUPS.stream().map(g -> arguments(g.label(), g));
    }

    static Stream<Arguments> singleConditionFailures() {
        return GROUPS.stream().flatMap(g -> {
            List<SegmentRule> rules = rulesOf(g);
            List<Arguments> cases = new ArrayList<>();
            for (int i = 0; i < g.breakers().size() && i < rules.size(); i++) {
                SegmentRule rule = rules.get(i);
                String condition = rule.apiField() + " " + rule.operator() + (rule.ruleValue() == null ? "" : " " + rule.ruleValue());
                cases.add(arguments(g.label() + " fails only [" + condition + "] with " + g.breakers().get(i).getValue(), g, i));
            }
            return cases.stream();
        });
    }

    @Nested
    class SeedCopy {

        @Test
        void hasTheSeventyFiveConditionRowsOfTheMigration() {
            assertThat(SEED).hasSize(75);
            assertThat(rulesFor(HPHC)).hasSize(4);
            assertThat(rulesFor(THP)).hasSize(71);
        }

        @Test
        void idsAreTheIdentityValuesOfAFreshDatabase() {
            assertThat(SEED).extracting(SegmentRule::id).containsExactlyElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, 75).boxed().toList());
        }

        @Test
        void hasTwentyThreeRuleGroupsAcrossBothCompanies() {
            Set<String> keys = new HashSet<>();
            for (SegmentRule r : SEED) keys.add(r.segment().key() + "|" + r.company() + "|" + r.ruleGroup());
            assertThat(keys).hasSize(23);
            assertThat(keys.stream().filter(k -> k.contains("|HPHC|"))).hasSize(2);
            assertThat(keys.stream().filter(k -> k.contains("|THP|"))).hasSize(21);
        }

        @Test
        void everyGroupOfTheSeedHasExactlyOneCaseInTheTable() {
            Set<String> seedGroups = new HashSet<>();
            for (SegmentRule r : SEED) seedGroups.add(r.segment().key() + "|" + r.company() + "|" + r.ruleGroup());
            List<String> caseGroups = GROUPS.stream().map(g -> g.segment().key() + "|" + g.company() + "|" + g.group()).toList();
            assertThat(caseGroups).doesNotHaveDuplicates();
            assertThat(new HashSet<>(caseGroups)).isEqualTo(seedGroups);
        }

        @Test
        void ruleValueIsPresentExactlyWhenTheOperatorNeedsOne() {
            // mirrors ck_rule_value_present in V1__schema.sql
            for (SegmentRule r : SEED) {
                if (r.operator().needsRuleValue()) {
                    assertThat(r.ruleValue()).as("row %d", r.id()).isNotBlank();
                } else {
                    assertThat(r.ruleValue()).as("row %d", r.id()).isNull();
                }
            }
        }

        @Test
        void evaluationOrderIsUniquePerSegmentAndCompany() {
            // mirrors ux_segment_rule_expression_order
            Set<String> seen = new HashSet<>();
            for (SegmentRule r : SEED) {
                assertThat(seen.add(r.segment().key() + "|" + r.company() + "|" + r.evaluationOrder())).as("row %d", r.id()).isTrue();
            }
        }

        @Test
        void rowsOfAGroupAreContiguousAndInEvaluationOrder() {
            Set<String> closedGroups = new HashSet<>();
            String current = null;
            int lastOrder = 0;
            for (SegmentRule r : SEED) {
                String key = r.segment().key() + "|" + r.company() + "|" + r.ruleGroup();
                if (!key.equals(current)) {
                    if (current != null) assertThat(closedGroups.add(current)).as("group %s appears twice", current).isTrue();
                    assertThat(closedGroups).doesNotContain(key);
                    current = key;
                    lastOrder = 0;
                }
                assertThat(r.evaluationOrder()).as("row %d", r.id()).isGreaterThan(lastOrder);
                lastOrder = r.evaluationOrder();
            }
        }

        @Test
        void planOfCareGroup3ListsTwentyPlanCodes() {
            SegmentRule row51 = planOfCareGroup3PlanCodes();
            assertThat(row51.id()).isEqualTo(51L);
            assertThat(row51.evaluationOrder()).isEqualTo(13);
            assertThat(row51.operator()).isEqualTo(IN);
            assertThat(row51.ruleValue()).isEqualTo(PLAN_OF_CARE_GROUP_3_PLAN_CODES);
            assertThat(row51.valueSet()).hasSize(20).contains("10GT100004", "10PL100002", "10PL100006", "10GT100018")
                    .doesNotContain("10GT100000", "10GT100001", "10GT100002", "10PL100001", "10GT100003");
        }

        @Test
        void inAndNotInRowsHaveTheirListsParsedAndOtherRowsDoNot() {
            for (SegmentRule r : SEED) {
                if (r.operator() == IN || r.operator() == NOT_IN) {
                    assertThat(r.valueSet()).as("row %d", r.id()).isNotEmpty();
                } else {
                    assertThat(r.valueSet()).as("row %d", r.id()).isEmpty();
                }
                assertThat(r.numericValue()).as("row %d (no GREATER_THAN in the seed)", r.id()).isNull();
            }
        }
    }

    @Nested
    class Table {

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.point32health.memberprofile.segmentation.SeededSegmentationRulesTest#groups")
        void caseMirrorsTheSeedRowsOfItsGroup(String label, GroupCase g) {
            List<SegmentRule> rules = rulesOf(g);
            assertThat(rules).isNotEmpty();
            assertThat(g.breakers()).as("one breaker per condition").hasSameSizeAs(rules);
            for (int i = 0; i < rules.size(); i++) {
                assertThat(g.breakers().get(i).getKey()).as("breaker %d field", i + 1).isEqualTo(rules.get(i).apiField());
            }
            assertThat(g.matching().keySet()).containsAll(rules.stream().map(SegmentRule::apiField).toList());
        }
    }

    @Nested
    class EveryGroup {

        @ParameterizedTest(name = "{0} matches")
        @MethodSource("org.point32health.memberprofile.segmentation.SeededSegmentationRulesTest#groups")
        void groupMatchesWhenEveryConditionHolds(String label, GroupCase g) {
            SegmentationResult result = evaluate(g.company(), g.matching(), true);
            assertThat(result.isTrue(g.segment())).isTrue();

            GroupTrace trace = traceOf(result, g.segment(), g.group());
            assertThat(trace.matched()).isTrue();
            assertThat(trace.conditions()).hasSameSizeAs(rulesOf(g));
            assertThat(trace.conditions()).allMatch(ConditionTrace::passed);
            assertThat(trace.conditions()).extracting(ConditionTrace::ruleId)
                    .containsExactlyElementsOf(rulesOf(g).stream().map(SegmentRule::id).toList());

            // the same outcome without explain, and with the group's rows alone
            assertThat(evaluate(g.company(), g.matching(), false).flags()).isEqualTo(result.flags());
            assertThat(EVALUATOR.evaluate(rulesOf(g), MemberFacts.of(g.matching()), false).isTrue(g.segment())).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.point32health.memberprofile.segmentation.SeededSegmentationRulesTest#singleConditionFailures")
        void groupFailsWhenExactlyOneConditionFails(String label, GroupCase g, int index) {
            Map.Entry<String, Object> breaker = g.breakers().get(index);
            Map<String, Object> attributes = new LinkedHashMap<>(g.matching());
            attributes.put(breaker.getKey(), breaker.getValue());
            SegmentRule expectedToFail = rulesOf(g).get(index);

            SegmentationResult result = evaluate(g.company(), attributes, true);
            GroupTrace trace = traceOf(result, g.segment(), g.group());
            assertThat(trace.matched()).isFalse();
            List<ConditionTrace> failed = trace.conditions().stream().filter(c -> !c.passed()).toList();
            assertThat(failed).as("only the mutated condition fails").hasSize(1);
            assertThat(failed.get(0).ruleId()).isEqualTo(expectedToFail.id());
            assertThat(failed.get(0).apiField()).isEqualTo(breaker.getKey());
            assertThat(failed.get(0).operator()).isEqualTo(expectedToFail.operator());
            assertThat(failed.get(0).actualValue()).isEqualTo(breaker.getValue());

            // the group's rows alone give false, with and without explain
            assertThat(EVALUATOR.evaluate(rulesOf(g), MemberFacts.of(attributes), false).isTrue(g.segment())).isFalse();
            assertThat(EVALUATOR.evaluate(rulesOf(g), MemberFacts.of(attributes), true).isTrue(g.segment())).isFalse();
            assertThat(evaluate(g.company(), attributes, false).flags()).isEqualTo(result.flags());
        }

        @ParameterizedTest(name = "{0}: removing any one fact fails the group")
        @MethodSource("org.point32health.memberprofile.segmentation.SeededSegmentationRulesTest#groups")
        void groupFailsWhenAnyOneOfItsFactsIsMissing(String label, GroupCase g) {
            for (SegmentRule rule : rulesOf(g)) {
                Map<String, Object> attributes = new LinkedHashMap<>(g.matching());
                attributes.remove(rule.apiField());
                SegmentationResult result = EVALUATOR.evaluate(rulesOf(g), MemberFacts.of(attributes), true);
                assertThat(result.isTrue(g.segment())).as("without %s", rule.apiField()).isFalse();
                assertThat(traceOf(result, g.segment(), g.group()).conditions())
                        .filteredOn(c -> c.apiField().equals(rule.apiField()))
                        .allSatisfy(c -> {
                            assertThat(c.passed()).isFalse();
                            assertThat(c.actualValue()).isNull();
                        });
            }
        }
    }

    @Nested
    class HphcAlwaysFalse {

        @ParameterizedTest
        @EnumSource(value = Segment.class, names = {"ALL_PUBLIC_PLANS_MA", "ALL_TUFTS_MEDICARE_PREFERRED", "TMP_OTC_MA", "PLAN_OF_CARE", "INTEROPERABILITY"})
        void hphcHasNoRulesForTheSegment(Segment segment) {
            assertThat(rulesFor(HPHC)).noneMatch(r -> r.segment() == segment);
        }

        @ParameterizedTest
        @EnumSource(value = Segment.class, names = {"ALL_PUBLIC_PLANS_MA", "ALL_TUFTS_MEDICARE_PREFERRED", "TMP_OTC_MA", "PLAN_OF_CARE", "INTEROPERABILITY"})
        void segmentStaysFalseForHphcEvenWithFactsThatSatisfyEveryThpGroup(Segment segment) {
            List<GroupCase> thpGroups = GROUPS.stream().filter(g -> g.segment() == segment && g.company().equals(THP)).toList();
            assertThat(thpGroups).isNotEmpty();
            for (GroupCase g : thpGroups) {
                assertThat(evaluate(THP, g.matching(), false).isTrue(segment)).as("sanity: THP group %d matches", g.group()).isTrue();
                SegmentationResult hphc = evaluate(HPHC, g.matching(), true);
                assertThat(hphc.isTrue(segment)).as("HPHC with THP group %d facts", g.group()).isFalse();
                assertThat(hphc.trace()).extracting(GroupTrace::segment).doesNotContain(segment.key());
            }
        }

        @Test
        void hphcEvaluatesOnlyItsTwoGroups() {
            SegmentationResult result = evaluate(HPHC, facts("dependentType", "01", "memberCategory", "B2I",
                    "customerCategory", "GROUP", "basicMedicalDrugCoverageIndicator", "N"), true);
            assertThat(result.trace()).extracting(GroupTrace::segment, GroupTrace::ruleGroup, GroupTrace::matched).containsExactly(
                    tuple("onlineBillPay", 1, true),
                    tuple("optumRxCoverage", 1, false));
            assertThat(result.flags()).containsExactly(
                    Map.entry("onlineBillPay", true), Map.entry("optumRxCoverage", false), Map.entry("allPublicPlansMa", false),
                    Map.entry("allTuftsMedicarePreferred", false), Map.entry("tmpOtcMa", false), Map.entry("planOfCare", false),
                    Map.entry("interoperability", false));
        }

        @Test
        void hphcMemberOfTheEndToEndTestGetsOnlineBillPayOnly() {
            // MemberProfileEndToEndTest.hphcSubscriberWithYoungChild
            SegmentationResult result = evaluate(HPHC, facts("dependentType", "01", "memberCategory", "B2I",
                    "customerCategory", "GROUP", "basicMedicalDrugCoverageIndicator", "N"), false);
            assertThat(result.flags()).containsEntry("onlineBillPay", true).containsEntry("optumRxCoverage", false);
            assertThat(result.flags().values().stream().filter(Boolean::booleanValue)).hasSize(1);
        }

        @Test
        void thpFactsAloneNeverSatisfyAnHphcGroup() {
            for (GroupCase g : GROUPS) {
                if (!g.company().equals(THP)) continue;
                assertThat(evaluate(HPHC, g.matching(), false).flags().values()).as(g.label()).containsOnly(Boolean.FALSE);
            }
        }
    }

    @Nested
    class CrossChecks {

        /**
         * Catalog: a THP member with planTypeCode SCO and coverageGroupTypeCode 2 fails onlineBillPay group 1
         * on the NOT_IN condition, but tmpOtcMa group 1 and interoperability group 4 can be true.
         */
        @Test
        void scoMemberFailsOnlineBillPayButGetsOtcAndInteroperability() {
            Map<String, Object> sco = facts("sourceSystemId", 2001, "planTypeCode", "SCO", "coverageGroupTypeCode", "2",
                    "hasActivePdp", false, "coverageActive", true, "coverageStarted", true,
                    "groupProductEffectiveForCoverage", true, "planOfCareCoverageEligible", true);

            SegmentationResult result = evaluate(THP, sco, true);

            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            GroupTrace billPay1 = traceOf(result, ONLINE_BILL_PAY, 1);
            assertThat(billPay1.matched()).isFalse();
            assertThat(billPay1.conditions()).filteredOn(c -> !c.passed()).singleElement().satisfies(c -> {
                assertThat(c.ruleId()).isEqualTo(5L);
                assertThat(c.apiField()).isEqualTo("planTypeCode");
                assertThat(c.operator()).isEqualTo(NOT_IN);
                assertThat(c.ruleValue()).isEqualTo("SCO,PDP");
                assertThat(c.actualValue()).isEqualTo("SCO");
            });
            assertThat(traceOf(result, ONLINE_BILL_PAY, 2).matched()).isFalse();   // SCO is not CTH

            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            assertThat(traceOf(result, TMP_OTC_MA, 1).matched()).isTrue();
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(traceOf(result, INTEROPERABILITY, 4).matched()).isTrue();
            assertThat(result.isTrue(PLAN_OF_CARE)).isTrue();                        // group 5: 2001, SCO, eligible
            assertThat(traceOf(result, PLAN_OF_CARE, 5).matched()).isTrue();

            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            assertThat(result.isTrue(ALL_PUBLIC_PLANS_MA)).isFalse();
            assertThat(result.isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
            assertThat(evaluate(THP, sco, false).flags()).isEqualTo(result.flags());
        }

        @Test
        void pdpPlanTypeAlsoFailsOnlineBillPayGroup1() {
            Map<String, Object> pdp = facts("sourceSystemId", 2001, "planTypeCode", "PDP", "coverageGroupTypeCode", "2", "hasActivePdp", false);
            SegmentationResult result = evaluate(THP, pdp, true);
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(traceOf(result, ONLINE_BILL_PAY, 1).conditions()).filteredOn(c -> !c.passed())
                    .extracting(ConditionTrace::apiField).containsExactly("planTypeCode");
        }

        /**
         * Catalog: a THP member with product PDP matches optumRxCoverage through group 3. A standalone PDP
         * member has no pharmacy rider and no TMP medical coverage, so groups 1 and 2 fail on those facts.
         */
        @Test
        void standalonePdpMemberGetsOptumRxThroughGroup3Only() {
            Map<String, Object> pdp = facts("sourceSystemId", 2001, "product", "PDP", "hasPharmacyRider", false,
                    "hasTmpMedicalCoverage", false, "hasActivePdp", true);

            SegmentationResult result = evaluate(THP, pdp, true);

            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 1).matched()).isFalse();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 2).matched()).isFalse();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 3).matched()).isTrue();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 3).conditions()).extracting(ConditionTrace::ruleId).containsExactly(17L, 18L);

            // without group 3 the segment is false: group 3 is the only path
            List<SegmentRule> withoutGroup3 = rulesFor(THP).stream()
                    .filter(r -> !(r.segment() == OPTUM_RX_COVERAGE && r.ruleGroup() == 3)).toList();
            assertThat(EVALUATOR.evaluate(withoutGroup3, MemberFacts.of(pdp), false).isTrue(OPTUM_RX_COVERAGE)).isFalse();
        }

        /**
         * IN / NOT_IN is exact list membership (Operators tab), so product PDP is not in {NPDP, RPDP} and the
         * NOT_IN condition of groups 1 and 2 passes for it; only NPDP and RPDP fail it. The catalog's wording
         * "fails groups 1 and 2 (NOT_IN)" holds for NPDP / RPDP products, not for the literal value PDP.
         */
        @ParameterizedTest
        @ValueSource(strings = {"NPDP", "RPDP", "npdp", "rpdp"})
        void npdpAndRpdpProductsFailTheNotInConditionOfGroups1And2(String product) {
            Map<String, Object> facts = facts("sourceSystemId", 2001, "product", product, "hasPharmacyRider", true,
                    "hasTmpMedicalCoverage", true, "hasActivePdp", true);
            SegmentationResult result = evaluate(THP, facts, true);
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            for (int group : new int[]{1, 2}) {
                GroupTrace trace = traceOf(result, OPTUM_RX_COVERAGE, group);
                assertThat(trace.matched()).isFalse();
                assertThat(trace.conditions()).filteredOn(c -> !c.passed()).singleElement().satisfies(c -> {
                    assertThat(c.apiField()).isEqualTo("product");
                    assertThat(c.operator()).isEqualTo(NOT_IN);
                });
            }
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 3).matched()).isFalse();
        }

        @Test
        void pdpProductWithARiderPassesTheNotInConditionOfGroup1() {
            Map<String, Object> facts = facts("sourceSystemId", 2001, "product", "PDP", "hasPharmacyRider", true);
            SegmentationResult result = evaluate(THP, facts, true);
            GroupTrace group1 = traceOf(result, OPTUM_RX_COVERAGE, 1);
            assertThat(group1.matched()).isTrue();
            assertThat(group1.conditions()).filteredOn(c -> c.operator() == NOT_IN).singleElement()
                    .satisfies(c -> assertThat(c.passed()).isTrue());
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
        }

        @Test
        void thpMemberOfTheEndToEndTestGetsFourSegments() {
            // MemberProfileEndToEndTest.thpSubscriberWithTeenNeedsConsentForClaims
            Map<String, Object> sam = facts("sourceSystemId", 2001, "planTypeCode", "MR", "planCode", "10EG1234",
                    "coverageActive", true, "coverageStarted", true, "groupProductEffectiveForCoverage", true,
                    "hasPharmacyRider", true, "product", "MAPD", "coverageGroupTypeCode", "2", "hasActivePdp", false);
            SegmentationResult result = evaluate(THP, sam, true);
            assertThat(result.flags()).containsExactly(
                    Map.entry("onlineBillPay", true), Map.entry("optumRxCoverage", true), Map.entry("allPublicPlansMa", false),
                    Map.entry("allTuftsMedicarePreferred", true), Map.entry("tmpOtcMa", false), Map.entry("planOfCare", false),
                    Map.entry("interoperability", true));
            assertThat(result.trace()).filteredOn(t -> t.segment().equals("interoperability") && t.matched())
                    .extracting(GroupTrace::ruleGroup).containsExactly(4);
            assertThat(result.trace()).hasSize(21);       // every THP group is reported with explain
        }

        @Test
        void tmpOtcMaGroupsAreAlternativesForTheSameMember() {
            Map<String, Object> base = facts("sourceSystemId", 2001, "coverageActive", true);
            for (Map.Entry<String, String> variant : List.of(Map.entry("planTypeCode", "SCO"), Map.entry("planTypeCode", "MAP"),
                    Map.entry("planCode", "STD SAVER"), Map.entry("planCode", "10SMV001"))) {
                Map<String, Object> attributes = new LinkedHashMap<>(base);
                attributes.put(variant.getKey(), variant.getValue());
                assertThat(evaluate(THP, attributes, false).isTrue(TMP_OTC_MA)).as(variant.toString()).isTrue();
            }
            assertThat(evaluate(THP, base, false).isTrue(TMP_OTC_MA)).isFalse();
            Map<String, Object> inactive = new LinkedHashMap<>(base);
            inactive.put("planTypeCode", "SCO");
            inactive.put("coverageActive", false);
            assertThat(evaluate(THP, inactive, false).isTrue(TMP_OTC_MA)).isFalse();
        }

        @Test
        void allTuftsMedicarePreferredExcludesPdpPlanCodes() {
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "planCode", "10EG1234"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isTrue();
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "planCode", "10eg1234"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isTrue();
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "planCode", "EGPDP"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "planCode", "PDP10EG"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "planCode", "10MR1234"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
            assertThat(evaluate(THP, facts("sourceSystemId", 2026, "planCode", "10EG1234"), false).isTrue(ALL_TUFTS_MEDICARE_PREFERRED)).isFalse();
        }

        @Test
        void planOfCareGroup2AndGroup3PlanCodeListsAreDisjointAlternatives() {
            Map<String, Object> base = facts("subsidiary", "THPPMA", "sourceSystemId", 2026, "product", "GT", "planOfCareCoverageEligible", true);
            for (String code : List.of("10GT100000", "10GT100001", "10GT100002", "10PL100001")) {
                Map<String, Object> attributes = new LinkedHashMap<>(base);
                attributes.put("planCode", code);
                SegmentationResult result = evaluate(THP, attributes, true);
                assertThat(result.isTrue(PLAN_OF_CARE)).as(code).isTrue();
                assertThat(traceOf(result, PLAN_OF_CARE, 2).matched()).as(code).isTrue();
                assertThat(traceOf(result, PLAN_OF_CARE, 3).matched()).as(code).isFalse();
            }
            Set<String> group3Codes = planOfCareGroup3PlanCodes().valueSet();
            assertThat(group3Codes).hasSize(20);
            for (String code : group3Codes) {
                Map<String, Object> attributes = new LinkedHashMap<>(base);
                attributes.put("planCode", code);
                SegmentationResult result = evaluate(THP, attributes, true);
                assertThat(result.isTrue(PLAN_OF_CARE)).as(code).isTrue();
                assertThat(traceOf(result, PLAN_OF_CARE, 2).matched()).as(code).isFalse();
                assertThat(traceOf(result, PLAN_OF_CARE, 3).matched()).as(code).isTrue();
            }
            Map<String, Object> unknownCode = new LinkedHashMap<>(base);
            unknownCode.put("planCode", "10GT100003");
            assertThat(evaluate(THP, unknownCode, false).isTrue(PLAN_OF_CARE)).isFalse();
        }

        @Test
        void interoperabilityGroup4AcceptsEachPlanTypeOfItsList() {
            for (String planType : List.of("MR", "SCO", "MAP", "mr", " sco ")) {
                Map<String, Object> attributes = facts("sourceSystemId", 2001, "planTypeCode", planType,
                        "coverageStarted", "Y", "groupProductEffectiveForCoverage", "1");
                assertThat(evaluate(THP, attributes, false).isTrue(INTEROPERABILITY)).as(planType).isTrue();
            }
            Map<String, Object> cth = facts("sourceSystemId", 2001, "planTypeCode", "CTH",
                    "coverageStarted", "Y", "groupProductEffectiveForCoverage", "1");
            assertThat(evaluate(THP, cth, false).isTrue(INTEROPERABILITY)).isFalse();
        }

        @Test
        void dmaMemberOfSourceSystem2064GetsOtcPlanOfCareAndInteroperabilityTogether() {
            Map<String, Object> dma = facts("subsidiary", "THPPMA", "sourceSystemId", 2064, "product", "DMA",
                    "coverageActive", true, "planOfCareCoverageEligible", true);
            SegmentationResult result = evaluate(THP, dma, true);
            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            assertThat(traceOf(result, TMP_OTC_MA, 5).matched()).isTrue();
            assertThat(result.isTrue(PLAN_OF_CARE)).isTrue();
            assertThat(traceOf(result, PLAN_OF_CARE, 4).matched()).isTrue();
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(traceOf(result, INTEROPERABILITY, 3).matched()).isTrue();
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(result.isTrue(ALL_PUBLIC_PLANS_MA)).isFalse();
        }
    }

    @Nested
    class Contract {

        @ParameterizedTest
        @ValueSource(strings = {HPHC, THP})
        void sevenKeysAlwaysPresentInContractOrder(String company) {
            assertThat(evaluate(company, Map.of(), false).flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
            assertThat(evaluate(company, Map.of(), true).flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
            for (GroupCase g : GROUPS) {
                assertThat(evaluate(company, g.matching(), false).flags().keySet()).as(g.label()).containsExactlyElementsOf(CONTRACT_KEYS);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {HPHC, THP})
        void emptyAttributesGiveAllFalse(String company) {
            SegmentationResult result = evaluate(company, Map.of(), true);
            assertThat(result.flags().values()).containsOnly(Boolean.FALSE);
            assertThat(result.trace()).isNotEmpty().allSatisfy(g -> {
                assertThat(g.matched()).isFalse();
                assertThat(g.conditions()).isNotEmpty().allSatisfy(c -> {
                    assertThat(c.passed()).isFalse();
                    assertThat(c.actualValue()).isNull();
                });
            });
            assertThat(evaluate(company, Map.of(), false).flags().values()).containsOnly(Boolean.FALSE);
            assertThat(EVALUATOR.evaluate(rulesFor(company), MemberFacts.of(null), false).flags().values()).containsOnly(Boolean.FALSE);
        }

        @ParameterizedTest
        @ValueSource(strings = {HPHC, THP})
        void explainReportsEveryGroupOfTheCompany(String company) {
            SegmentationResult result = evaluate(company, Map.of(), true);
            assertThat(result.trace()).hasSize(company.equals(HPHC) ? 2 : 21);
            int conditions = result.trace().stream().mapToInt(t -> t.conditions().size()).sum();
            assertThat(conditions).isEqualTo(rulesFor(company).size());
        }

        @Test
        void repositoryOrderBySegmentNameGivesTheSameFlagsAsSeedOrder() {
            // SegmentRuleRepository orders by segment_name, rule_group, evaluation_order (alphabetical segment names)
            List<SegmentRule> alphabetical = rulesFor(THP).stream()
                    .sorted(Comparator.comparing((SegmentRule r) -> r.segment().key())
                            .thenComparingInt(SegmentRule::ruleGroup)
                            .thenComparingInt(SegmentRule::evaluationOrder))
                    .toList();
            assertThat(alphabetical.get(0).segment()).isEqualTo(ALL_PUBLIC_PLANS_MA);
            for (GroupCase g : GROUPS) {
                if (!g.company().equals(THP)) continue;
                SegmentationResult seedOrder = evaluate(THP, g.matching(), false);
                SegmentationResult repoOrder = EVALUATOR.evaluate(alphabetical, MemberFacts.of(g.matching()), false);
                assertThat(repoOrder.flags()).as(g.label()).isEqualTo(seedOrder.flags());
                assertThat(repoOrder.flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
            }
        }

        @Test
        void booleanFactsInEverySpellingDriveTheSeededBooleanConditions() {
            for (Object spelling : List.of(true, "true", "TRUE", "Y", "y", "yes", "YES", "1", 1)) {
                Map<String, Object> attributes = facts("sourceSystemId", 2001, "planTypeCode", "SCO", "coverageActive", spelling);
                assertThat(evaluate(THP, attributes, false).isTrue(TMP_OTC_MA)).as(String.valueOf(spelling)).isTrue();
            }
            for (Object spelling : List.of(false, "false", "N", "no", "0", 0, "maybe", "", 2)) {
                Map<String, Object> attributes = facts("sourceSystemId", 2001, "planTypeCode", "SCO", "coverageActive", spelling);
                assertThat(evaluate(THP, attributes, false).isTrue(TMP_OTC_MA)).as(String.valueOf(spelling)).isFalse();
            }
            for (Object spelling : List.of(false, "false", "N", "no", "0", 0)) {
                Map<String, Object> attributes = facts("sourceSystemId", 2001, "planTypeCode", "MR", "coverageGroupTypeCode", 2, "hasActivePdp", spelling);
                assertThat(evaluate(THP, attributes, false).isTrue(ONLINE_BILL_PAY)).as(String.valueOf(spelling)).isTrue();
            }
            for (Object spelling : List.of(true, "Y", "1", "maybe", "")) {
                Map<String, Object> attributes = facts("sourceSystemId", 2001, "planTypeCode", "MR", "coverageGroupTypeCode", 2, "hasActivePdp", spelling);
                assertThat(evaluate(THP, attributes, false).isTrue(ONLINE_BILL_PAY)).as(String.valueOf(spelling)).isFalse();
            }
        }

        @Test
        void sourceSystemIdMatchesAsNumberOrString() {
            assertThat(evaluate(THP, facts("sourceSystemId", 2026, "subsidiary", "THPPMA"), false).isTrue(ALL_PUBLIC_PLANS_MA)).isTrue();
            assertThat(evaluate(THP, facts("sourceSystemId", "2026", "subsidiary", "thppma"), false).isTrue(ALL_PUBLIC_PLANS_MA)).isTrue();
            assertThat(evaluate(THP, facts("sourceSystemId", 2026L, "subsidiary", " THPPMA "), false).isTrue(ALL_PUBLIC_PLANS_MA)).isTrue();
            assertThat(evaluate(THP, facts("sourceSystemId", 2001, "subsidiary", "THPPMA"), false).isTrue(ALL_PUBLIC_PLANS_MA)).isFalse();
        }
    }
}
