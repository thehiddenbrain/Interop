package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog section 5, relationship derivation: MemberDomain code plus age to the actor word (the logged-in
 * member) and the viewing word (a family member as seen by the actor). Boundaries: 12 is a minor, 13 and 17
 * are teenagers, 18 is an adult.
 */
class RelationshipResolverTest {

    private final RelationshipResolver resolver = new RelationshipResolver();

    @Test
    void ageThresholdsFollowTheWorkbookBands() {
        assertThat(RelationshipResolver.TEEN_AGE).isEqualTo(13);
        assertThat(RelationshipResolver.ADULT_AGE).isEqualTo(18);
    }

    // ------------------------------------------------------------------------------- actor

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 42, 99})
    void subscriberIsTheSubscriberActorAtAnyAge(int age) {
        assertThat(resolver.actor(FamilyRelationship.SUBSCRIBER, age)).isEqualTo(ActorRelationship.SUBSCRIBER);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 42, 99})
    void spouseIsTheSpouseActorAtAnyAge(int age) {
        assertThat(resolver.actor(FamilyRelationship.SPOUSE, age)).isEqualTo(ActorRelationship.SPOUSE);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 42, 99})
    void exSpouseIsTheExSpouseActorAtAnyAge(int age) {
        assertThat(resolver.actor(FamilyRelationship.EX_SPOUSE, age)).isEqualTo(ActorRelationship.EX_SPOUSE);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 1, 5, 11, 12})
    void childUnderThirteenIsAMinorActor(int age) {
        assertThat(resolver.actor(FamilyRelationship.CHILD, age)).isEqualTo(ActorRelationship.CHILD_MINOR);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {13, 14, 15, 16, 17})
    void childThirteenToSeventeenIsATeenagerActor(int age) {
        assertThat(resolver.actor(FamilyRelationship.CHILD, age)).isEqualTo(ActorRelationship.CHILD_TEENAGER);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 25, 60})
    void childEighteenOrOlderIsAnAdultChildActor(int age) {
        assertThat(resolver.actor(FamilyRelationship.CHILD, age)).isEqualTo(ActorRelationship.ADULT_CHILD);
    }

    @Test
    void minorToTeenagerBoundaryIsBetweenTwelveAndThirteen() {
        assertThat(resolver.actor(FamilyRelationship.CHILD, 12)).isEqualTo(ActorRelationship.CHILD_MINOR);
        assertThat(resolver.actor(FamilyRelationship.CHILD, 13)).isEqualTo(ActorRelationship.CHILD_TEENAGER);
    }

    @Test
    void teenagerToAdultBoundaryIsBetweenSeventeenAndEighteen() {
        assertThat(resolver.actor(FamilyRelationship.CHILD, 17)).isEqualTo(ActorRelationship.CHILD_TEENAGER);
        assertThat(resolver.actor(FamilyRelationship.CHILD, 18)).isEqualTo(ActorRelationship.ADULT_CHILD);
    }

    @Test
    void actorRequiresAKnownRelationship() {
        // the service raises MEMBER_DATA_INCOMPLETE (422) for an unmapped code before ever resolving the actor
        assertThatThrownBy(() -> resolver.actor(null, 30)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------------------- viewing

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 42, 99})
    void subscriberIsViewedAsSubscriberAtAnyAge(int age) {
        assertThat(resolver.viewing(FamilyRelationship.SUBSCRIBER, age)).isEqualTo(ViewingRelationship.SUBSCRIBER);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 42, 99})
    void spouseIsViewedAsSpouseAtAnyAge(int age) {
        assertThat(resolver.viewing(FamilyRelationship.SPOUSE, age)).isEqualTo(ViewingRelationship.SPOUSE);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 7, 12, 13, 15, 17})
    void childUnderEighteenIsViewedAsChild(int age) {
        assertThat(resolver.viewing(FamilyRelationship.CHILD, age)).isEqualTo(ViewingRelationship.CHILD);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 26, 40})
    void childEighteenOrOlderIsViewedAsAdultDependent(int age) {
        assertThat(resolver.viewing(FamilyRelationship.CHILD, age)).isEqualTo(ViewingRelationship.ADULT_DEPENDENT);
    }

    @Test
    void viewedChildBoundaryIsBetweenSeventeenAndEighteen() {
        assertThat(resolver.viewing(FamilyRelationship.CHILD, 17)).isEqualTo(ViewingRelationship.CHILD);
        assertThat(resolver.viewing(FamilyRelationship.CHILD, 18)).isEqualTo(ViewingRelationship.ADULT_DEPENDENT);
    }

    @Test
    void viewedChildDoesNotSplitAtThirteen() {
        // the 0-12 / 13-17 distinction is made by the rule rows' age bands, not by the viewing word
        assertThat(resolver.viewing(FamilyRelationship.CHILD, 12)).isEqualTo(ViewingRelationship.CHILD);
        assertThat(resolver.viewing(FamilyRelationship.CHILD, 13)).isEqualTo(ViewingRelationship.CHILD);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 45, 80})
    void exSpouseEighteenOrOlderIsViewedAsAdultDependent(int age) {
        assertThat(resolver.viewing(FamilyRelationship.EX_SPOUSE, age)).isEqualTo(ViewingRelationship.ADULT_DEPENDENT);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17})
    void exSpouseUnderEighteenFallsIntoAllOtherFamilyMembers(int age) {
        assertThat(resolver.viewing(FamilyRelationship.EX_SPOUSE, age)).isEqualTo(ViewingRelationship.ALL_OTHER);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 70})
    void unknownRelationshipCodeEighteenOrOlderIsViewedAsAdultDependent(int age) {
        assertThat(resolver.viewing(null, age)).isEqualTo(ViewingRelationship.ADULT_DEPENDENT);
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17})
    void unknownRelationshipCodeUnderEighteenFallsIntoAllOtherFamilyMembers(int age) {
        assertThat(resolver.viewing(null, age)).isEqualTo(ViewingRelationship.ALL_OTHER);
    }

    @ParameterizedTest(name = "unknown code age {0} -> {1}")
    @CsvSource({
            "0, ALL_OTHER",
            "12, ALL_OTHER",
            "17, ALL_OTHER",
            "18, ADULT_DEPENDENT",
            "70, ADULT_DEPENDENT"})
    void unknownRelationshipCodeIsBucketedByAgeOnly(int age, ViewingRelationship expected) {
        assertThat(resolver.viewing(null, age)).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------- the full section 5 table

    @ParameterizedTest(name = "{0} age {1} -> actor {2}, viewed as {3}")
    @CsvSource({
            "SUBSCRIBER, 0,  SUBSCRIBER,     SUBSCRIBER",
            "SUBSCRIBER, 42, SUBSCRIBER,     SUBSCRIBER",
            "SUBSCRIBER, 99, SUBSCRIBER,     SUBSCRIBER",
            "SPOUSE,     0,  SPOUSE,         SPOUSE",
            "SPOUSE,     30, SPOUSE,         SPOUSE",
            "EX_SPOUSE,  0,  EX_SPOUSE,      ALL_OTHER",
            "EX_SPOUSE,  17, EX_SPOUSE,      ALL_OTHER",
            "EX_SPOUSE,  18, EX_SPOUSE,      ADULT_DEPENDENT",
            "EX_SPOUSE,  50, EX_SPOUSE,      ADULT_DEPENDENT",
            "CHILD,      0,  CHILD_MINOR,    CHILD",
            "CHILD,      7,  CHILD_MINOR,    CHILD",
            "CHILD,      12, CHILD_MINOR,    CHILD",
            "CHILD,      13, CHILD_TEENAGER, CHILD",
            "CHILD,      15, CHILD_TEENAGER, CHILD",
            "CHILD,      17, CHILD_TEENAGER, CHILD",
            "CHILD,      18, ADULT_CHILD,    ADULT_DEPENDENT",
            "CHILD,      45, ADULT_CHILD,    ADULT_DEPENDENT"})
    void relationshipDerivationTable(FamilyRelationship relationship, int age,
                                     ActorRelationship expectedActor, ViewingRelationship expectedViewing) {
        assertThat(resolver.actor(relationship, age)).isEqualTo(expectedActor);
        assertThat(resolver.viewing(relationship, age)).isEqualTo(expectedViewing);
    }

    @ParameterizedTest
    @EnumSource(FamilyRelationship.class)
    void aFamilyMemberIsNeverViewedAsSelf(FamilyRelationship relationship) {
        // Self is reserved for the actor's own permissions, which the service evaluates separately
        for (int age = 0; age <= 100; age++) {
            assertThat(resolver.viewing(relationship, age)).isNotEqualTo(ViewingRelationship.SELF);
        }
    }

    @Test
    void actorAndViewingAgreeOnTheAdultThresholdForChildren() {
        for (int age = 0; age <= 40; age++) {
            boolean adultActor = resolver.actor(FamilyRelationship.CHILD, age) == ActorRelationship.ADULT_CHILD;
            boolean adultViewed = resolver.viewing(FamilyRelationship.CHILD, age) == ViewingRelationship.ADULT_DEPENDENT;
            assertThat(adultActor).as("age %d", age).isEqualTo(adultViewed).isEqualTo(age >= 18);
        }
    }

    @Test
    void exSpouseAndUnknownCodeShareTheSameViewingBuckets() {
        for (int age = 0; age <= 100; age++) {
            assertThat(resolver.viewing(FamilyRelationship.EX_SPOUSE, age)).as("age %d", age)
                    .isEqualTo(resolver.viewing(null, age));
        }
    }
}
