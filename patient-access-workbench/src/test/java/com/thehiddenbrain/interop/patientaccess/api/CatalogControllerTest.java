package com.thehiddenbrain.interop.patientaccess.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogControllerTest extends ApiTestSupport {

    private static final String PDEX_PA = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization";

    @Test
    void summaryListsIgsResourceTypesAndEobProfiles() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.igs.c4bb.version").value("2.1.0"))
                .andExpect(jsonPath("$.igs.pdex.canonical").value("http://hl7.org/fhir/us/davinci-pdex"))
                .andExpect(jsonPath("$.resourceTypes", hasItems("Patient", "Coverage", "ExplanationOfBenefit", "Condition")))
                .andExpect(jsonPath("$.eobProfiles", hasSize(6)))
                .andExpect(jsonPath("$.eobProfiles[?(@.use == 'preauthorization')].url").value(hasItem(PDEX_PA)));
    }

    @Test
    void resourceSpecsProfilesAndCodesAreServed() throws Exception {
        mvc.perform(get("/api/v1/catalog/resources/ExplanationOfBenefit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ExplanationOfBenefit"))
                .andExpect(jsonPath("$.searchParams[?(@.name == 'patient')].expectation").value(hasItem("SHALL")))
                .andExpect(jsonPath("$.searchParams[?(@.name == 'use')].igs[0]").value(hasItem("pdex")))
                .andExpect(jsonPath("$.includes", hasItem("ExplanationOfBenefit:patient")));
        mvc.perform(get("/api/v1/catalog/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Patient.combos[?(@.expectation == 'SHALL')].params[*]").value(hasItems("birthdate", "name")));
        mvc.perform(get("/api/v1/catalog/resources/Appointment"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource type 'Appointment' not found"));

        mvc.perform(get("/api/v1/catalog/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(10))))
                .andExpect(jsonPath("$[?(@.url == '" + PDEX_PA + "')].type").value(hasItem("ExplanationOfBenefit")));
        mvc.perform(get("/api/v1/catalog/profiles").param("url", PDEX_PA + "|2.1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PdexPriorAuthorization"))
                .andExpect(jsonPath("$.elements[?(@.id == 'ExplanationOfBenefit.use')].fixed.patternCode").value(hasItem("preauthorization")));
        mvc.perform(get("/api/v1/catalog/profiles").param("url", "http://example.org/nope"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/catalog/codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeSystems.pdexAdjudicationDiscriminator.codes.allowedunits").value("allowed units"))
                .andExpect(jsonPath("$.valueSets").exists());
    }
}
