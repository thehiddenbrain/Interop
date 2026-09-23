package org.point32health.memberprofile.memberdomain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What this service needs from the MemberDomain service for one member.
 * <p>
 * {@code attributes} carries the member facts the segmentation rules name in {@code api_field}
 * ({@code sourceSystemId}, {@code planTypeCode}, {@code product}, {@code coverageActive}, ...). Values may be
 * strings, numbers or booleans; the evaluator normalizes them. Either {@code age} or {@code dateOfBirth} must
 * be present for the member and for each family member. Unknown properties are ignored so MemberDomain can
 * grow without breaking this client.
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
        LocalDate dateOfBirth,
        Integer age,
        Boolean activePolicy,
        String accountNumber,
        String policyStatus,
        Map<String, Object> attributes,
        List<FamilyMember> familyMembers) {

    public MemberDomainMember {
        // Map.copyOf / List.copyOf reject nulls; a null-valued fact is simply an absent fact.
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(attributes));
        familyMembers = familyMembers == null ? List.of()
                : familyMembers.stream().filter(Objects::nonNull).toList();
    }

    /** One member of the same family or policy, as MemberDomain lists them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FamilyMember(
            String memberId,
            String fullName,
            String relationshipCode,
            LocalDate dateOfBirth,
            Integer age) {
    }
}
