package com.thehiddenbrain.interop.memberprofile.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thehiddenbrain.interop.memberprofile.permission.PermissionResult;
import com.thehiddenbrain.interop.memberprofile.segmentation.SegmentationResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The single response League receives at login. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberProfileResponse(
        Member member,
        Map<String, Boolean> segmentation,
        Map<String, List<Integer>> selfPermissions,
        List<FamilyPermission> familyPermissions,
        Map<Integer, String> actionCodes,
        Meta meta,
        Explain explain) {

    public record Member(
            String memberId,
            String fullName,
            String firstName,
            String lastName,
            String planName,
            String relationshipCode,
            String memberTypeCode,
            String userTypeCode,
            String company,
            boolean isImpersonating,
            Integer age,
            Boolean activePolicy,
            String accountNumber,
            String policyStatus) {
    }

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

    public record Meta(Instant generatedAt) {
    }

    /** Only present when the caller asked for {@code ?explain=true}. */
    public record Explain(
            String actorRelationship,
            Map<String, Object> memberFacts,
            List<SegmentationResult.GroupTrace> segmentation,
            Map<String, List<PermissionResult.RuleTrace>> permissions) {
    }
}
