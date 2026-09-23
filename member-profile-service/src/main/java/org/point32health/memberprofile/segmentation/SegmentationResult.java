package org.point32health.memberprofile.segmentation;

import java.util.List;
import java.util.Map;

/**
 * The seven flags plus, when the caller asked to explain, why each came out the way it did.
 *
 * @param flags every {@link Segment}, true or false, in contract order
 * @param trace one entry per rule group evaluated; null unless explain was requested
 */
public record SegmentationResult(Map<String, Boolean> flags, List<GroupTrace> trace) {

    public boolean isTrue(Segment segment) {
        return Boolean.TRUE.equals(flags.get(segment.key()));
    }

    /** One rule group and the outcome of each of its conditions. */
    public record GroupTrace(String segment, int ruleGroup, boolean matched, List<ConditionTrace> conditions) {
    }

    /** One condition: the rule, the member's actual value, and whether it passed. */
    public record ConditionTrace(long ruleId, String apiField, ComparisonOperator operator, String ruleValue,
                                 Object actualValue, boolean passed) {
    }
}
