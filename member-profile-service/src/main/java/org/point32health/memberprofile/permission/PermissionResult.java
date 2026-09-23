package org.point32health.memberprofile.permission;

import java.util.List;
import java.util.Map;

/**
 * Permissions for one viewed member.
 *
 * @param permissions     permission key to sorted action codes; parent keys hold [1] when any child has an action
 * @param consentRequired keys that would grant access once consent is on file (no actions until then)
 * @param masked          keys whose data must be shown masked
 * @param trace           which rule rows were selected and why; null unless explain was requested
 */
public record PermissionResult(Map<String, List<Integer>> permissions,
                               List<String> consentRequired,
                               List<String> masked,
                               List<RuleTrace> trace) {

    public record RuleTrace(long ruleId, String permissionKey, String viewingRelationship,
                            Integer minimumAge, Integer maximumAge, String accessStatus, String outcome) {
    }
}
