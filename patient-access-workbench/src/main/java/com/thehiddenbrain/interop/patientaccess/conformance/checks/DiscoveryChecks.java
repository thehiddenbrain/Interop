package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reference implementation of the check style: the CapabilityStatement basics. More checks live in this package. */
@Configuration
public class DiscoveryChecks {

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
            b.detail("software: " + cs.path("software").path("name").asText("?") + " " + cs.path("software").path("version").asText(""));
            b.detail("fhirVersion: " + cs.path("fhirVersion").asText("?"));
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
            String v = d.capabilityStatement().path("fhirVersion").asText("");
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
                String t = f.asText();
                if (t.equals("json") || t.contains("json")) {
                    return b.pass("format includes " + t);
                }
            }
            return b.fail("format does not list json");
        });
    }
}
