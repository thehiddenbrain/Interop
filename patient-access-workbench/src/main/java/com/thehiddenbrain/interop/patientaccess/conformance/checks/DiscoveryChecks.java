package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.C4BB;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.PDEX;

/** Reference implementation of the check style: the CapabilityStatement basics. More checks live in this package. */
@Configuration
public class DiscoveryChecks {

    /** Resource types every Patient Access API server declares (C4BB + PDex) and the US Core clinical types it should. */
    static final List<String> REQUIRED_TYPES = List.of("Patient", "Coverage", "ExplanationOfBenefit", "Organization", "Practitioner");
    static final List<String> US_CORE_TYPES = List.of("AllergyIntolerance", "CarePlan", "CareTeam", "Condition", "Device", "DiagnosticReport",
            "DocumentReference", "Encounter", "Goal", "Immunization", "MedicationDispense", "MedicationRequest", "Observation", "Procedure",
            "Provenance");
    /** Profiles a payer's CapabilityStatement should list as supported. */
    static final List<String> EXPECTED_PROFILES = List.of(
            C4BB + "C4BB-Patient", C4BB + "C4BB-Coverage",
            C4BB + "C4BB-ExplanationOfBenefit-Inpatient-Institutional", C4BB + "C4BB-ExplanationOfBenefit-Outpatient-Institutional",
            C4BB + "C4BB-ExplanationOfBenefit-Professional-NonClinician", C4BB + "C4BB-ExplanationOfBenefit-Pharmacy",
            C4BB + "C4BB-ExplanationOfBenefit-Oral", PDEX + "pdex-priorauthorization");
    static final String SMART_SECURITY_CODE = "SMART-on-FHIR";

    @Bean
    Check metadataPresent() {
        return new SimpleCheck("discovery.metadata", "discovery", "CapabilityStatement is served", Severity.SHALL,
                "GET [base]/metadata returns a CapabilityStatement (FHIR R4 conformance; C4BB and PDex servers SHALL publish one)",
                "FHIR R4 http://hl7.org/fhir/R4/http.html#capabilities", false, ctx -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            CheckResult.Builder b = CheckResult.builder(metadataPresent()).evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.fail("no CapabilityStatement at " + ctx.environment().baseUrl() + "/metadata (HTTP " + d.metadataStatus() + ")");
            }
            JsonNode cs = d.capabilityStatement();
            b.detail("software: " + cs.path("software").path("name").asString("?") + " " + cs.path("software").path("version").asString(""));
            b.detail("fhirVersion: " + cs.path("fhirVersion").asString("?"));
            return b.pass("CapabilityStatement received in " + d.metadataMs() + " ms");
        });
    }

    @Bean
    Check fhirVersion() {
        return new SimpleCheck("discovery.fhirVersion", "discovery", "FHIR version is 4.0.1", Severity.SHALL,
                "CapabilityStatement.fhirVersion is 4.0.1 (45 CFR 170.215(a)(1): HL7 FHIR Release 4.0.1)",
                "45 CFR 170.215(a)(1); C4BB 2.1.0 and PDex 2.1.0 are R4 4.0.1 IGs", false, ctx -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            CheckResult.Builder b = CheckResult.builder(fhirVersion()).evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            String v = d.capabilityStatement().path("fhirVersion").asString("");
            if (v.startsWith("4.0")) {
                return b.pass("fhirVersion " + v);
            }
            return b.fail("fhirVersion is '" + v + "', expected 4.0.1");
        });
    }

    @Bean
    Check jsonFormat() {
        return new SimpleCheck("discovery.jsonFormat", "discovery", "JSON format is declared", Severity.SHALL,
                "CapabilityStatement.format includes json / application/fhir+json (C4BB and US Core servers SHALL support JSON)",
                "US Core general requirements; C4BB 2.1.0 CapabilityStatement format", false, ctx -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            CheckResult.Builder b = CheckResult.builder(jsonFormat()).evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            for (JsonNode f : d.capabilityStatement().path("format")) {
                String t = f.asString("");
                if (t.equals("json") || t.contains("json")) {
                    return b.pass("format includes " + t);
                }
            }
            return b.fail("format does not list json");
        });
    }

    @Bean
    Check resourcesDeclared() {
        return SimpleCheck.of("discovery.resources.declared", "discovery", "Required resource types are declared", Severity.SHALL,
                "CapabilityStatement.rest.resource lists Patient, Coverage, ExplanationOfBenefit, Organization and Practitioner (SHALL: the "
                        + "C4BB and PDex server CapabilityStatements require them) and the US Core clinical types of the Patient Access API "
                        + "(SHOULD: CMS-9115-F 42 CFR 422.119(b)(1)(ii) clinical data per USCDI)",
                "C4BB 2.1.0 CapabilityStatement c4bb; PDex 2.1.0 CapabilityStatement pdex-server; US Core server CapabilityStatement", false,
                (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            Set<String> declared = CheckSupport.declaredTypes(d.capabilityStatement());
            b.detail("declared: " + String.join(", ", declared));
            List<String> missingRequired = new ArrayList<>();
            for (String t : REQUIRED_TYPES) {
                if (!declared.contains(t)) {
                    missingRequired.add(t);
                }
            }
            List<String> missingClinical = new ArrayList<>();
            for (String t : US_CORE_TYPES) {
                if (!declared.contains(t)) {
                    missingClinical.add(t);
                }
            }
            if (!missingClinical.isEmpty()) {
                b.detail("US Core types not declared (SHOULD): " + String.join(", ", missingClinical));
            }
            if (!missingRequired.isEmpty()) {
                return b.fail("required resource types not declared: " + String.join(", ", missingRequired));
            }
            if (!missingClinical.isEmpty()) {
                return b.warn("required types declared; " + missingClinical.size() + " US Core clinical types are not");
            }
            return b.pass("all required and US Core clinical types are declared");
        });
    }

    @Bean
    Check searchParamsDeclared() {
        return SimpleCheck.of("discovery.searchParams.declared", "discovery", "SHALL search parameters are declared", Severity.SHALL,
                "For every declared resource type the IG catalog knows, each search parameter the IGs mark SHALL appears in "
                        + "CapabilityStatement.rest.resource.searchParam (C4BB: 'Support the searchParameters on each profile individually "
                        + "and in combination'); lists every missing parameter",
                "C4BB 2.1.0 CapabilityStatement c4bb rest.resource.searchParam (SHALL expectations); PDex 2.1.0; US Core server CapabilityStatement",
                false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            List<String> missing = new ArrayList<>();
            int checkedTypes = 0;
            for (String type : CheckSupport.declaredTypes(d.capabilityStatement())) {
                var spec = ctx.catalog().resource(type);
                if (spec.isEmpty()) {
                    continue;
                }
                checkedTypes++;
                Set<String> declared = CheckSupport.declaredSearchParams(d.capabilityStatement(), type);
                for (var p : spec.get().searchParams()) {
                    if ("SHALL".equals(p.expectation()) && !declared.contains(p.name())) {
                        missing.add(type + "." + p.name() + " (SHALL by " + String.join(", ", p.igs()) + ")");
                    }
                }
            }
            CheckSupport.list(b, "missing: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " SHALL search parameter(s) not declared across " + checkedTypes + " resource types");
            }
            return b.pass("all SHALL search parameters declared for " + checkedTypes + " catalog resource types");
        });
    }

    @Bean
    Check profilesDeclared() {
        return SimpleCheck.of("discovery.profiles.declared", "discovery", "IG profiles are declared as supported", Severity.SHOULD,
                "CapabilityStatement.rest.resource.supportedProfile lists the five C4BB ExplanationOfBenefit profiles, C4BB Patient and "
                        + "Coverage, and the PDex PriorAuthorization profile (servers SHOULD declare the profiles they support so apps can "
                        + "discover them); lists which are missing",
                "C4BB 2.1.0 CapabilityStatement c4bb supportedProfile; PDex 2.1.0 CapabilityStatement pdex-server ExplanationOfBenefit "
                        + "supportedProfile pdex-priorauthorization", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            Set<String> supported = CheckSupport.allSupportedProfiles(d.capabilityStatement());
            List<String> missing = new ArrayList<>();
            for (String p : EXPECTED_PROFILES) {
                if (!supported.contains(p)) {
                    missing.add(p);
                }
            }
            b.detail("supportedProfile entries: " + supported.size());
            CheckSupport.list(b, "missing: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + EXPECTED_PROFILES.size() + " expected profiles are not declared");
            }
            return b.pass("all " + EXPECTED_PROFILES.size() + " expected C4BB / PDex profiles are declared");
        });
    }

    @Bean
    Check securitySmart() {
        return SimpleCheck.of("discovery.security.smart", "discovery", "SMART on FHIR security is declared", Severity.SHALL,
                "CapabilityStatement.rest.security.service has a coding SMART-on-FHIR and the extension "
                        + SmartDiscoveryService.OAUTH_URIS_EXTENSION + " with authorize and token URIs (a SMART server SHALL declare its "
                        + "OAuth endpoints in the CapabilityStatement; US Core and PDex require SMART App Launch)",
                "SMART App Launch 2.2.0 Conformance: CapabilityStatement declaration; US Core Security; PDex 2.1.0 CapabilityStatement security",
                false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            JsonNode security = CheckSupport.security(d.capabilityStatement());
            if (security == null) {
                return b.fail("CapabilityStatement.rest has no security element");
            }
            boolean smart = false;
            for (JsonNode service : security.path("service")) {
                for (JsonNode c : service.path("coding")) {
                    if (SMART_SECURITY_CODE.equals(c.path("code").asString(""))) {
                        smart = true;
                    }
                }
            }
            String authorize = null;
            String token = null;
            for (JsonNode ext : security.path("extension")) {
                if (SmartDiscoveryService.OAUTH_URIS_EXTENSION.equals(ext.path("url").asString(""))) {
                    for (JsonNode inner : ext.path("extension")) {
                        String value = inner.hasNonNull("valueUri") ? inner.get("valueUri").asString("") : inner.path("valueUrl").asString(null);
                        if ("authorize".equals(inner.path("url").asString(""))) {
                            authorize = value;
                        } else if ("token".equals(inner.path("url").asString(""))) {
                            token = value;
                        }
                    }
                }
            }
            List<String> missing = new ArrayList<>();
            if (!smart) {
                missing.add("security.service coding " + SMART_SECURITY_CODE);
            }
            if (authorize == null) {
                missing.add("oauth-uris extension 'authorize'");
            } else {
                b.detail("authorize: " + authorize);
            }
            if (token == null) {
                missing.add("oauth-uris extension 'token'");
            } else {
                b.detail("token: " + token);
            }
            if (!missing.isEmpty()) {
                return b.fail("missing: " + String.join("; ", missing));
            }
            return b.pass("SMART-on-FHIR service and OAuth authorize/token URIs declared");
        });
    }

    @Bean
    Check implementationGuides() {
        return SimpleCheck.of("discovery.implementationGuides", "discovery", "Implementation guides are referenced", Severity.MAY,
                "CapabilityStatement.instantiates, implementationGuide or imports mention the CARIN Blue Button, Da Vinci PDex and US Core "
                        + "guides (informational: tells testers which IG versions the server claims)",
                "FHIR R4 CapabilityStatement.implementationGuide / instantiates; C4BB 2.1.0; PDex 2.1.0; US Core", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement");
            }
            Set<String> refs = new LinkedHashSet<>();
            for (String field : List.of("instantiates", "implementationGuide", "imports")) {
                for (JsonNode v : d.capabilityStatement().path(field)) {
                    refs.add(v.asString(""));
                }
            }
            CheckSupport.list(b, "referenced: ", new ArrayList<>(refs));
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String[] ig : new String[][]{{"carin-bb", "CARIN Blue Button"}, {"davinci-pdex", "Da Vinci PDex"}, {"us/core", "US Core"}}) {
                if (refs.stream().anyMatch(r -> r.contains(ig[0]))) {
                    found.add(ig[1]);
                } else {
                    missing.add(ig[1]);
                }
            }
            if (refs.isEmpty()) {
                return b.info("no implementation guides referenced in the CapabilityStatement");
            }
            return b.info("referenced: " + String.join(", ", found) + (missing.isEmpty() ? "" : "; not referenced: " + String.join(", ", missing)));
        });
    }
}
