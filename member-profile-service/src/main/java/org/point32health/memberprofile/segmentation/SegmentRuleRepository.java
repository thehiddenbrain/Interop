package org.point32health.memberprofile.segmentation;

import org.point32health.memberprofile.common.MemberProfileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads segmentation rules on every request; nothing is cached. One indexed query per request
 * ({@code ix_segment_rule_company_active}), rows already in evaluation order.
 */
@Repository
public class SegmentRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(SegmentRuleRepository.class);

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

    /**
     * All active conditions for one company, ordered by segment, rule group and evaluation order.
     * Rows whose {@code segment_name} is not one of the seven contract segments are skipped with a warning:
     * they cannot appear in the response anyway.
     */
    public List<SegmentRule> activeRulesFor(String company) {
        return jdbc.sql(ACTIVE_RULES_FOR_COMPANY)
                .param("company", company)
                .query((rs, i) -> {
                    String segmentName = rs.getString("segment_name");
                    Segment segment = Segment.fromKey(segmentName).orElse(null);
                    if (segment == null) {
                        log.warn("segment_rule {} has unknown segment_name '{}'; ignored", rs.getLong("segment_rule_id"), segmentName);
                        return null;
                    }
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
                .list()
                .stream().filter(r -> r != null).toList();
    }
}
