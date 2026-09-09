package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.contract.Address;
import com.thehiddenbrain.interop.cms1500.contract.BillingProvider;
import com.thehiddenbrain.interop.cms1500.contract.Carrier;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.Condition;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
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
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.thehiddenbrain.interop.cms1500.pdf.Cms1500Fields.LINES_PER_PAGE;

/**
 * Fills the CMS-1500 AcroForm template. A claim with more than six service lines becomes
 * several form pages: the header (items 1-23, 25-27, 31-33) is repeated on every page, each
 * page carries up to six lines, and items 28 (total charge) and 29 (amount paid) are printed on
 * the last page only, as the NUCC instructions require for multi-page claims.
 */
public class Cms1500FormFiller {

    private final byte[] template;
    private final FormText text;
    private final String continuationMarker;

    /**
     * @param template           the fillable NUCC 02/12 template
     * @param text               value formatting rules
     * @param continuationMarker printed in item 28 on every page but the last; blank leaves it empty
     */
    public Cms1500FormFiller(byte[] template, FormText text, String continuationMarker) {
        this.template = template.clone();
        this.text = text;
        this.continuationMarker = continuationMarker == null ? "" : continuationMarker.trim();
    }

    public static int pageCount(int serviceLines) {
        return Math.max(1, (serviceLines + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
    }

    /**
     * Fills the claim onto one or more form pages, flattened (values become page content, no
     * editable fields remain). The caller owns the returned document.
     */
    public PDDocument fill(Cms1500Claim claim) throws IOException {
        int pages = pageCount(claim.getServiceLines().size());
        PDDocument out = new PDDocument();
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                try (PDDocument page = fillPage(claim, pageIndex, pages)) {
                    flatten(page);
                    merger.appendDocument(out, page);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            out.close();
            throw e;
        }
    }

    /**
     * Fills one form page and keeps the AcroForm intact so field values can be read back by name.
     *
     * @param pageIndex zero-based page of the claim
     * @param pageCount total pages of the claim
     */
    public PDDocument fillPage(Cms1500Claim claim, int pageIndex, int pageCount) throws IOException {
        PDDocument doc = Loader.loadPDF(template);
        try {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                throw new ClaimException(ErrorCode.TEMPLATE_ERROR, claim.getClaimNumber(),
                        "CMS-1500 template has no AcroForm fields");
            }
            while (doc.getNumberOfPages() > 1) {
                doc.removePage(doc.getNumberOfPages() - 1);
            }
            removeField(doc, form, Cms1500Fields.CLEAR_FORM_BUTTON);

            Writer w = new Writer(form, claim.getClaimNumber());
            fillCarrier(w, claim.getCarrier());
            fillInsuranceType(w, claim);
            fillPatient(w, claim.getPatient());
            fillInsured(w, claim);
            fillOtherInsured(w, claim.getOtherInsured());
            fillCondition(w, claim.getCondition());
            fillClaimDates(w, claim);
            fillReferring(w, claim.getReferringProvider());
            fillItems19to23(w, claim);
            fillServiceLines(w, claim, pageIndex);
            fillItems25to29(w, claim, pageIndex == pageCount - 1);
            fillProviders(w, claim);
            return doc;
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e;
        }
    }

    private void fillCarrier(Writer w, Carrier carrier) {
        if (carrier == null) {
            return;
        }
        w.text(Cms1500Fields.CARRIER_NAME, text.text(carrier.getName()));
        w.text(Cms1500Fields.CARRIER_STREET, text.text(carrier.getStreet()));
        w.text(Cms1500Fields.CARRIER_STREET2, text.text(carrier.getStreet2()));
        w.text(Cms1500Fields.CARRIER_CITY_STATE_ZIP, text.cityStateZip(carrier.getCity(), carrier.getState(), carrier.getZip()));
    }

    private void fillInsuranceType(Writer w, Cms1500Claim claim) {
        InsuranceType type = claim.getInsuranceType();
        if (type != null) {
            w.check(Cms1500Fields.INSURANCE_TYPE, switch (type) {
                case MEDICARE -> "Medicare";
                case MEDICAID -> "Medicaid";
                case TRICARE -> "Tricare";
                case CHAMPVA -> "Champva";
                case GROUP_HEALTH_PLAN -> "Group";
                case FECA_BLACK_LUNG -> "Feca";
                case OTHER -> "Other";
            });
        }
        w.text(Cms1500Fields.INSURED_ID, text.text(claim.getInsuredId()));
    }

    private void fillPatient(Writer w, Patient patient) {
        if (patient == null) {
            return;
        }
        w.text(Cms1500Fields.PATIENT_NAME, text.personName(patient.getName()));
        w.date8(Cms1500Fields.PATIENT_DOB_MM, Cms1500Fields.PATIENT_DOB_DD, Cms1500Fields.PATIENT_DOB_YY, patient.getDateOfBirth());
        if (patient.getSex() != null) {
            w.check(Cms1500Fields.PATIENT_SEX, patient.getSex() == Sex.M ? "M" : "F");
        }
        Address a = patient.getAddress();
        if (a != null) {
            w.text(Cms1500Fields.PATIENT_STREET, text.text(a.getStreet()));
            w.text(Cms1500Fields.PATIENT_CITY, text.text(a.getCity()));
            w.text(Cms1500Fields.PATIENT_STATE, FormText.code(a.getState()));
            w.text(Cms1500Fields.PATIENT_ZIP, FormText.zip(a.getZip()));
            w.text(Cms1500Fields.PATIENT_PHONE_AREA, FormText.digits(a.getPhoneAreaCode()));
            w.text(Cms1500Fields.PATIENT_PHONE, FormText.digits(a.getPhoneNumber()));
        }
        if (patient.getRelationshipToInsured() != null) {
            w.check(Cms1500Fields.RELATIONSHIP, switch (patient.getRelationshipToInsured()) {
                case SELF -> "S";
                case SPOUSE -> "M";
                case CHILD -> "C";
                case OTHER -> "O";
            });
        }
        if (Boolean.TRUE.equals(patient.isSignatureOnFile())) {
            w.text(Cms1500Fields.PATIENT_SIGNATURE, FormText.SIGNATURE_ON_FILE);
        }
        w.text(Cms1500Fields.PATIENT_SIGNATURE_DATE, FormText.dateText(patient.getSignatureDate()));
    }

    private void fillInsured(Writer w, Cms1500Claim claim) {
        Insured insured = claim.getInsured();
        Patient patient = claim.getPatient();
        boolean self = patient != null && patient.getRelationshipToInsured() == Relationship.SELF;

        PersonName name = insured != null && insured.getName() != null ? insured.getName()
                : self ? patient.getName() : null;
        Address address = insured != null && insured.getAddress() != null ? insured.getAddress()
                : self ? patient.getAddress() : null;
        LocalDate dob = insured != null && insured.getDateOfBirth() != null ? insured.getDateOfBirth()
                : self ? patient.getDateOfBirth() : null;
        Sex sex = insured != null && insured.getSex() != null ? insured.getSex()
                : self ? patient.getSex() : null;

        w.text(Cms1500Fields.INSURED_NAME, text.personName(name));
        if (address != null) {
            w.text(Cms1500Fields.INSURED_STREET, text.text(address.getStreet()));
            w.text(Cms1500Fields.INSURED_CITY, text.text(address.getCity()));
            w.text(Cms1500Fields.INSURED_STATE, FormText.code(address.getState()));
            w.text(Cms1500Fields.INSURED_ZIP, FormText.zip(address.getZip()));
            w.text(Cms1500Fields.INSURED_PHONE_AREA, FormText.digits(address.getPhoneAreaCode()));
            w.text(Cms1500Fields.INSURED_PHONE, FormText.digits(address.getPhoneNumber()));
        }
        w.date8(Cms1500Fields.INSURED_DOB_MM, Cms1500Fields.INSURED_DOB_DD, Cms1500Fields.INSURED_DOB_YY, dob);
        if (sex != null) {
            w.check(Cms1500Fields.INSURED_SEX, sex == Sex.M ? "MALE" : "FEMALE");
        }
        if (insured == null) {
            return;
        }
        w.text(Cms1500Fields.INSURED_POLICY_GROUP, text.text(insured.getPolicyGroupNumber()));
        w.text(Cms1500Fields.OTHER_CLAIM_ID_QUALIFIER, FormText.code(insured.getOtherClaimIdQualifier()));
        w.text(Cms1500Fields.OTHER_CLAIM_ID, text.text(insured.getOtherClaimId()));
        w.text(Cms1500Fields.INSURED_PLAN_NAME, text.text(insured.getPlanName()));
        w.yesNo(Cms1500Fields.ANOTHER_HEALTH_PLAN, insured.isAnotherHealthBenefitPlan());
        if (Boolean.TRUE.equals(insured.isSignatureOnFile())) {
            w.text(Cms1500Fields.INSURED_SIGNATURE, FormText.SIGNATURE_ON_FILE);
        }
    }

    private void fillOtherInsured(Writer w, OtherInsured other) {
        if (other == null) {
            return;
        }
        w.text(Cms1500Fields.OTHER_INSURED_NAME, text.personName(other.getName()));
        w.text(Cms1500Fields.OTHER_INSURED_POLICY_GROUP, text.text(other.getPolicyGroupNumber()));
        w.text(Cms1500Fields.OTHER_INSURED_PLAN_NAME, text.text(other.getPlanName()));
    }

    private void fillCondition(Writer w, Condition condition) {
        if (condition == null) {
            return;
        }
        w.yesNo(Cms1500Fields.EMPLOYMENT, condition.isEmploymentRelated());
        w.yesNo(Cms1500Fields.AUTO_ACCIDENT, condition.isAutoAccident());
        w.text(Cms1500Fields.AUTO_ACCIDENT_STATE, FormText.code(condition.getAutoAccidentState()));
        w.yesNo(Cms1500Fields.OTHER_ACCIDENT, condition.isOtherAccident());
        w.text(Cms1500Fields.CLAIM_CODES, text.text(condition.getClaimCodes()));
    }

    private void fillClaimDates(Writer w, Cms1500Claim claim) {
        w.date8(Cms1500Fields.CURRENT_ILLNESS_MM, Cms1500Fields.CURRENT_ILLNESS_DD, Cms1500Fields.CURRENT_ILLNESS_YY,
                claim.getDateOfCurrentIllness());
        w.text(Cms1500Fields.CURRENT_ILLNESS_QUALIFIER, FormText.code(claim.getDateOfCurrentIllnessQualifier()));
        w.date8(Cms1500Fields.OTHER_DATE_MM, Cms1500Fields.OTHER_DATE_DD, Cms1500Fields.OTHER_DATE_YY, claim.getOtherDate());
        w.text(Cms1500Fields.OTHER_DATE_QUALIFIER, FormText.code(claim.getOtherDateQualifier()));
        w.date8(Cms1500Fields.WORK_FROM_MM, Cms1500Fields.WORK_FROM_DD, Cms1500Fields.WORK_FROM_YY, claim.getUnableToWorkFrom());
        w.date8(Cms1500Fields.WORK_TO_MM, Cms1500Fields.WORK_TO_DD, Cms1500Fields.WORK_TO_YY, claim.getUnableToWorkTo());
        w.date8(Cms1500Fields.HOSP_FROM_MM, Cms1500Fields.HOSP_FROM_DD, Cms1500Fields.HOSP_FROM_YY, claim.getHospitalizationFrom());
        w.date8(Cms1500Fields.HOSP_TO_MM, Cms1500Fields.HOSP_TO_DD, Cms1500Fields.HOSP_TO_YY, claim.getHospitalizationTo());
    }

    private void fillReferring(Writer w, ReferringProvider ref) {
        if (ref == null) {
            return;
        }
        w.text(Cms1500Fields.REFERRING_QUALIFIER, FormText.code(ref.getQualifier()));
        w.text(Cms1500Fields.REFERRING_NAME, text.text(ref.getName()));
        w.text(Cms1500Fields.REFERRING_OTHER_ID_QUALIFIER, FormText.code(ref.getOtherIdQualifier()));
        w.text(Cms1500Fields.REFERRING_OTHER_ID, text.text(ref.getOtherId()));
        w.text(Cms1500Fields.REFERRING_NPI, FormText.digits(ref.getNpi()));
    }

    private void fillItems19to23(Writer w, Cms1500Claim claim) {
        w.text(Cms1500Fields.ADDITIONAL_CLAIM_INFO, text.text(claim.getAdditionalClaimInfo()));
        w.yesNo(Cms1500Fields.OUTSIDE_LAB, claim.isOutsideLab());
        w.text(Cms1500Fields.OUTSIDE_LAB_CHARGES, FormText.money(claim.getOutsideLabCharges()));
        List<String> diagnoses = claim.getDiagnoses();
        if (!diagnoses.isEmpty()) {
            IcdIndicator icd = claim.getIcdIndicator() == null ? IcdIndicator.ICD_10 : claim.getIcdIndicator();
            w.text(Cms1500Fields.ICD_INDICATOR, icd == IcdIndicator.ICD_10 ? "0" : "9");
        }
        for (int i = 0; i < Math.min(diagnoses.size(), Cms1500Fields.MAX_DIAGNOSES); i++) {
            w.text(Cms1500Fields.diagnosis(i + 1), text.diagnosis(diagnoses.get(i)));
        }
        w.text(Cms1500Fields.RESUBMISSION_CODE, text.text(claim.getResubmissionCode()));
        w.text(Cms1500Fields.ORIGINAL_REFERENCE, text.text(claim.getOriginalReferenceNumber()));
        w.text(Cms1500Fields.PRIOR_AUTHORIZATION, text.text(claim.getPriorAuthorizationNumber()));
    }

    private void fillServiceLines(Writer w, Cms1500Claim claim, int pageIndex) {
        List<ServiceLine> all = claim.getServiceLines();
        int start = pageIndex * LINES_PER_PAGE;
        for (int line = 1; line <= LINES_PER_PAGE; line++) {
            int index = start + line - 1;
            if (index >= all.size()) {
                break;
            }
            ServiceLine sl = all.get(index);
            LocalDate from = sl.getDateFrom();
            LocalDate to = sl.getDateTo() != null ? sl.getDateTo() : from;
            w.date6(Cms1500Fields.serviceFromMm(line), Cms1500Fields.serviceFromDd(line), Cms1500Fields.serviceFromYy(line), from);
            w.date6(Cms1500Fields.serviceToMm(line), Cms1500Fields.serviceToDd(line), Cms1500Fields.serviceToYy(line), to);
            w.text(Cms1500Fields.placeOfService(line), FormText.digits(sl.getPlaceOfService()));
            if (Boolean.TRUE.equals(sl.isEmergency())) {
                w.text(Cms1500Fields.emergency(line), "Y");
            }
            w.text(Cms1500Fields.procedureCode(line), FormText.code(sl.getProcedureCode()));
            List<String> mods = sl.getModifiers();
            for (int slot = 0; slot < Math.min(mods.size(), 4); slot++) {
                w.text(Cms1500Fields.modifier(line, slot), FormText.code(mods.get(slot)));
            }
            w.text(Cms1500Fields.diagnosisPointers(line), FormText.pointers(sl.getDiagnosisPointers()));
            w.text(Cms1500Fields.charges(line), FormText.money(sl.getCharges()));
            w.text(Cms1500Fields.units(line), FormText.units(sl.getUnits()));
            w.text(Cms1500Fields.epsdt(line), FormText.code(sl.getEpsdt()));
            if (Boolean.TRUE.equals(sl.isFamilyPlanning())) {
                w.text(Cms1500Fields.familyPlanning(line), "Y");
            }
            w.text(Cms1500Fields.renderingIdQualifier(line), FormText.code(sl.getRenderingProviderIdQualifier()));
            w.text(Cms1500Fields.renderingOtherId(line), text.text(sl.getRenderingProviderOtherId()));
            w.text(Cms1500Fields.renderingNpi(line), FormText.digits(sl.getRenderingProviderNpi()));
            w.text(Cms1500Fields.supplementalInfo(line), text.text(sl.getSupplementalInfo()));
        }
    }

    private void fillItems25to29(Writer w, Cms1500Claim claim, boolean lastPage) {
        w.text(Cms1500Fields.TAX_ID, FormText.digits(claim.getFederalTaxId()));
        if (claim.getFederalTaxIdType() != null) {
            w.check(Cms1500Fields.TAX_ID_TYPE, claim.getFederalTaxIdType() == TaxIdType.SSN ? "SSN" : "EIN");
        }
        w.text(Cms1500Fields.PATIENT_ACCOUNT, text.text(claim.getPatientAccountNumber()));
        w.yesNo(Cms1500Fields.ACCEPT_ASSIGNMENT, claim.isAcceptAssignment());
        if (lastPage) {
            w.text(Cms1500Fields.TOTAL_CHARGE, FormText.money(totalCharge(claim)));
            w.text(Cms1500Fields.AMOUNT_PAID, FormText.money(claim.getAmountPaid()));
        } else if (!continuationMarker.isEmpty()) {
            w.text(Cms1500Fields.TOTAL_CHARGE, text.text(continuationMarker));
        }
    }

    /** Item 28: the caller's total when given, otherwise the sum of all service line charges. */
    public static BigDecimal totalCharge(Cms1500Claim claim) {
        if (claim.getTotalCharge() != null) {
            return claim.getTotalCharge();
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ServiceLine line : claim.getServiceLines()) {
            if (line.getCharges() != null) {
                sum = sum.add(line.getCharges());
            }
        }
        return sum;
    }

    private void fillProviders(Writer w, Cms1500Claim claim) {
        Signature sig = claim.getPhysicianSignature();
        if (sig != null) {
            w.text(Cms1500Fields.PHYSICIAN_SIGNATURE,
                    Boolean.TRUE.equals(sig.isOnFile()) ? FormText.SIGNATURE_ON_FILE : text.text(sig.getName()));
            w.text(Cms1500Fields.PHYSICIAN_SIGNATURE_DATE, FormText.dateText(sig.getDate()));
        }
        ServiceFacility facility = claim.getServiceFacility();
        if (facility != null) {
            w.text(Cms1500Fields.FACILITY_NAME, text.text(facility.getName()));
            w.text(Cms1500Fields.FACILITY_STREET, text.text(facility.getStreet()));
            w.text(Cms1500Fields.FACILITY_CITY_STATE_ZIP, text.cityStateZip(facility.getCity(), facility.getState(), facility.getZip()));
            w.text(Cms1500Fields.FACILITY_NPI, FormText.digits(facility.getNpi()));
            w.text(Cms1500Fields.FACILITY_OTHER_ID, text.text(facility.getOtherId()));
        }
        BillingProvider bp = claim.getBillingProvider();
        if (bp != null) {
            w.text(Cms1500Fields.BILLING_PHONE_AREA, FormText.digits(bp.getPhoneAreaCode()));
            w.text(Cms1500Fields.BILLING_PHONE, FormText.digits(bp.getPhoneNumber()));
            w.text(Cms1500Fields.BILLING_NAME, text.text(bp.getName()));
            w.text(Cms1500Fields.BILLING_STREET, text.text(bp.getStreet()));
            w.text(Cms1500Fields.BILLING_CITY_STATE_ZIP, text.cityStateZip(bp.getCity(), bp.getState(), bp.getZip()));
            w.text(Cms1500Fields.BILLING_NPI, FormText.digits(bp.getNpi()));
            w.text(Cms1500Fields.BILLING_OTHER_ID, text.text(bp.getOtherId()));
        }
    }

    /**
     * Burns the field appearances into the page content and removes every trace of the form:
     * remaining widget annotations (unfilled fields keep theirs) and the AcroForm dictionary itself,
     * so merged output carries no editable fields.
     */
    static void flatten(PDDocument doc) throws IOException {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form != null) {
            form.flatten();
        }
        for (PDPage page : doc.getPages()) {
            // COSArrayList.removeIf does not write through to the PDF array; rebuild the list instead
            page.setAnnotations(page.getAnnotations().stream().filter(a -> !(a instanceof PDAnnotationWidget)).toList());
        }
        doc.getDocumentCatalog().getCOSObject().removeItem(COSName.ACRO_FORM);
    }

    /** Drops a field (and its widgets) from the document, e.g. the template's Clear Form button. */
    static void removeField(PDDocument doc, PDAcroForm form, String name) throws IOException {
        PDField field = form.getField(name);
        if (field == null) {
            return;
        }
        for (PDAnnotationWidget widget : field.getWidgets()) {
            PDPage page = widget.getPage();
            List<PDPage> pages = page != null ? List.of(page) : pagesOf(doc);
            for (PDPage p : pages) {
                p.setAnnotations(p.getAnnotations().stream()
                        .filter(a -> a.getCOSObject() != widget.getCOSObject()).toList());
            }
        }
        COSArray fields = form.getCOSObject().getCOSArray(COSName.FIELDS);
        if (fields != null) {
            for (int i = fields.size() - 1; i >= 0; i--) {
                if (fields.getObject(i) == field.getCOSObject()) {
                    fields.remove(i);
                }
            }
        }
    }

    private static List<PDPage> pagesOf(PDDocument doc) {
        List<PDPage> pages = new ArrayList<>();
        doc.getPages().forEach(pages::add);
        return pages;
    }

    /** Writes values into named fields, failing loudly when the template lacks a field. */
    private static final class Writer {
        private final PDAcroForm form;
        private final String claimNumber;

        Writer(PDAcroForm form, String claimNumber) {
            this.form = form;
            this.claimNumber = claimNumber;
        }

        void text(String fieldName, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            PDField field = field(fieldName);
            if (!(field instanceof PDTextField textField)) {
                throw templateError("field '" + fieldName + "' is not a text field");
            }
            try {
                textField.setValue(value);
            } catch (IOException | IllegalArgumentException e) {
                throw new ClaimException(ErrorCode.TEMPLATE_ERROR, claimNumber,
                        "cannot write '" + value + "' into field '" + fieldName + "': " + e.getMessage(), e);
            }
        }

        void check(String fieldName, String onValue) {
            PDField field = field(fieldName);
            if (!(field instanceof PDCheckBox box)) {
                throw templateError("field '" + fieldName + "' is not a check box");
            }
            if (!box.getOnValues().contains(onValue)) {
                throw templateError("check box '" + fieldName + "' has no option '" + onValue + "' (has " + box.getOnValues() + ")");
            }
            try {
                box.setValue(onValue);
            } catch (IOException e) {
                throw new ClaimException(ErrorCode.TEMPLATE_ERROR, claimNumber,
                        "cannot check '" + onValue + "' on field '" + fieldName + "': " + e.getMessage(), e);
            }
        }

        void yesNo(String fieldName, Boolean value) {
            if (value != null) {
                check(fieldName, value ? "YES" : "NO");
            }
        }

        /** Three-field date with a two-digit year (item 24A). */
        void date6(String mm, String dd, String yy, LocalDate date) {
            if (date == null) {
                return;
            }
            text(mm, FormText.twoDigitMonth(date));
            text(dd, FormText.twoDigitDay(date));
            text(yy, FormText.twoDigitYear(date));
        }

        /** Three-field date with a four-digit year (items 3, 11a, 14-16, 18). */
        void date8(String mm, String dd, String yyyy, LocalDate date) {
            if (date == null) {
                return;
            }
            text(mm, FormText.twoDigitMonth(date));
            text(dd, FormText.twoDigitDay(date));
            text(yyyy, FormText.fourDigitYear(date));
        }

        private PDField field(String fieldName) {
            PDField field = form.getField(fieldName);
            if (field == null) {
                throw templateError("field '" + fieldName + "' is missing");
            }
            return field;
        }

        private ClaimException templateError(String detail) {
            return new ClaimException(ErrorCode.TEMPLATE_ERROR, claimNumber, "CMS-1500 template mismatch: " + detail);
        }
    }
}
