package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileLiteCheckerTest {

    private static final IgCatalog CATALOG = new IgCatalog();
    private static final ProfileLiteChecker CHECKER = new ProfileLiteChecker(CATALOG);
    private static final String C4BB_PATIENT = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient";
    private static final String C4BB_INPATIENT = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-ExplanationOfBenefit-Inpatient-Institutional";

    private static Map<String, IgCatalog.ProfileRule> rules(String profile) {
        Map<String, IgCatalog.ProfileRule> byId = new LinkedHashMap<>();
        for (IgCatalog.ProfileRule r : CATALOG.profile(profile).orElseThrow().elements()) {
            byId.put(r.id(), r);
        }
        return byId;
    }

    private static List<ProfileLiteChecker.Issue> errors(ProfileLiteChecker.Report r) {
        return r.issues().stream().filter(i -> i.severity().equals("error")).toList();
    }

    @Test
    void c4bbExamplesPassTheirDeclaredProfiles() {
        ProfileLiteChecker.Report patient = CHECKER.check(Fixtures.demo("c4bb-Patient-Patient1"));
        assertThat(patient.profile()).isEqualTo(C4BB_PATIENT);
        assertThat(patient.profileName()).isEqualTo("C4BBPatient");
        assertThat(patient.declared()).isTrue();
        assertThat(errors(patient)).isEmpty();
        assertThat(patient.errors()).isZero();
        assertThat(patient.warnings()).isZero();
        assertThat(patient.infos()).isEqualTo(patient.issues().size());
        // must-support elements the example does not populate are reported as info only
        assertThat(patient.issues()).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("info");
            assertThat(i.message()).contains("must-support element");
        });

        ProfileLiteChecker.Report eob = CHECKER.check(Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1"));
        assertThat(eob.profile()).isEqualTo(C4BB_INPATIENT);
        assertThat(eob.declared()).isTrue();
        assertThat(errors(eob)).isEmpty();

        ProfileLiteChecker.Report coverage = CHECKER.check(Fixtures.demo("c4bb-Coverage-Coverage1"));
        assertThat(coverage.declared()).isTrue();
        assertThat(errors(coverage)).isEmpty();

        ProfileLiteChecker.Report pa = CHECKER.check(Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1"));
        assertThat(pa.profile()).isEqualTo(PriorAuthSummarizer.PDEX_PA_PROFILE);
        assertThat(errors(pa)).isEmpty();
    }

    @Test
    void missingRequiredIdentifierIsAnError() {
        ObjectNode patient = Fixtures.demo("c4bb-Patient-Patient1");
        patient.remove("identifier");
        ProfileLiteChecker.Report r = CHECKER.check(patient);
        assertThat(r.errors()).isGreaterThanOrEqualTo(1);
        // the catalog paths are relative to the resource ("identifier", not "Patient.identifier")
        assertThat(errors(r)).allSatisfy(i -> assertThat(i.path()).isEqualTo("identifier"));
        assertThat(errors(r)).anySatisfy(i -> assertThat(i.message()).contains("required element (1..*) is missing"));
        assertThat(errors(r)).anySatisfy(i -> assertThat(i.message()).contains("required slice 'memberid'"));

        // identifiers present but none typed MB: the member id slice is not satisfied
        ObjectNode wrongType = Fixtures.demo("c4bb-Patient-Patient1");
        ((ObjectNode) wrongType.withArray("identifier").get(0).path("type").withArray("coding").get(0)).put("code", "MR");
        ProfileLiteChecker.Report slice = CHECKER.check(wrongType);
        assertThat(errors(slice)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("identifier");
            assertThat(i.message()).contains("required slice 'memberid'");
        });
    }

    @Test
    void fixedValuesOfTheDeclaredProfileAreEnforced() {
        ObjectNode eob = Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1");
        eob.put("use", "claim");
        ProfileLiteChecker.Report r = CHECKER.check(eob);
        assertThat(r.profile()).isEqualTo(PriorAuthSummarizer.PDEX_PA_PROFILE);
        assertThat(r.declared()).isTrue();
        assertThat(errors(r)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("use");
            assertThat(i.message()).contains("does not match the fixed/pattern value").contains("preauthorization");
        });
        assertThat(CHECKER.check(eob, PriorAuthSummarizer.PDEX_PA_PROFILE + "|2.1.0").errors()).isEqualTo(1);
    }

    @Test
    void undeclaredProfilesFallBackToTheResourceTypeDefault() {
        ObjectNode patient = Fixtures.demo("c4bb-Patient-Patient1");
        patient.remove("meta");
        ProfileLiteChecker.Report r = CHECKER.check(patient);
        assertThat(r.profile()).isEqualTo(C4BB_PATIENT);
        assertThat(r.declared()).isFalse();
        assertThat(r.warnings()).isEqualTo(1);
        assertThat(r.issues().get(0).path()).isEqualTo("meta.profile");
        assertThat(r.issues().get(0).message()).contains("does not declare " + C4BB_PATIENT);
        // Patient.meta is required by the profile
        assertThat(errors(r)).anySatisfy(i -> assertThat(i.path()).isEqualTo("meta"));

        ObjectNode pharmacy = Fixtures.resource("ExplanationOfBenefit", "rx");
        pharmacy.putObject("type").putArray("coding").addObject().put("code", "pharmacy");
        assertThat(ProfileLiteChecker.eobProfile(pharmacy)).endsWith("C4BB-ExplanationOfBenefit-Pharmacy");
        ObjectNode outpatient = Fixtures.resource("ExplanationOfBenefit", "op");
        outpatient.putObject("type").putArray("coding").addObject().put("code", "institutional");
        outpatient.putObject("subType").putArray("coding").addObject().put("code", "outpatient");
        assertThat(ProfileLiteChecker.eobProfile(outpatient)).endsWith("Outpatient-Institutional");
        assertThat(ProfileLiteChecker.eobProfile(Fixtures.resource("ExplanationOfBenefit", "x").put("use", "preauthorization")))
                .isEqualTo(PriorAuthSummarizer.PDEX_PA_PROFILE);
        assertThat(ProfileLiteChecker.eobProfile(Fixtures.resource("ExplanationOfBenefit", "untyped"))).endsWith("C4BB-ExplanationOfBenefit");

        ProfileLiteChecker.Report none = CHECKER.check(Fixtures.resource("Condition", "c"));
        assertThat(none.profile()).isNull();
        assertThat(none.issues()).singleElement().satisfies(i -> assertThat(i.message()).contains("no C4BB / PDex profile rules for Condition"));
        assertThat(CHECKER.check(Fixtures.resource("Patient", "p"), "http://example.org/unknown").issues().get(0).message()).contains("unknown profile");
    }

    @Test
    void slicesAreMatchedByTheirDiscriminators() {
        JsonNode patient = Fixtures.demo("c4bb-Patient-Patient1");
        Map<String, IgCatalog.ProfileRule> patientRules = rules(C4BB_PATIENT);
        assertThat(CHECKER.matches(patient, "Patient.identifier", patientRules)).hasSize(2);
        assertThat(CHECKER.matches(patient, "Patient.identifier:memberid", patientRules)).singleElement()
                .satisfies(n -> assertThat(n.path("value").asText()).isEqualTo("1234-234-1243-12345678901"));
        assertThat(CHECKER.matches(patient, "Patient.identifier:memberid.value", patientRules)).singleElement()
                .satisfies(n -> assertThat(n.asText()).isEqualTo("1234-234-1243-12345678901"));
        assertThat(CHECKER.matches(patient, "Patient.identifier:uniquememberid", patientRules)).singleElement()
                .satisfies(n -> assertThat(n.path("value").asText()).endsWith("u"));
        assertThat(CHECKER.matches(patient, "Patient.name.given", patientRules)).singleElement().satisfies(n -> assertThat(n.asText()).isEqualTo("Johnny"));
        assertThat(CHECKER.matches(patient, "Patient.deceased[x]", patientRules)).isEmpty();

        JsonNode pa = Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1");
        Map<String, IgCatalog.ProfileRule> paRules = rules(PriorAuthSummarizer.PDEX_PA_PROFILE);
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.extension:levelOfServiceType", paRules)).singleElement()
                .satisfies(n -> assertThat(n.path("url").asText()).isEqualTo(PriorAuthSummarizer.EXT_LEVEL_OF_SERVICE));
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.item.adjudication.extension:reviewAction", paRules)).singleElement()
                .satisfies(n -> assertThat(n.path("url").asText()).isEqualTo(PriorAuthSummarizer.EXT_REVIEW_ACTION));
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.item.adjudication.extension:adjudicationActionDate", paRules)).hasSize(1);
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.item.extension:preAuthPeriod", paRules)).isEmpty();
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.item.adjudication:allowedunits", paRules)).isEmpty();
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.diagnosis.diagnosis[x]", paRules)).singleElement()
                .satisfies(n -> assertThat(n.path("coding").get(0).path("code").asText()).isEqualTo("G89.4"));
        assertThat(CHECKER.matches(pa, "ExplanationOfBenefit.total.extension:priorauth-utilization", paRules)).hasSize(1);

        // type slices of choice elements select the property named by the slice; binding-only slices accept every candidate
        JsonNode inpatient = Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1");
        Map<String, IgCatalog.ProfileRule> inpatientRules = rules(C4BB_INPATIENT);
        assertThat(CHECKER.matches(inpatient, "ExplanationOfBenefit.supportingInfo:admissionperiod.timing[x]:timingPeriod", inpatientRules)).singleElement()
                .satisfies(n -> assertThat(n.path("start").asText()).isEqualTo("2011-05-23"));
        assertThat(CHECKER.matches(inpatient, "ExplanationOfBenefit.supportingInfo:clmrecvddate.timing[x]", inpatientRules)).singleElement()
                .satisfies(n -> assertThat(n.isTextual()).isTrue());
        assertThat(CHECKER.matches(inpatient, "ExplanationOfBenefit.total:adjudicationamounttype", inpatientRules)).hasSize(3);
        assertThat(CHECKER.matches(inpatient, "ExplanationOfBenefit.item.adjudication:allowedunits", inpatientRules)).isEmpty();
    }

    @Test
    void fixedValueMatchingUnderstandsPatternsCodingsAndCanonicals() {
        ObjectNode cc = Fixtures.parse("{\"coding\":[{\"system\":\"s\",\"code\":\"a\",\"display\":\"A\"},{\"system\":\"s\",\"code\":\"b\"}]}");
        assertThat(ProfileLiteChecker.matchesFixed(cc, Fixtures.parse("{\"patternCodeableConcept\":{\"coding\":[{\"system\":\"s\",\"code\":\"b\"}]}}"))).isTrue();
        assertThat(ProfileLiteChecker.matchesFixed(cc, Fixtures.parse("{\"patternCodeableConcept\":{\"coding\":[{\"system\":\"other\",\"code\":\"b\"}]}}"))).isFalse();
        assertThat(ProfileLiteChecker.matchesFixed(cc.withArray("coding").get(0), Fixtures.parse("{\"fixedCoding\":{\"code\":\"a\"}}"))).isTrue();
        assertThat(ProfileLiteChecker.matchesFixed(Fixtures.parse("{\"system\":\"urn:x\",\"value\":\"1\"}"), Fixtures.parse("{\"patternIdentifier\":{\"system\":\"urn:x\"}}"))).isTrue();
        assertThat(ProfileLiteChecker.matchesFixed(Fixtures.MAPPER.getNodeFactory().textNode("http://p|1.0"), Fixtures.parse("{\"patternCanonical\":\"http://p\"}"))).isTrue();
        assertThat(ProfileLiteChecker.matchesFixed(Fixtures.MAPPER.getNodeFactory().textNode("claim"), Fixtures.parse("{\"patternCode\":\"preauthorization\"}"))).isFalse();
        assertThat(ProfileLiteChecker.matchesFixed(cc, Fixtures.parse("{\"patternCode\":\"x\"}"))).isFalse();
    }
}
