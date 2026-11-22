package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The fields F1 adds to the shared model: the vendor's own ids, the fax, the carried coverage information and two lookup states. */
class DetailAdditionsTest {

    @Test
    void aMemberCarriesTheVendorPatientIdThroughItsBuilderAndNeverPrintsIt() {
        MemberDetail member = MemberDetail.builder().memberId("S123456789").vendorPatientId("P32H-PATIENT-7").build();
        assertThat(member.vendorPatientId()).isEqualTo("P32H-PATIENT-7");
        assertThat(member.toBuilder().build().vendorPatientId()).isEqualTo("P32H-PATIENT-7");
        assertThat(member.toString()).doesNotContain("P32H-PATIENT-7").doesNotContain("S123456789");
        JsonNode json = JsonSafe.mapper().valueToTree(member);
        assertThat(json.get("vendorPatientId").asString()).isEqualTo("P32H-PATIENT-7");
        assertThat(JsonSafe.mapper().valueToTree(MemberDetail.builder().memberId("S123456789").build()).has("vendorPatientId"))
                .as("not stated is left out, never written as a default").isFalse();
    }

    @Test
    void aProviderCarriesFaxAndTheVendorPractitionerIdThroughItsBuilder() {
        ProviderDetail provider = ProviderDetail.builder().npi("1234567893").phone("555-0100").fax("555-0101")
                .vendorPractitionerId("P123").build();
        assertThat(provider.fax()).isEqualTo("555-0101");
        assertThat(provider.vendorPractitionerId()).isEqualTo("P123");
        ProviderDetail copy = provider.toBuilder().build();
        assertThat(copy).isEqualTo(provider);
        assertThat(provider.toString()).doesNotContain("555-0101");
        ProviderDetail vendorOnly = ProviderDetail.builder().vendorPractitionerId("P124").build();
        assertThat(vendorOnly.npi()).isNull();
        assertThat(JsonSafe.mapper().valueToTree(vendorOnly).propertyNames()).containsExactly("vendorPractitionerId");
    }

    @Test
    void coverageInformationIsCarriedUnderItsOwnKeyAndWords() {
        String value = "{\"order\":\"ServiceRequest/service-request-1\",\"extension\":{\"url\":\"x\"}}";
        NextStepView next = new NextStepView(NextAction.FIX_TEST_DATA, "Next: answer the questionnaire.", null,
                Map.of(NextStepView.CARRY_COVERAGE_INFORMATION, List.of(value)));
        assertThat(NextStepView.CARRY_COVERAGE_INFORMATION).isEqualTo("coverageInformation");
        assertThat(next.first("coverageInformation")).isEqualTo(value);
        assertThat(next.carryLabels()).containsEntry("coverageInformation", "Coverage information CRD returned");
    }

    @Test
    void severalMatchedAndNotAnsweringAreTheirOwnStates() {
        assertThat(LookupStatus.SEVERAL.label()).isEqualTo("Several matched");
        assertThat(LookupStatus.NOT_ANSWERING.label()).isEqualTo("Not answering");
        assertThat(LookupStatus.FOUND.label()).isEqualTo("Found");
        assertThat(LookupStatus.NOT_FOUND.label()).isEqualTo("Not found");
        assertThat(LookupStatus.FAULT.label()).isEqualTo("The lookup failed");
        assertThat(LookupStatus.DISABLED.label()).isEqualTo("No lookup is configured");
    }
}
