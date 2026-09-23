package org.point32health.memberprofile.db;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Flyway migrations and the seed as PostgreSQL holds them: V1..V4 applied, the row counts of catalog
 * sections 3 and 4, the unique index and every check constraint of {@code segment_rule} and
 * {@code family_permission_rule}, the 26-key permission catalog, the action codes and the relationship codes.
 * Skipped unless {@code MEMBER_PROFILE_TEST_DB=true}. Tests that attempt writes run inside a transaction that
 * is rolled back, so a constraint that unexpectedly lets a row through still leaves the seed untouched.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class SchemaAndSeedTest {

    static final List<String> CATALOG_KEYS = List.of(
            "benefits", "benefits.accumulator", "benefits.activePolicy", "benefits.coverage", "benefits.idCard", "benefits.spendingAccount",
            "claims", "claims.authorization", "claims.claim", "claims.referral",
            "demographic", "demographic.contract", "demographic.memberOther", "demographic.memberSelf",
            "documents", "documents.letter", "documents.planDocument", "documents.taxDocument",
            "forms", "forms.capeCodHealthcare", "forms.coordinationOfBenefits", "forms.designationOfRepresentative", "forms.medicalReimbursement",
            "profile", "profile.raceEthnicityLanguage", "profile.sexualOrientationGenderIdentity");

    private static final String INSERT_SEGMENT_RULE = """
            INSERT INTO league_segmentation.segment_rule
              (segment_name, segment_category, league_capability, company, rule_group, evaluation_order,
               logical_operator, api_field, comparison_operator, rule_value, is_active)
            VALUES (:segmentName, 'Test', 'test', :company, :ruleGroup, :evaluationOrder,
                    :logicalOperator, 'someField', :operator, :ruleValue, TRUE)
            """;

    private static final String INSERT_PERMISSION_RULE = """
            INSERT INTO family_permission.family_permission_rule
              (permission_family, permission_key, actor_relationship, viewing_relationship, age_description,
               minimum_age, maximum_age, action_codes, access_status, consent_required, administrative_consent,
               revocable, masked_data, is_active, source_sheet, source_row, source_access_text, notes)
            VALUES (:family, :key, :actor, :viewing, 'test', :minimumAge, :maximumAge, '{1}', :status,
                    FALSE, FALSE, FALSE, FALSE, TRUE, 'Test', 0, NULL, NULL)
            """;

    @Autowired JdbcClient jdbc;

    // ------------------------------------------------------------------------------------------ helpers

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void insertSegmentRule(String segmentName, String company, int group, int order, String logicalOperator,
                                   String operator, String ruleValue) {
        jdbc.sql(INSERT_SEGMENT_RULE)
                .param("segmentName", segmentName)
                .param("company", company)
                .param("ruleGroup", group)
                .param("evaluationOrder", order)
                .param("logicalOperator", logicalOperator, Types.VARCHAR)
                .param("operator", operator)
                .param("ruleValue", ruleValue, Types.VARCHAR)
                .update();
    }

    private void insertPermissionRule(String family, String key, String actor, String viewing,
                                      Integer minimumAge, Integer maximumAge, String status) {
        jdbc.sql(INSERT_PERMISSION_RULE)
                .param("family", family)
                .param("key", key)
                .param("actor", actor)
                .param("viewing", viewing)
                .param("minimumAge", minimumAge, Types.SMALLINT)
                .param("maximumAge", maximumAge, Types.SMALLINT)
                .param("status", status)
                .update();
    }

    // =========================================================================================== migrations

    @Nested
    class Migrations {

        @Test
        void v1ToV4AppliedSuccessfully() {
            List<Map<String, Object>> history = jdbc.sql(
                            "SELECT version, description, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")
                    .query().listOfRows();

            assertThat(history).extracting(r -> r.get("version"), r -> r.get("description"), r -> r.get("success"))
                    .containsExactly(
                            tuple("1", "schema", true),
                            tuple("2", "seed segmentation", true),
                            tuple("3", "seed permission reference", true),
                            tuple("4", "seed family permission rules", true));
        }

        @Test
        void noMigrationFailed() {
            assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isZero();
        }

        @Test
        void bothSchemasExist() {
            List<String> schemas = jdbc.sql("SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('league_segmentation', 'family_permission') ORDER BY 1")
                    .query(String.class).list();

            assertThat(schemas).containsExactly("family_permission", "league_segmentation");
        }

        @Test
        void allSixTablesExist() {
            List<String> tables = jdbc.sql("SELECT table_schema || '.' || table_name FROM information_schema.tables WHERE table_schema IN ('league_segmentation', 'family_permission') ORDER BY 1")
                    .query(String.class).list();

            assertThat(tables).containsExactly(
                    "family_permission.action_code", "family_permission.family_consent", "family_permission.family_permission_rule",
                    "family_permission.permission_catalog", "family_permission.relationship_code", "league_segmentation.segment_rule");
        }

        @Test
        void thePerRequestIndexesExist() {
            List<String> indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname IN ('league_segmentation', 'family_permission') ORDER BY 1")
                    .query(String.class).list();

            assertThat(indexes).contains("ux_segment_rule_expression_order", "ix_segment_rule_company_active",
                    "ix_family_permission_rule_actor_active", "ix_family_consent_actor");
        }

        @Test
        void familyConsentIsReadableAndUsesTheExpectedColumns() {
            assertThatCode(() -> jdbc.sql("SELECT consent_id, actor_member_id, viewed_member_id, permission_family, granted_at, granted_by, revoked_at FROM family_permission.family_consent LIMIT 1")
                    .query().listOfRows()).doesNotThrowAnyException();
        }
    }

    // =========================================================================================== segment_rule seed

    @Nested
    class SegmentRuleSeed {

        @Test
        void hasSeventyFiveRowsAllActive() {
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule")).isEqualTo(75);
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE is_active")).isEqualTo(75);
        }

        @Test
        void perCompanyCountsMatchTheCatalog() {
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE company = 'HPHC'")).isEqualTo(4);
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE company = 'THP'")).isEqualTo(71);
        }

        @ParameterizedTest(name = "{0} {1}: {2} conditions in {3} groups")
        @CsvSource({
                "onlineBillPay, HPHC, 3, 1",
                "onlineBillPay, THP, 6, 2",
                "optumRxCoverage, HPHC, 1, 1",
                "optumRxCoverage, THP, 8, 3",
                "allPublicPlansMa, THP, 2, 1",
                "allTuftsMedicarePreferred, THP, 3, 1",
                "tmpOtcMa, THP, 15, 5",
                "planOfCare, THP, 21, 5",
                "interoperability, THP, 16, 4"})
        void perSegmentAndCompanyCountsMatchTheCatalog(String segment, String company, int rows, int groups) {
            Map<String, Object> counts = jdbc.sql("""
                            SELECT count(*) AS row_count, count(DISTINCT rule_group) AS group_count
                              FROM league_segmentation.segment_rule
                             WHERE segment_name = :segment AND company = :company
                            """)
                    .param("segment", segment).param("company", company)
                    .query().singleRow();

            assertThat(((Number) counts.get("row_count")).intValue()).isEqualTo(rows);
            assertThat(((Number) counts.get("group_count")).intValue()).isEqualTo(groups);
        }

        @Test
        void exactlySevenSegmentNamesAndTwentyThreeGroups() {
            List<String> segments = jdbc.sql("SELECT DISTINCT segment_name FROM league_segmentation.segment_rule ORDER BY 1")
                    .query(String.class).list();
            assertThat(segments).containsExactly("allPublicPlansMa", "allTuftsMedicarePreferred", "interoperability",
                    "onlineBillPay", "optumRxCoverage", "planOfCare", "tmpOtcMa");
            assertThat(count("SELECT count(*) FROM (SELECT DISTINCT segment_name, company, rule_group FROM league_segmentation.segment_rule) g"))
                    .isEqualTo(23);
        }

        @Test
        void hphcHasNoRowsForTheFiveThpOnlySegments() {
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE company = 'HPHC' AND segment_name IN "
                    + "('allPublicPlansMa', 'allTuftsMedicarePreferred', 'tmpOtcMa', 'planOfCare', 'interoperability')")).isZero();
        }

        @Test
        void logicalOperatorIsNullOnlyOnTheLastConditionOfEachSegmentAndCompany() {
            // 9 (segment, company) pairs end with NULL; 23 groups minus 9 pairs = 14 groups end with OR; the rest are AND
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE logical_operator IS NULL")).isEqualTo(9);
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE logical_operator = 'OR'")).isEqualTo(14);
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE logical_operator = 'AND'")).isEqualTo(52);
            assertThat(count("""
                    SELECT count(*) FROM league_segmentation.segment_rule r
                     WHERE logical_operator IS NULL
                       AND evaluation_order <> (SELECT max(evaluation_order) FROM league_segmentation.segment_rule
                                                 WHERE segment_name = r.segment_name AND company = r.company)
                    """)).isZero();
        }

        @Test
        void ruleValueIsPresentExactlyWhenTheOperatorNeedsOne() {
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE comparison_operator IN ('IS_TRUE', 'IS_FALSE') AND rule_value IS NOT NULL")).isZero();
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE comparison_operator NOT IN ('IS_TRUE', 'IS_FALSE') AND (rule_value IS NULL OR btrim(rule_value) = '')")).isZero();
            // 21 IS_TRUE rows (1 HPHC + 20 THP) and the single IS_FALSE row (onlineBillPay THP hasActivePdp)
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE comparison_operator = 'IS_TRUE'")).isEqualTo(21);
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE comparison_operator = 'IS_FALSE'")).isEqualTo(1);
        }

        @Test
        void planOfCareGroup3ListsTwentyPlanCodes() {
            String value = jdbc.sql("SELECT rule_value FROM league_segmentation.segment_rule WHERE segment_name = 'planOfCare' AND rule_group = 3 AND api_field = 'planCode'")
                    .query(String.class).single();

            assertThat(value.split(",")).hasSize(20).contains("10GT100004", "10GT100018", "10PL100002");
        }
    }

    // =========================================================================================== segment_rule constraints

    @Nested
    @Transactional
    class SegmentRuleConstraints {

        @Test
        void uniqueIndexRejectsADuplicateSegmentCompanyAndEvaluationOrder() {
            // onlineBillPay / THP / evaluation_order 1 already exists (group 1); a different group does not help
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 3, 1, null, "EQUALS", "x"))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("ux_segment_rule_expression_order");
        }

        @Test
        void sameEvaluationOrderIsAllowedForTheOtherCompany() {
            // HPHC onlineBillPay has orders 1..3; order 4 is free
            assertThatCode(() -> insertSegmentRule("onlineBillPay", "HPHC", 2, 4, null, "EQUALS", "x")).doesNotThrowAnyException();
            assertThat(count("SELECT count(*) FROM league_segmentation.segment_rule WHERE company = 'HPHC'")).isEqualTo(5);
        }

        @ParameterizedTest(name = "company ''{0}''")
        @ValueSource(strings = {"ACME", "thp", "hphc", "BOTH", ""})
        void checkConstraintRejectsABadCompany(String company) {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", company, 9, 90, null, "EQUALS", "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_segment_company");
        }

        @ParameterizedTest(name = "operator ''{0}''")
        @ValueSource(strings = {"BETWEEN", "equals", "LIKE", "IS_NULL", "GT", ""})
        void checkConstraintRejectsABadOperator(String operator) {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 9, 90, null, operator, "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_comparison_operator");
        }

        @ParameterizedTest(name = "operator {0}")
        @ValueSource(strings = {"EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "CONTAINS", "NOT_CONTAINS", "IS_TRUE", "IS_FALSE", "GREATER_THAN"})
        void everyCatalogOperatorIsAccepted(String operator) {
            String value = operator.equals("IS_TRUE") || operator.equals("IS_FALSE") ? null : "x";
            assertThatCode(() -> insertSegmentRule("onlineBillPay", "THP", 9, 90, null, operator, value)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "segment_name ''{0}''")
        @ValueSource(strings = {"OnlineBillPay", "online_bill_pay", "online-bill-pay", "1stSegment", "online bill pay", "", "ONLINEBILLPAY"})
        void checkConstraintRejectsASegmentNameThatIsNotLowerCamelCase(String segmentName) {
            assertThatThrownBy(() -> insertSegmentRule(segmentName, "THP", 9, 90, null, "EQUALS", "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_segment_name_camel_case");
        }

        @Test
        void lowerCamelCaseSegmentNamesOutsideTheSevenAreAcceptedByTheDatabase() {
            // the service skips them at read time; the table itself only enforces the spelling
            assertThatCode(() -> insertSegmentRule("futureSegment2", "THP", 1, 1, null, "EQUALS", "x")).doesNotThrowAnyException();
        }

        @Test
        void checkConstraintRejectsAMissingRuleValueForEquals() {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 9, 90, null, "EQUALS", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_rule_value_present");
        }

        /** {@code btrim} strips spaces only, so "blank" here means empty or spaces; a tab-only value is not caught. */
        @ParameterizedTest(name = "blank rule_value ''{0}'' for {1}")
        @CsvSource({"'', EQUALS", "'   ', IN", "'', GREATER_THAN", "' ', CONTAINS", "'  ', NOT_IN"})
        void checkConstraintRejectsABlankRuleValueForAnOperatorThatNeedsOne(String value, String operator) {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 9, 90, null, operator, value))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_rule_value_present");
        }

        @Test
        void isTrueAndIsFalseAcceptANullRuleValue() {
            assertThatCode(() -> {
                insertSegmentRule("onlineBillPay", "THP", 9, 90, "AND", "IS_TRUE", null);
                insertSegmentRule("onlineBillPay", "THP", 9, 91, null, "IS_FALSE", null);
            }).doesNotThrowAnyException();
        }

        @Test
        void checkConstraintRejectsANonPositiveRuleGroupOrEvaluationOrder() {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 0, 90, null, "EQUALS", "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_rule_group_positive");
        }

        @Test
        void checkConstraintRejectsANonPositiveEvaluationOrder() {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 9, 0, null, "EQUALS", "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_evaluation_order_positive");
        }

        @Test
        void checkConstraintRejectsABadLogicalOperator() {
            assertThatThrownBy(() -> insertSegmentRule("onlineBillPay", "THP", 9, 90, "XOR", "EQUALS", "x"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_logical_operator");
        }
    }

    // =========================================================================================== family permission seed

    @Nested
    class FamilyPermissionSeed {

        @Test
        void permissionCatalogHasTheTwentySixKeys() {
            List<String> keys = jdbc.sql("SELECT permission_key FROM family_permission.permission_catalog ORDER BY permission_key")
                    .query(String.class).list();

            assertThat(keys).hasSize(26).containsExactlyElementsOf(CATALOG_KEYS);
        }

        @Test
        void permissionCatalogFamilyIsTheKeyPrefixAndHasSixParents() {
            assertThat(count("SELECT count(*) FROM family_permission.permission_catalog WHERE permission_family <> split_part(permission_key, '.', 1)")).isZero();
            List<String> parents = jdbc.sql("SELECT permission_key FROM family_permission.permission_catalog WHERE position('.' IN permission_key) = 0 ORDER BY 1")
                    .query(String.class).list();
            assertThat(parents).containsExactly("benefits", "claims", "demographic", "documents", "forms", "profile");
            assertThat(count("SELECT count(DISTINCT permission_family) FROM family_permission.permission_catalog")).isEqualTo(6);
        }

        @Test
        void everySeededRuleRowReferencesACatalogKey() {
            assertThat(count("""
                    SELECT count(*) FROM family_permission.family_permission_rule r
                     WHERE NOT EXISTS (SELECT 1 FROM family_permission.permission_catalog c WHERE c.permission_key = r.permission_key)
                    """)).isZero();
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule r JOIN family_permission.permission_catalog c USING (permission_key)"))
                    .isEqualTo(63);
        }

        @Test
        void seededRuleRowCountsMatchTheCatalog() {
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule")).isEqualTo(63);
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule WHERE is_active")).isEqualTo(53);
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule WHERE NOT is_active")).isEqualTo(10);
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule WHERE NOT is_active AND access_status <> 'NOT_APPLICABLE'")).isZero();
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule WHERE is_active AND access_status = 'NOT_APPLICABLE'")).isZero();
        }

        @Test
        void seededRuleRowsPerActorMatchTheCatalog() {
            List<Map<String, Object>> rows = jdbc.sql("""
                            SELECT actor_relationship, count(*) FILTER (WHERE is_active) AS active, count(*) AS total
                              FROM family_permission.family_permission_rule GROUP BY 1 ORDER BY 1
                            """)
                    .query().listOfRows();

            assertThat(rows).extracting(r -> r.get("actor_relationship"), r -> ((Number) r.get("active")).intValue(), r -> ((Number) r.get("total")).intValue())
                    .containsExactly(
                            tuple("Adult Child", 5, 6),
                            tuple("Child (minor)", 0, 3),
                            tuple("Child (teenager)", 4, 6),
                            tuple("Ex-Spouse", 10, 12),
                            tuple("Spouse", 3, 5),
                            tuple("Subscriber", 31, 31));
        }

        @Test
        void seededFamilyAlwaysMatchesTheKeyPrefix() {
            assertThat(count("SELECT count(*) FROM family_permission.family_permission_rule WHERE permission_family <> split_part(permission_key, '.', 1)")).isZero();
        }

        @Test
        void consentRequiredRowsAreTheTwoTeenClaimsRows() {
            List<Map<String, Object>> rows = jdbc.sql("""
                            SELECT permission_key, access_status, masked_data FROM family_permission.family_permission_rule
                             WHERE consent_required ORDER BY permission_key
                            """)
                    .query().listOfRows();

            assertThat(rows).extracting(r -> r.get("permission_key"), r -> r.get("access_status"), r -> r.get("masked_data"))
                    .containsExactly(
                            tuple("claims.authorization", "CONSENT_REQUIRED", false),
                            tuple("claims.claim", "MASKED_ACCESS", true));
        }

        @Test
        void actionCodeHasTheFourCodes() {
            List<Map<String, Object>> rows = jdbc.sql("SELECT action_code, description FROM family_permission.action_code ORDER BY action_code")
                    .query().listOfRows();

            assertThat(rows).hasSize(4)
                    .extracting(r -> ((Number) r.get("action_code")).intValue(), r -> r.get("description"))
                    .containsExactly(tuple(1, "View"), tuple(2, "Edit"), tuple(3, "Download"), tuple(4, "Delete"));
        }

        @Test
        void relationshipCodeHasTheFourCodes() {
            List<Map<String, Object>> rows = jdbc.sql("SELECT relationship_code, relationship FROM family_permission.relationship_code ORDER BY relationship_code")
                    .query().listOfRows();

            assertThat(rows).extracting(r -> r.get("relationship_code"), r -> r.get("relationship"))
                    .containsExactly(tuple("01", "Subscriber"), tuple("02", "Spouse"), tuple("03", "Child"), tuple("04", "Ex-Spouse"));
        }

        @Test
        void everySeededActionCodeIsACatalogActionCode() {
            assertThat(count("""
                    SELECT count(*) FROM family_permission.family_permission_rule r
                     WHERE EXISTS (SELECT 1 FROM unnest(r.action_codes) AS code
                                    WHERE code NOT IN (SELECT action_code FROM family_permission.action_code))
                    """)).isZero();
        }
    }

    // =========================================================================================== family permission constraints

    @Nested
    @Transactional
    class FamilyPermissionRuleConstraints {

        @ParameterizedTest(name = "actor ''{0}''")
        @ValueSource(strings = {"Grandparent", "subscriber", "Child", "Adult child", "Child (Teenager)", ""})
        void checkConstraintRejectsABadActor(String actor) {
            assertThatThrownBy(() -> insertPermissionRule("claims", "claims.claim", actor, "Self", null, null, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_actor_relationship");
        }

        @ParameterizedTest(name = "viewing ''{0}''")
        @ValueSource(strings = {"Cousin", "self", "Adult dependent", "Child (minor)", "All other", ""})
        void checkConstraintRejectsABadViewingRelationship(String viewing) {
            assertThatThrownBy(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", viewing, null, null, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_viewing_relationship");
        }

        @ParameterizedTest(name = "access_status ''{0}''")
        @ValueSource(strings = {"PARTIAL_ACCESS", "full_access", "N/A", "CONSENT", ""})
        void checkConstraintRejectsABadAccessStatus(String status) {
            assertThatThrownBy(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", "Self", null, null, status))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_access_status");
        }

        @ParameterizedTest(name = "access_status {0}")
        @ValueSource(strings = {"FULL_ACCESS", "NO_ACCESS", "CONSENT_REQUIRED", "CONSENT_REQUIRED_ADMIN", "REVOCABLE_ACCESS",
                "MASKED_ACCESS", "NOT_APPLICABLE", "REVIEW_REQUIRED"})
        void everyCatalogAccessStatusIsAccepted(String status) {
            assertThatCode(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", "Self", null, null, status))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "actor {0}")
        @ValueSource(strings = {"Subscriber", "Spouse", "Ex-Spouse", "Adult Child", "Child (teenager)", "Child (minor)"})
        void everyCatalogActorIsAccepted(String actor) {
            assertThatCode(() -> insertPermissionRule("claims", "claims.claim", actor, "Self", null, null, "FULL_ACCESS"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "viewing {0}")
        @ValueSource(strings = {"Self", "Subscriber", "Spouse", "Child", "Adult dependent (any relationship)", "All other family members"})
        void everyCatalogViewingRelationshipIsAccepted(String viewing) {
            assertThatCode(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", viewing, null, null, "FULL_ACCESS"))
                    .doesNotThrowAnyException();
        }

        @Test
        void checkConstraintRejectsAFamilyThatDoesNotMatchTheKeyPrefix() {
            assertThatThrownBy(() -> insertPermissionRule("claims", "benefits.idCard", "Subscriber", "Self", null, null, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_family_matches_key");
        }

        @Test
        void checkConstraintRejectsAParentKeyWithAnotherFamily() {
            assertThatThrownBy(() -> insertPermissionRule("benefits", "claims", "Subscriber", "Self", null, null, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_family_matches_key");
        }

        @Test
        void foreignKeyRejectsAKeyOutsideTheCatalog() {
            assertThatThrownBy(() -> insertPermissionRule("claims", "claims.appeal", "Subscriber", "Self", null, null, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("family_permission_rule_permission_key_fkey");
        }

        @Test
        void checkConstraintRejectsAMinimumAgeAboveTheMaximum() {
            assertThatThrownBy(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", "Child", 18, 12, "FULL_ACCESS"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_age_bounds");
        }

        @ParameterizedTest(name = "band {0}..{1}")
        @CsvSource(value = {"null, null", "0, 11", "0, 12", "13, 17", "18, null", "null, 17", "5, 5"}, nullValues = "null")
        void everyCatalogAgeBandIsAccepted(Integer minimumAge, Integer maximumAge) {
            assertThatCode(() -> insertPermissionRule("claims", "claims.claim", "Subscriber", "Child", minimumAge, maximumAge, "FULL_ACCESS"))
                    .doesNotThrowAnyException();
        }
    }
}
