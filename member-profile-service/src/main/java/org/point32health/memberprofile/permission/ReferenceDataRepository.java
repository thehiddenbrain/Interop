package org.point32health.memberprofile.permission;

import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads {@code relationship_code} and {@code action_code} per request; they do not depend on the member, so they run alongside the MemberDomain call. */
@Repository
public class ReferenceDataRepository {

    private final JdbcClient jdbc;

    public ReferenceDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReferenceData load() {
        Map<String, FamilyRelationship> relationships = new LinkedHashMap<>();
        jdbc.sql("SELECT relationship_code, relationship FROM family_permission.relationship_code ORDER BY relationship_code")
                .query(rs -> {
                    String code = rs.getString(1);
                    String label = rs.getString(2);
                    relationships.put(code, FamilyRelationship.fromLabel(label).orElseThrow(() ->
                            MemberProfileException.ruleDataInvalid("relationship_code '" + code + "' maps to unknown relationship '" + label + "'")));
                });
        Map<Integer, String> actions = new LinkedHashMap<>();
        jdbc.sql("SELECT action_code, description FROM family_permission.action_code ORDER BY action_code")
                .query(rs -> { actions.put(rs.getInt(1), rs.getString(2)); });
        return new ReferenceData(Collections.unmodifiableMap(relationships), Collections.unmodifiableMap(actions));
    }
}
