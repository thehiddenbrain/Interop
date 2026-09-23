package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyTest {

    @ParameterizedTest
    @CsvSource({"HPHC, HPHC", "THP, THP", "hphc, HPHC", " thp , THP", "Hphc, HPHC"})
    void knownCodesAreNormalized(String code, Company expected) {
        assertThat(Company.fromMemberTypeCode(code)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "THPP", "TUFTS", "HPHC1", "BOTH", "HPHC THP"})
    void anythingElseIsUnknown(String code) {
        assertThat(Company.fromMemberTypeCode(code)).isEmpty();
    }
}
