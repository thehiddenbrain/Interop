package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.segmentation.SegmentationResult.ConditionTrace;
import org.point32health.memberprofile.segmentation.SegmentationResult.GroupTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.point32health.memberprofile.segmentation.Segment.ALL_PUBLIC_PLANS_MA;
import static org.point32health.memberprofile.segmentation.Segment.ALL_TUFTS_MEDICARE_PREFERRED;
import static org.point32health.memberprofile.segmentation.Segment.INTEROPERABILITY;
import static org.point32health.memberprofile.segmentation.Segment.ONLINE_BILL_PAY;
import static org.point32health.memberprofile.segmentation.Segment.OPTUM_RX_COVERAGE;
import static org.point32health.memberprofile.segmentation.Segment.PLAN_OF_CARE;
import static org.point32health.memberprofile.segmentation.Segment.TMP_OTC_MA;

/**
 * {@link SegmentRuleRepository} against the real PostgreSQL database and the V2 seed (catalog section 3).
 * Skipped unless {@code MEMBER_PROFILE_TEST_DB=true}; Flyway applies the migrations when the context starts.
 * <p>
 * Covers the query (order, company filter, active filter, unknown segment names skipped), the row mapping
 * (every column, IN/NOT_IN lists parsed into sets, numeric values parsed only for GREATER_THAN) and a golden
 * run: the rules read from the database are fed to the real {@link SegmentationEvaluator} with one matching
 * fact set for every rule group of the catalog, plus one fact set per condition that fails only that
 * condition. That proves the seed in the database is the workbook, not just the in-memory copy the unit
 * tests use. Tests that insert rows run inside a transaction that is rolled back, so the seed is untouched.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class SegmentRuleRepositoryTest {

    static final Company HPHC = Company.HPHC;
    static final Company THP = Company.THP;

    static final List<String> CONTRACT_KEYS = List.of("onlineBillPay", "optumRxCoverage", "allPublicPlansMa",
            "allTuftsMedicarePreferred", "tmpOtcMa", "planOfCare", "interoperability");

    /** {@code ORDER BY segment_name} in the database collation (C.UTF-8): plain byte order of the keys. */
    static final List<String> THP_SEGMENTS_IN_QUERY_ORDER = List.of("allPublicPlansMa", "allTuftsMedicarePreferred",
            "interoperability", "onlineBillPay", "optumRxCoverage", "planOfCare", "tmpOtcMa");

    static final List<String> PLAN_OF_CARE_GROUP_3_PLAN_CODES = List.of(
            "10GT100004", "10GT100005", "10GT100006", "10GT100007", "10GT100008", "10GT100009", "10GT100010",
            "10GT100011", "10GT100012", "10GT100013", "10GT100014", "10GT100015", "10PL100002", "10PL100003",
            "10PL100004", "10PL100005", "10PL100006", "10GT100016", "10GT100017", "10GT100018");

    private static final String INSERT_RULE = """
            INSERT INTO league_segmentation.segment_rule
              (segment_name, segment_category, league_capability, company, rule_group, evaluation_order,
               logical_operator, api_field, comparison_operator, rule_value, is_active)
            VALUES (:segmentName, 'Test', 'test', :company, :ruleGroup, :evaluationOrder,
                    NULL, :apiField, :operator, :ruleValue, :active)
            RETURNING segment_rule_id
            """;

    @Autowired SegmentRuleRepository repository;
    @Autowired SegmentationEvaluator evaluator;
    @Autowired JdbcClient jdbc;

    // ------------------------------------------------------------------------------------------ helpers

    /** Inserts one condition row (inside the test's transaction) and returns its identity value. */
    private long insertRule(String segmentName, String company, int group, int order,
                            String field, String operator, String value, boolean active) {
        return jdbc.sql(INSERT_RULE)
                .param("segmentName", segmentName)
                .param("company", company)
                .param("ruleGroup", group)
                .param("evaluationOrder", order)
                .param("apiField", field)
                .param("operator", operator)
                .param("ruleValue", value, Types.VARCHAR)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private long activeRowCountInTable(String company) {
        return jdbc.sql("SELECT count(*) FROM league_segmentation.segment_rule WHERE company = :company AND is_active")
                .param("company", company)
                .query(Long.class)
                .single();
    }

    private SegmentRule rule(Company company, Segment segment, int group, int order) {
        return repository.activeRulesFor(company).stream()
                .filter(r -> r.segment() == segment && r.ruleGroup() == group && r.evaluationOrder() == order)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + company + " row for " + segment.key() + " group " + group + " order " + order));
    }

    private SegmentRule ruleWithId(Company company, long id) {
        return repository.activeRulesFor(company).stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new AssertionError("row " + id + " not returned for " + company));
    }

    private static Map<String, Object> facts(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    private static List<Map.Entry<String, Object>> breaks(Object... fieldsAndValues) {
        List<Map.Entry<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < fieldsAndValues.length; i += 2) list.add(Map.entry((String) fieldsAndValues[i], fieldsAndValues[i + 1]));
        return list;
    }

    private SegmentationResult evaluate(Company company, Map<String, Object> attributes, boolean explain) {
        return evaluator.evaluate(repository.activeRulesFor(company), MemberFacts.of(attributes), explain);
    }

    private static GroupTrace traceOf(SegmentationResult result, Segment segment, int group) {
        return result.trace().stream()
                .filter(t -> t.segment().equals(segment.key()) && t.ruleGroup() == group)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no trace for " + segment.key() + " group " + group));
    }

    // ------------------------------------------------------------------------------------------ golden cases

    /**
     * One rule group of the catalog table: a fact set that satisfies every condition and, per condition in
     * evaluation order, the one fact to change so that only that condition fails.
     */
    record GroupCase(Segment segment, Company company, int group, Map<String, Object> matching,
                     List<Map.Entry<String, Object>> breakers) {
        String label() {
            return segment.key() + " " + company + " group " + group;
        }

        Map<String, Object> breaking(int index) {
            Map<String, Object> copy = new LinkedHashMap<>(matching);
            copy.put(breakers.get(index).getKey(), breakers.get(index).getValue());
            return copy;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private static GroupCase group(Segment segment, Company company, int group, Map<String, Object> matching,
                                   List<Map.Entry<String, Object>> breakers) {
        return new GroupCase(segment, company, group, matching, breakers);
    }

    /** Catalog section 3, one entry per row of the table. */
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
                    // condition 2 (CONTAINS EG) fails with no EG at all; condition 3 (NOT_CONTAINS PDP) fails with EG plus PDP
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

    static Stream<Arguments> groupCases() {
        return GROUPS.stream().map(g -> arguments(g.label(), g));
    }

    static Stream<Arguments> singleConditionFailures() {
        return GROUPS.stream().flatMap(g -> {
            List<Arguments> cases = new ArrayList<>();
            for (int i = 0; i < g.breakers().size(); i++) {
                Map.Entry<String, Object> breaker = g.breakers().get(i);
                cases.add(arguments(g.label() + " condition " + (i + 1) + " fails with " + breaker.getKey() + "=" + breaker.getValue(), g, i));
            }
            return cases.stream();
        });
    }

    // =========================================================================================== the query

    @Nested
    class ReadingTheSeed {

        @Test
        void thpRowsComeBackOrderedBySegmentRuleGroupAndEvaluationOrder() {
            List<SegmentRule> rules = repository.activeRulesFor(THP);

            assertThat(rules).hasSize(71).allSatisfy(r -> assertThat(r.company()).isEqualTo(THP.name()));
            assertThat(rules).isSortedAccordingTo(Comparator.comparing((SegmentRule r) -> r.segment().key())
                    .thenComparingInt(SegmentRule::ruleGroup)
                    .thenComparingInt(SegmentRule::evaluationOrder));
            assertThat(rules.stream().map(r -> r.segment().key()).distinct()).containsExactlyElementsOf(THP_SEGMENTS_IN_QUERY_ORDER);
            assertThat(rules.get(0)).extracting(SegmentRule::segment, SegmentRule::ruleGroup, SegmentRule::evaluationOrder)
                    .containsExactly(ALL_PUBLIC_PLANS_MA, 1, 1);
            assertThat(rules.get(70)).extracting(SegmentRule::segment, SegmentRule::ruleGroup, SegmentRule::evaluationOrder)
                    .containsExactly(TMP_OTC_MA, 5, 15);
        }

        @Test
        void rowsOfARuleGroupAreContiguous() {
            List<SegmentRule> rules = repository.activeRulesFor(THP);
            List<String> seen = new ArrayList<>();
            String previous = null;
            for (SegmentRule r : rules) {
                String key = r.segment().key() + "#" + r.ruleGroup();
                if (!key.equals(previous)) {
                    assertThat(seen).as("group %s appears twice, so the rows of a group are not contiguous", key).doesNotContain(key);
                    seen.add(key);
                    previous = key;
                }
            }
            assertThat(seen).hasSize(21);
        }

        @Test
        void thpPerSegmentRowCountsMatchTheCatalog() {
            Map<Segment, Long> rows = new LinkedHashMap<>();
            Map<Segment, Long> groups = new LinkedHashMap<>();
            for (SegmentRule r : repository.activeRulesFor(THP)) rows.merge(r.segment(), 1L, Long::sum);
            repository.activeRulesFor(THP).stream().map(r -> r.segment() + "#" + r.ruleGroup()).distinct()
                    .forEach(k -> groups.merge(Segment.valueOf(k.substring(0, k.indexOf('#'))), 1L, Long::sum));

            assertThat(rows).containsOnly(
                    Map.entry(ONLINE_BILL_PAY, 6L), Map.entry(OPTUM_RX_COVERAGE, 8L), Map.entry(ALL_PUBLIC_PLANS_MA, 2L),
                    Map.entry(ALL_TUFTS_MEDICARE_PREFERRED, 3L), Map.entry(TMP_OTC_MA, 15L), Map.entry(PLAN_OF_CARE, 21L),
                    Map.entry(INTEROPERABILITY, 16L));
            assertThat(groups).containsOnly(
                    Map.entry(ONLINE_BILL_PAY, 2L), Map.entry(OPTUM_RX_COVERAGE, 3L), Map.entry(ALL_PUBLIC_PLANS_MA, 1L),
                    Map.entry(ALL_TUFTS_MEDICARE_PREFERRED, 1L), Map.entry(TMP_OTC_MA, 5L), Map.entry(PLAN_OF_CARE, 5L),
                    Map.entry(INTEROPERABILITY, 4L));
        }

        @Test
        void hphcReturnsExactlyItsFourRows() {
            List<SegmentRule> rules = repository.activeRulesFor(HPHC);

            assertThat(rules).allSatisfy(r -> assertThat(r.company()).isEqualTo(HPHC.name()));
            assertThat(rules)
                    .extracting(SegmentRule::segment, SegmentRule::ruleGroup, SegmentRule::evaluationOrder,
                            SegmentRule::apiField, SegmentRule::operator, SegmentRule::ruleValue)
                    .containsExactly(
                            tuple(ONLINE_BILL_PAY, 1, 1, "dependentType", ComparisonOperator.EQUALS, "01"),
                            tuple(ONLINE_BILL_PAY, 1, 2, "memberCategory", ComparisonOperator.EQUALS, "B2I"),
                            tuple(ONLINE_BILL_PAY, 1, 3, "customerCategory", ComparisonOperator.NOT_EQUALS, "NH_39_WEEK"),
                            tuple(OPTUM_RX_COVERAGE, 1, 1, "basicMedicalDrugCoverageIndicator", ComparisonOperator.IS_TRUE, null));
        }

        @Test
        void hphcHasNoRulesForTheFiveThpOnlySegments() {
            assertThat(repository.activeRulesFor(HPHC)).extracting(SegmentRule::segment)
                    .doesNotContain(ALL_PUBLIC_PLANS_MA, ALL_TUFTS_MEDICARE_PREFERRED, TMP_OTC_MA, PLAN_OF_CARE, INTEROPERABILITY);
        }

        @ParameterizedTest(name = "memberTypeCode ''{0}''")
        @ValueSource(strings = {"ACME", "", "BOTH", "THPP", "TUFTS"})
        void unknownCompanyCodesCannotReachTheQuery(String code) {
            assertThat(Company.fromMemberTypeCode(code)).isEmpty();
        }

        @ParameterizedTest(name = "memberTypeCode ''{0}''")
        @ValueSource(strings = {"thp", "Hphc", " THP", "THP ", "HPHC"})
        void companyCodesAreNormalizedBeforeTheQuery(String code) {
            assertThat(Company.fromMemberTypeCode(code)).isPresent();
        }

        @Test
        void bothCompaniesTogetherAreTheSeventyFiveSeedRowsWithIdentityIdsInSeedOrder() {
            List<Long> ids = Stream.concat(repository.activeRulesFor(THP).stream(), repository.activeRulesFor(HPHC).stream())
                    .map(SegmentRule::id).sorted().toList();

            assertThat(ids).containsExactlyElementsOf(LongStream.rangeClosed(1, 75).boxed().toList());
            // the identity values follow the VALUES order of V2: HPHC onlineBillPay first, optumRxCoverage HPHC tenth
            assertThat(repository.activeRulesFor(HPHC)).extracting(SegmentRule::id).containsExactly(1L, 2L, 3L, 10L);
            assertThat(rule(THP, PLAN_OF_CARE, 3, 13).id()).isEqualTo(51L);
        }
    }

    // =========================================================================================== row mapping

    @Nested
    class RowMapping {

        @Test
        void planOfCareGroup3PlanCodeListIsParsedIntoTwentyCodes() {
            SegmentRule row = rule(THP, PLAN_OF_CARE, 3, 13);

            assertThat(row.segment()).isEqualTo(PLAN_OF_CARE);
            assertThat(row.company()).isEqualTo(THP.name());
            assertThat(row.ruleGroup()).isEqualTo(3);
            assertThat(row.evaluationOrder()).isEqualTo(13);
            assertThat(row.apiField()).isEqualTo("planCode");
            assertThat(row.operator()).isEqualTo(ComparisonOperator.IN);
            assertThat(row.ruleValue()).isEqualTo(String.join(",", PLAN_OF_CARE_GROUP_3_PLAN_CODES));
            assertThat(row.normalizedValue()).isEqualTo(row.ruleValue());
            assertThat(row.valueSet()).hasSize(20).containsExactlyInAnyOrderElementsOf(PLAN_OF_CARE_GROUP_3_PLAN_CODES);
            assertThat(row.valueSet()).doesNotContain("10GT100003", "10PL100001");
            assertThat(row.numericValue()).isNull();
        }

        @Test
        void notInListIsParsedIntoASet() {
            SegmentRule planType = rule(THP, ONLINE_BILL_PAY, 1, 2);
            assertThat(planType.operator()).isEqualTo(ComparisonOperator.NOT_IN);
            assertThat(planType.ruleValue()).isEqualTo("SCO,PDP");
            assertThat(planType.valueSet()).containsExactlyInAnyOrder("SCO", "PDP");

            SegmentRule product = rule(THP, OPTUM_RX_COVERAGE, 2, 6);
            assertThat(product.valueSet()).containsExactlyInAnyOrder("NPDP", "RPDP");
        }

        @Test
        void inListOfInteroperabilityGroup4HoldsThePlanTypes() {
            assertThat(rule(THP, INTEROPERABILITY, 4, 14).valueSet()).containsExactlyInAnyOrder("MR", "SCO", "MAP");
            assertThat(rule(THP, INTEROPERABILITY, 2, 7).valueSet()).containsExactlyInAnyOrder("PL", "GT", "MC");
            assertThat(rule(THP, PLAN_OF_CARE, 2, 8).valueSet())
                    .containsExactlyInAnyOrder("10GT100000", "10GT100001", "10GT100002", "10PL100001");
        }

        @Test
        void onlyInAndNotInRowsHaveAValueSet() {
            List<SegmentRule> all = Stream.concat(repository.activeRulesFor(THP).stream(), repository.activeRulesFor(HPHC).stream()).toList();

            List<SegmentRule> withSets = all.stream().filter(r -> !r.valueSet().isEmpty()).toList();
            assertThat(withSets).hasSize(9)
                    .allSatisfy(r -> assertThat(r.operator()).isIn(ComparisonOperator.IN, ComparisonOperator.NOT_IN));
            assertThat(all.stream().filter(r -> r.operator() == ComparisonOperator.IN || r.operator() == ComparisonOperator.NOT_IN))
                    .hasSize(9);
        }

        @Test
        void equalsRowKeepsTheRawValueAndNormalizesItOnce() {
            SegmentRule row = rule(THP, TMP_OTC_MA, 3, 8);

            assertThat(row.apiField()).isEqualTo("planCode");
            assertThat(row.operator()).isEqualTo(ComparisonOperator.EQUALS);
            assertThat(row.ruleValue()).isEqualTo("STD SAVER");
            assertThat(row.normalizedValue()).isEqualTo("STD SAVER");
            assertThat(row.valueSet()).isEmpty();
            assertThat(row.numericValue()).isNull();
        }

        @Test
        void containsAndNotContainsRowsCarryTheirSubstrings() {
            assertThat(rule(THP, ALL_TUFTS_MEDICARE_PREFERRED, 1, 2))
                    .extracting(SegmentRule::apiField, SegmentRule::operator, SegmentRule::normalizedValue)
                    .containsExactly("planCode", ComparisonOperator.CONTAINS, "EG");
            assertThat(rule(THP, ALL_TUFTS_MEDICARE_PREFERRED, 1, 3))
                    .extracting(SegmentRule::apiField, SegmentRule::operator, SegmentRule::normalizedValue)
                    .containsExactly("planCode", ComparisonOperator.NOT_CONTAINS, "PDP");
            assertThat(rule(THP, TMP_OTC_MA, 4, 11).normalizedValue()).isEqualTo("SMV");
        }

        @Test
        void booleanOperatorRowsHaveNoRuleValue() {
            SegmentRule isFalse = rule(THP, ONLINE_BILL_PAY, 1, 4);
            assertThat(isFalse.apiField()).isEqualTo("hasActivePdp");
            assertThat(isFalse.operator()).isEqualTo(ComparisonOperator.IS_FALSE);
            assertThat(isFalse.ruleValue()).isNull();
            assertThat(isFalse.normalizedValue()).isEmpty();
            assertThat(isFalse.valueSet()).isEmpty();
            assertThat(isFalse.numericValue()).isNull();

            SegmentRule isTrue = rule(HPHC, OPTUM_RX_COVERAGE, 1, 1);
            assertThat(isTrue.operator()).isEqualTo(ComparisonOperator.IS_TRUE);
            assertThat(isTrue.ruleValue()).isNull();
        }

        @Test
        void everySeededRowHasARuleValueExactlyWhenItsOperatorNeedsOne() {
            List<SegmentRule> all = Stream.concat(repository.activeRulesFor(THP).stream(), repository.activeRulesFor(HPHC).stream()).toList();
            assertThat(all).allSatisfy(r -> {
                if (r.operator().needsRuleValue()) assertThat(r.ruleValue()).as("row %d", r.id()).isNotBlank();
                else assertThat(r.ruleValue()).as("row %d", r.id()).isNull();
            });
        }

        @Test
        void numericValueIsNullForEverySeededRowBecauseOnlyGreaterThanParsesNumbers() {
            List<SegmentRule> all = Stream.concat(repository.activeRulesFor(THP).stream(), repository.activeRulesFor(HPHC).stream()).toList();
            assertThat(all).noneMatch(r -> r.operator() == ComparisonOperator.GREATER_THAN);
            assertThat(all).allSatisfy(r -> assertThat(r.numericValue()).isNull());
            // '2001' is numeric text, but an EQUALS row compares text, so it is not parsed
            assertThat(rule(THP, ONLINE_BILL_PAY, 1, 1).ruleValue()).isEqualTo("2001");
            assertThat(rule(THP, ONLINE_BILL_PAY, 1, 1).numericValue()).isNull();
        }

        @Test
        @Transactional
        void greaterThanRowParsesItsNumberAndLeavesANonNumericValueNull() {
            long numeric = insertRule("onlineBillPay", THP.name(), 9, 91, "age", "GREATER_THAN", "18", true);
            long decimal = insertRule("onlineBillPay", THP.name(), 9, 92, "premium", "GREATER_THAN", " 12.50 ", true);
            long words = insertRule("onlineBillPay", THP.name(), 9, 93, "age", "GREATER_THAN", "eighteen", true);

            assertThat(ruleWithId(THP, numeric).numericValue()).isEqualByComparingTo("18");
            assertThat(ruleWithId(THP, decimal).numericValue()).isEqualByComparingTo("12.50");
            assertThat(ruleWithId(THP, words).numericValue()).isNull();
            assertThat(ruleWithId(THP, words).normalizedValue()).isEqualTo("EIGHTEEN");
        }

        @Test
        @Transactional
        void ruleValueIsTrimmedAndUpperCasedAtReadTimeButKeptVerbatim() {
            long id = insertRule("onlineBillPay", THP.name(), 9, 91, "planCode", "EQUALS", "  std saver ", true);

            SegmentRule row = ruleWithId(THP, id);
            assertThat(row.ruleValue()).isEqualTo("  std saver ");
            assertThat(row.normalizedValue()).isEqualTo("STD SAVER");
        }

        @Test
        @Transactional
        void inListEntriesAreTrimmedUpperCasedAndEmptyEntriesDropped() {
            long id = insertRule("onlineBillPay", THP.name(), 9, 91, "product", "IN", " pl , gt ,, mc ,", true);

            assertThat(ruleWithId(THP, id).valueSet()).containsExactlyInAnyOrder("PL", "GT", "MC");
        }
    }

    // =========================================================================================== filtering

    @Nested
    class Filtering {

        @Test
        @Transactional
        void inactiveRowsAreExcluded() {
            long inactive = insertRule("onlineBillPay", THP.name(), 9, 91, "planTypeCode", "EQUALS", "ZZZ", false);
            long active = insertRule("onlineBillPay", THP.name(), 9, 92, "planTypeCode", "EQUALS", "YYY", true);

            List<SegmentRule> rules = repository.activeRulesFor(THP);

            assertThat(rules).extracting(SegmentRule::id).contains(active).doesNotContain(inactive);
            assertThat(rules).hasSize(72);
            assertThat(activeRowCountInTable(THP.name())).isEqualTo(72);
        }

        @Test
        @Transactional
        void deactivatingASeededRowRemovesItFromTheNextRead() {
            // no caching: the next request sees the change (rolled back after the test)
            int updated = jdbc.sql("UPDATE league_segmentation.segment_rule SET is_active = FALSE WHERE segment_rule_id = 51").update();
            assertThat(updated).isEqualTo(1);

            assertThat(repository.activeRulesFor(THP)).hasSize(70).extracting(SegmentRule::id).doesNotContain(51L);
        }

        @Test
        @Transactional
        void unknownCamelCaseSegmentNameIsSkippedNotAnError() {
            long id = insertRule("futureSegment", THP.name(), 1, 1, "someField", "EQUALS", "x", true);
            long other = insertRule("futureSegment", THP.name(), 1, 2, "otherField", "IS_TRUE", null, true);

            List<SegmentRule> rules = repository.activeRulesFor(THP);

            assertThat(rules).hasSize(71).extracting(SegmentRule::id).doesNotContain(id, other);
            assertThat(rules).extracting(SegmentRule::segment).containsOnly(Segment.values());
            assertThat(activeRowCountInTable(THP.name())).isEqualTo(73);
        }

        @Test
        @Transactional
        void unknownSegmentNameForHphcIsSkippedToo() {
            insertRule("futureSegment", HPHC.name(), 1, 1, "someField", "EQUALS", "x", true);

            assertThat(repository.activeRulesFor(HPHC)).hasSize(4);
        }

        @Test
        @Transactional
        void unknownComparisonOperatorRaisesRuleDataInvalid() {
            // the check constraint normally blocks such a row; drop it inside this rolled-back transaction to reach the mapper
            jdbc.sql("ALTER TABLE league_segmentation.segment_rule DROP CONSTRAINT ck_comparison_operator").update();
            long id = insertRule("onlineBillPay", THP.name(), 9, 91, "age", "BETWEEN", "1,5", true);

            assertThatThrownBy(() -> repository.activeRulesFor(THP))
                    .isInstanceOfSatisfying(MemberProfileException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID);
                        assertThat(e.getMessage()).contains("segment_rule " + id).contains("unknown comparison_operator 'BETWEEN'");
                    });
        }

        @Test
        @Transactional
        void newRowForAnotherCompanyDoesNotLeakIntoThisOne() {
            insertRule("onlineBillPay", HPHC.name(), 9, 91, "planTypeCode", "EQUALS", "YYY", true);

            assertThat(repository.activeRulesFor(THP)).hasSize(71);
            assertThat(repository.activeRulesFor(HPHC)).hasSize(5);
        }
    }

    // =========================================================================================== golden run

    /**
     * The seed as the database holds it, through the real evaluator: every rule group of catalog section 3
     * turns its segment on with a representative fact set, and each of its conditions alone turns it off.
     */
    @Nested
    class SeededGoldenRun {

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.point32health.memberprofile.segmentation.SegmentRuleRepositoryTest#groupCases")
        void matchingFactsTurnTheSegmentOn(String label, GroupCase g) {
            SegmentationResult result = evaluate(g.company(), g.matching(), true);

            assertThat(result.isTrue(g.segment())).as("%s should be true", label).isTrue();
            assertThat(result.flags().get(g.segment().key())).isTrue();
            GroupTrace trace = traceOf(result, g.segment(), g.group());
            assertThat(trace.matched()).isTrue();
            assertThat(trace.conditions()).as("the case must have one breaker per seeded condition").hasSize(g.breakers().size());
            assertThat(trace.conditions()).allSatisfy(c -> assertThat(c.passed()).as("condition %s", c).isTrue());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.point32health.memberprofile.segmentation.SegmentRuleRepositoryTest#groupCases")
        void matchingFactsTurnTheSegmentOnWithoutExplainToo(String label, GroupCase g) {
            SegmentationResult result = evaluate(g.company(), g.matching(), false);

            assertThat(result.isTrue(g.segment())).isTrue();
            assertThat(result.trace()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.point32health.memberprofile.segmentation.SegmentRuleRepositoryTest#singleConditionFailures")
        void eachConditionAloneBreaksItsGroup(String label, GroupCase g, int index) {
            SegmentationResult result = evaluate(g.company(), g.breaking(index), true);

            GroupTrace trace = traceOf(result, g.segment(), g.group());
            assertThat(trace.matched()).as("%s should fail", label).isFalse();
            List<ConditionTrace> conditions = trace.conditions();
            for (int i = 0; i < conditions.size(); i++) {
                assertThat(conditions.get(i).passed())
                        .as("condition %d (%s %s %s) of %s", i + 1, conditions.get(i).apiField(), conditions.get(i).operator(),
                                conditions.get(i).ruleValue(), g.label())
                        .isEqualTo(i != index);
            }
            assertThat(conditions.get(index).apiField()).isEqualTo(g.breakers().get(index).getKey());
        }

        @ParameterizedTest(name = "{0}: removing any one fact turns the group off")
        @MethodSource("org.point32health.memberprofile.segmentation.SegmentRuleRepositoryTest#groupCases")
        void aMissingFactNeverSatisfiesACondition(String label, GroupCase g) {
            for (String field : g.matching().keySet()) {
                Map<String, Object> without = new LinkedHashMap<>(g.matching());
                without.remove(field);
                GroupTrace trace = traceOf(evaluate(g.company(), without, true), g.segment(), g.group());
                assertThat(trace.matched()).as("%s without %s", label, field).isFalse();
                assertThat(trace.conditions()).filteredOn(c -> c.apiField().equals(field))
                        .allSatisfy(c -> assertThat(c.passed()).isFalse());
            }
        }

        @Test
        void everyRuleGroupInTheDatabaseHasAGoldenCase() {
            Set<String> inDatabase = new java.util.TreeSet<>();
            for (Company company : List.of(THP, HPHC)) {
                for (SegmentRule r : repository.activeRulesFor(company)) {
                    inDatabase.add(r.segment().key() + " " + company + " group " + r.ruleGroup());
                }
            }
            Set<String> inCases = new java.util.TreeSet<>();
            GROUPS.forEach(g -> inCases.add(g.label()));

            assertThat(inDatabase).hasSize(23).containsExactlyElementsOf(inCases);
        }

        @ParameterizedTest
        @EnumSource(value = Segment.class, names = {"ALL_PUBLIC_PLANS_MA", "ALL_TUFTS_MEDICARE_PREFERRED", "TMP_OTC_MA", "PLAN_OF_CARE", "INTEROPERABILITY"})
        void hphcNeverTurnsOnASegmentItHasNoRulesFor(Segment segment) {
            Map<String, Object> everyThpFact = new LinkedHashMap<>();
            GROUPS.stream().filter(g -> g.segment() == segment).forEach(g -> everyThpFact.putAll(g.matching()));

            SegmentationResult hphc = evaluate(HPHC, everyThpFact, true);
            SegmentationResult thp = evaluate(THP, everyThpFact, false);

            assertThat(hphc.isTrue(segment)).isFalse();
            assertThat(hphc.trace()).noneMatch(t -> t.segment().equals(segment.key()));
            assertThat(thp.isTrue(segment)).as("the same facts do turn %s on for THP", segment).isTrue();
        }

        @Test
        void hphcMemberOfTheApiExampleGetsOnlineBillPayOnly() {
            SegmentationResult result = evaluate(HPHC, facts("dependentType", "01", "memberCategory", "B2I",
                    "customerCategory", "GROUP", "basicMedicalDrugCoverageIndicator", "N"), false);

            assertThat(result.flags()).containsExactly(
                    Map.entry("onlineBillPay", true), Map.entry("optumRxCoverage", false), Map.entry("allPublicPlansMa", false),
                    Map.entry("allTuftsMedicarePreferred", false), Map.entry("tmpOtcMa", false), Map.entry("planOfCare", false),
                    Map.entry("interoperability", false));
        }

        @Test
        void hphcNh39WeekCustomerLosesOnlineBillPay() {
            SegmentationResult result = evaluate(HPHC, facts("dependentType", "01", "memberCategory", "B2I",
                    "customerCategory", "NH_39_WEEK", "basicMedicalDrugCoverageIndicator", "Y"), false);

            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
        }

        @Test
        void thpMedicarePreferredMemberOfTheEndToEndTestGetsFourSegments() {
            SegmentationResult result = evaluate(THP, facts("sourceSystemId", 2001, "planTypeCode", "MR", "planCode", "10EG1234",
                    "coverageActive", true, "coverageStarted", true, "groupProductEffectiveForCoverage", true,
                    "hasPharmacyRider", true, "product", "MAPD", "coverageGroupTypeCode", "2", "hasActivePdp", false), true);

            assertThat(result.flags()).containsExactly(
                    Map.entry("onlineBillPay", true), Map.entry("optumRxCoverage", true), Map.entry("allPublicPlansMa", false),
                    Map.entry("allTuftsMedicarePreferred", true), Map.entry("tmpOtcMa", false), Map.entry("planOfCare", false),
                    Map.entry("interoperability", true));
            assertThat(traceOf(result, INTEROPERABILITY, 4).matched()).isTrue();
            assertThat(traceOf(result, ONLINE_BILL_PAY, 1).matched()).isTrue();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 1).matched()).isTrue();
        }

        @Test
        void scoMemberFailsOnlineBillPayButGetsOtcAndInteroperability() {
            SegmentationResult result = evaluate(THP, facts("sourceSystemId", 2001, "planTypeCode", "SCO",
                    "coverageGroupTypeCode", "2", "hasActivePdp", false, "coverageActive", true,
                    "coverageStarted", true, "groupProductEffectiveForCoverage", true), true);

            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(traceOf(result, ONLINE_BILL_PAY, 1).conditions())
                    .extracting(ConditionTrace::apiField, ConditionTrace::passed)
                    .containsExactly(tuple("sourceSystemId", true), tuple("planTypeCode", false),
                            tuple("coverageGroupTypeCode", true), tuple("hasActivePdp", true));
            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            assertThat(traceOf(result, TMP_OTC_MA, 1).matched()).isTrue();
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(traceOf(result, INTEROPERABILITY, 4).matched()).isTrue();
        }

        @Test
        void pdpProductMemberGetsOptumRxThroughGroup3Only() {
            SegmentationResult result = evaluate(THP, facts("sourceSystemId", 2001, "hasPharmacyRider", true,
                    "hasTmpMedicalCoverage", true, "hasActivePdp", true, "product", "PDP"), true);

            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isTrue();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 1).matched()).isTrue();   // PDP is not in NPDP,RPDP
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 2).matched()).isTrue();
            assertThat(traceOf(result, OPTUM_RX_COVERAGE, 3).matched()).isTrue();

            SegmentationResult npdp = evaluate(THP, facts("sourceSystemId", 2001, "hasPharmacyRider", true,
                    "hasTmpMedicalCoverage", true, "hasActivePdp", true, "product", "NPDP"), true);
            assertThat(npdp.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            assertThat(traceOf(npdp, OPTUM_RX_COVERAGE, 1).matched()).isFalse();
            assertThat(traceOf(npdp, OPTUM_RX_COVERAGE, 2).matched()).isFalse();
            assertThat(traceOf(npdp, OPTUM_RX_COVERAGE, 3).matched()).isFalse();

            SegmentationResult standalonePdp = evaluate(THP, facts("sourceSystemId", 2001, "product", "PDP"), true);
            assertThat(standalonePdp.isTrue(OPTUM_RX_COVERAGE)).isTrue();
            assertThat(traceOf(standalonePdp, OPTUM_RX_COVERAGE, 1).matched()).isFalse();
            assertThat(traceOf(standalonePdp, OPTUM_RX_COVERAGE, 2).matched()).isFalse();
            assertThat(traceOf(standalonePdp, OPTUM_RX_COVERAGE, 3).matched()).isTrue();
        }

        @Test
        void dmaMemberOfSourceSystem2064GetsOtcPlanOfCareAndInteroperabilityTogether() {
            SegmentationResult result = evaluate(THP, facts("subsidiary", "THPPMA", "sourceSystemId", 2064, "product", "DMA",
                    "coverageActive", true, "planOfCareCoverageEligible", true), false);

            assertThat(result.isTrue(TMP_OTC_MA)).isTrue();
            assertThat(result.isTrue(PLAN_OF_CARE)).isTrue();
            assertThat(result.isTrue(INTEROPERABILITY)).isTrue();
            assertThat(result.isTrue(ONLINE_BILL_PAY)).isFalse();
            assertThat(result.isTrue(OPTUM_RX_COVERAGE)).isFalse();
            assertThat(result.isTrue(ALL_PUBLIC_PLANS_MA)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(Company.class)
        void emptyAttributesGiveAllFalse(Company company) {
            SegmentationResult result = evaluate(company, Map.of(), true);

            assertThat(result.flags().values()).containsOnly(false);
            assertThat(result.trace()).allSatisfy(t -> assertThat(t.matched()).isFalse());
        }

        @ParameterizedTest
        @EnumSource(Company.class)
        void flagsAlwaysHaveTheSevenKeysInContractOrder(Company company) {
            assertThat(evaluate(company, Map.of(), false).flags().keySet()).containsExactlyElementsOf(CONTRACT_KEYS);
        }

        @Test
        void explainReportsEveryGroupOfTheCompany() {
            assertThat(evaluate(THP, Map.of(), true).trace()).hasSize(21);
            assertThat(evaluate(HPHC, Map.of(), true).trace()).hasSize(2);
        }
    }
}
