package com.thehiddenbrain.interop.patientaccess.demo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import tools.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the sample data: every file is a FHIR R4 resource HAPI parses strictly, every reference to a resource
 * type the demo holds resolves inside the store, and the two demo members have what the workbench demos need.
 */
class DemoDataIntegrityTest {

    /** Reference targets that must exist in the demo data; Bundle etc. are allowed to dangle (HL7 examples). */
    static final Set<String> MUST_RESOLVE = Set.of("Patient", "Organization", "Practitioner", "Coverage", "Encounter", "Location",
            "Condition", "Observation", "Goal", "DiagnosticReport", "MedicationRequest", "AllergyIntolerance", "CarePlan", "CareTeam",
            "Immunization", "Procedure", "DocumentReference", "InsurancePlan", "MedicationKnowledge", "Device", "Provenance", "ExplanationOfBenefit");

    static final DemoDataStore STORE = new DemoDataStore();

    @Test
    void everyFileIsAValidResourceAndReferencesResolve() {
        IParser parser = FhirContext.forR4Cached().newJsonParser().setParserErrorHandler(new StrictErrorHandler());
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String type : STORE.types()) {
            for (ObjectNode resource : STORE.all(type)) {
                String key = type + "/" + resource.get("id").asString("");
                String file = STORE.sources().get(key);
                try {
                    parser.parseResource(resource.toString());
                } catch (Exception e) {
                    problems.add(file + ": " + e.getMessage());
                }
                assertEquals(DemoDataStore.DEFAULT_VERSION, resource.path("meta").path("versionId").asString(""), file + " versionId");
                assertTrue(resource.path("meta").hasNonNull("lastUpdated"), file + " lastUpdated");
                for (String ref : DemoParams.allReferences(resource)) {
                    String target = DemoDataStore.referenceKey(ref);
                    if (target == null) {
                        continue;
                    }
                    checked++;
                    if (MUST_RESOLVE.contains(target.substring(0, target.indexOf('/'))) && STORE.resolve(target).isEmpty()) {
                        problems.add(file + ": reference " + ref + " does not resolve");
                    }
                }
            }
        }
        assertTrue(checked > 100, "expected many references, checked " + checked);
        assertTrue(problems.isEmpty(), "demo data problems:\n" + String.join("\n", problems));
    }

    @Test
    void demoMembersHaveTheDataTheWorkbenchDemonstrates() {
        assertTrue(STORE.find("Patient", "Patient1").isPresent(), "C4BB Patient1");
        assertTrue(STORE.find("Patient", "1").isPresent(), "PDex Patient/1");
        for (String patient : List.of("Patient1", "1")) {
            long priorAuths = STORE.all("ExplanationOfBenefit").stream()
                    .filter(e -> patient.equals(STORE.patientOf(e).orElse(null)) && PriorAuthSummarizer.isPriorAuth(e)).count();
            assertTrue(priorAuths >= 1, "prior authorizations for Patient/" + patient);
            assertTrue(STORE.all("Coverage").stream().anyMatch(c -> patient.equals(STORE.patientOf(c).orElse(null))), "coverage for " + patient);
            for (String type : List.of("Condition", "Observation", "MedicationRequest", "AllergyIntolerance", "Immunization", "Procedure",
                    "Encounter", "DocumentReference", "CarePlan", "CareTeam", "Goal", "DiagnosticReport")) {
                assertTrue(STORE.all(type).stream().anyMatch(r -> patient.equals(STORE.patientOf(r).orElse(null))), type + " for Patient/" + patient);
            }
        }
        assertEquals(4, STORE.all("ExplanationOfBenefit").stream().filter(e -> "1".equals(STORE.patientOf(e).orElse(null))
                && PriorAuthSummarizer.isPriorAuth(e)).count(), "PDex patient has the HL7 example PA plus denied, pended and partial");
        assertTrue(STORE.all("Provenance").stream().anyMatch(p -> "Patient1".equals(STORE.patientOf(p).orElse(null))), "Provenance resolves to Patient1 through its targets");
        assertTrue(STORE.all("InsurancePlan").size() >= 2 && STORE.all("Basic").size() >= 2 && STORE.all("MedicationKnowledge").size() >= 2, "formulary examples");
    }

    @Test
    void referenceKeysNormalise() {
        assertEquals("Patient/1", DemoDataStore.referenceKey("Patient/1"));
        assertEquals("Patient/1", DemoDataStore.referenceKey("http://localhost:8090/demo/fhir/Patient/1"));
        assertEquals("Patient/1", DemoDataStore.referenceKey("https://host/fhir/Patient/1/_history/3"));
        assertEquals(null, DemoDataStore.referenceKey("1"));
        assertEquals(null, DemoDataStore.referenceKey("#contained"));
        assertEquals(null, DemoDataStore.referenceKey("urn:uuid:abc"));
    }
}
