package com.thehiddenbrain.interop.memberprofile.segmentation;

/** One active row of league_segmentation.segment_rule. */
public record SegmentRule(
        long id,
        String segmentName,
        String company,
        int ruleGroup,
        int evaluationOrder,
        String apiField,
        ComparisonOperator operator,
        String ruleValue) {

    boolean matches(java.util.Map<String, Object> facts) {
        return operator.test(facts.get(apiField), ruleValue);
    }
}
