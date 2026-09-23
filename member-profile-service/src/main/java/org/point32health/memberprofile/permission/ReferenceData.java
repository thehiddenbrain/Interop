package org.point32health.memberprofile.permission;

import java.util.Map;
import java.util.Optional;

/**
 * The two small lookup tables read per request.
 *
 * @param relationshipCodes MemberDomain relationship code to {@link FamilyRelationship} ({@code family_permission.relationship_code})
 * @param actionCodes       action code to description ({@code family_permission.action_code}), for the response's {@code actionCodeDescriptions}
 */
public record ReferenceData(Map<String, FamilyRelationship> relationshipCodes, Map<Integer, String> actionCodes) {

    public Optional<FamilyRelationship> relationship(String relationshipCode) {
        return Optional.ofNullable(relationshipCode).map(relationshipCodes::get);
    }
}
