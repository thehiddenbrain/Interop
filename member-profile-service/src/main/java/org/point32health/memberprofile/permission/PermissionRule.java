package org.point32health.memberprofile.permission;

import java.util.List;

/**
 * One active row of {@code family_permission.family_permission_rule}.
 *
 * @param actionCodes sorted, distinct action codes (1 View, 2 Edit, 3 Download, 4 Delete); empty means no default access
 */
public record PermissionRule(
        long id,
        String permissionFamily,
        String permissionKey,
        ActorRelationship actor,
        ViewingRelationship viewing,
        Integer minimumAge,
        Integer maximumAge,
        List<Integer> actionCodes,
        String accessStatus,
        boolean consentRequired,
        boolean maskedData) {

    public boolean appliesToAge(int age) {
        return (minimumAge == null || age >= minimumAge) && (maximumAge == null || age <= maximumAge);
    }

    /** Parent keys ({@code benefits}, {@code claims}, ...) are derived from their children, never read from a row. */
    public boolean isParentKey() {
        return permissionKey.indexOf('.') < 0;
    }

    /** {@code benefits} for {@code benefits.idCard}; the key itself for a parent key. */
    public static String familyOf(String permissionKey) {
        int dot = permissionKey.indexOf('.');
        return dot < 0 ? permissionKey : permissionKey.substring(0, dot);
    }
}
