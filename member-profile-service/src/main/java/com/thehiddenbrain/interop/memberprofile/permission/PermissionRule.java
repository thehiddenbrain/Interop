package com.thehiddenbrain.interop.memberprofile.permission;

import java.util.List;

/** One active row of family_permission.family_permission_rule. */
public record PermissionRule(
        long id,
        String permissionFamily,
        String permissionKey,
        String actorRelationship,
        String viewingRelationship,
        Integer minimumAge,
        Integer maximumAge,
        List<Integer> actionCodes,
        String accessStatus,
        boolean consentRequired,
        boolean maskedData) {

    public static final String CATCH_ALL_VIEWING = "All other family members";

    boolean appliesToAge(int age) {
        return (minimumAge == null || age >= minimumAge) && (maximumAge == null || age <= maximumAge);
    }

    boolean isCatchAll() {
        return CATCH_ALL_VIEWING.equals(viewingRelationship);
    }

    /** Parent keys (benefits, claims, ...) are derived from their children, never read from a row. */
    boolean isParentKey() {
        return !permissionKey.contains(".");
    }
}
