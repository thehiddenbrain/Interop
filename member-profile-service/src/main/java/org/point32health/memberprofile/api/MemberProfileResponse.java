package org.point32health.memberprofile.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.point32health.memberprofile.permission.PermissionResult;
import org.point32health.memberprofile.segmentation.SegmentationResult;

import java.util.List;
import java.util.Map;

/**
 * The single response League receives at login, in the shape of the agreed API design:
 * <pre>
 * { "member": { ...identity..., "segmentation": {...}, "permissions": {...}, "familyPermissions": [ {...} ] },
 *   "actionCodeDescriptions": { "1": "View", "2": "Edit", "3": "Download", "4": "Delete" } }
 * </pre>
 *
 * @param member                 the logged-in member with their flags and permissions
 * @param actionCodeDescriptions meaning of the action codes used in every permissions map
 * @param explain                the evaluation trace; only present when the request asked for it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberProfileResponse(Member member, Map<Integer, String> actionCodeDescriptions, Explain explain) {

    /**
     * @param segmentation      all seven segmentation flags, always present, true or false
     * @param permissions       what the member may do with their own information (viewing relationship Self)
     * @param familyPermissions one entry per other family member on the policy
     */
    public record Member(
            String memberId,
            String fullName,
            String firstName,
            String lastName,
            String planName,
            String relationshipCode,
            String memberTypeCode,
            String userTypeCode,
            boolean isImpersonating,
            Integer age,
            Boolean activePolicy,
            String accountNumber,
            String policyStatus,
            Map<String, Boolean> segmentation,
            Map<String, List<Integer>> permissions,
            List<FamilyPermission> familyPermissions) {
    }

    /**
     * @param permissions     permission key to action codes; parent keys carry [1] when any child key has an action;
     *                        keys with no allowed action are absent
     * @param consentRequired keys that become available once consent is on file (absent when empty)
     * @param masked          keys whose data must be shown masked (absent when empty)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record FamilyPermission(
            String memberId,
            String fullName,
            String relationshipCode,
            Integer age,
            Map<String, List<Integer>> permissions,
            List<String> consentRequired,
            List<String> masked) {
    }

    /** Only present when the request set {@code explain}: how every flag and permission was decided. */
    public record Explain(
            String actorRelationship,
            Map<String, Object> memberFacts,
            List<SegmentationResult.GroupTrace> segmentation,
            Map<String, List<PermissionResult.RuleTrace>> permissions,
            Timings timings) {
    }

    /** Milliseconds spent per dependency for this request. */
    public record Timings(long memberDomainMs, long rulesMs, long totalMs) {
    }
}
