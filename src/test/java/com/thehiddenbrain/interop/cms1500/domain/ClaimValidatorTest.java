package com.thehiddenbrain.interop.cms1500.domain;

import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.IcdIndicator;
import com.thehiddenbrain.interop.cms1500.contract.Relationship;
import com.thehiddenbrain.interop.cms1500.contract.ServiceLine;
import com.thehiddenbrain.interop.cms1500.contract.Signature;
import com.thehiddenbrain.interop.cms1500.contract.TaxIdType;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    private final ClaimValidator validator = new ClaimValidator();

    @Test
    void fullClaimIsValidWithoutWarnings() {
        ClaimValidator.Result result = validator.validate(ClaimFixtures.fullClaim("CLM-1"), TODAY);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void minimalSelfInsuredClaimIsValidWithWarnings() {
        ClaimValidator.Result result = validator.validate(ClaimFixtures.minimalClaim("CLM-2"), TODAY);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("insured omitted"))
                .anyMatch(w -> w.contains("federalTaxId"));
    }

    @Test
    void nullClaimIsRejected() {
        assertThat(validator.validate(null, TODAY).errors()).extracting(ViolationDetail::getField).containsExactly("claim");
    }

    @Test
    void claimNumberMustBeFileNameSafe() {
        assertThat(errors(c -> c.setClaimNumber("../etc"))).contains("claimNumber");
        assertThat(errors(c -> c.setClaimNumber("a b"))).contains("claimNumber");
        assertThat(errors(c -> c.setClaimNumber(""))).contains("claimNumber");
        assertThat(errors(c -> c.setClaimNumber(null))).contains("claimNumber");
        assertThat(errors(c -> c.setClaimNumber("A".repeat(65)))).contains("claimNumber");
        assertThat(errors(c -> c.setClaimNumber("CLM_2026.09-001"))).doesNotContain("claimNumber");
    }

    @Test
    void patientRulesAreEnforced() {
        assertThat(errors(c -> c.setPatient(null))).contains("patient");
        assertThat(errors(c -> c.getPatient().setDateOfBirth(TODAY.plusDays(1)))).contains("patient.dateOfBirth");
        assertThat(errors(c -> c.getPatient().setDateOfBirth(null))).contains("patient.dateOfBirth");
        assertThat(errors(c -> c.getPatient().setSex(null))).contains("patient.sex");
        assertThat(errors(c -> c.getPatient().getName().setLastName(" "))).contains("patient.name.lastName");
        assertThat(errors(c -> c.getPatient().getName().setLastName("X".repeat(29)))).contains("patient.name.lastName");
        assertThat(errors(c -> c.getPatient().getName().setMiddleInitial("AB"))).contains("patient.name.middleInitial");
        assertThat(errors(c -> c.getPatient().getAddress().setZip("1234"))).contains("patient.address.zip");
        assertThat(errors(c -> c.getPatient().getAddress().setState("Illinois"))).contains("patient.address.state");
        assertThat(errors(c -> c.getPatient().getAddress().setPhoneNumber(null))).contains("patient.address.phoneNumber");
        assertThat(errors(c -> c.getPatient().getAddress().setPhoneAreaCode("21"))).contains("patient.address.phoneAreaCode");
        assertThat(errors(c -> c.getPatient().setRelationshipToInsured(null))).contains("patient.relationshipToInsured");
        assertThat(errors(c -> c.getPatient().setSignatureDate(TODAY.plusDays(3)))).contains("patient.signatureDate");
    }

    @Test
    void insuredIsRequiredUnlessPatientIsSelf() {
        assertThat(errors(c -> c.setInsured(null))).contains("insured");
        assertThat(errors(c -> {
            c.getPatient().setRelationshipToInsured(Relationship.SELF);
            c.setInsured(null);
        })).doesNotContain("insured");
        assertThat(errors(c -> c.getInsured().setName(null))).contains("insured.name");
        assertThat(errors(c -> c.getInsured().setAddress(null))).contains("insured.address");
        assertThat(errors(c -> c.getInsured().setOtherClaimIdQualifier(null))).contains("insured.otherClaimIdQualifier");
        assertThat(errors(c -> c.getInsured().setOtherClaimIdQualifier("ZZ"))).contains("insured.otherClaimIdQualifier");
        assertThat(errors(c -> c.getInsured().setDateOfBirth(TODAY.plusYears(1)))).contains("insured.dateOfBirth");
        assertThat(errors(c -> c.setOtherInsured(null))).as("11d is yes").contains("otherInsured");
        assertThat(errors(c -> {
            c.getInsured().setAnotherHealthBenefitPlan(false);
            c.setOtherInsured(null);
        })).doesNotContain("otherInsured");
        assertThat(errors(c -> c.getOtherInsured().setName(null))).contains("otherInsured.name");
        assertThat(warnings(c -> c.getInsured().setPolicyGroupNumber(null))).anyMatch(w -> w.contains("policyGroupNumber"));
    }

    @Test
    void conditionRulesAreEnforced() {
        assertThat(errors(c -> c.getCondition().setAutoAccidentState(null))).contains("condition.autoAccidentState");
        assertThat(errors(c -> c.getCondition().setAutoAccidentState("ILL"))).contains("condition.autoAccidentState");
        assertThat(errors(c -> c.getCondition().setAutoAccident(false))).contains("condition.autoAccidentState");
        assertThat(errors(c -> {
            c.getCondition().setAutoAccident(null);
            c.getCondition().setAutoAccidentState(null);
        })).doesNotContain("condition.autoAccidentState");
    }

    @Test
    void dateAndQualifierPairsAreEnforced() {
        assertThat(errors(c -> c.setDateOfCurrentIllnessQualifier(null))).contains("dateOfCurrentIllnessQualifier");
        assertThat(errors(c -> c.setDateOfCurrentIllness(null))).contains("dateOfCurrentIllness");
        assertThat(errors(c -> c.setDateOfCurrentIllnessQualifier("999"))).contains("dateOfCurrentIllnessQualifier");
        assertThat(errors(c -> c.setDateOfCurrentIllness(TODAY.plusDays(1)))).contains("dateOfCurrentIllness");
        assertThat(errors(c -> c.setOtherDateQualifier("123"))).contains("otherDateQualifier");
        assertThat(errors(c -> c.setOtherDate(null))).contains("otherDate");
        assertThat(errors(c -> c.setUnableToWorkTo(c.getUnableToWorkFrom().minusDays(1)))).contains("unableToWorkTo");
        assertThat(errors(c -> c.setUnableToWorkFrom(null))).contains("unableToWorkFrom");
        assertThat(errors(c -> c.setHospitalizationTo(c.getHospitalizationFrom().minusDays(1)))).contains("hospitalizationTo");
        assertThat(errors(c -> c.setHospitalizationFrom(null))).contains("hospitalizationFrom");
    }

    @Test
    void referringProviderRulesAreEnforced() {
        assertThat(errors(c -> c.getReferringProvider().setQualifier("XX"))).contains("referringProvider.qualifier");
        assertThat(errors(c -> c.getReferringProvider().setName(""))).contains("referringProvider.name");
        assertThat(errors(c -> c.getReferringProvider().setOtherId(null))).contains("referringProvider.otherId");
        assertThat(errors(c -> c.getReferringProvider().setOtherIdQualifier("99"))).contains("referringProvider.otherIdQualifier");
        assertThat(errors(c -> c.getReferringProvider().setNpi(ClaimFixtures.NPI_INVALID_CHECK))).contains("referringProvider.npi");
        assertThat(errors(c -> c.getReferringProvider().setNpi("12345"))).contains("referringProvider.npi");
    }

    @Test
    void outsideLabChargesNeedOutsideLabYes() {
        assertThat(errors(c -> c.setOutsideLab(false))).contains("outsideLabCharges");
        assertThat(errors(c -> c.setOutsideLab(null))).contains("outsideLabCharges");
        assertThat(errors(c -> c.setOutsideLabCharges(new BigDecimal("-1")))).contains("outsideLabCharges");
    }

    @Test
    void diagnosisRulesAreEnforced() {
        assertThat(errors(c -> c.getDiagnoses().clear())).contains("diagnoses");
        assertThat(errors(c -> {
            c.getDiagnoses().clear();
            for (int i = 0; i < 13; i++) {
                c.getDiagnoses().add("I1" + i % 10);
            }
        })).contains("diagnoses");
        assertThat(errors(c -> c.getDiagnoses().set(0, "123"))).contains("diagnoses[0]");
        assertThat(errors(c -> c.getDiagnoses().set(1, ""))).contains("diagnoses[1]");
        assertThat(errors(c -> c.getDiagnoses().set(1, "M54.5.1"))).contains("diagnoses[1]");
        assertThat(errors(c -> {
            c.setIcdIndicator(IcdIndicator.ICD_9);
            c.getDiagnoses().clear();
            c.getDiagnoses().addAll(List.of("250.00", "V70.0", "E812.1", "401"));
        })).isEmpty();
        assertThat(errors(c -> {
            c.setIcdIndicator(IcdIndicator.ICD_9);
            c.getDiagnoses().set(0, "S82.101A");
        })).contains("diagnoses[0]");
        assertThat(warnings(c -> c.getDiagnoses().set(1, "S82101A"))).anyMatch(w -> w.contains("duplicate"));
    }

    @Test
    void resubmissionCodeNeedsOriginalReference() {
        assertThat(errors(c -> c.setOriginalReferenceNumber(null))).contains("originalReferenceNumber");
        assertThat(errors(c -> {
            c.setResubmissionCode(null);
            c.setOriginalReferenceNumber(null);
        })).doesNotContain("originalReferenceNumber");
    }

    @Test
    void serviceLineRulesAreEnforced() {
        assertThat(errors(c -> c.getServiceLines().clear())).contains("serviceLines");
        assertThat(errors(c -> ClaimFixtures.withServiceLines(c, 61))).contains("serviceLines");
        assertThat(errors(c -> ClaimFixtures.withServiceLines(c, 60))).doesNotContain("serviceLines");
        assertThat(errors(c -> line(c).setDateFrom(TODAY.plusDays(1)))).contains("serviceLines[0].dateFrom");
        assertThat(errors(c -> line(c).setDateFrom(null))).contains("serviceLines[0].dateFrom");
        assertThat(errors(c -> line(c).setDateTo(line(c).getDateFrom().minusDays(1)))).contains("serviceLines[0].dateTo");
        assertThat(errors(c -> line(c).setDateFrom(LocalDate.of(1970, 1, 1)))).as("before DOB").contains("serviceLines[0].dateFrom");
        assertThat(errors(c -> line(c).setPlaceOfService("ABC"))).contains("serviceLines[0].placeOfService");
        assertThat(errors(c -> line(c).setProcedureCode("1234"))).contains("serviceLines[0].procedureCode");
        assertThat(errors(c -> line(c).getModifiers().addAll(List.of("A1", "A2", "A3", "A4")))).contains("serviceLines[0].modifiers");
        assertThat(errors(c -> line(c).getModifiers().set(0, "ABC"))).contains("serviceLines[0].modifiers[0]");
        assertThat(errors(c -> line(c).setDiagnosisPointers("AE"))).as("E has no diagnosis").contains("serviceLines[0].diagnosisPointers");
        assertThat(errors(c -> line(c).setDiagnosisPointers("AA"))).contains("serviceLines[0].diagnosisPointers");
        assertThat(errors(c -> line(c).setDiagnosisPointers("A,B"))).contains("serviceLines[0].diagnosisPointers");
        assertThat(errors(c -> line(c).setDiagnosisPointers(null))).contains("serviceLines[0].diagnosisPointers");
        assertThat(errors(c -> line(c).setDiagnosisPointers("abcd"))).doesNotContain("serviceLines[0].diagnosisPointers");
        assertThat(errors(c -> line(c).setCharges(new BigDecimal("-5")))).contains("serviceLines[0].charges");
        assertThat(errors(c -> line(c).setCharges(new BigDecimal("1.005")))).contains("serviceLines[0].charges");
        assertThat(errors(c -> line(c).setCharges(null))).contains("serviceLines[0].charges");
        assertThat(errors(c -> line(c).setUnits(BigDecimal.ZERO))).contains("serviceLines[0].units");
        assertThat(errors(c -> line(c).setUnits(new BigDecimal("10000")))).contains("serviceLines[0].units");
        assertThat(errors(c -> line(c).setUnits(new BigDecimal("1.5")))).doesNotContain("serviceLines[0].units");
        assertThat(errors(c -> line(c).setEpsdt("XX"))).contains("serviceLines[0].epsdt");
        assertThat(errors(c -> line(c).setRenderingProviderIdQualifier(null))).contains("serviceLines[0].renderingProviderIdQualifier");
        assertThat(errors(c -> line(c).setRenderingProviderNpi(ClaimFixtures.NPI_INVALID_CHECK))).contains("serviceLines[0].renderingProviderNpi");
        assertThat(errors(c -> line(c).setSupplementalInfo("x".repeat(62)))).contains("serviceLines[0].supplementalInfo");
    }

    @Test
    void taxIdAndTotalsAreEnforced() {
        assertThat(errors(c -> c.setFederalTaxId("12345678"))).contains("federalTaxId");
        assertThat(errors(c -> c.setFederalTaxIdType(null))).contains("federalTaxIdType");
        assertThat(errors(c -> {
            c.setFederalTaxId(null);
            c.setFederalTaxIdType(TaxIdType.SSN);
        })).contains("federalTaxId");
        assertThat(errors(c -> c.setTotalCharge(new BigDecimal("535.50")))).doesNotContain("totalCharge");
        assertThat(errors(c -> c.setTotalCharge(new BigDecimal("500.00")))).contains("totalCharge");
        assertThat(errors(c -> c.setAmountPaid(new BigDecimal("10000000")))).contains("amountPaid");
        assertThat(errors(c -> c.setPatientAccountNumber("123456789012345"))).contains("patientAccountNumber");
        assertThat(errors(c -> c.setAdditionalClaimInfo("x".repeat(72)))).contains("additionalClaimInfo");
    }

    @Test
    void providerRulesAreEnforced() {
        assertThat(errors(c -> c.setPhysicianSignature(new Signature()))).contains("physicianSignature");
        assertThat(errors(c -> {
            Signature s = new Signature();
            s.setOnFile(true);
            c.setPhysicianSignature(s);
        })).doesNotContain("physicianSignature");
        assertThat(errors(c -> c.getPhysicianSignature().setDate(TODAY.plusDays(1)))).contains("physicianSignature.date");
        assertThat(errors(c -> c.getServiceFacility().setNpi(ClaimFixtures.NPI_INVALID_CHECK))).contains("serviceFacility.npi");
        assertThat(errors(c -> c.getServiceFacility().setName(null))).contains("serviceFacility.name");
        assertThat(errors(c -> c.setBillingProvider(null))).contains("billingProvider");
        assertThat(errors(c -> c.getBillingProvider().setNpi(null))).contains("billingProvider.npi");
        assertThat(errors(c -> c.getBillingProvider().setNpi(ClaimFixtures.NPI_INVALID_CHECK))).contains("billingProvider.npi");
        assertThat(errors(c -> c.getBillingProvider().setZip("ABCDE"))).contains("billingProvider.zip");
        assertThat(errors(c -> c.getBillingProvider().setPhoneAreaCode(null))).contains("billingProvider.phoneAreaCode");
        assertThat(errors(c -> c.getCarrier().setState("T"))).contains("carrier.state");
        assertThat(errors(c -> c.setInsuranceType(null))).contains("insuranceType");
    }

    @Test
    void allViolationsAreReportedTogether() {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-MANY");
        claim.setClaimNumber("bad claim");
        claim.getPatient().setSex(null);
        claim.getBillingProvider().setNpi("1");
        ClaimValidator.Result result = validator.validate(claim, TODAY);
        assertThat(result.errors()).extracting(ViolationDetail::getField)
                .containsExactlyInAnyOrder("claimNumber", "patient.sex", "billingProvider.npi");
        assertThat(result.errors()).allSatisfy(v -> assertThat(v.getMessage()).isNotBlank());
    }

    private static ServiceLine line(Cms1500Claim c) {
        return c.getServiceLines().get(0);
    }

    private List<String> errors(Consumer<Cms1500Claim> mutation) {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-T");
        mutation.accept(claim);
        return validator.validate(claim, TODAY).errors().stream().map(ViolationDetail::getField).toList();
    }

    private List<String> warnings(Consumer<Cms1500Claim> mutation) {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-T");
        mutation.accept(claim);
        return validator.validate(claim, TODAY).warnings();
    }
}
