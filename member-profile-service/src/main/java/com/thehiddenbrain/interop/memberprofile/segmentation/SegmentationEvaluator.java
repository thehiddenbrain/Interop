package com.thehiddenbrain.interop.memberprofile.segmentation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates segment rules against member facts.
 * <p>
 * Rows are grouped by (segment, rule_group). Every condition in a group must pass (AND);
 * a segment is true when at least one of its groups passes (OR). A segment with no active
 * rules for the member's company is false. The evaluation is pure: rules and facts in, flags out.
 */
@Component
public class SegmentationEvaluator {

    public SegmentationResult evaluate(List<String> segments, List<SegmentRule> rules, Map<String, Object> facts) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        segments.forEach(s -> flags.put(s, Boolean.FALSE));

        List<SegmentationResult.GroupTrace> trace = new ArrayList<>();
        for (var groupEntry : groupBySegmentAndGroup(rules).entrySet()) {
            String segment = groupEntry.getKey().segment();
            int group = groupEntry.getKey().ruleGroup();
            boolean groupMatched = true;
            List<SegmentationResult.ConditionTrace> conditions = new ArrayList<>();
            for (SegmentRule rule : groupEntry.getValue()) {
                boolean passed = rule.matches(facts);
                groupMatched &= passed;
                conditions.add(new SegmentationResult.ConditionTrace(
                        rule.apiField(), rule.operator(), rule.ruleValue(), facts.get(rule.apiField()), passed));
            }
            trace.add(new SegmentationResult.GroupTrace(segment, group, groupMatched, conditions));
            if (groupMatched && flags.containsKey(segment)) {
                flags.put(segment, Boolean.TRUE);
            }
        }
        return new SegmentationResult(Collections.unmodifiableMap(flags), List.copyOf(trace));
    }

    private record GroupKey(String segment, int ruleGroup) {
    }

    private static Map<GroupKey, List<SegmentRule>> groupBySegmentAndGroup(List<SegmentRule> rules) {
        Map<GroupKey, List<SegmentRule>> groups = new LinkedHashMap<>();
        for (SegmentRule rule : rules) {
            groups.computeIfAbsent(new GroupKey(rule.segmentName(), rule.ruleGroup()), k -> new ArrayList<>()).add(rule);
        }
        return groups;
    }
}
