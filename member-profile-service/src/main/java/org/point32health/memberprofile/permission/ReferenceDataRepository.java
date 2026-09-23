package org.point32health.memberprofile.permission;

import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code relationship_code} and {@code action_code} per request in one round trip (a UNION of the
 * two tiny tables); they do not depend on the member, so the read runs alongside the MemberDomain call.
 */
@Repository
public class ReferenceDataRepository {

    private static final String REFERENCE_DATA = """
            SELECT 'R' AS kind, relationship_code AS code, relationship AS value, 0 AS ord
              FROM family_permission.relationship_code
            UNION ALL
            SELECT 'A', action_code::text, description, action_code
              FROM family_permission.action_code
             ORDER BY kind DESC, ord, code
            """;

    private final JdbcClient jdbc;

    public ReferenceDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReferenceData load() {
        Map<String, FamilyRelationship> relationships = new LinkedHashMap<>();
        Map<Integer, String> actions = new LinkedHashMap<>();
        jdbc.sql(REFERENCE_DATA).query(rs -> {
            String code = rs.getString("code");
            String value = rs.getString("value");
            if ("R".equals(rs.getString("kind"))) {
                relationships.put(code, FamilyRelationship.fromLabel(value).orElseThrow(() ->
                        MemberProfileException.ruleDataInvalid("relationship_code '" + code + "' maps to unknown relationship '" + value + "'")));
            } else {
                actions.put(Integer.parseInt(code), value);
            }
        });
        return new ReferenceData(Collections.unmodifiableMap(relationships), Collections.unmodifiableMap(actions));
    }
}
