package com.thehiddenbrain.interop.memberprofile.permission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

/** Consent on file for CONSENT_REQUIRED rules, read once per request for the logged-in member. */
@Repository
public class ConsentRepository {

    private static final String ACTIVE_CONSENTS = """
            SELECT viewed_member_id, permission_family
              FROM family_permission.family_consent
             WHERE actor_member_id = :actor
               AND revoked_at IS NULL
            """;

    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return keys of the form {@code viewedMemberId|permissionFamily} */
    public Set<String> activeConsentsFor(String actorMemberId) {
        Set<String> consents = new HashSet<>();
        jdbc.sql(ACTIVE_CONSENTS).param("actor", actorMemberId)
                .query(rs -> { consents.add(consentKey(rs.getString(1), rs.getString(2))); });
        return consents;
    }

    public static String consentKey(String viewedMemberId, String permissionFamily) {
        return viewedMemberId + "|" + permissionFamily;
    }
}
