package com.thehiddenbrain.interop.memberprofile.permission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reads family permission rules on every request; nothing is cached. */
@Repository
public class PermissionRuleRepository {

    private static final String ACTIVE_RULES_FOR_ACTOR = """
            SELECT permission_rule_id, permission_family, permission_key, actor_relationship, viewing_relationship,
                   minimum_age, maximum_age, action_codes, access_status, consent_required, masked_data
              FROM family_permission.family_permission_rule
             WHERE actor_relationship = :actor
               AND is_active = TRUE
             ORDER BY permission_key, viewing_relationship, minimum_age
            """;

    private final JdbcClient jdbc;

    public PermissionRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All active rules for what this kind of actor may do, across every viewing relationship. */
    public List<PermissionRule> activeRulesForActor(String actorRelationship) {
        return jdbc.sql(ACTIVE_RULES_FOR_ACTOR)
                .param("actor", actorRelationship)
                .query((rs, i) -> new PermissionRule(
                        rs.getLong("permission_rule_id"),
                        rs.getString("permission_family"),
                        rs.getString("permission_key"),
                        rs.getString("actor_relationship"),
                        rs.getString("viewing_relationship"),
                        (Integer) rs.getObject("minimum_age"),
                        (Integer) rs.getObject("maximum_age"),
                        toIntegers(rs.getArray("action_codes")),
                        rs.getString("access_status"),
                        rs.getBoolean("consent_required"),
                        rs.getBoolean("masked_data")))
                .list();
    }

    private static List<Integer> toIntegers(Array array) throws SQLException {
        List<Integer> codes = new ArrayList<>();
        if (array == null) return codes;
        Object raw = array.getArray();
        if (raw instanceof Object[] values) {
            for (Object v : values) {
                if (v != null) codes.add(((Number) v).intValue());
            }
        }
        codes.sort(null);
        return codes;
    }
}
