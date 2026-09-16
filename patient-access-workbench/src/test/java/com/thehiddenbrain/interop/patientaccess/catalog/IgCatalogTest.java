package com.thehiddenbrain.interop.patientaccess.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IgCatalogTest {

    private static final IgCatalog CATALOG = new IgCatalog();
    private static final String C4BB = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/";
    private static final String PDEX_PA = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization";

    @Test
    void loadsTheImplementationGuidesAndResourceTypes() {
        assertThat(CATALOG.igs()).containsKeys("c4bb", "pdex", "uscore", "usdf", "pas", "smart");
        assertThat(CATALOG.igs().get("c4bb").version()).isEqualTo("2.1.0");
        assertThat(CATALOG.igs().get("c4bb").canonical()).isEqualTo("http://hl7.org/fhir/us/carin-bb");
        assertThat(CATALOG.igs().get("pdex").version()).isEqualTo("2.1.0");
        assertThat(CATALOG.resources()).containsKeys("Patient", "Coverage", "ExplanationOfBenefit", "Organization", "Practitioner", "Condition",
                "Observation", "MedicationDispense", "Provenance", "InsurancePlan");
        assertThat(CATALOG.resource("Nope")).isEmpty();
        assertThat(CATALOG.resource("ExplanationOfBenefit").orElseThrow().profiles()).extracting(IgCatalog.ProfileRef::url)
                .contains(C4BB + "C4BB-ExplanationOfBenefit-Inpatient-Institutional", PDEX_PA);
        assertThat(CATALOG.resource("ExplanationOfBenefit").orElseThrow().includes()).contains("ExplanationOfBenefit:patient", "ExplanationOfBenefit:*");
        assertThat(CATALOG.resource("Coverage").orElseThrow().interactions()).contains("read", "search-type");
    }

    @Test
    void eobSearchParametersCarryTheirExpectations() {
        IgCatalog.ResourceSpec eob = CATALOG.resource("ExplanationOfBenefit").orElseThrow();
        for (String shall : List.of("patient", "_id", "identifier", "_lastUpdated", "type", "service-date")) {
            IgCatalog.SearchParamSpec p = eob.param(shall).orElseThrow(() -> new AssertionError("missing " + shall));
            assertThat(p.expectation()).as(shall).isEqualTo("SHALL");
            assertThat(p.igs()).as(shall).contains("c4bb");
        }
        IgCatalog.SearchParamSpec use = eob.param("use").orElseThrow();
        assertThat(use.igs()).containsExactly("pdex");
        assertThat(use.type()).isEqualTo("token");
        assertThat(use.expectation()).isEqualTo("MAY");
        assertThat(eob.param("patient").orElseThrow().type()).isEqualTo("reference");
        assertThat(eob.param("no-such-param")).isEmpty();
    }

    @Test
    void patientCombinationsIncludeBirthdateAndNameAsShall() {
        IgCatalog.ResourceSpec patient = CATALOG.resource("Patient").orElseThrow();
        assertThat(patient.param("identifier").orElseThrow().expectation()).isEqualTo("SHALL");
        assertThat(patient.param("name").orElseThrow().expectation()).isEqualTo("SHALL");
        assertThat(patient.combos()).anySatisfy(c -> {
            assertThat(c.params()).containsExactly("birthdate", "name");
            assertThat(c.expectation()).isEqualTo("SHALL");
            assertThat(c.igs()).contains("uscore");
        });
        assertThat(patient.combos()).anySatisfy(c -> {
            assertThat(c.params()).containsExactly("birthdate", "family");
            assertThat(c.expectation()).isEqualTo("SHOULD");
        });
    }

    @Test
    void validateParamsWarnsOnlyAboutUndeclaredParameters() {
        assertThat(CATALOG.validateParams("ExplanationOfBenefit", List.of("patient", "_count", "_lastUpdated", "use", "type:not"))).isEmpty();
        assertThat(CATALOG.validateParams("ExplanationOfBenefit", List.of("patient", "foo", "bar"))).satisfiesExactly(
                w -> assertThat(w).contains("'foo'").contains("not declared for ExplanationOfBenefit"),
                w -> assertThat(w).contains("'bar'"));
        assertThat(CATALOG.validateParams("Patient", List.of("identifier:of-type", "_include", "_revinclude"))).isEmpty();
        assertThat(CATALOG.validateParams("Appointment", List.of("patient", "date"))).singleElement()
                .satisfies(w -> assertThat(w).isEqualTo("Appointment is not part of the Patient Access API IGs in this catalog"));
        assertThat(CATALOG.validateParams("Appointment", List.of("_id"))).isEmpty();
    }

    @Test
    void profilesAreFoundWithOrWithoutAVersionSuffix() {
        IgCatalog.ProfileSpec patient = CATALOG.profile(C4BB + "C4BB-Patient|2.1.0").orElseThrow();
        assertThat(patient.name()).isEqualTo("C4BBPatient");
        assertThat(patient.type()).isEqualTo("Patient");
        assertThat(patient.ig()).isEqualTo("c4bb");
        assertThat(patient.version()).isEqualTo("2.1.0");
        assertThat(patient.elements()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo("Patient.identifier:memberid");
            assertThat(e.min()).isEqualTo(1);
            assertThat(e.mustSupport()).isTrue();
            assertThat(e.slice()).isEqualTo("memberid");
        });
        assertThat(CATALOG.profile(C4BB + "C4BB-Patient")).isPresent();
        assertThat(CATALOG.profile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")).isEmpty();
        IgCatalog.ProfileSpec pa = CATALOG.profile(PDEX_PA).orElseThrow();
        assertThat(pa.type()).isEqualTo("ExplanationOfBenefit");
        assertThat(pa.elements()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo("ExplanationOfBenefit.use");
            assertThat(e.fixed().path("patternCode").asString("")).isEqualTo("preauthorization");
        });
        assertThat(pa.elements()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo("ExplanationOfBenefit.item.adjudication:denialreason.category");
            assertThat(e.binding()).isNotNull();
        });
        assertThat(CATALOG.profiles()).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void eobProfilesMapClaimTypesAndUse() {
        assertThat(CATALOG.eobProfiles()).hasSize(6);
        IgCatalog.EobProfile inpatient = CATALOG.eobProfile(C4BB + "C4BB-ExplanationOfBenefit-Inpatient-Institutional|2.1.0").orElseThrow();
        assertThat(inpatient.claimType()).isEqualTo("institutional");
        assertThat(inpatient.subType()).isEqualTo("inpatient");
        assertThat(inpatient.use()).isEqualTo("claim");
        IgCatalog.EobProfile pharmacy = CATALOG.eobProfile(C4BB + "C4BB-ExplanationOfBenefit-Pharmacy").orElseThrow();
        assertThat(pharmacy.claimType()).isEqualTo("pharmacy");
        assertThat(pharmacy.subType()).isNull();
        IgCatalog.EobProfile pa = CATALOG.eobProfile(PDEX_PA).orElseThrow();
        assertThat(pa.use()).isEqualTo("preauthorization");
        assertThat(pa.claimType()).isNull();
        assertThat(CATALOG.eobProfile(C4BB + "C4BB-Patient")).isEmpty();
    }

    @Test
    void displaysPdexAndC4bbCodes() {
        assertThat(CATALOG.display("pdexAdjudicationDiscriminator", "allowedunits")).isEqualTo("allowed units");
        assertThat(CATALOG.display("pdexAdjudicationDiscriminator", "denialreason")).isEqualTo("Denial Reason");
        assertThat(CATALOG.display("priorAuthorizationValueCodes", "eligible")).isEqualTo("Eligible");
        assertThat(CATALOG.display("c4bbAdjudication", "paidbypatient")).isEqualTo("Paid by patient");
        assertThat(CATALOG.display("c4bbAdjudication", "unknown-code")).isNull();
        assertThat(CATALOG.display("noSuchSystem", "x")).isNull();
        assertThat(CATALOG.codeSystems().path("pdexPayerAdjudicationStatus").path("codes").path("innetwork").asString("")).isEqualTo("In Network");
        assertThat(CATALOG.valueSets()).isNotNull();
    }
}
