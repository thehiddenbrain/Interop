package org.point32health.memberprofile.segmentation;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The seven segmentation properties of the response contract (Response Contract tab of
 * League_Segmentation_Table_Design.xlsx). Every one is always returned, true or false.
 * <p>
 * The list lives in code rather than in a table because League's UI must know each flag to act on it,
 * so adding a segment is always a coordinated release; the rules for each segment live in
 * {@code league_segmentation.segment_rule}.
 */
public enum Segment {
    ONLINE_BILL_PAY("onlineBillPay"),
    OPTUM_RX_COVERAGE("optumRxCoverage"),
    ALL_PUBLIC_PLANS_MA("allPublicPlansMa"),
    ALL_TUFTS_MEDICARE_PREFERRED("allTuftsMedicarePreferred"),
    TMP_OTC_MA("tmpOtcMa"),
    PLAN_OF_CARE("planOfCare"),
    INTEROPERABILITY("interoperability");

    private static final Map<String, Segment> BY_KEY = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Segment::key, Function.identity()));

    private final String key;

    Segment(String key) {
        this.key = key;
    }

    /** The lower camelCase JSON property name, equal to {@code segment_name} in the rule table. */
    public String key() {
        return key;
    }

    public static Optional<Segment> fromKey(String segmentName) {
        return Optional.ofNullable(BY_KEY.get(segmentName));
    }
}
