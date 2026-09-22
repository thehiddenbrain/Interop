package com.thehiddenbrain.interop.memberprofile.segmentation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Reads segmentation rules on every request; nothing is cached. */
@Repository
public class SegmentRuleRepository {

    private static final String ACTIVE_SEGMENTS = """
            SELECT segment_name
              FROM league_segmentation.segment
             WHERE is_active = TRUE
             ORDER BY segment_name
            """;

    private static final String ACTIVE_RULES_FOR_COMPANY = """
            SELECT segment_rule_id, segment_name, company, rule_group, evaluation_order,
                   api_field, comparison_operator, rule_value
              FROM league_segmentation.segment_rule
             WHERE company = :company
               AND is_active = TRUE
             ORDER BY segment_name, rule_group, evaluation_order
            """;

    private final JdbcClient jdbc;

    public SegmentRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every segment the response must contain, true or false. */
    public List<String> activeSegments() {
        return jdbc.sql(ACTIVE_SEGMENTS).query(String.class).list();
    }

    /** All active conditions for one company, ordered for grouping. */
    public List<SegmentRule> activeRulesFor(String company) {
        return jdbc.sql(ACTIVE_RULES_FOR_COMPANY)
                .param("company", company)
                .query((rs, i) -> new SegmentRule(
                        rs.getLong("segment_rule_id"),
                        rs.getString("segment_name"),
                        rs.getString("company"),
                        rs.getInt("rule_group"),
                        rs.getInt("evaluation_order"),
                        rs.getString("api_field"),
                        ComparisonOperator.valueOf(rs.getString("comparison_operator")),
                        rs.getString("rule_value")))
                .list();
    }
}
