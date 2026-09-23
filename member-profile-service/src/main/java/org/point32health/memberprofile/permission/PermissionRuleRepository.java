package org.point32health.memberprofile.permission;

import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads family permission rules on every request; nothing is cached. One indexed query per request
 * ({@code ix_family_permission_rule_actor_active}) limited to the viewing relationships the member's
 * family actually contains, plus the catch-all. No ORDER BY: the evaluator groups rows itself and sorts
 * its output, so the index scan is returned as is.
 */
@Repository
public class PermissionRuleRepository {

    private static final String ACTIVE_RULES = """
            SELECT permission_rule_id, permission_family, permission_key, actor_relationship, viewing_relationship,
                   minimum_age, maximum_age, action_codes, access_status, consent_required, masked_data
              FROM family_permission.family_permission_rule
             WHERE actor_relationship = :actor
               AND viewing_relationship IN (:viewings)
               AND is_active = TRUE
            """;

    private final JdbcClient jdbc;

    public PermissionRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All active rules for this actor and these viewing relationships (the catch-all is always included). */
    public List<PermissionRule> activeRulesFor(ActorRelationship actor, Collection<ViewingRelationship> viewings) {
        Set<ViewingRelationship> wanted = EnumSet.of(ViewingRelationship.ALL_OTHER);
        wanted.addAll(viewings);
        List<String> labels = wanted.stream().map(ViewingRelationship::label).toList();
        return jdbc.sql(ACTIVE_RULES)
                .param("actor", actor.label())
                .param("viewings", labels)
                .query((rs, i) -> {
                    long id = rs.getLong("permission_rule_id");
                    String actorLabel = rs.getString("actor_relationship");
                    String viewingLabel = rs.getString("viewing_relationship");
                    ActorRelationship rowActor = ActorRelationship.fromLabel(actorLabel)
                            .orElseThrow(() -> MemberProfileException.ruleDataInvalid("family_permission_rule " + id
                                    + " has unknown actor_relationship '" + actorLabel + "'"));
                    ViewingRelationship rowViewing = ViewingRelationship.fromLabel(viewingLabel)
                            .orElseThrow(() -> MemberProfileException.ruleDataInvalid("family_permission_rule " + id
                                    + " has unknown viewing_relationship '" + viewingLabel + "'"));
                    return new PermissionRule(
                            id,
                            rs.getString("permission_family"),
                            rs.getString("permission_key"),
                            rowActor,
                            rowViewing,
                            rs.getObject("minimum_age", Integer.class),
                            rs.getObject("maximum_age", Integer.class),
                            toSortedCodes(rs.getArray("action_codes")),
                            rs.getString("access_status"),
                            rs.getBoolean("consent_required"),
                            rs.getBoolean("masked_data"));
                })
                .list();
    }

    private static List<Integer> toSortedCodes(Array array) throws SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values) || values.length == 0) return List.of();
        TreeSet<Integer> codes = new TreeSet<>();
        for (Object v : values) {
            if (v != null) codes.add(((Number) v).intValue());
        }
        return List.copyOf(codes);
    }
}
