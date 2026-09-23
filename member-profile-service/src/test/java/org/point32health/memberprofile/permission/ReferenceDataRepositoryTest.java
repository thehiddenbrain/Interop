package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReferenceDataRepository} against the real database and the V3 seed: relationship codes 01..04 map
 * to {@link FamilyRelationship}, action codes 1..4 carry their descriptions in order, and an unmappable
 * relationship label is reported as RULE_DATA_INVALID. Skipped unless {@code MEMBER_PROFILE_TEST_DB=true}.
 * Tests that write rows run in a transaction that is rolled back.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class ReferenceDataRepositoryTest {

    @Autowired ReferenceDataRepository repository;
    @Autowired JdbcClient jdbc;

    private void insertRelationshipCode(String code, String relationship) {
        jdbc.sql("INSERT INTO family_permission.relationship_code (relationship_code, relationship, description) VALUES (:code, :relationship, 'test')")
                .param("code", code)
                .param("relationship", relationship)
                .update();
    }

    // ------------------------------------------------------------------------------------------ relationship codes

    @Test
    void relationshipCodes01To04MapToTheEnumsInCodeOrder() {
        ReferenceData data = repository.load();

        assertThat(data.relationshipCodes()).containsExactly(
                Map.entry("01", FamilyRelationship.SUBSCRIBER),
                Map.entry("02", FamilyRelationship.SPOUSE),
                Map.entry("03", FamilyRelationship.CHILD),
                Map.entry("04", FamilyRelationship.EX_SPOUSE));
    }

    @ParameterizedTest(name = "code {0} is {1}")
    @CsvSource({"01, SUBSCRIBER", "02, SPOUSE", "03, CHILD", "04, EX_SPOUSE"})
    void relationshipLookupByCode(String code, FamilyRelationship expected) {
        assertThat(repository.load().relationship(code)).contains(expected);
    }

    @ParameterizedTest(name = "code ''{0}'' is unknown")
    @ValueSource(strings = {"00", "05", "1", "01 ", "Subscriber", ""})
    void unknownRelationshipCodeIsEmpty(String code) {
        assertThat(repository.load().relationship(code)).isEmpty();
    }

    @Test
    void nullRelationshipCodeIsEmpty() {
        assertThat(repository.load().relationship(null)).isEmpty();
    }

    @Test
    void relationshipLabelsInTheTableAreExactlyTheEnumLabels() {
        Map<String, String> labels = jdbc.sql("SELECT relationship_code, relationship FROM family_permission.relationship_code ORDER BY relationship_code")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new));

        assertThat(labels).containsExactly(
                Map.entry("01", "Subscriber"), Map.entry("02", "Spouse"), Map.entry("03", "Child"), Map.entry("04", "Ex-Spouse"));
        labels.values().forEach(label -> assertThat(FamilyRelationship.fromLabel(label)).isPresent());
    }

    // ------------------------------------------------------------------------------------------ action codes

    @Test
    void actionCodes1To4CarryTheirDescriptionsInOrder() {
        ReferenceData data = repository.load();

        assertThat(data.actionCodes()).containsExactly(
                Map.entry(1, "View"), Map.entry(2, "Edit"), Map.entry(3, "Download"), Map.entry(4, "Delete"));
        assertThat(data.actionCodes().keySet()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void actionCodesAreKeyedByIntegerSoTheResponseSerializesThemAsStrings() {
        Map<Integer, String> codes = repository.load().actionCodes();

        assertThat(codes.get(1)).isEqualTo("View");
        assertThat(codes.get(4)).isEqualTo("Delete");
        assertThat(codes).doesNotContainKey(5);
    }

    // ------------------------------------------------------------------------------------------ immutability and freshness

    @Test
    void bothMapsAreUnmodifiable() {
        ReferenceData data = repository.load();

        assertThatThrownBy(() -> data.relationshipCodes().put("05", FamilyRelationship.CHILD))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> data.actionCodes().put(5, "Share"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @Transactional
    void everyLoadReadsTheTablesAgainSoANewRowIsVisibleAtOnce() {
        jdbc.sql("INSERT INTO family_permission.action_code (action_code, description) VALUES (5, 'Share')").update();
        insertRelationshipCode("05", "Spouse");

        ReferenceData data = repository.load();

        assertThat(data.actionCodes()).hasSize(5).containsEntry(5, "Share");
        assertThat(data.actionCodes().keySet()).containsExactly(1, 2, 3, 4, 5);
        assertThat(data.relationshipCodes()).hasSize(5).containsEntry("05", FamilyRelationship.SPOUSE);
    }

    // ------------------------------------------------------------------------------------------ bad data

    @Test
    @Transactional
    void unknownRelationshipLabelIsRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> insertRelationshipCode("05", "Grandparent"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_relationship");
    }

    @Test
    @Transactional
    void unknownRelationshipLabelRaisesRuleDataInvalidWhenItGetsPastTheConstraint() {
        // drop the constraint inside this rolled-back transaction to reach the mapper
        jdbc.sql("ALTER TABLE family_permission.relationship_code DROP CONSTRAINT ck_relationship").update();
        insertRelationshipCode("05", "Grandparent");

        assertThatThrownBy(() -> repository.load())
                .isInstanceOfSatisfying(MemberProfileException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID);
                    assertThat(e.getMessage()).contains("relationship_code '05'").contains("unknown relationship 'Grandparent'");
                });
    }

    @Test
    @Transactional
    void relationshipLabelsAreCaseSensitive() {
        jdbc.sql("ALTER TABLE family_permission.relationship_code DROP CONSTRAINT ck_relationship").update();
        insertRelationshipCode("05", "SUBSCRIBER");

        assertThatThrownBy(() -> repository.load())
                .isInstanceOfSatisfying(MemberProfileException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID));
    }
}
