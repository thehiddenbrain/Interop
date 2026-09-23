package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three relationship enums must spell their labels exactly as the workbook and the CHECK constraints in
 * V1__schema.sql do: PermissionRuleRepository and ReferenceDataRepository map database text to constants with
 * {@code fromLabel} and raise RULE_DATA_INVALID for anything unknown, so a one-character drift would take the
 * whole endpoint down.
 */
class RelationshipEnumsTest {

    // ------------------------------------------------------------------------------- ActorRelationship

    @ParameterizedTest(name = "{0} is spelled \"{1}\"")
    @CsvSource({
            "SUBSCRIBER, Subscriber",
            "SPOUSE, Spouse",
            "EX_SPOUSE, Ex-Spouse",
            "ADULT_CHILD, Adult Child",
            "CHILD_TEENAGER, Child (teenager)",
            "CHILD_MINOR, Child (minor)"})
    void actorLabelsAreSpelledAsTheWorkbook(ActorRelationship actor, String label) {
        assertThat(actor.label()).isEqualTo(label);
    }

    @ParameterizedTest
    @EnumSource(ActorRelationship.class)
    void actorLabelRoundTripsThroughFromLabel(ActorRelationship actor) {
        assertThat(ActorRelationship.fromLabel(actor.label())).contains(actor);
    }

    @ParameterizedTest(name = "\"{0}\" is not an actor relationship")
    @ValueSource(strings = {"subscriber", "SUBSCRIBER", " Subscriber", "Subscriber ", "Child", "Child (Teenager)",
            "Child(teenager)", "Child (teen)", "Adult child", "Ex Spouse", "ExSpouse", "Self",
            "Adult dependent (any relationship)", "All other family members", ""})
    void unknownActorLabelsAreEmpty(String label) {
        assertThat(ActorRelationship.fromLabel(label)).isEmpty();
    }

    @Test
    void actorLabelsMatchTheCheckConstraintOnActorRelationship() {
        assertThat(ActorRelationship.values()).hasSize(6);
        assertThat(Stream.of(ActorRelationship.values()).map(ActorRelationship::label))
                .containsExactlyInAnyOrder("Subscriber", "Spouse", "Ex-Spouse", "Adult Child", "Child (teenager)", "Child (minor)");
    }

    @ParameterizedTest
    @EnumSource(ActorRelationship.class)
    void actorLabelsAreCaseSensitive(ActorRelationship actor) {
        assertThat(ActorRelationship.fromLabel(actor.label().toLowerCase())).isEmpty();
        assertThat(ActorRelationship.fromLabel(actor.label().toUpperCase())).isEmpty();
    }

    // ------------------------------------------------------------------------------- ViewingRelationship

    @ParameterizedTest(name = "{0} is spelled \"{1}\"")
    @CsvSource({
            "SELF, Self",
            "SUBSCRIBER, Subscriber",
            "SPOUSE, Spouse",
            "CHILD, Child",
            "ADULT_DEPENDENT, Adult dependent (any relationship)",
            "ALL_OTHER, All other family members"})
    void viewingLabelsAreSpelledAsTheWorkbook(ViewingRelationship viewing, String label) {
        assertThat(viewing.label()).isEqualTo(label);
    }

    @Test
    void adultDependentLabelIsExactlyAsTheWorkbookSpellsIt() {
        assertThat(ViewingRelationship.ADULT_DEPENDENT.label()).isEqualTo("Adult dependent (any relationship)");
        assertThat(ViewingRelationship.fromLabel("Adult dependent (any relationship)")).contains(ViewingRelationship.ADULT_DEPENDENT);
    }

    @Test
    void allOtherFamilyMembersLabelIsExactlyAsTheWorkbookSpellsIt() {
        assertThat(ViewingRelationship.ALL_OTHER.label()).isEqualTo("All other family members");
        assertThat(ViewingRelationship.fromLabel("All other family members")).contains(ViewingRelationship.ALL_OTHER);
    }

    @ParameterizedTest
    @EnumSource(ViewingRelationship.class)
    void viewingLabelRoundTripsThroughFromLabel(ViewingRelationship viewing) {
        assertThat(ViewingRelationship.fromLabel(viewing.label())).contains(viewing);
    }

    @ParameterizedTest(name = "\"{0}\" is not a viewing relationship")
    @ValueSource(strings = {"self", "SELF", "Child (minor)", "Child (teenager)", "Adult Child", "Adult dependent",
            "Adult Dependent (any relationship)", "Adult dependent (any relationship) ", "All other",
            "All Other Family Members", "All other family member", "Ex-Spouse", ""})
    void unknownViewingLabelsAreEmpty(String label) {
        assertThat(ViewingRelationship.fromLabel(label)).isEmpty();
    }

    @Test
    void viewingLabelsMatchTheCheckConstraintOnViewingRelationship() {
        assertThat(ViewingRelationship.values()).hasSize(6);
        assertThat(Stream.of(ViewingRelationship.values()).map(ViewingRelationship::label))
                .containsExactlyInAnyOrder("Self", "Subscriber", "Spouse", "Child",
                        "Adult dependent (any relationship)", "All other family members");
    }

    @ParameterizedTest
    @EnumSource(ViewingRelationship.class)
    void viewingLabelsAreCaseSensitive(ViewingRelationship viewing) {
        assertThat(ViewingRelationship.fromLabel(viewing.label().toLowerCase())).isEmpty();
        assertThat(ViewingRelationship.fromLabel(viewing.label().toUpperCase())).isEmpty();
    }

    // ------------------------------------------------------------------------------- FamilyRelationship

    @ParameterizedTest(name = "{0} is spelled \"{1}\"")
    @CsvSource({
            "SUBSCRIBER, Subscriber",
            "SPOUSE, Spouse",
            "EX_SPOUSE, Ex-Spouse",
            "CHILD, Child"})
    void familyLabelsAreSpelledAsTheRelationshipCodeTable(FamilyRelationship relationship, String label) {
        assertThat(relationship.label()).isEqualTo(label);
    }

    @ParameterizedTest
    @EnumSource(FamilyRelationship.class)
    void familyLabelRoundTripsThroughFromLabel(FamilyRelationship relationship) {
        assertThat(FamilyRelationship.fromLabel(relationship.label())).contains(relationship);
    }

    @ParameterizedTest(name = "\"{0}\" is not a family relationship")
    @ValueSource(strings = {"child", "CHILD", "Adult Child", "Child (minor)", "Child (teenager)", "Self",
            "Ex Spouse", "Exspouse", "Dependent", "All other family members", ""})
    void unknownFamilyLabelsAreEmpty(String label) {
        assertThat(FamilyRelationship.fromLabel(label)).isEmpty();
    }

    @Test
    void familyLabelsMatchTheCheckConstraintOnRelationshipCode() {
        assertThat(FamilyRelationship.values()).hasSize(4);
        assertThat(Stream.of(FamilyRelationship.values()).map(FamilyRelationship::label))
                .containsExactlyInAnyOrder("Subscriber", "Spouse", "Ex-Spouse", "Child");
    }

    @ParameterizedTest
    @EnumSource(FamilyRelationship.class)
    void familyLabelsAreCaseSensitive(FamilyRelationship relationship) {
        assertThat(FamilyRelationship.fromLabel(relationship.label().toLowerCase())).isEmpty();
        assertThat(FamilyRelationship.fromLabel(relationship.label().toUpperCase())).isEmpty();
    }

    // ------------------------------------------------------------------------------- across the enums

    @Test
    void childIsAViewingAndFamilyRelationshipButNeverAnActorOnItsOwn() {
        // an acting child is always age-qualified: Adult Child, Child (teenager) or Child (minor)
        assertThat(ActorRelationship.fromLabel("Child")).isEmpty();
        assertThat(ViewingRelationship.fromLabel("Child")).contains(ViewingRelationship.CHILD);
        assertThat(FamilyRelationship.fromLabel("Child")).contains(FamilyRelationship.CHILD);
    }

    @Test
    void selfIsOnlyAViewingRelationship() {
        assertThat(ViewingRelationship.fromLabel("Self")).contains(ViewingRelationship.SELF);
        assertThat(ActorRelationship.fromLabel("Self")).isEmpty();
        assertThat(FamilyRelationship.fromLabel("Self")).isEmpty();
    }

    @Test
    void exSpouseIsAnActorAndFamilyRelationshipButNeverAViewingRelationship() {
        // an ex-spouse is viewed as an adult dependent or falls into the catch-all, see RelationshipResolver
        assertThat(ActorRelationship.fromLabel("Ex-Spouse")).contains(ActorRelationship.EX_SPOUSE);
        assertThat(FamilyRelationship.fromLabel("Ex-Spouse")).contains(FamilyRelationship.EX_SPOUSE);
        assertThat(ViewingRelationship.fromLabel("Ex-Spouse")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FamilyRelationship.class, names = {"SUBSCRIBER", "SPOUSE", "EX_SPOUSE"})
    void adultFamilyLabelsAreAlsoActorLabels(FamilyRelationship relationship) {
        assertThat(ActorRelationship.fromLabel(relationship.label())).isPresent();
    }

    @Test
    void labelsAreUniqueWithinEachEnum() {
        assertThat(Stream.of(ActorRelationship.values()).map(ActorRelationship::label)).doesNotHaveDuplicates();
        assertThat(Stream.of(ViewingRelationship.values()).map(ViewingRelationship::label)).doesNotHaveDuplicates();
        assertThat(Stream.of(FamilyRelationship.values()).map(FamilyRelationship::label)).doesNotHaveDuplicates();
    }
}
