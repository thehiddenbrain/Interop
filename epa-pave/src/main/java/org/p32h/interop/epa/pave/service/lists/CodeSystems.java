package org.p32h.interop.epa.pave.service.lists;

import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.postman.Names;

/** A code system as a tester names it, for a line on screen. The system itself is kept and sent as written. */
public final class CodeSystems {

    private static final Map<String, String> SHORT = Map.ofEntries(
            Map.entry("http://www.ama-assn.org/go/cpt", "CPT"),
            Map.entry("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "HCPCS"),
            Map.entry("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "HCPCS"),
            Map.entry("https://bluebutton.cms.gov/resources/codesystem/hcpcs", "HCPCS"),
            Map.entry("urn:oid:2.16.840.1.113883.6.285", "HCPCS"),
            Map.entry("http://www.cms.gov/Medicare/Coding/ICD10", "ICD-10-PCS"),
            Map.entry("http://hl7.org/fhir/sid/icd-10-cm", "ICD-10-CM"),
            Map.entry("http://snomed.info/sct", "SNOMED CT"),
            Map.entry("http://loinc.org", "LOINC"));

    /**
     * Systems of medication codes. A medication ordered or claimed is not a procedure, and the
     * Service card's code is sent as one, so such a code is never offered as a procedure code.
     */
    static final Set<String> MEDICATION = Set.of("http://www.nlm.nih.gov/research/umls/rxnorm", "http://hl7.org/fhir/sid/ndc");

    private CodeSystems() {
    }

    /** CPT, HCPCS and the like for a system this tool knows; otherwise the system as written, a long one cut; null for none. */
    public static String shortName(String system) {
        if (system == null) {
            return null;
        }
        String known = SHORT.get(system);
        return known != null ? known : Names.shown(system);
    }
}
