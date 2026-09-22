package com.thehiddenbrain.interop.memberprofile.permission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small lookup tables read per request: action code descriptions and relationship codes. */
@Repository
public class ReferenceDataRepository {

    private final JdbcClient jdbc;

    public ReferenceDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code 1 -> View, 2 -> Edit, ...} */
    public Map<Integer, String> actionCodes() {
        Map<Integer, String> codes = new LinkedHashMap<>();
        jdbc.sql("SELECT action_code, description FROM family_permission.action_code ORDER BY action_code")
                .query(rs -> { codes.put(rs.getInt(1), rs.getString(2)); });
        return codes;
    }

    /** {@code "01" -> Subscriber, "03" -> Child, ...} */
    public Map<String, String> relationshipCodes() {
        Map<String, String> codes = new LinkedHashMap<>();
        jdbc.sql("SELECT relationship_code, relationship FROM family_permission.relationship_code")
                .query(rs -> { codes.put(rs.getString(1), rs.getString(2)); });
        return codes;
    }
}
