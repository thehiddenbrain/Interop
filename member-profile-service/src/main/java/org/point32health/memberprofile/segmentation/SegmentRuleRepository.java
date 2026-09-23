package org.point32health.memberprofile.segmentation;

import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads segmentation rules on every request; nothing is cached. One indexed query per request
 * ({@code ix_segment_rule_company_active}), rows already in evaluation order.
 */
@Repository
public class SegmentRuleRepository {

    private static final String ACTIVE_RULES_FOR_COMPANY = """
            SELECT segment_rule_id, segment_name, company, rule_group, evaluation_order,
                   api_field, comparison_operator, rule_value
              FROM league_segmentation.segment_rule
             WHERE company = :company
               AND is_active = TRUE
               AND segment_name IN (:segments)
             ORDER BY segment_name, rule_group, evaluation_order
            """;

    private final JdbcClient jdbc;

    public SegmentRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final List<String> SEGMENT_KEYS = java.util.Arrays.stream(Segment.values()).map(Segment::key).toList();

    /**
     * All active conditions for one company, ordered by segment, rule group and evaluation order, so the
     * evaluator can treat adjacent rows with the same (segment, rule group) as one AND group. Rows for a
     * {@code segment_name} outside the seven contract segments are not read at all: rules for a segment
     * League does not know yet may be loaded ahead of its release without affecting logins.
     */
    public List<SegmentRule> activeRulesFor(Company company) {
        return jdbc.sql(ACTIVE_RULES_FOR_COMPANY)
                .param("company", company.name())
                .param("segments", SEGMENT_KEYS)
                .query((rs, i) -> {
                    Segment segment = Segment.fromKey(rs.getString("segment_name")).orElseThrow();
                    ComparisonOperator operator;
                    try {
                        operator = ComparisonOperator.valueOf(rs.getString("comparison_operator"));
                    } catch (IllegalArgumentException e) {
                        throw MemberProfileException.ruleDataInvalid("segment_rule " + rs.getLong("segment_rule_id")
                                + " has unknown comparison_operator '" + rs.getString("comparison_operator") + "'");
                    }
                    return SegmentRule.of(
                            rs.getLong("segment_rule_id"), segment, rs.getString("company"),
                            rs.getInt("rule_group"), rs.getInt("evaluation_order"),
                            rs.getString("api_field"), operator, rs.getString("rule_value"));
                })
                .list();
    }
}
