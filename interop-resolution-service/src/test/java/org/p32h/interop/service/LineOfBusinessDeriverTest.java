package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.memberinfo.MemberPlan;
import org.slf4j.LoggerFactory;

/**
 * The member portal's ES Members criteria, one rule per line of business, run on the coverage records that cover the date
 * of service.
 */
class LineOfBusinessDeriverTest {

    private static final MemberRecord RECORD = new MemberRecord("TESTMEMBER", "TESTMEMBER", "THP", "MCR", null, null, List.of(), 0);
    private static final LocalDate DOS = LocalDate.parse("2026-10-15");
    private final LineOfBusinessDeriver deriver = new LineOfBusinessDeriver();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(LineOfBusinessDeriver.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(LineOfBusinessDeriver.class)).detachAppender(logs);
    }

    /** A coverage record with only the fields the rules and the date check read. */
    static MemberPlan record(String sourceSystemId, String subsidiary, String productCode, String planStartDate, String planEndDate) {
        return new MemberPlan(null, null, null, null, null, null, null, null, null, null, null, null, planEndDate, null, planStartDate,
                null, productCode, sourceSystemId, subsidiary, "N", null, null, null, null, null);
    }

    /** The line of business for one record covering the date of service, from 2025 with no end. */
    private String lob(String sourceSystemId, String subsidiary, String productCode) {
        return deriver.derive(RECORD, List.of(record(sourceSystemId, subsidiary, productCode, "2025-01-01T05:00:00.000+00:00", null)), DOS);
    }

    /** A coverage record covering the date of service with only the fields the SCO check reads, plus a source system. */
    private String scoLob(String businessTypeIndicator, String planCode, String sourceSystemId, String subsidiary, String productCode) {
        MemberPlan c = new MemberPlan(null, null, businessTypeIndicator, null, null, null, null, null, null, null, null, planCode, null,
                null, "2025-01-01", null, productCode, sourceSystemId, subsidiary, "N", null, null, null, null, null);
        return deriver.derive(RECORD, List.of(c), DOS);
    }

    @Test
    void scoIsBusinessTypeShWithScoInThePlanCode() {
        assertThat(scoLob("SH", "SCO10001", null, null, null)).isEqualTo("SCO");
        assertThat(scoLob("SH", "10SCO001", null, null, null)).as("SCO anywhere in the plan code").isEqualTo("SCO");
        assertThat(scoLob(" sh ", "10sco001", null, null, null)).as("ignoring case and spaces").isEqualTo("SCO");
        assertThat(scoLob("PP", "SCO10001", null, null, null)).as("another business type").isEqualTo("MCR");
        assertThat(scoLob("SH", "10GT1000", null, null, null)).as("no SCO in the plan code").isEqualTo("MCR");
        assertThat(scoLob("SH", null, null, null, null)).as("no plan code").isEqualTo("MCR");
        assertThat(scoLob(null, "SCO10001", null, null, null)).as("no business type").isEqualTo("MCR");
        assertThat(scoLob("SH", "SCO10001", "2026", "THPPMA", "GT")).as("checked before the ES Members rules").isEqualTo("SCO");
    }

    @Test
    void dsnpIsSourceSystem2064WithProductDmaWhateverTheSubsidiary() {
        assertThat(lob("2064", null, "DMA")).isEqualTo("D-SNP");
        assertThat(lob("2064", "THPPMA", "DMA")).isEqualTo("D-SNP");
        assertThat(lob("2064", null, "PL")).as("another product").isEqualTo("MCR");
        assertThat(lob("2026", "THPPMA", "DMA")).as("another source system").isEqualTo("MCR");
    }

    @Test
    void maTogetherIsThppmaOnSourceSystem2026WithProductPlOrGt() {
        assertThat(lob("2026", "THPPMA", "PL")).isEqualTo("MA-TOGETHER");
        assertThat(lob("2026", "THPPMA", "GT")).isEqualTo("MA-TOGETHER");
        assertThat(lob("2048", "THPPMA", "PL")).as("another source system").isEqualTo("MCR");
        assertThat(lob("2026", "THPPRI", "PL")).as("another subsidiary").isEqualTo("MCR");
    }

    @Test
    void riTogetherIsThppriOnSourceSystem2048WhateverTheProduct() {
        assertThat(lob("2048", "THPPRI", null)).isEqualTo("RI-TOGETHER");
        assertThat(lob("2048", "THPPRI", "GT")).isEqualTo("RI-TOGETHER");
        assertThat(lob("2026", "THPPRI", "GT")).as("another source system").isEqualTo("MCR");
        assertThat(lob("2048", "THPPMA", "GT")).as("another subsidiary").isEqualTo("MCR");
    }

    @Test
    void maQhpDirectIsThppmaOnSourceSystem2026WithProductNsOrSb() {
        assertThat(lob("2026", "THPPMA", "NS")).isEqualTo("MA-QHP-DIRECT");
        assertThat(lob("2026", "THPPMA", "SB")).as("the service's contract example").isEqualTo("MA-QHP-DIRECT");
        assertThat(lob("2026", "THPPMA", "XX")).as("a product no rule names").isEqualTo("MCR");
        assertThat(lob("2026", "THPPRI", "SB")).as("another subsidiary").isEqualTo("MCR");
    }

    @Test
    void codesAreComparedIgnoringCaseAndSurroundingSpaces() {
        assertThat(lob(" 2026 ", "thppma", " sb")).isEqualTo("MA-QHP-DIRECT");
    }

    @Test
    void aRecordWithoutTheFieldsMatchesNothing() {
        assertThat(lob(null, null, null)).isEqualTo("MCR");
        assertThat(lob("2064", null, null)).as("D-SNP's source system without a product").isEqualTo("MCR");
        assertThat(lob("2026", "THPPMA", null)).as("THPPMA on 2026 without a product").isEqualTo("MCR");
    }

    @Test
    void onlyTheRecordCoveringTheDateOfServiceIsRead() {
        List<MemberPlan> fiveYears = List.of(
                record("2048", "THPPRI", null, "2021-01-01T05:00:00.000+00:00", "2023-12-31T05:00:00.000+00:00"),
                record("2026", "THPPMA", "SB", "2024-01-01T05:00:00.000+00:00", "2026-10-14T04:00:00.000+00:00"),
                record("2026", "THPPMA", "GT", "2026-10-15T04:00:00.000+00:00", "9999-12-31T05:00:00.000+00:00"));
        assertThat(deriver.derive(RECORD, fiveYears, LocalDate.parse("2022-06-01"))).isEqualTo("RI-TOGETHER");
        assertThat(deriver.derive(RECORD, fiveYears, LocalDate.parse("2026-10-14"))).as("the last day, inclusive").isEqualTo("MA-QHP-DIRECT");
        assertThat(deriver.derive(RECORD, fiveYears, LocalDate.parse("2026-10-15"))).as("the first day, inclusive").isEqualTo("MA-TOGETHER");
        assertThat(deriver.derive(RECORD, fiveYears, LocalDate.parse("2020-06-01"))).as("before every record").isEqualTo("MCR");
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("marker=LOB_NOT_DERIVED")
                .contains("coverageRecords=3").contains("covering=0").contains("lob=MCR"));
    }

    @Test
    void aRecordWithoutAStartOrWithAnUnreadableDateCoversNothing() {
        assertThat(deriver.derive(RECORD, List.of(record("2048", "THPPRI", null, null, null)), DOS)).isEqualTo("MCR");
        assertThat(deriver.derive(RECORD, List.of(record("2048", "THPPRI", null, "01/01/2025", null)), DOS)).isEqualTo("MCR");
        assertThat(deriver.derive(RECORD, List.of(record("2048", "THPPRI", null, "2025-01-01", "not a date")), DOS)).isEqualTo("MCR");
    }

    @Test
    void severalRecordsOnTheDateGiveTheFirstMatchAndWarnWhenTheyDisagree() {
        List<MemberPlan> overlapping = List.of(
                record("2026", "THPPMA", "XX", "2026-01-01", null),
                record("2048", "THPPRI", null, "2026-01-01", null),
                record("2064", null, "DMA", "2026-01-01", null));
        assertThat(deriver.derive(RECORD, overlapping, DOS)).as("the first record a rule matches").isEqualTo("RI-TOGETHER");
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("marker=LOB_SEVERAL_ON_DATE")
                .contains("first=RI-TOGETHER").contains("also=D-SNP"));
    }

    @Test
    void noRecordsKeepTheRecordsLineOfBusiness() {
        assertThat(deriver.derive(RECORD, List.of(), DOS)).isEqualTo("MCR");
    }
}
