package org.point32health.memberid.mmi;

import static org.assertj.core.api.Assertions.assertThat;

import org.point32health.memberid.domain.MemberRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MmiRecordMapperTest {

    private final MmiRecordMapper mapper = new MmiRecordMapper();

    @Test
    void mapsDatesStrictlyAndSkipsVoidAndUnreadableSpans() {
        MmiMember m = new MmiMember("123456789   01", "123456789   01", "03/15/1950", null, "thp", " MCR ", "N", null, List.of(
                new MmiCoverage("01/01/2024", null, "g", "N"),
                new MmiCoverage("01/01/2020", "12/31/9999", "g", "N"),
                new MmiCoverage("01/01/2026", null, "g", "Y"),
                new MmiCoverage("02/30/2024", "12/31/2024", "g", "N"),
                new MmiCoverage("01/01/2025", "01/01/2024", "g", "N"),
                new MmiCoverage(null, "12/31/2024", "g", "N")));
        MemberRecord r = mapper.toRecord(m);
        assertThat(r.storedMemberId()).isEqualTo("123456789   01");
        assertThat(r.matchKey()).isEqualTo("12345678901");
        assertThat(r.company()).isEqualTo("THP");
        assertThat(r.lineOfBusiness()).isEqualTo("MCR");
        assertThat(r.dateOfBirth()).isEqualTo(LocalDate.parse("1950-03-15"));
        assertThat(r.spans()).hasSize(2);
        assertThat(r.spans().get(1).end()).isNull();
        assertThat(r.unreadableSpans()).isEqualTo(3);
    }

    @Test
    void infersCompanyFromTheIdShapeWhenMissing() {
        assertThat(mapper.toRecord(new MmiMember("HP456789012", null, null, null, null, null, null, null, null)).company()).isEqualTo("HPHC");
        assertThat(mapper.toRecord(new MmiMember("123456789   01", null, null, null, "", null, null, null, null)).company()).isEqualTo("THP");
    }

    @Test
    void unreadableDobBecomesNull() {
        assertThat(mapper.toRecord(new MmiMember("123456789   01", null, "1950-03-15", null, "THP", null, null, null, null)).dateOfBirth()).isNull();
    }

    @Test
    void legacyIdIsNormalisedForMatching() {
        assertThat(mapper.toRecord(new MmiMember("HP567890123", null, null, "567890123   01", "HPHC", null, null, null, null)).legacyMatchKey())
                .isEqualTo("56789012301");
    }

    @Test
    void mmiDates() {
        assertThat(MmiDates.parseOrNull("02/29/2024")).isEqualTo(LocalDate.parse("2024-02-29"));
        assertThat(MmiDates.parseOrNull("02/29/2023")).isNull();
        assertThat(MmiDates.parseOrNull("2024-01-01")).isNull();
        assertThat(MmiDates.isOpenEnd(null)).isTrue();
        assertThat(MmiDates.isOpenEnd(" ")).isTrue();
        assertThat(MmiDates.isOpenEnd("12/31/9999")).isTrue();
        assertThat(MmiDates.isOpenEnd("12/31/2024")).isFalse();
    }
}
