package com.thehiddenbrain.interop.memberprofile.permission;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Turns member domain relationship codes and ages into the relationship words the rule table uses.
 * <p>
 * Actor (the logged-in member): Subscriber | Spouse | Ex-Spouse | Adult Child | Child (teenager) | Child (minor).
 * Viewing (the family member being looked at): Self | Subscriber | Spouse | Child |
 * Adult dependent (any relationship) | All other family members.
 */
@Component
public class RelationshipResolver {

    public static final int ADULT_AGE = 18;
    public static final int TEEN_AGE = 13;

    public String actorRelationship(String relationshipCode, int age, Map<String, String> relationshipCodes) {
        String base = relationshipCodes.getOrDefault(relationshipCode, "");
        return switch (base) {
            case "Subscriber" -> "Subscriber";
            case "Spouse" -> "Spouse";
            case "Ex-Spouse" -> "Ex-Spouse";
            case "Child" -> age >= ADULT_AGE ? "Adult Child" : age >= TEEN_AGE ? "Child (teenager)" : "Child (minor)";
            default -> throw new UnknownRelationshipException(relationshipCode);
        };
    }

    public String viewingRelationship(String actorMemberId, String viewedMemberId, String viewedRelationshipCode,
                                      int viewedAge, Map<String, String> relationshipCodes) {
        if (actorMemberId.equals(viewedMemberId)) {
            return "Self";
        }
        String base = relationshipCodes.getOrDefault(viewedRelationshipCode, "");
        return switch (base) {
            case "Subscriber" -> "Subscriber";
            case "Spouse" -> "Spouse";
            case "Child" -> viewedAge >= ADULT_AGE ? "Adult dependent (any relationship)" : "Child";
            default -> viewedAge >= ADULT_AGE ? "Adult dependent (any relationship)" : PermissionRule.CATCH_ALL_VIEWING;
        };
    }

    public static class UnknownRelationshipException extends RuntimeException {
        public UnknownRelationshipException(String code) {
            super("Relationship code '" + code + "' is not in family_permission.relationship_code");
        }
    }
}
