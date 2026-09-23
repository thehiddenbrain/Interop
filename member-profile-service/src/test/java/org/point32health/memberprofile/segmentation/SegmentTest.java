package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Segment}: the seven contract flags, their JSON keys, the reverse lookup used by the rule reader,
 * and the contract order the response must keep (Response Contract tab of League_Segmentation_Table_Design.xlsx).
 */
class SegmentTest {

    /** Section 1 of the catalog: exactly these seven keys, in this order. */
    static final List<String> CONTRACT_ORDER = List.of(
            "onlineBillPay", "optumRxCoverage", "allPublicPlansMa", "allTuftsMedicarePreferred",
            "tmpOtcMa", "planOfCare", "interoperability");

    @Test
    void exactlySevenSegmentsInContractOrder() {
        assertThat(Segment.values()).hasSize(7);
        assertThat(Arrays.stream(Segment.values()).map(Segment::key)).containsExactlyElementsOf(CONTRACT_ORDER);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "ONLINE_BILL_PAY, onlineBillPay",
            "OPTUM_RX_COVERAGE, optumRxCoverage",
            "ALL_PUBLIC_PLANS_MA, allPublicPlansMa",
            "ALL_TUFTS_MEDICARE_PREFERRED, allTuftsMedicarePreferred",
            "TMP_OTC_MA, tmpOtcMa",
            "PLAN_OF_CARE, planOfCare",
            "INTEROPERABILITY, interoperability"})
    void keyIsTheLowerCamelCaseJsonPropertyName(Segment segment, String key) {
        assertThat(segment.key()).isEqualTo(key);
    }

    @ParameterizedTest
    @EnumSource(Segment.class)
    void keySatisfiesTheSegmentNameCheckConstraint(Segment segment) {
        // ck_segment_name_camel_case in V1__schema.sql: the key is what segment_name holds in the rule table
        assertThat(segment.key()).matches("^[a-z][A-Za-z0-9]*$");
    }

    @Test
    void keysAreUnique() {
        assertThat(Arrays.stream(Segment.values()).map(Segment::key).distinct()).hasSize(Segment.values().length);
    }

    @ParameterizedTest
    @EnumSource(Segment.class)
    void fromKeyRoundTripsEveryConstant(Segment segment) {
        assertThat(Segment.fromKey(segment.key())).contains(segment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OnlineBillPay", "ONLINEBILLPAY", "online_bill_pay", " onlineBillPay", "onlineBillPay ",
            "", "unknown", "benefits", "ONLINE_BILL_PAY"})
    void fromKeyIsAnExactMatchAndEmptyForAnythingElse(String name) {
        assertThat(Segment.fromKey(name)).isEmpty();
    }

    @Test
    void ordinalFollowsContractOrder() {
        for (int i = 0; i < CONTRACT_ORDER.size(); i++) {
            assertThat(Segment.values()[i].key()).isEqualTo(CONTRACT_ORDER.get(i));
            assertThat(Segment.fromKey(CONTRACT_ORDER.get(i)).orElseThrow().ordinal()).isEqualTo(i);
        }
    }

    @Test
    void fromKeyResolvesEveryContractKey() {
        assertThat(CONTRACT_ORDER).allSatisfy(key -> assertThat(Segment.fromKey(key)).isPresent());
    }
}
