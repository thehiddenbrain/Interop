package com.thehiddenbrain.interop.memberprofile.segmentation;

import java.util.List;
import java.util.Map;

/**
 * The seven flags plus, for the explain endpoint, why each came out the way it did.
 *
 * @param flags every active segment, true or false, in segment name order
 * @param trace one entry per rule group evaluated
 */
public record SegmentationResult(Map<String, Boolean> flags, List<GroupTrace> trace) {

    public record GroupTrace(String segment, int ruleGroup, boolean matched, List<ConditionTrace> conditions) {
    }

    public record ConditionTrace(String apiField, ComparisonOperator operator, String ruleValue,
                                 Object actualValue, boolean passed) {
    }
}
