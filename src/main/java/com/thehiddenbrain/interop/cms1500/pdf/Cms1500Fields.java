package com.thehiddenbrain.interop.cms1500.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * AcroForm field names of the NUCC 02/12 CMS-1500 template, grouped by form item.
 * Checkbox groups list their export ("on") values in the comment.
 */
public final class Cms1500Fields {

    public static final int LINES_PER_PAGE = 6;
    public static final int MAX_DIAGNOSES = 12;

    // Carrier block (top right)
    public static final String CARRIER_NAME = "insurance_name";
    public static final String CARRIER_STREET = "insurance_address";
    public static final String CARRIER_STREET2 = "insurance_address2";
    public static final String CARRIER_CITY_STATE_ZIP = "insurance_city_state_zip";

    // Item 1 / 1a
    public static final String INSURANCE_TYPE = "insurance_type"; // Medicare Medicaid Tricare Champva Group Feca Other
    public static final String INSURED_ID = "insurance_id";

    // Items 2, 3, 5, 6, 12 (patient)
    public static final String PATIENT_NAME = "pt_name";
    public static final String PATIENT_DOB_MM = "birth_mm";
    public static final String PATIENT_DOB_DD = "birth_dd";
    public static final String PATIENT_DOB_YY = "birth_yy";
    public static final String PATIENT_SEX = "sex"; // M F
    public static final String PATIENT_STREET = "pt_street";
    public static final String PATIENT_CITY = "pt_city";
    public static final String PATIENT_STATE = "pt_state";
    public static final String PATIENT_ZIP = "pt_zip";
    public static final String PATIENT_PHONE_AREA = "pt_AreaCode";
    public static final String PATIENT_PHONE = "pt_phone";
    public static final String RELATIONSHIP = "rel_to_ins"; // S M C O
    public static final String PATIENT_SIGNATURE = "pt_signature";
    public static final String PATIENT_SIGNATURE_DATE = "pt_date";

    // Items 4, 7, 11, 11a-11d, 13 (insured)
    public static final String INSURED_NAME = "ins_name";
    public static final String INSURED_STREET = "ins_street";
    public static final String INSURED_CITY = "ins_city";
    public static final String INSURED_STATE = "ins_state";
    public static final String INSURED_ZIP = "ins_zip";
    public static final String INSURED_PHONE_AREA = "ins_phone area";
    public static final String INSURED_PHONE = "ins_phone";
    public static final String INSURED_POLICY_GROUP = "ins_policy";
    public static final String INSURED_DOB_MM = "ins_dob_mm";
    public static final String INSURED_DOB_DD = "ins_dob_dd";
    public static final String INSURED_DOB_YY = "ins_dob_yy";
    public static final String INSURED_SEX = "ins_sex"; // MALE FEMALE
    public static final String OTHER_CLAIM_ID_QUALIFIER = "57";
    public static final String OTHER_CLAIM_ID = "58";
    public static final String INSURED_PLAN_NAME = "ins_plan_name";
    public static final String ANOTHER_HEALTH_PLAN = "ins_benefit_plan"; // YES NO
    public static final String INSURED_SIGNATURE = "ins_signature";

    // Items 9, 9a, 9d
    public static final String OTHER_INSURED_NAME = "other_ins_name";
    public static final String OTHER_INSURED_POLICY_GROUP = "other_ins_policy";
    public static final String OTHER_INSURED_PLAN_NAME = "other_ins_plan_name";

    // Item 10
    public static final String EMPLOYMENT = "employment"; // YES NO
    public static final String AUTO_ACCIDENT = "pt_auto_accident"; // YES NO
    public static final String AUTO_ACCIDENT_STATE = "accident_place";
    public static final String OTHER_ACCIDENT = "other_accident"; // YES NO
    public static final String CLAIM_CODES = "50";

    // Items 14 - 16, 18
    public static final String CURRENT_ILLNESS_MM = "cur_ill_mm";
    public static final String CURRENT_ILLNESS_DD = "cur_ill_dd";
    public static final String CURRENT_ILLNESS_YY = "cur_ill_yy";
    public static final String CURRENT_ILLNESS_QUALIFIER = "73";
    public static final String OTHER_DATE_MM = "sim_ill_mm";
    public static final String OTHER_DATE_DD = "sim_ill_dd";
    public static final String OTHER_DATE_YY = "sim_ill_yy";
    public static final String OTHER_DATE_QUALIFIER = "74";
    public static final String WORK_FROM_MM = "work_mm_from";
    public static final String WORK_FROM_DD = "work_dd_from";
    public static final String WORK_FROM_YY = "work_yy_from";
    public static final String WORK_TO_MM = "work_mm_end";
    public static final String WORK_TO_DD = "work_dd_end";
    public static final String WORK_TO_YY = "work_yy_end";
    public static final String HOSP_FROM_MM = "hosp_mm_from";
    public static final String HOSP_FROM_DD = "hosp_dd_from";
    public static final String HOSP_FROM_YY = "hosp_yy_from";
    public static final String HOSP_TO_MM = "hosp_mm_end";
    public static final String HOSP_TO_DD = "hosp_dd_end";
    public static final String HOSP_TO_YY = "hosp_yy_end";

    // Item 17, 17a, 17b
    public static final String REFERRING_QUALIFIER = "85";
    public static final String REFERRING_NAME = "ref_physician";
    public static final String REFERRING_OTHER_ID_QUALIFIER = "physician number 17a1";
    public static final String REFERRING_OTHER_ID = "physician number 17a";
    public static final String REFERRING_NPI = "id_physician";

    // Items 19 - 23
    public static final String ADDITIONAL_CLAIM_INFO = "96";
    public static final String OUTSIDE_LAB = "lab"; // YES NO
    public static final String OUTSIDE_LAB_CHARGES = "charge";
    public static final String ICD_INDICATOR = "99icd";
    public static final String RESUBMISSION_CODE = "medicaid_resub";
    public static final String ORIGINAL_REFERENCE = "original_ref";
    public static final String PRIOR_AUTHORIZATION = "prior_auth";

    // Items 25 - 29
    public static final String TAX_ID = "tax_id";
    public static final String TAX_ID_TYPE = "ssn"; // SSN EIN
    public static final String PATIENT_ACCOUNT = "pt_account";
    public static final String ACCEPT_ASSIGNMENT = "assignment"; // YES NO
    public static final String TOTAL_CHARGE = "t_charge";
    public static final String AMOUNT_PAID = "amt_paid";

    // Items 31 - 33
    public static final String PHYSICIAN_SIGNATURE = "physician_signature";
    public static final String PHYSICIAN_SIGNATURE_DATE = "physician_date";
    public static final String FACILITY_NAME = "fac_name";
    public static final String FACILITY_STREET = "fac_street";
    public static final String FACILITY_CITY_STATE_ZIP = "fac_location";
    public static final String FACILITY_NPI = "pin1";
    public static final String FACILITY_OTHER_ID = "grp1";
    public static final String BILLING_PHONE_AREA = "doc_phone area";
    public static final String BILLING_PHONE = "doc_phone";
    public static final String BILLING_NAME = "doc_name";
    public static final String BILLING_STREET = "doc_street";
    public static final String BILLING_CITY_STATE_ZIP = "doc_location";
    public static final String BILLING_NPI = "pin";
    public static final String BILLING_OTHER_ID = "grp";

    /** JavaScript push button on the template; removed before flattening. */
    public static final String CLEAR_FORM_BUTTON = "Clear Form";

    private static final String[] SUPPLEMENTAL = {"Suppl", "Suppla", "Supplb", "Supplc", "Suppld", "Supple"};

    private Cms1500Fields() {
    }

    /** Item 21 diagnosis A-L, {@code index} 1-12. */
    public static String diagnosis(int index) {
        return "diagnosis" + index;
    }

    // Item 24 line fields, {@code line} 1-6

    public static String serviceFromMm(int line) { return "sv" + line + "_mm_from"; }
    public static String serviceFromDd(int line) { return "sv" + line + "_dd_from"; }
    public static String serviceFromYy(int line) { return "sv" + line + "_yy_from"; }
    public static String serviceToMm(int line) { return "sv" + line + "_mm_end"; }
    public static String serviceToDd(int line) { return "sv" + line + "_dd_end"; }
    public static String serviceToYy(int line) { return "sv" + line + "_yy_end"; }
    public static String placeOfService(int line) { return "place" + line; }
    public static String emergency(int line) { return "type" + line; }
    public static String procedureCode(int line) { return "cpt" + line; }
    public static String diagnosisPointers(int line) { return "diag" + line; }
    public static String charges(int line) { return "ch" + line; }
    public static String units(int line) { return "day" + line; }
    public static String epsdt(int line) { return "epsdt" + line; }
    public static String familyPlanning(int line) { return "plan" + line; }
    public static String renderingIdQualifier(int line) { return "emg" + line; }
    public static String renderingOtherId(int line) { return "local" + line + "a"; }
    public static String renderingNpi(int line) { return "local" + line; }
    public static String supplementalInfo(int line) { return SUPPLEMENTAL[line - 1]; }

    /** Modifier {@code slot} 0-3 of a line. */
    public static String modifier(int line, int slot) {
        return "mod" + line + switch (slot) {
            case 0 -> "";
            case 1 -> "a";
            case 2 -> "b";
            case 3 -> "c";
            default -> throw new IllegalArgumentException("modifier slot " + slot);
        };
    }

    /** Every text and checkbox field the filler may write; used to verify a template at startup. */
    public static List<String> allWritableFields() {
        List<String> names = new ArrayList<>(List.of(
                CARRIER_NAME, CARRIER_STREET, CARRIER_STREET2, CARRIER_CITY_STATE_ZIP,
                INSURANCE_TYPE, INSURED_ID,
                PATIENT_NAME, PATIENT_DOB_MM, PATIENT_DOB_DD, PATIENT_DOB_YY, PATIENT_SEX, PATIENT_STREET,
                PATIENT_CITY, PATIENT_STATE, PATIENT_ZIP, PATIENT_PHONE_AREA, PATIENT_PHONE, RELATIONSHIP,
                PATIENT_SIGNATURE, PATIENT_SIGNATURE_DATE,
                INSURED_NAME, INSURED_STREET, INSURED_CITY, INSURED_STATE, INSURED_ZIP, INSURED_PHONE_AREA,
                INSURED_PHONE, INSURED_POLICY_GROUP, INSURED_DOB_MM, INSURED_DOB_DD, INSURED_DOB_YY, INSURED_SEX,
                OTHER_CLAIM_ID_QUALIFIER, OTHER_CLAIM_ID, INSURED_PLAN_NAME, ANOTHER_HEALTH_PLAN, INSURED_SIGNATURE,
                OTHER_INSURED_NAME, OTHER_INSURED_POLICY_GROUP, OTHER_INSURED_PLAN_NAME,
                EMPLOYMENT, AUTO_ACCIDENT, AUTO_ACCIDENT_STATE, OTHER_ACCIDENT, CLAIM_CODES,
                CURRENT_ILLNESS_MM, CURRENT_ILLNESS_DD, CURRENT_ILLNESS_YY, CURRENT_ILLNESS_QUALIFIER,
                OTHER_DATE_MM, OTHER_DATE_DD, OTHER_DATE_YY, OTHER_DATE_QUALIFIER,
                WORK_FROM_MM, WORK_FROM_DD, WORK_FROM_YY, WORK_TO_MM, WORK_TO_DD, WORK_TO_YY,
                HOSP_FROM_MM, HOSP_FROM_DD, HOSP_FROM_YY, HOSP_TO_MM, HOSP_TO_DD, HOSP_TO_YY,
                REFERRING_QUALIFIER, REFERRING_NAME, REFERRING_OTHER_ID_QUALIFIER, REFERRING_OTHER_ID, REFERRING_NPI,
                ADDITIONAL_CLAIM_INFO, OUTSIDE_LAB, OUTSIDE_LAB_CHARGES, ICD_INDICATOR, RESUBMISSION_CODE,
                ORIGINAL_REFERENCE, PRIOR_AUTHORIZATION,
                TAX_ID, TAX_ID_TYPE, PATIENT_ACCOUNT, ACCEPT_ASSIGNMENT, TOTAL_CHARGE, AMOUNT_PAID,
                PHYSICIAN_SIGNATURE, PHYSICIAN_SIGNATURE_DATE, FACILITY_NAME, FACILITY_STREET, FACILITY_CITY_STATE_ZIP,
                FACILITY_NPI, FACILITY_OTHER_ID, BILLING_PHONE_AREA, BILLING_PHONE, BILLING_NAME, BILLING_STREET,
                BILLING_CITY_STATE_ZIP, BILLING_NPI, BILLING_OTHER_ID));
        for (int d = 1; d <= MAX_DIAGNOSES; d++) {
            names.add(diagnosis(d));
        }
        for (int line = 1; line <= LINES_PER_PAGE; line++) {
            names.addAll(List.of(serviceFromMm(line), serviceFromDd(line), serviceFromYy(line),
                    serviceToMm(line), serviceToDd(line), serviceToYy(line), placeOfService(line), emergency(line),
                    procedureCode(line), diagnosisPointers(line), charges(line), units(line), epsdt(line),
                    familyPlanning(line), renderingIdQualifier(line), renderingOtherId(line), renderingNpi(line),
                    supplementalInfo(line)));
            for (int slot = 0; slot < 4; slot++) {
                names.add(modifier(line, slot));
            }
        }
        return names;
    }
}
