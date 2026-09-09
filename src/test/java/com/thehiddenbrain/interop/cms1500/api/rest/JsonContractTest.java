package com.thehiddenbrain.interop.cms1500.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.cms1500.contract.ClaimBundleResult;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.IcdIndicator;
import com.thehiddenbrain.interop.cms1500.contract.ResultStatus;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;
import com.thehiddenbrain.interop.cms1500.config.JacksonConfig;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON binding must mirror the XSD: element names, ISO dates, enum values, booleans. */
@JsonTest
@Import(JacksonConfig.class)
class JsonContractTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void claimRoundTripsThroughJsonWithXsdNames() throws Exception {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-JSON");
        String json = mapper.writeValueAsString(claim);
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.get("claimNumber").asText()).isEqualTo("CLM-JSON");
        assertThat(tree.get("icdIndicator").asText()).as("XML enum value, not Java constant").isEqualTo("ICD10");
        assertThat(tree.get("insuranceType").asText()).isEqualTo("GROUP_HEALTH_PLAN");
        assertThat(tree.get("patient").get("dateOfBirth").asText()).isEqualTo("1980-05-17");
        assertThat(tree.get("patient").get("signatureOnFile").asBoolean()).isTrue();
        assertThat(tree.get("outsideLab").asBoolean()).isTrue();
        assertThat(tree.get("diagnoses").isArray()).isTrue();
        assertThat(tree.get("serviceLines").size()).isEqualTo(3);
        assertThat(tree.get("serviceLines").get(0).get("emergency").asBoolean()).isTrue();
        assertThat(tree.get("serviceLines").get(0).get("modifiers").get(0).asText()).isEqualTo("25");
        assertThat(tree.get("serviceLines").get(1).get("charges").decimalValue()).isEqualByComparingTo("85.50");
        assertThat(tree.has("totalCharge")).as("nulls omitted").isFalse();

        Cms1500Claim back = mapper.readValue(json, Cms1500Claim.class);
        assertThat(back.getIcdIndicator()).isEqualTo(IcdIndicator.ICD_10);
        assertThat(back.getPatient().getDateOfBirth()).isEqualTo(claim.getPatient().getDateOfBirth());
        assertThat(back.isOutsideLab()).isTrue();
        assertThat(back.getServiceLines().get(0).isEmergency()).isTrue();
        assertThat(back.getServiceLines().get(2).getUnits()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(back.getDiagnoses()).containsExactly("S82.101A", "M54.5", "E11.9", "I10");
        assertThat(mapper.writeValueAsString(back)).isEqualTo(json);
    }

    @Test
    void enumValuesFromTheXsdAreAcceptedOnInput() throws Exception {
        String json = """
                {"claimNumber":"X1","insuranceType":"MEDICARE","icdIndicator":"ICD9",
                 "patient":{"name":{"lastName":"A","firstName":"B"},"dateOfBirth":"1950-01-02","sex":"F",
                            "address":{"street":"s","city":"c","state":"IL","zip":"61602"},"relationshipToInsured":"SELF"},
                 "serviceLines":[{"dateFrom":"2026-01-01","placeOfService":"11","procedureCode":"99213",
                                  "diagnosisPointers":"A","charges":10,"units":1}],
                 "billingProvider":{"name":"n","street":"s","city":"c","state":"IL","zip":"61602","npi":"1234567893"}}
                """;
        Cms1500Claim claim = mapper.readValue(json, Cms1500Claim.class);
        assertThat(claim.getIcdIndicator()).isEqualTo(IcdIndicator.ICD_9);
        assertThat(claim.getServiceLines().get(0).getCharges()).isEqualByComparingTo("10");
    }

    @Test
    void recordDtosWorkWithTheJaxbAwareMapper() throws Exception {
        com.thehiddenbrain.interop.cms1500.config.SettingsUpdate update = mapper.readValue(
                "{\"outputRoot\":\"/x\",\"allowedExtensions\":[\"pdf\"],\"overwrite\":false,\"unsupported\":\"SKIP\"}",
                com.thehiddenbrain.interop.cms1500.config.SettingsUpdate.class);
        assertThat(update.outputRoot()).isEqualTo("/x");
        assertThat(update.overwrite()).isFalse();
        assertThat(update.unsupported()).isEqualTo(com.thehiddenbrain.interop.cms1500.config.Cms1500Properties.UnsupportedPolicy.SKIP);
        assertThat(update.template()).isNull();

        com.thehiddenbrain.interop.cms1500.service.ValidationReport report =
                new com.thehiddenbrain.interop.cms1500.service.ValidationReport(false,
                        java.util.List.of(com.thehiddenbrain.interop.cms1500.domain.ClaimException.detail("f", "m")), java.util.List.of("w"));
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(report));
        assertThat(tree.get("valid").asBoolean()).isFalse();
        assertThat(tree.get("errors").get(0).get("field").asText()).isEqualTo("f");
        assertThat(tree.get("warnings").get(0).asText()).isEqualTo("w");

        com.thehiddenbrain.interop.cms1500.service.BundleInfo info = new com.thehiddenbrain.interop.cms1500.service.BundleInfo(
                "C1", "C1.pdf", 12, OffsetDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneOffset.UTC));
        JsonNode infoTree = mapper.readTree(mapper.writeValueAsString(info));
        assertThat(infoTree.get("claimNumber").asText()).isEqualTo("C1");
        assertThat(infoTree.get("sizeBytes").asLong()).isEqualTo(12);
        assertThat(infoTree.get("modifiedAt").asText()).startsWith("2026-09-09T10:00");
    }

    @Test
    void resultSerializesTimestampsAsIso() throws Exception {
        ClaimBundleResult result = new ClaimBundleResult();
        result.setStatus(ResultStatus.GENERATED);
        result.setClaimNumber("C1");
        result.setFileName("C1.pdf");
        result.setBundlePath("/tmp/C1.pdf");
        result.setGeneratedAt(OffsetDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneOffset.UTC));
        result.getWarnings().add("w");
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(result));
        assertThat(tree.get("status").asText()).isEqualTo("GENERATED");
        assertThat(tree.get("generatedAt").asText()).startsWith("2026-09-09T10:00:00");
        assertThat(tree.get("warnings").get(0).asText()).isEqualTo("w");
        assertThat(tree.get("replacedExisting").asBoolean()).isFalse();
    }
}
