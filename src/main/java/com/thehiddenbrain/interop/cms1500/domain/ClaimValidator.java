package com.thehiddenbrain.interop.cms1500.domain;

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
import com.thehiddenbrain.interop.cms1500.contract.Signature;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic validation of a CMS-1500 claim: required items, value formats, cross-field rules
 * (accident state, date ranges, pointer/diagnosis references, totals) and the NPI check digit.
 * Produces hard errors (the claim is rejected) and soft warnings (returned with the result).
 */
@Component
public class ClaimValidator {

    public static final int MAX_SERVICE_LINES = 60;
    public static final int MAX_DIAGNOSES = 12;
    public static final int MAX_MODIFIERS = 4;
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999.99");
    private static final BigDecimal MAX_UNITS = new BigDecimal("9999.99");

    public record Result(List<ViolationDetail> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Result validate(Cms1500Claim claim) {
        return validate(claim, LocalDate.now());
    }

    Result validate(Cms1500Claim claim, LocalDate today) {
        Collector c = new Collector();
        if (claim == null) {
            c.error("claim", "claim is required");
            return c.result();
        }
        c.requireMatch("claimNumber", claim.getClaimNumber(), Patterns.CLAIM_NUMBER,
                "1-64 letters, digits, '.', '_' or '-' starting with a letter or digit");
        c.require("insuranceType", claim.getInsuranceType());
        c.maxLen("insuredId", claim.getInsuredId(), 29);
        if (claim.getInsuranceType() != null && claim.getInsuranceType() != InsuranceType.OTHER
                && isBlank(claim.getInsuredId())) {
            c.warn("insuredId (item 1a) is empty");
        }

        validateCarrier(c, claim.getCarrier());
        validatePatient(c, claim.getPatient(), today);
        validateInsured(c, claim, today);
        validateOtherInsured(c, claim);
        validateCondition(c, claim.getCondition());
        validateClaimDates(c, claim, today);
        validateReferring(c, claim.getReferringProvider());
        c.maxLen("additionalClaimInfo", claim.getAdditionalClaimInfo(), 71);
        validateOutsideLab(c, claim);
        validateDiagnoses(c, claim);
        validateResubmission(c, claim);
        c.maxLen("priorAuthorizationNumber", claim.getPriorAuthorizationNumber(), 29);
        validateServiceLines(c, claim, today);
        validateTaxId(c, claim);
        c.maxLen("patientAccountNumber", claim.getPatientAccountNumber(), 14);
        validateTotals(c, claim);
        validateSignature(c, claim.getPhysicianSignature(), today);
        validateFacility(c, claim.getServiceFacility());
        validateBillingProvider(c, claim.getBillingProvider());
        return c.result();
    }

    private void validateCarrier(Collector c, Carrier carrier) {
        if (carrier == null) {
            return;
        }
        c.requireText("carrier.name", carrier.getName(), 40);
        c.maxLen("carrier.street", carrier.getStreet(), 40);
        c.maxLen("carrier.street2", carrier.getStreet2(), 40);
        c.maxLen("carrier.city", carrier.getCity(), 24);
        c.optionalMatch("carrier.state", carrier.getState(), Patterns.STATE, "two-letter state code");
        c.optionalMatch("carrier.zip", carrier.getZip(), Patterns.ZIP, "5 or 9 digit ZIP code");
    }

    private void validatePatient(Collector c, Patient patient, LocalDate today) {
        if (patient == null) {
            c.error("patient", "patient (items 2-6) is required");
            return;
        }
        validateName(c, "patient.name", patient.getName(), true);
        c.require("patient.dateOfBirth", patient.getDateOfBirth());
        c.notFuture("patient.dateOfBirth", patient.getDateOfBirth(), today);
        c.require("patient.sex", patient.getSex());
        validateAddress(c, "patient.address", patient.getAddress(), true);
        c.require("patient.relationshipToInsured", patient.getRelationshipToInsured());
        c.notFuture("patient.signatureDate", patient.getSignatureDate(), today);
    }

    private void validateInsured(Collector c, Cms1500Claim claim, LocalDate today) {
        Insured insured = claim.getInsured();
        Relationship relationship = claim.getPatient() == null ? null : claim.getPatient().getRelationshipToInsured();
        boolean self = relationship == Relationship.SELF;
        if (insured == null) {
            if (relationship != null && !self) {
                c.error("insured", "insured (items 4, 7, 11) is required when the patient is not the insured");
            } else if (self) {
                c.warn("insured omitted: items 4, 7 and 11a are copied from the patient; item 11 (policy/group number) is blank");
            }
            return;
        }
        if (insured.getName() == null && !self) {
            c.error("insured.name", "insured name (item 4) is required when the patient is not the insured");
        }
        validateName(c, "insured.name", insured.getName(), false);
        if (insured.getAddress() == null && !self) {
            c.error("insured.address", "insured address (item 7) is required when the patient is not the insured");
        }
        validateAddress(c, "insured.address", insured.getAddress(), false);
        c.maxLen("insured.policyGroupNumber", insured.getPolicyGroupNumber(), 29);
        if (isBlank(insured.getPolicyGroupNumber())) {
            c.warn("insured.policyGroupNumber (item 11) is empty; enter NONE when Medicare is primary");
        }
        c.notFuture("insured.dateOfBirth", insured.getDateOfBirth(), today);
        c.optionalMatch("insured.otherClaimIdQualifier", insured.getOtherClaimIdQualifier(),
                Patterns.OTHER_CLAIM_ID_QUALIFIER, "Y4");
        c.maxLen("insured.otherClaimId", insured.getOtherClaimId(), 28);
        c.pair("insured.otherClaimIdQualifier", insured.getOtherClaimIdQualifier(),
                "insured.otherClaimId", insured.getOtherClaimId());
        c.maxLen("insured.planName", insured.getPlanName(), 29);
        if (Boolean.TRUE.equals(insured.isAnotherHealthBenefitPlan()) && claim.getOtherInsured() == null) {
            c.error("otherInsured", "otherInsured (items 9, 9a, 9d) is required when insured.anotherHealthBenefitPlan is true");
        }
    }

    private void validateOtherInsured(Collector c, Cms1500Claim claim) {
        OtherInsured other = claim.getOtherInsured();
        if (other == null) {
            return;
        }
        if (other.getName() == null) {
            c.error("otherInsured.name", "other insured name (item 9) is required");
        }
        validateName(c, "otherInsured.name", other.getName(), false);
        c.maxLen("otherInsured.policyGroupNumber", other.getPolicyGroupNumber(), 28);
        c.maxLen("otherInsured.planName", other.getPlanName(), 28);
    }

    private void validateCondition(Collector c, Condition condition) {
        if (condition == null) {
            return;
        }
        boolean auto = Boolean.TRUE.equals(condition.isAutoAccident());
        if (auto && isBlank(condition.getAutoAccidentState())) {
            c.error("condition.autoAccidentState", "state (item 10b PLACE) is required when autoAccident is true");
        }
        if (!auto && !isBlank(condition.getAutoAccidentState())) {
            c.error("condition.autoAccidentState", "only allowed when autoAccident is true");
        }
        c.optionalMatch("condition.autoAccidentState", condition.getAutoAccidentState(), Patterns.STATE, "two-letter state code");
        c.maxLen("condition.claimCodes", condition.getClaimCodes(), 19);
    }

    private void validateClaimDates(Collector c, Cms1500Claim claim, LocalDate today) {
        c.pair("dateOfCurrentIllness", claim.getDateOfCurrentIllness(),
                "dateOfCurrentIllnessQualifier", claim.getDateOfCurrentIllnessQualifier());
        c.optionalMatch("dateOfCurrentIllnessQualifier", claim.getDateOfCurrentIllnessQualifier(),
                Patterns.ITEM14_QUALIFIER, "431 or 484");
        c.notFuture("dateOfCurrentIllness", claim.getDateOfCurrentIllness(), today);

        c.pair("otherDate", claim.getOtherDate(), "otherDateQualifier", claim.getOtherDateQualifier());
        c.optionalMatch("otherDateQualifier", claim.getOtherDateQualifier(), Patterns.ITEM15_QUALIFIER,
                "one of 454, 304, 453, 439, 455, 471, 090, 091, 444, 050, 054");

        c.range("unableToWorkFrom", claim.getUnableToWorkFrom(), "unableToWorkTo", claim.getUnableToWorkTo());
        c.range("hospitalizationFrom", claim.getHospitalizationFrom(), "hospitalizationTo", claim.getHospitalizationTo());
    }

    private void validateReferring(Collector c, ReferringProvider ref) {
        if (ref == null) {
            return;
        }
        c.requireMatch("referringProvider.qualifier", ref.getQualifier(), Patterns.REFERRING_QUALIFIER, "DN, DK or DQ");
        c.requireText("referringProvider.name", ref.getName(), 26);
        c.optionalMatch("referringProvider.otherIdQualifier", ref.getOtherIdQualifier(), Patterns.OTHER_ID_QUALIFIER,
                "0B, 1G, G2 or LU");
        c.maxLen("referringProvider.otherId", ref.getOtherId(), 17);
        c.pair("referringProvider.otherIdQualifier", ref.getOtherIdQualifier(),
                "referringProvider.otherId", ref.getOtherId());
        c.npi("referringProvider.npi", ref.getNpi(), false);
    }

    private void validateOutsideLab(Collector c, Cms1500Claim claim) {
        boolean lab = Boolean.TRUE.equals(claim.isOutsideLab());
        c.money("outsideLabCharges", claim.getOutsideLabCharges());
        if (!lab && claim.getOutsideLabCharges() != null) {
            c.error("outsideLabCharges", "only allowed when outsideLab is true");
        }
    }

    private void validateDiagnoses(Collector c, Cms1500Claim claim) {
        List<String> diagnoses = claim.getDiagnoses();
        if (diagnoses.isEmpty()) {
            c.error("diagnoses", "at least one diagnosis code (item 21) is required");
            return;
        }
        if (diagnoses.size() > MAX_DIAGNOSES) {
            c.error("diagnoses", "at most " + MAX_DIAGNOSES + " diagnosis codes (A-L) fit on the form");
        }
        IcdIndicator icd = claim.getIcdIndicator() == null ? IcdIndicator.ICD_10 : claim.getIcdIndicator();
        Pattern codePattern = icd == IcdIndicator.ICD_10 ? Patterns.ICD10_CODE : Patterns.ICD9_CODE;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < diagnoses.size(); i++) {
            String field = "diagnoses[" + i + "]";
            String code = diagnoses.get(i);
            if (isBlank(code)) {
                c.error(field, "diagnosis code is blank");
                continue;
            }
            if (!Patterns.DIAGNOSIS_CODE.matcher(code).matches() || !codePattern.matcher(code).matches()) {
                c.error(field, "'" + code + "' is not a valid " + (icd == IcdIndicator.ICD_10 ? "ICD-10-CM" : "ICD-9-CM") + " code");
            }
            if (!seen.add(code.replace(".", "").toUpperCase(Locale.ROOT))) {
                c.warn(field + ": duplicate diagnosis code " + code);
            }
        }
    }

    private void validateResubmission(Collector c, Cms1500Claim claim) {
        c.maxLen("resubmissionCode", claim.getResubmissionCode(), 11);
        c.maxLen("originalReferenceNumber", claim.getOriginalReferenceNumber(), 18);
        if (!isBlank(claim.getResubmissionCode()) && isBlank(claim.getOriginalReferenceNumber())) {
            c.error("originalReferenceNumber", "required when resubmissionCode (item 22) is present");
        }
    }

    private void validateServiceLines(Collector c, Cms1500Claim claim, LocalDate today) {
        List<ServiceLine> lines = claim.getServiceLines();
        if (lines.isEmpty()) {
            c.error("serviceLines", "at least one service line (item 24) is required");
            return;
        }
        if (lines.size() > MAX_SERVICE_LINES) {
            c.error("serviceLines", "at most " + MAX_SERVICE_LINES + " service lines are accepted per claim");
        }
        int diagnosisCount = claim.getDiagnoses().size();
        LocalDate dob = claim.getPatient() == null ? null : claim.getPatient().getDateOfBirth();
        for (int i = 0; i < lines.size(); i++) {
            String f = "serviceLines[" + i + "]";
            ServiceLine line = lines.get(i);
            if (line == null) {
                c.error(f, "service line is null");
                continue;
            }
            c.require(f + ".dateFrom", line.getDateFrom());
            c.notFuture(f + ".dateFrom", line.getDateFrom(), today);
            c.notFuture(f + ".dateTo", line.getDateTo(), today);
            c.range(f + ".dateFrom", line.getDateFrom(), f + ".dateTo", line.getDateTo());
            if (dob != null && line.getDateFrom() != null && line.getDateFrom().isBefore(dob)) {
                c.error(f + ".dateFrom", "service date precedes the patient's date of birth");
            }
            c.requireMatch(f + ".placeOfService", line.getPlaceOfService(), Patterns.PLACE_OF_SERVICE, "two-digit place of service code");
            c.requireMatch(f + ".procedureCode", line.getProcedureCode(), Patterns.PROCEDURE_CODE, "five-character CPT/HCPCS code");
            List<String> mods = line.getModifiers();
            if (mods.size() > MAX_MODIFIERS) {
                c.error(f + ".modifiers", "at most " + MAX_MODIFIERS + " modifiers fit on a line");
            }
            for (int m = 0; m < mods.size(); m++) {
                c.requireMatch(f + ".modifiers[" + m + "]", mods.get(m), Patterns.MODIFIER, "two-character modifier");
            }
            validatePointers(c, f + ".diagnosisPointers", line.getDiagnosisPointers(), diagnosisCount);
            c.require(f + ".charges", line.getCharges());
            c.money(f + ".charges", line.getCharges());
            c.require(f + ".units", line.getUnits());
            if (line.getUnits() != null) {
                if (line.getUnits().signum() <= 0) {
                    c.error(f + ".units", "must be greater than zero");
                } else if (line.getUnits().compareTo(MAX_UNITS) > 0 || line.getUnits().stripTrailingZeros().scale() > 2) {
                    c.error(f + ".units", "at most 9999.99 with two decimals");
                }
            }
            c.optionalMatch(f + ".epsdt", line.getEpsdt(), Patterns.EPSDT_CODE, "AV, S2, ST or NU");
            c.optionalMatch(f + ".renderingProviderIdQualifier", line.getRenderingProviderIdQualifier(),
                    Patterns.RENDERING_ID_QUALIFIER, "0B, 1G, G2, LU or ZZ");
            c.maxLen(f + ".renderingProviderOtherId", line.getRenderingProviderOtherId(), 11);
            c.pair(f + ".renderingProviderIdQualifier", line.getRenderingProviderIdQualifier(),
                    f + ".renderingProviderOtherId", line.getRenderingProviderOtherId());
            c.npi(f + ".renderingProviderNpi", line.getRenderingProviderNpi(), false);
            c.maxLen(f + ".supplementalInfo", line.getSupplementalInfo(), 61);
        }
    }

    private void validatePointers(Collector c, String field, String pointers, int diagnosisCount) {
        if (isBlank(pointers)) {
            c.error(field, "diagnosis pointer (item 24E) is required");
            return;
        }
        if (!Patterns.DIAGNOSIS_POINTERS.matcher(pointers).matches()) {
            c.error(field, "'" + pointers + "' must be 1-4 letters A-L without separators");
            return;
        }
        Set<Character> seen = new HashSet<>();
        for (char ch : pointers.toUpperCase(Locale.ROOT).toCharArray()) {
            int index = ch - 'A';
            if (index >= diagnosisCount) {
                c.error(field, "pointer " + ch + " has no matching diagnosis in item 21 (" + diagnosisCount + " code(s) given)");
            }
            if (!seen.add(ch)) {
                c.error(field, "pointer " + ch + " is repeated");
            }
        }
    }

    private void validateTaxId(Collector c, Cms1500Claim claim) {
        c.optionalMatch("federalTaxId", claim.getFederalTaxId(), Patterns.TAX_ID, "nine digits without punctuation");
        c.pair("federalTaxId", claim.getFederalTaxId(), "federalTaxIdType", claim.getFederalTaxIdType());
        if (isBlank(claim.getFederalTaxId())) {
            c.warn("federalTaxId (item 25) is empty");
        }
    }

    private void validateTotals(Collector c, Cms1500Claim claim) {
        c.money("totalCharge", claim.getTotalCharge());
        c.money("amountPaid", claim.getAmountPaid());
        BigDecimal sum = BigDecimal.ZERO;
        for (ServiceLine line : claim.getServiceLines()) {
            if (line != null && line.getCharges() != null) {
                sum = sum.add(line.getCharges());
            }
        }
        if (claim.getTotalCharge() != null && claim.getTotalCharge().compareTo(sum) != 0) {
            c.error("totalCharge", "does not equal the sum of service line charges (" + sum.setScale(2) + ")");
        }
        if (sum.compareTo(MAX_MONEY) > 0) {
            c.error("serviceLines", "total charge exceeds " + MAX_MONEY);
        }
    }

    private void validateSignature(Collector c, Signature signature, LocalDate today) {
        if (signature == null) {
            return;
        }
        c.maxLen("physicianSignature.name", signature.getName(), 26);
        if (isBlank(signature.getName()) && !Boolean.TRUE.equals(signature.isOnFile())) {
            c.error("physicianSignature", "either name or onFile=true is required (item 31)");
        }
        c.notFuture("physicianSignature.date", signature.getDate(), today);
    }

    private void validateFacility(Collector c, ServiceFacility facility) {
        if (facility == null) {
            return;
        }
        c.requireText("serviceFacility.name", facility.getName(), 26);
        c.maxLen("serviceFacility.street", facility.getStreet(), 26);
        c.maxLen("serviceFacility.city", facility.getCity(), 24);
        c.optionalMatch("serviceFacility.state", facility.getState(), Patterns.STATE, "two-letter state code");
        c.optionalMatch("serviceFacility.zip", facility.getZip(), Patterns.ZIP, "5 or 9 digit ZIP code");
        c.npi("serviceFacility.npi", facility.getNpi(), false);
        c.maxLen("serviceFacility.otherId", facility.getOtherId(), 14);
    }

    private void validateBillingProvider(Collector c, BillingProvider bp) {
        if (bp == null) {
            c.error("billingProvider", "billing provider (item 33) is required");
            return;
        }
        c.requireText("billingProvider.name", bp.getName(), 29);
        c.requireText("billingProvider.street", bp.getStreet(), 29);
        c.requireText("billingProvider.city", bp.getCity(), 24);
        c.requireMatch("billingProvider.state", bp.getState(), Patterns.STATE, "two-letter state code");
        c.requireMatch("billingProvider.zip", bp.getZip(), Patterns.ZIP, "5 or 9 digit ZIP code");
        c.phone("billingProvider", bp.getPhoneAreaCode(), bp.getPhoneNumber());
        c.npi("billingProvider.npi", bp.getNpi(), true);
        c.maxLen("billingProvider.otherId", bp.getOtherId(), 17);
    }

    private void validateName(Collector c, String field, PersonName name, boolean required) {
        if (name == null) {
            if (required) {
                c.error(field, "name is required");
            }
            return;
        }
        c.requireText(field + ".lastName", name.getLastName(), 28);
        c.requireText(field + ".firstName", name.getFirstName(), 28);
        c.maxLen(field + ".middleInitial", name.getMiddleInitial(), 1);
    }

    private void validateAddress(Collector c, String field, Address address, boolean required) {
        if (address == null) {
            if (required) {
                c.error(field, "address is required");
            }
            return;
        }
        c.requireText(field + ".street", address.getStreet(), 29);
        c.requireText(field + ".city", address.getCity(), 24);
        c.requireMatch(field + ".state", address.getState(), Patterns.STATE, "two-letter state code");
        c.requireMatch(field + ".zip", address.getZip(), Patterns.ZIP, "5 or 9 digit ZIP code");
        c.phone(field, address.getPhoneAreaCode(), address.getPhoneNumber());
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Accumulates violations and warnings with the small rule helpers used above. */
    private static final class Collector {
        private final List<ViolationDetail> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        Result result() {
            return new Result(List.copyOf(errors), List.copyOf(warnings));
        }

        void error(String field, String message) {
            errors.add(ClaimException.detail(field, message));
        }

        void warn(String message) {
            warnings.add(message);
        }

        void require(String field, Object value) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                error(field, "is required");
            }
        }

        void requireText(String field, String value, int maxLen) {
            if (isBlank(value)) {
                error(field, "is required");
            } else {
                maxLen(field, value, maxLen);
            }
        }

        void maxLen(String field, String value, int maxLen) {
            if (value != null && value.trim().length() > maxLen) {
                error(field, "longer than " + maxLen + " characters");
            }
        }

        void requireMatch(String field, String value, Pattern pattern, String expected) {
            if (isBlank(value)) {
                error(field, "is required (" + expected + ")");
            } else if (!pattern.matcher(value.trim()).matches()) {
                error(field, "'" + value + "' is invalid; expected " + expected);
            }
        }

        void optionalMatch(String field, String value, Pattern pattern, String expected) {
            if (!isBlank(value) && !pattern.matcher(value.trim()).matches()) {
                error(field, "'" + value + "' is invalid; expected " + expected);
            }
        }

        /** Both values must be present together or absent together. */
        void pair(String fieldA, Object a, String fieldB, Object b) {
            boolean hasA = a != null && !(a instanceof String s && s.isBlank());
            boolean hasB = b != null && !(b instanceof String s && s.isBlank());
            if (hasA && !hasB) {
                error(fieldB, "is required when " + fieldA + " is present");
            } else if (hasB && !hasA) {
                error(fieldA, "is required when " + fieldB + " is present");
            }
        }

        void phone(String field, String area, String number) {
            optionalMatch(field + ".phoneAreaCode", area, Patterns.AREA_CODE, "three digits");
            optionalMatch(field + ".phoneNumber", number, Patterns.PHONE, "seven digits");
            pair(field + ".phoneAreaCode", area, field + ".phoneNumber", number);
        }

        void npi(String field, String npi, boolean required) {
            if (isBlank(npi)) {
                if (required) {
                    error(field, "NPI is required");
                }
                return;
            }
            if (!Patterns.NPI.matcher(npi).matches()) {
                error(field, "'" + npi + "' must be ten digits");
            } else if (!Patterns.validNpi(npi)) {
                error(field, "'" + npi + "' fails the NPI check digit");
            }
        }

        void money(String field, BigDecimal amount) {
            if (amount == null) {
                return;
            }
            if (amount.signum() < 0) {
                error(field, "must not be negative");
            } else if (amount.compareTo(MAX_MONEY) > 0) {
                error(field, "exceeds " + MAX_MONEY);
            } else if (amount.stripTrailingZeros().scale() > 2) {
                error(field, "more than two decimal places");
            }
        }

        void notFuture(String field, LocalDate date, LocalDate today) {
            if (date != null && date.isAfter(today)) {
                error(field, "is in the future");
            }
        }

        void range(String fromField, LocalDate from, String toField, LocalDate to) {
            if (to != null && from == null) {
                error(fromField, "is required when " + toField + " is present");
            } else if (from != null && to != null && to.isBefore(from)) {
                error(toField, "is before " + fromField);
            }
        }
    }
}
