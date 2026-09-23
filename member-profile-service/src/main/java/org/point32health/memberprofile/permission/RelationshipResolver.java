package org.point32health.memberprofile.permission;

import org.springframework.stereotype.Component;

/**
 * Turns MemberDomain relationship codes and ages into the relationship words the rule table uses.
 * Age thresholds follow the workbook's age bands: 0 to 12 minor, 13 to 17 teenager, 18 or older adult.
 */
@Component
public class RelationshipResolver {

    public static final int ADULT_AGE = 18;
    public static final int TEEN_AGE = 13;

    /** The logged-in member as an actor. */
    public ActorRelationship actor(FamilyRelationship relationship, int age) {
        return switch (relationship) {
            case SUBSCRIBER -> ActorRelationship.SUBSCRIBER;
            case SPOUSE -> ActorRelationship.SPOUSE;
            case EX_SPOUSE -> ActorRelationship.EX_SPOUSE;
            case CHILD -> age >= ADULT_AGE ? ActorRelationship.ADULT_CHILD
                    : age >= TEEN_AGE ? ActorRelationship.CHILD_TEENAGER
                    : ActorRelationship.CHILD_MINOR;
        };
    }

    /**
     * A family member as seen by the actor. {@code relationship} may be null when MemberDomain sent a code
     * that {@code relationship_code} does not map; such a member falls into the age-based buckets.
     */
    public ViewingRelationship viewing(FamilyRelationship relationship, int age) {
        if (relationship == null) {
            return age >= ADULT_AGE ? ViewingRelationship.ADULT_DEPENDENT : ViewingRelationship.ALL_OTHER;
        }
        return switch (relationship) {
            case SUBSCRIBER -> ViewingRelationship.SUBSCRIBER;
            case SPOUSE -> ViewingRelationship.SPOUSE;
            case CHILD -> age >= ADULT_AGE ? ViewingRelationship.ADULT_DEPENDENT : ViewingRelationship.CHILD;
            case EX_SPOUSE -> age >= ADULT_AGE ? ViewingRelationship.ADULT_DEPENDENT : ViewingRelationship.ALL_OTHER;
        };
    }
}
