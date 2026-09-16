package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSummariesTest {

    @Test
    void eobColumnsShowClaimIdentityTotalsAndParties() {
        Map<String, String> c = ResourceSummaries.columns(Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1"));
        assertThat(c).containsEntry("claimId", "AW123412341234123412341234123412")
                .containsEntry("use", "claim")
                .containsEntry("type", "Institutional")
                .containsEntry("subType", "Inpatient")
                .containsEntry("status", "active")
                .containsEntry("outcome", "partial")
                .containsEntry("billablePeriod", "2019-01-01 – 2019-10-31")
                .containsEntry("created", "2019-11-02T00:00:00+00:00")
                .containsEntry("provider", "XXX Health Plan [Organization/ProviderOrganization1]")
                .containsEntry("insurer", "XXX Health Plan [Organization/Payer1]")
                .containsEntry("items", "1")
                .containsEntry("total.submitted", "2650 USD")
                .containsEntry("total.paidtoprovider", "620 USD")
                .containsEntry("total.paidbypatient", "0 USD")
                .containsEntry("processNotes", "0")
                .doesNotContainKey("payment");
        Map<String, String> pa = ResourceSummaries.columns(Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1"));
        assertThat(pa).containsEntry("use", "preauthorization").containsEntry("claimId", "PA123412341234123412341234").containsEntry("total.eligible", "100 USD");
    }

    @Test
    void coverageColumnsIncludeClassesAndPayor() {
        Map<String, String> c = ResourceSummaries.columns(Fixtures.demo("c4bb-Coverage-Coverage1"));
        assertThat(c).containsEntry("status", "active")
                .containsEntry("subscriberId", "888009335")
                .containsEntry("beneficiary", "Patient/Patient1")
                .containsEntry("relationship", "Self")
                .containsEntry("period", "2020-01-01 – ")
                .containsEntry("payor", "UPMC Health Plan [Organization/Payer2]")
                .containsEntry("class.group", "MCHMO1 (MEDICARE HMO PLAN)")
                .containsEntry("class.plan", "GR5 (GR5-HMO DEDUCTIBLE)")
                .doesNotContainKey("type");
    }

    @Test
    void conditionColumnsRenderCodesAndOnset() {
        ObjectNode condition = Fixtures.resource("Condition", "c1");
        condition.putObject("code").putArray("coding").addObject().put("system", "http://snomed.info/sct").put("code", "44054006").put("display", "Diabetes mellitus type 2");
        condition.putArray("category").addObject().putArray("coding").addObject().put("code", "problem-list-item").put("display", "Problem List Item");
        condition.putObject("clinicalStatus").putArray("coding").addObject().put("code", "active");
        condition.put("onsetDateTime", "2015-03-01");
        condition.put("recordedDate", "2015-03-02");
        condition.putObject("encounter").put("reference", "Encounter/e1");
        Map<String, String> c = ResourceSummaries.columns(condition);
        assertThat(c).containsEntry("code", "Diabetes mellitus type 2 (44054006)")
                .containsEntry("category", "Problem List Item (problem-list-item)")
                .containsEntry("clinicalStatus", "active")
                .containsEntry("onset", "2015-03-01")
                .containsEntry("recordedDate", "2015-03-02")
                .containsEntry("encounter", "Encounter/e1")
                .doesNotContainKey("verificationStatus");
    }

    @Test
    void observationColumnsRenderSimpleAndComponentValues() {
        ObjectNode bp = Fixtures.resource("Observation", "bp");
        bp.put("status", "final");
        bp.putObject("code").put("text", "Blood pressure");
        bp.putArray("category").addObject().putArray("coding").addObject().put("code", "vital-signs");
        bp.put("effectiveDateTime", "2024-05-01T10:00:00Z");
        ObjectNode systolic = bp.putArray("component").addObject();
        systolic.putObject("code").put("text", "Systolic");
        systolic.putObject("valueQuantity").put("value", 120).put("unit", "mm[Hg]");
        ObjectNode diastolic = bp.withArray("component").addObject();
        diastolic.putObject("code").put("text", "Diastolic");
        diastolic.putObject("valueQuantity").put("value", 80).put("unit", "mm[Hg]");
        Map<String, String> c = ResourceSummaries.columns(bp);
        assertThat(c).containsEntry("code", "Blood pressure").containsEntry("category", "vital-signs")
                .containsEntry("value", "Systolic: 120 mm[Hg], Diastolic: 80 mm[Hg]")
                .containsEntry("effective", "2024-05-01T10:00:00Z").containsEntry("status", "final");

        ObjectNode glucose = Fixtures.resource("Observation", "g");
        glucose.putObject("code").put("text", "Glucose");
        glucose.putObject("valueQuantity").put("value", 5.4).put("code", "mmol/L");
        glucose.putObject("effectivePeriod").put("start", "2024-01-01").put("end", "2024-01-02");
        assertThat(ResourceSummaries.columns(glucose)).containsEntry("value", "5.4 mmol/L").containsEntry("effective", "2024-01-01 – 2024-01-02");

        ObjectNode absent = Fixtures.resource("Observation", "a");
        absent.putObject("dataAbsentReason").put("text", "not performed");
        assertThat(ResourceSummaries.columns(absent)).containsEntry("value", "absent: not performed");

        ObjectNode string = Fixtures.resource("Observation", "s");
        string.put("valueString", "positive");
        string.putObject("valueCodeableConcept");
        assertThat(ResourceSummaries.observationValue(string)).isEqualTo("positive");
    }

    @Test
    void rowCarriesIdentityProfilesAndChecks() {
        ObjectNode patient = Fixtures.demo("c4bb-Patient-Patient1");
        ProfileLiteChecker.Report report = ProfileLiteChecker.Report.none("skipped");
        ResourceRow row = ResourceSummaries.row(patient, report);
        assertThat(row.resourceType()).isEqualTo("Patient");
        assertThat(row.id()).isEqualTo("Patient1");
        assertThat(row.profiles()).hasSize(1);
        assertThat(row.lastUpdated()).isEqualTo("2020-07-07T13:26:22.0314215+00:00");
        assertThat(row.columns()).containsEntry("name", "Johnny Example1").containsEntry("identifiers", "MB 1234-234-1243-12345678901, um 1234-234-1243-12345678901u");
        assertThat(row.checks()).isSameAs(report);
        assertThat(row.resource()).isSameAs(patient);

        Map<String, String> org = ResourceSummaries.columns(Fixtures.demo("c4bb-Organization-Payer1"));
        assertThat(org).containsKey("name").containsKey("identifiers");
        ObjectNode unknown = Fixtures.resource("Appointment", "a").put("status", "booked");
        assertThat(ResourceSummaries.columns(unknown)).containsExactly(Map.entry("status", "booked"));
    }
}
