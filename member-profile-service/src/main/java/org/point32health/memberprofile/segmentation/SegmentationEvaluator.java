package org.point32health.memberprofile.segmentation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates segment rules against member facts in one pass over the rows.
 * <p>
 * Rows arrive ordered by segment, rule group and evaluation order. Every condition in a group must pass
 * (AND); a segment is true when at least one of its groups passes (OR). A segment with no active rules for
 * the member's company is false. Without explain the pass short-circuits: a failed condition skips the rest
 * of its group and a matched group skips the rest of its segment. With explain every condition is
 * evaluated and recorded. The evaluation is pure: rules and facts in, flags out.
 */
@Component
public class SegmentationEvaluator {

    public SegmentationResult evaluate(List<SegmentRule> rules, MemberFacts facts, boolean explain) {
        EnumMap<Segment, Boolean> flags = new EnumMap<>(Segment.class);
        for (Segment s : Segment.values()) flags.put(s, Boolean.FALSE);

        List<SegmentationResult.GroupTrace> trace = explain ? new ArrayList<>() : null;
        List<SegmentationResult.ConditionTrace> conditions = null;

        int i = 0;
        int n = rules.size();
        while (i < n) {
            SegmentRule first = rules.get(i);
            Segment segment = first.segment();
            int group = first.ruleGroup();
            boolean groupMatched = true;
            if (explain) conditions = new ArrayList<>();

            // walk the conditions of this (segment, group)
            while (i < n && rules.get(i).segment() == segment && rules.get(i).ruleGroup() == group) {
                SegmentRule rule = rules.get(i++);
                if (!groupMatched && !explain) continue;          // group already failed: skip the rest
                boolean passed = rule.matches(facts);
                groupMatched &= passed;
                if (explain) {
                    conditions.add(new SegmentationResult.ConditionTrace(rule.id(), rule.apiField(), rule.operator(),
                            rule.ruleValue(), facts.raw(rule.apiField()), passed));
                }
            }
            if (explain) trace.add(new SegmentationResult.GroupTrace(segment.key(), group, groupMatched, conditions));
            if (groupMatched) {
                flags.put(segment, Boolean.TRUE);
                if (!explain) {                                    // segment decided: skip its remaining groups
                    while (i < n && rules.get(i).segment() == segment) i++;
                }
            }
        }

        Map<String, Boolean> byKey = new LinkedHashMap<>(16);
        flags.forEach((segment, value) -> byKey.put(segment.key(), value));
        return new SegmentationResult(Collections.unmodifiableMap(byKey), trace == null ? null : List.copyOf(trace));
    }
}
