package com.thehiddenbrain.interop.memberprofile.memberdomain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What this service needs from the MemberDomain service for one member.
 * <p>
 * {@code attributes} carries the member facts the segmentation rules reference by {@code api_field}
 * (sourceSystemId, planTypeCode, product, coverageActive, ...). Values may be strings, numbers or
 * booleans; the evaluator normalizes them. Unknown top-level properties are ignored so MemberDomain
 * can grow without breaking this client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberDomainMember(
        String memberId,
        String firstName,
        String lastName,
        String fullName,
        String planName,
        String relationshipCode,
        String memberTypeCode,
        String userTypeCode,
        String company,
        LocalDate dateOfBirth,
        Integer age,
        Boolean activePolicy,
        String accountNumber,
        String policyStatus,
        Map<String, Object> attributes,
        List<FamilyMember> familyMembers) {

    public MemberDomainMember {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        familyMembers = familyMembers == null ? List.of() : List.copyOf(familyMembers);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FamilyMember(
            String memberId,
            String fullName,
            String relationshipCode,
            LocalDate dateOfBirth,
            Integer age) {
    }
}
