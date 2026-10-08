package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.memberinfo.MemberPlan;

/** The member portal's ES Members criteria, one rule per line of business, read against the member's plan. */
class LineOfBusinessDeriverTest {

    private static final MemberRecord RECORD = new MemberRecord("TESTMEMBER", "TESTMEMBER", "THP", "MCR", null, null, List.of(), 0);
    private final LineOfBusinessDeriver deriver = new LineOfBusinessDeriver();

    /** A plan with only the fields the rules read set: source system, subsidiary and product. */
    private static MemberPlan plan(String sourceSystemId, String subsidiary, String productCode) {
        return new MemberPlan(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                productCode, sourceSystemId, subsidiary, "N", null, null, null, null, null);
    }

    private String lob(String sourceSystemId, String subsidiary, String productCode) {
        return deriver.derive(RECORD, plan(sourceSystemId, subsidiary, productCode));
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
    void noPlanOrAPlanWithoutTheFieldsKeepsTheRecordsLineOfBusiness() {
        assertThat(deriver.derive(RECORD, null)).isEqualTo("MCR");
        assertThat(lob(null, null, null)).isEqualTo("MCR");
        assertThat(lob("2064", null, null)).as("D-SNP's source system without a product").isEqualTo("MCR");
        assertThat(lob("2026", "THPPMA", null)).as("THPPMA on 2026 without a product").isEqualTo("MCR");
    }
}
