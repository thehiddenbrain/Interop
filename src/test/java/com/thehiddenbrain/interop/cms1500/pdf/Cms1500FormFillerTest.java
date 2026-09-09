package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.Relationship;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TestTemplate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.thehiddenbrain.interop.cms1500.pdf.Cms1500Fields.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Cms1500FormFillerTest {

    private final Cms1500FormFiller filler = TestTemplate.filler();

    @Test
    void fillsEveryHeaderItemFromTheClaim() throws IOException {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-FULL");
        try (PDDocument doc = filler.fillPage(claim, 0, 1)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();

            // carrier block, items 1, 1a
            assertThat(value(form, CARRIER_NAME)).isEqualTo("CIGNA HEALTHCARE");
            assertThat(value(form, CARRIER_STREET)).isEqualTo("PO BOX 188061");
            assertThat(value(form, CARRIER_CITY_STATE_ZIP)).isEqualTo("CHATTANOOGA TN 37422");
            assertThat(value(form, INSURANCE_TYPE)).isEqualTo("Group");
            assertThat(value(form, INSURED_ID)).isEqualTo("U12345678");

            // items 2-6, 12
            assertThat(value(form, PATIENT_NAME)).isEqualTo("DOE, JOHN A");
            assertThat(value(form, PATIENT_DOB_MM)).isEqualTo("05");
            assertThat(value(form, PATIENT_DOB_DD)).isEqualTo("17");
            assertThat(value(form, PATIENT_DOB_YY)).isEqualTo("1980");
            assertThat(value(form, PATIENT_SEX)).isEqualTo("M");
            assertThat(value(form, PATIENT_STREET)).isEqualTo("123 MAIN ST");
            assertThat(value(form, PATIENT_CITY)).isEqualTo("SPRINGFIELD");
            assertThat(value(form, PATIENT_STATE)).isEqualTo("IL");
            assertThat(value(form, PATIENT_ZIP)).isEqualTo("62704");
            assertThat(value(form, PATIENT_PHONE_AREA)).isEqualTo("217");
            assertThat(value(form, PATIENT_PHONE)).isEqualTo("5551234");
            assertThat(value(form, RELATIONSHIP)).isEqualTo("M");
            assertThat(value(form, PATIENT_SIGNATURE)).isEqualTo("SIGNATURE ON FILE");
            assertThat(value(form, PATIENT_SIGNATURE_DATE)).isEqualTo("08 01 2026");

            // items 4, 7, 11, 13
            assertThat(value(form, INSURED_NAME)).isEqualTo("DOE, MARY K");
            assertThat(value(form, INSURED_STREET)).isEqualTo("123 MAIN ST");
            assertThat(value(form, INSURED_ZIP)).isEqualTo("62704");
            assertThat(value(form, INSURED_PHONE_AREA)).isEqualTo("217");
            assertThat(value(form, INSURED_POLICY_GROUP)).isEqualTo("GRP-4471");
            assertThat(value(form, INSURED_DOB_MM)).isEqualTo("11");
            assertThat(value(form, INSURED_DOB_DD)).isEqualTo("03");
            assertThat(value(form, INSURED_DOB_YY)).isEqualTo("1982");
            assertThat(value(form, INSURED_SEX)).isEqualTo("FEMALE");
            assertThat(value(form, OTHER_CLAIM_ID_QUALIFIER)).isEqualTo("Y4");
            assertThat(value(form, OTHER_CLAIM_ID)).isEqualTo("PC-778");
            assertThat(value(form, INSURED_PLAN_NAME)).isEqualTo("CIGNA OPEN ACCESS PLUS");
            assertThat(value(form, ANOTHER_HEALTH_PLAN)).isEqualTo("YES");
            assertThat(value(form, INSURED_SIGNATURE)).isEqualTo("SIGNATURE ON FILE");

            // item 9
            assertThat(value(form, OTHER_INSURED_NAME)).isEqualTo("DOE, JANE B");
            assertThat(value(form, OTHER_INSURED_POLICY_GROUP)).isEqualTo("OTH-2231");
            assertThat(value(form, OTHER_INSURED_PLAN_NAME)).isEqualTo("AETNA PPO");

            // item 10
            assertThat(value(form, EMPLOYMENT)).isEqualTo("NO");
            assertThat(value(form, AUTO_ACCIDENT)).isEqualTo("YES");
            assertThat(value(form, AUTO_ACCIDENT_STATE)).isEqualTo("IL");
            assertThat(value(form, OTHER_ACCIDENT)).isEqualTo("NO");
            assertThat(value(form, CLAIM_CODES)).isEqualTo("W2");

            // items 14-18
            assertThat(value(form, CURRENT_ILLNESS_MM)).isEqualTo("07");
            assertThat(value(form, CURRENT_ILLNESS_YY)).isEqualTo("2026");
            assertThat(value(form, CURRENT_ILLNESS_QUALIFIER)).isEqualTo("431");
            assertThat(value(form, OTHER_DATE_DD)).isEqualTo("20");
            assertThat(value(form, OTHER_DATE_QUALIFIER)).isEqualTo("454");
            assertThat(value(form, WORK_FROM_DD)).isEqualTo("15");
            assertThat(value(form, WORK_TO_DD)).isEqualTo("30");
            assertThat(value(form, REFERRING_QUALIFIER)).isEqualTo("DN");
            assertThat(value(form, REFERRING_NAME)).isEqualTo("SMITH, ALICE MD");
            assertThat(value(form, REFERRING_OTHER_ID_QUALIFIER)).isEqualTo("G2");
            assertThat(value(form, REFERRING_OTHER_ID)).isEqualTo("REF-991");
            assertThat(value(form, REFERRING_NPI)).isEqualTo(ClaimFixtures.NPI_REFERRING);
            assertThat(value(form, HOSP_FROM_DD)).isEqualTo("16");
            assertThat(value(form, HOSP_TO_DD)).isEqualTo("18");

            // items 19-23
            assertThat(value(form, ADDITIONAL_CLAIM_INFO)).isEqualTo("PWK OZ 1234");
            assertThat(value(form, OUTSIDE_LAB)).isEqualTo("YES");
            assertThat(value(form, OUTSIDE_LAB_CHARGES)).isEqualTo("45 00");
            assertThat(value(form, ICD_INDICATOR)).isEqualTo("0");
            assertThat(value(form, diagnosis(1))).isEqualTo("S82101A");
            assertThat(value(form, diagnosis(2))).isEqualTo("M545");
            assertThat(value(form, diagnosis(3))).isEqualTo("E119");
            assertThat(value(form, diagnosis(4))).isEqualTo("I10");
            assertThat(value(form, diagnosis(5))).isEmpty();
            assertThat(value(form, RESUBMISSION_CODE)).isEqualTo("7");
            assertThat(value(form, ORIGINAL_REFERENCE)).isEqualTo("ORIG-555");
            assertThat(value(form, PRIOR_AUTHORIZATION)).isEqualTo("PA-2026-001");

            // items 25-29
            assertThat(value(form, TAX_ID)).isEqualTo("123456789");
            assertThat(value(form, TAX_ID_TYPE)).isEqualTo("EIN");
            assertThat(value(form, PATIENT_ACCOUNT)).isEqualTo("ACCT-0099");
            assertThat(value(form, ACCEPT_ASSIGNMENT)).isEqualTo("YES");
            assertThat(value(form, TOTAL_CHARGE)).isEqualTo("535 50");
            assertThat(value(form, AMOUNT_PAID)).isEqualTo("100 00");

            // items 31-33
            assertThat(value(form, PHYSICIAN_SIGNATURE)).isEqualTo("ROBERT JONES MD");
            assertThat(value(form, PHYSICIAN_SIGNATURE_DATE)).isEqualTo("08 02 2026");
            assertThat(value(form, FACILITY_NAME)).isEqualTo("SPRINGFIELD ORTHO CENTER");
            assertThat(value(form, FACILITY_STREET)).isEqualTo("500 CLINIC DR");
            assertThat(value(form, FACILITY_CITY_STATE_ZIP)).isEqualTo("SPRINGFIELD IL 62704");
            assertThat(value(form, FACILITY_NPI)).isEqualTo(ClaimFixtures.NPI_FACILITY);
            assertThat(value(form, FACILITY_OTHER_ID)).isEqualTo("FAC-77");
            assertThat(value(form, BILLING_PHONE_AREA)).isEqualTo("217");
            assertThat(value(form, BILLING_PHONE)).isEqualTo("5559876");
            assertThat(value(form, BILLING_NAME)).isEqualTo("SPRINGFIELD ORTHOPEDICS LLC");
            assertThat(value(form, BILLING_STREET)).isEqualTo("500 CLINIC DR STE 200");
            assertThat(value(form, BILLING_CITY_STATE_ZIP)).isEqualTo("SPRINGFIELD IL 627041234");
            assertThat(value(form, BILLING_NPI)).isEqualTo(ClaimFixtures.NPI_BILLING);
            assertThat(value(form, BILLING_OTHER_ID)).isEqualTo("BP-12");
        }
    }

    @Test
    void fillsServiceLinesWithAllColumns() throws IOException {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-LINES");
        try (PDDocument doc = filler.fillPage(claim, 0, 1)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            // line 1: single date repeated in TO, EMG, one modifier, shaded columns
            assertThat(value(form, serviceFromMm(1))).isEqualTo("07");
            assertThat(value(form, serviceFromDd(1))).isEqualTo("16");
            assertThat(value(form, serviceFromYy(1))).isEqualTo("26");
            assertThat(value(form, serviceToMm(1))).isEqualTo("07");
            assertThat(value(form, serviceToDd(1))).isEqualTo("16");
            assertThat(value(form, serviceToYy(1))).isEqualTo("26");
            assertThat(value(form, placeOfService(1))).isEqualTo("11");
            assertThat(value(form, emergency(1))).isEqualTo("Y");
            assertThat(value(form, procedureCode(1))).isEqualTo("99213");
            assertThat(value(form, modifier(1, 0))).isEqualTo("25");
            assertThat(value(form, modifier(1, 1))).isEmpty();
            assertThat(value(form, diagnosisPointers(1))).isEqualTo("AB");
            assertThat(value(form, charges(1))).isEqualTo("150 00");
            assertThat(value(form, units(1))).isEqualTo("1");
            assertThat(value(form, renderingIdQualifier(1))).isEqualTo("G2");
            assertThat(value(form, renderingOtherId(1))).isEqualTo("RP-100");
            assertThat(value(form, renderingNpi(1))).isEqualTo(ClaimFixtures.NPI_RENDERING);
            assertThat(value(form, supplementalInfo(1))).isEqualTo("N4 12345678901 UN 1");
            // line 2: two modifiers, no EMG
            assertThat(value(form, emergency(2))).isEmpty();
            assertThat(value(form, modifier(2, 0))).isEqualTo("RT");
            assertThat(value(form, modifier(2, 1))).isEqualTo("26");
            assertThat(value(form, charges(2))).isEqualTo("85 50");
            // line 3: EPSDT + family planning, units 2
            assertThat(value(form, epsdt(3))).isEqualTo("AV");
            assertThat(value(form, familyPlanning(3))).isEqualTo("Y");
            assertThat(value(form, units(3))).isEqualTo("2");
            assertThat(value(form, diagnosisPointers(3))).isEqualTo("AC");
            // lines 4-6 untouched
            for (int line = 4; line <= 6; line++) {
                assertThat(value(form, procedureCode(line))).isEmpty();
                assertThat(value(form, charges(line))).isEmpty();
            }
        }
    }

    @Test
    void selfInsuredPatientIsCopiedIntoInsuredItemsWhenInsuredIsOmitted() throws IOException {
        Cms1500Claim claim = ClaimFixtures.minimalClaim("CLM-SELF");
        assertThat(claim.getInsured()).isNull();
        try (PDDocument doc = filler.fillPage(claim, 0, 1)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertThat(value(form, RELATIONSHIP)).isEqualTo("S");
            assertThat(value(form, INSURED_NAME)).isEqualTo("ROE, RICHARD");
            assertThat(value(form, INSURED_STREET)).isEqualTo("9 ELM AVE");
            assertThat(value(form, INSURED_CITY)).isEqualTo("PEORIA");
            assertThat(value(form, INSURED_DOB_YY)).isEqualTo("1950");
            assertThat(value(form, INSURED_SEX)).isEqualTo("MALE");
            assertThat(value(form, INSURED_POLICY_GROUP)).isEmpty();
            assertThat(value(form, ANOTHER_HEALTH_PLAN)).isEqualTo("Off");
            assertThat(value(form, EMPLOYMENT)).isEqualTo("Off");
            assertThat(value(form, TOTAL_CHARGE)).isEqualTo("120 00");
        }
    }

    @Test
    void paginatesMoreThanSixServiceLinesAndPrintsTotalsOnLastPageOnly() throws IOException {
        Cms1500Claim claim = ClaimFixtures.withServiceLines(ClaimFixtures.fullClaim("CLM-14"), 14);
        assertThat(Cms1500FormFiller.pageCount(14)).isEqualTo(3);

        try (PDDocument page0 = filler.fillPage(claim, 0, 3);
             PDDocument page2 = filler.fillPage(claim, 2, 3)) {
            PDAcroForm first = page0.getDocumentCatalog().getAcroForm();
            PDAcroForm last = page2.getDocumentCatalog().getAcroForm();
            // header repeated on every page
            assertThat(value(first, PATIENT_NAME)).isEqualTo(value(last, PATIENT_NAME)).isEqualTo("DOE, JOHN A");
            assertThat(value(last, BILLING_NPI)).isEqualTo(ClaimFixtures.NPI_BILLING);
            // lines 1-6 on page 1, lines 13-14 on page 3
            assertThat(value(first, procedureCode(1))).isEqualTo("90000");
            assertThat(value(first, procedureCode(6))).isEqualTo("90005");
            assertThat(value(last, procedureCode(1))).isEqualTo("90012");
            assertThat(value(last, procedureCode(2))).isEqualTo("90013");
            assertThat(value(last, procedureCode(3))).isEmpty();
            // totals only on the last page
            assertThat(value(first, TOTAL_CHARGE)).isEmpty();
            assertThat(value(first, AMOUNT_PAID)).isEmpty();
            assertThat(value(last, TOTAL_CHARGE)).isEqualTo("140 00");
            assertThat(value(last, AMOUNT_PAID)).isEqualTo("100 00");
        }

        try (PDDocument all = filler.fill(claim)) {
            assertThat(all.getNumberOfPages()).isEqualTo(3);
            assertThat(all.getDocumentCatalog().getAcroForm()).as("flattened").isNull();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String secondPage = stripper.getText(all);
            assertThat(secondPage).contains("90006").contains("90011").doesNotContain("90000").doesNotContain("90012");
        }
    }

    @Test
    void continuationMarkerIsPrintedOnEarlierPagesWhenConfigured() throws IOException {
        Cms1500FormFiller marked = new Cms1500FormFiller(TestTemplate.bytes(), new FormText(true, true), "Continued");
        Cms1500Claim claim = ClaimFixtures.withServiceLines(ClaimFixtures.fullClaim("CLM-CONT"), 7);
        try (PDDocument page0 = marked.fillPage(claim, 0, 2); PDDocument page1 = marked.fillPage(claim, 1, 2)) {
            assertThat(value(page0.getDocumentCatalog().getAcroForm(), TOTAL_CHARGE)).isEqualTo("CONTINUED");
            assertThat(value(page1.getDocumentCatalog().getAcroForm(), TOTAL_CHARGE)).isEqualTo("70 00");
        }
    }

    @Test
    void outputHasOnlyTheClaimPageWithoutTheClearFormButtonAndIsFlattened() throws IOException {
        Cms1500Claim claim = ClaimFixtures.minimalClaim("CLM-FLAT");
        try (PDDocument page = filler.fillPage(claim, 0, 1)) {
            assertThat(page.getNumberOfPages()).as("instructions page dropped").isEqualTo(1);
            PDAcroForm form = page.getDocumentCatalog().getAcroForm();
            assertThat(form.getField(CLEAR_FORM_BUTTON)).isNull();
            List<PDAnnotation> annotations = page.getPage(0).getAnnotations();
            assertThat(annotations).noneMatch(a -> "Btn".equals(a.getCOSObject().getNameAsString("FT"))
                    && a.getCOSObject().getString("T") != null
                    && a.getCOSObject().getString("T").contains("Clear"));
        }
        try (PDDocument flat = filler.fill(claim)) {
            assertThat(flat.getNumberOfPages()).isEqualTo(1);
            assertThat(flat.getDocumentCatalog().getAcroForm()).isNull();
            assertThat(flat.getPage(0).getAnnotations())
                    .as("only the template's link annotation may remain")
                    .noneMatch(a -> a instanceof PDAnnotationWidget);
            String text = new PDFTextStripper().getText(flat);
            assertThat(text).contains("ROE, RICHARD").contains("99214").contains("120 00").doesNotContain("Clear Form");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            flat.save(bytes);
            try (PDDocument reloaded = Loader.loadPDF(bytes.toByteArray())) {
                assertThat(reloaded.getNumberOfPages()).isEqualTo(1);
            }
        }
    }

    @Test
    void nonAsciiTextIsFoldedSoTheTemplateFontCanRenderIt() throws IOException {
        Cms1500Claim claim = ClaimFixtures.minimalClaim("CLM-UNI");
        claim.getPatient().setName(ClaimFixtures.name("Núñez-Ærø", "José", "É"));
        claim.getBillingProvider().setName("Clinique Générale 東京");
        try (PDDocument page = filler.fillPage(claim, 0, 1)) {
            PDAcroForm form = page.getDocumentCatalog().getAcroForm();
            assertThat(value(form, PATIENT_NAME)).isEqualTo("NUNEZ-AERO, JOSE E");
            assertThat(value(form, BILLING_NAME)).isEqualTo("CLINIQUE GENERALE ??");
        }
    }

    @Test
    void missingTemplateFieldIsReportedAsTemplateError() throws IOException {
        byte[] broken;
        try (PDDocument doc = Loader.loadPDF(TestTemplate.bytes())) {
            Cms1500FormFiller.removeField(doc, doc.getDocumentCatalog().getAcroForm(), PATIENT_NAME);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            broken = out.toByteArray();
        }
        Cms1500FormFiller brokenFiller = new Cms1500FormFiller(broken, new FormText(true, true), "");
        assertThatThrownBy(() -> brokenFiller.fillPage(ClaimFixtures.minimalClaim("CLM-BROKEN"), 0, 1).close())
                .isInstanceOf(ClaimException.class)
                .hasMessageContaining("pt_name");
    }

    @Test
    void rendersFilledFormForVisualInspection() throws IOException {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-RENDER");
        claim.getPatient().setRelationshipToInsured(Relationship.SPOUSE);
        File dir = new File("target/visual");
        assertThat(dir.mkdirs() || dir.isDirectory()).isTrue();
        try (PDDocument flat = filler.fill(claim)) {
            flat.save(new File(dir, "cms1500-full.pdf"));
            var image = new PDFRenderer(flat).renderImageWithDPI(0, 110);
            assertThat(ImageIO.write(image, "png", new File(dir, "cms1500-full.png"))).isTrue();
        }
    }

    static String value(PDAcroForm form, String name) {
        PDField field = form.getField(name);
        assertThat(field).as("field %s exists", name).isNotNull();
        if (field instanceof PDCheckBox box) {
            return box.getValue();
        }
        return field.getValueAsString();
    }
}
