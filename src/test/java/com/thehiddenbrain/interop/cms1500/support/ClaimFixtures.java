package com.thehiddenbrain.interop.cms1500.support;

import com.thehiddenbrain.interop.cms1500.contract.Address;
import com.thehiddenbrain.interop.cms1500.contract.BillingProvider;
import com.thehiddenbrain.interop.cms1500.contract.Carrier;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.Condition;
import com.thehiddenbrain.interop.cms1500.contract.IcdIndicator;
import com.thehiddenbrain.interop.cms1500.contract.Insured;
import com.thehiddenbrain.interop.cms1500.contract.InsuranceType;
import com.thehiddenbrain.interop.cms1500.contract.OtherInsured;
import com.thehiddenbrain.interop.cms1500.contract.Patient;
import com.thehiddenbrain.interop.cms1500.contract.PersonName;
import com.thehiddenbrain.interop.cms1500.contract.ReferringProvider;
import com.thehiddenbrain.interop.cms1500.contract.Relationship;
import com.thehiddenbrain.interop.cms1500.contract.ServiceFacility;
import com.thehiddenbrain.interop.cms1500.contract.ServiceLine;
import com.thehiddenbrain.interop.cms1500.contract.Sex;
import com.thehiddenbrain.interop.cms1500.contract.Signature;
import com.thehiddenbrain.interop.cms1500.contract.TaxIdType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Claims used across the tests. All NPIs pass the check digit; all dates are in the past. */
public final class ClaimFixtures {

    public static final String NPI_BILLING = "1234567893";
    public static final String NPI_RENDERING = "9876543213";
    public static final String NPI_REFERRING = "1111111112";
    public static final String NPI_FACILITY = "1000000004";
    public static final String NPI_INVALID_CHECK = "1234567890";

    private ClaimFixtures() {
    }

    /** Every item of the form populated, three service lines totalling 535.50. */
    public static Cms1500Claim fullClaim(String claimNumber) {
        Cms1500Claim c = new Cms1500Claim();
        c.setClaimNumber(claimNumber);

        Carrier carrier = new Carrier();
        carrier.setName("Cigna Healthcare");
        carrier.setStreet("PO Box 188061");
        carrier.setCity("Chattanooga");
        carrier.setState("TN");
        carrier.setZip("37422");
        c.setCarrier(carrier);

        c.setInsuranceType(InsuranceType.GROUP_HEALTH_PLAN);
        c.setInsuredId("U12345678");

        Patient patient = new Patient();
        patient.setName(name("Doe", "John", "A"));
        patient.setDateOfBirth(LocalDate.of(1980, 5, 17));
        patient.setSex(Sex.M);
        patient.setAddress(address("123 Main St", "Springfield", "IL", "62704", "217", "5551234"));
        patient.setRelationshipToInsured(Relationship.SPOUSE);
        patient.setSignatureOnFile(true);
        patient.setSignatureDate(LocalDate.of(2026, 8, 1));
        c.setPatient(patient);

        Insured insured = new Insured();
        insured.setName(name("Doe", "Mary", "K"));
        insured.setAddress(address("123 Main St", "Springfield", "IL", "62704", "217", "5551234"));
        insured.setPolicyGroupNumber("GRP-4471");
        insured.setDateOfBirth(LocalDate.of(1982, 11, 3));
        insured.setSex(Sex.F);
        insured.setOtherClaimIdQualifier("Y4");
        insured.setOtherClaimId("PC-778");
        insured.setPlanName("Cigna Open Access Plus");
        insured.setAnotherHealthBenefitPlan(true);
        insured.setSignatureOnFile(true);
        c.setInsured(insured);

        OtherInsured other = new OtherInsured();
        other.setName(name("Doe", "Jane", "B"));
        other.setPolicyGroupNumber("OTH-2231");
        other.setPlanName("Aetna PPO");
        c.setOtherInsured(other);

        Condition condition = new Condition();
        condition.setEmploymentRelated(false);
        condition.setAutoAccident(true);
        condition.setAutoAccidentState("IL");
        condition.setOtherAccident(false);
        condition.setClaimCodes("W2");
        c.setCondition(condition);

        c.setDateOfCurrentIllness(LocalDate.of(2026, 7, 15));
        c.setDateOfCurrentIllnessQualifier("431");
        c.setOtherDate(LocalDate.of(2026, 7, 20));
        c.setOtherDateQualifier("454");
        c.setUnableToWorkFrom(LocalDate.of(2026, 7, 15));
        c.setUnableToWorkTo(LocalDate.of(2026, 7, 30));

        ReferringProvider ref = new ReferringProvider();
        ref.setQualifier("DN");
        ref.setName("Smith, Alice MD");
        ref.setOtherIdQualifier("G2");
        ref.setOtherId("REF-991");
        ref.setNpi(NPI_REFERRING);
        c.setReferringProvider(ref);

        c.setHospitalizationFrom(LocalDate.of(2026, 7, 16));
        c.setHospitalizationTo(LocalDate.of(2026, 7, 18));
        c.setAdditionalClaimInfo("PWK OZ 1234");
        c.setOutsideLab(true);
        c.setOutsideLabCharges(new BigDecimal("45.00"));
        c.setIcdIndicator(IcdIndicator.ICD_10);
        c.getDiagnoses().addAll(List.of("S82.101A", "M54.5", "E11.9", "I10"));
        c.setResubmissionCode("7");
        c.setOriginalReferenceNumber("ORIG-555");
        c.setPriorAuthorizationNumber("PA-2026-001");

        ServiceLine l1 = line(LocalDate.of(2026, 7, 16), "11", "99213", "AB", "150.00", "1");
        l1.setEmergency(true);
        l1.getModifiers().add("25");
        l1.setRenderingProviderIdQualifier("G2");
        l1.setRenderingProviderOtherId("RP-100");
        l1.setRenderingProviderNpi(NPI_RENDERING);
        l1.setSupplementalInfo("N4 12345678901 UN 1");
        ServiceLine l2 = line(LocalDate.of(2026, 7, 16), "11", "73590", "A", "85.50", "1");
        l2.getModifiers().addAll(List.of("RT", "26"));
        l2.setRenderingProviderNpi(NPI_RENDERING);
        ServiceLine l3 = line(LocalDate.of(2026, 7, 18), "22", "29405", "AC", "300.00", "2");
        l3.setDateTo(LocalDate.of(2026, 7, 18));
        l3.getModifiers().add("LT");
        l3.setEpsdt("AV");
        l3.setFamilyPlanning(true);
        l3.setRenderingProviderNpi(NPI_RENDERING);
        c.getServiceLines().addAll(List.of(l1, l2, l3));

        c.setFederalTaxId("123456789");
        c.setFederalTaxIdType(TaxIdType.EIN);
        c.setPatientAccountNumber("ACCT-0099");
        c.setAcceptAssignment(true);
        c.setAmountPaid(new BigDecimal("100.00"));

        Signature sig = new Signature();
        sig.setName("Robert Jones MD");
        sig.setDate(LocalDate.of(2026, 8, 2));
        c.setPhysicianSignature(sig);

        ServiceFacility facility = new ServiceFacility();
        facility.setName("Springfield Ortho Center");
        facility.setStreet("500 Clinic Dr");
        facility.setCity("Springfield");
        facility.setState("IL");
        facility.setZip("62704");
        facility.setNpi(NPI_FACILITY);
        facility.setOtherId("FAC-77");
        c.setServiceFacility(facility);

        BillingProvider bp = new BillingProvider();
        bp.setName("Springfield Orthopedics LLC");
        bp.setStreet("500 Clinic Dr Ste 200");
        bp.setCity("Springfield");
        bp.setState("IL");
        bp.setZip("62704-1234");
        bp.setPhoneAreaCode("217");
        bp.setPhoneNumber("5559876");
        bp.setNpi(NPI_BILLING);
        bp.setOtherId("BP-12");
        c.setBillingProvider(bp);
        return c;
    }

    /** Only the required items: a self-insured patient, one diagnosis, one service line, billing provider. */
    public static Cms1500Claim minimalClaim(String claimNumber) {
        Cms1500Claim c = new Cms1500Claim();
        c.setClaimNumber(claimNumber);
        c.setInsuranceType(InsuranceType.MEDICARE);
        c.setInsuredId("1EG4TE5MK72");
        Patient patient = new Patient();
        patient.setName(name("Roe", "Richard", null));
        patient.setDateOfBirth(LocalDate.of(1950, 1, 2));
        patient.setSex(Sex.M);
        patient.setAddress(address("9 Elm Ave", "Peoria", "IL", "61602", null, null));
        patient.setRelationshipToInsured(Relationship.SELF);
        c.setPatient(patient);
        c.getDiagnoses().add("I10");
        c.getServiceLines().add(line(LocalDate.of(2026, 6, 1), "11", "99214", "A", "120.00", "1"));
        BillingProvider bp = new BillingProvider();
        bp.setName("Peoria Family Practice");
        bp.setStreet("1 Health Way");
        bp.setCity("Peoria");
        bp.setState("IL");
        bp.setZip("61602");
        bp.setNpi(NPI_BILLING);
        c.setBillingProvider(bp);
        return c;
    }

    /** Replaces the service lines with {@code count} generated lines (CPT 90000+i, 10.00 each). */
    public static Cms1500Claim withServiceLines(Cms1500Claim claim, int count) {
        claim.getServiceLines().clear();
        for (int i = 0; i < count; i++) {
            ServiceLine line = line(LocalDate.of(2026, 7, 1).plusDays(i % 20), "11", String.valueOf(90000 + i),
                    "A", "10.00", "1");
            line.setRenderingProviderNpi(NPI_RENDERING);
            claim.getServiceLines().add(line);
        }
        return claim;
    }

    public static ServiceLine line(LocalDate date, String pos, String cpt, String pointers, String charges, String units) {
        ServiceLine line = new ServiceLine();
        line.setDateFrom(date);
        line.setPlaceOfService(pos);
        line.setProcedureCode(cpt);
        line.setDiagnosisPointers(pointers);
        line.setCharges(new BigDecimal(charges));
        line.setUnits(new BigDecimal(units));
        return line;
    }

    public static PersonName name(String last, String first, String middle) {
        PersonName n = new PersonName();
        n.setLastName(last);
        n.setFirstName(first);
        n.setMiddleInitial(middle);
        return n;
    }

    public static Address address(String street, String city, String state, String zip, String area, String phone) {
        Address a = new Address();
        a.setStreet(street);
        a.setCity(city);
        a.setState(state);
        a.setZip(zip);
        a.setPhoneAreaCode(area);
        a.setPhoneNumber(phone);
        return a;
    }
}
