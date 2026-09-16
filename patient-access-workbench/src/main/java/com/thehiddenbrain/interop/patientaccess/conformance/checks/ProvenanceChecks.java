package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.PDEX;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.US_CORE;

/** Provenance retrieved with _revinclude on a clinical search, and its profile. */
@Configuration
public class ProvenanceChecks {

    /** Condition, else Encounter, with _revinclude=Provenance:target; fetched once per run. Null when neither returns resources. */
    static SearchPage revinclude(CheckContext ctx) {
        String pid = ctx.patientId().orElseThrow();
        return ctx.cached("provenance.revinclude", () -> {
            SearchPage last = null;
            for (String type : List.of("Condition", "Encounter")) {
                SearchPage page = ctx.searchPage(type, CheckContext.params("patient", pid, "_revinclude", "Provenance:target"));
                last = page;
                if (page.response().ok() && page.isBundle() && page.count() > 0) {
                    return page;
                }
            }
            return last;
        });
    }

    static List<JsonNode> provenances(SearchPage page) {
        List<JsonNode> out = new ArrayList<>();
        if (page == null) {
            return out;
        }
        for (JsonNode r : page.included()) {
            if ("Provenance".equals(r.path("resourceType").asText())) {
                out.add(r);
            }
        }
        for (JsonNode r : page.resources()) {
            if ("Provenance".equals(r.path("resourceType").asText())) {
                out.add(r);
            }
        }
        return out;
    }

    @Bean
    Check provenanceRevinclude() {
        return SimpleCheck.of("provenance.revinclude", "provenance", "Provenance via _revinclude", Severity.SHOULD,
                "GET Condition?patient={id}&_revinclude=Provenance:target (or Encounter when the patient has no Condition) returns "
                        + "Provenance entries for the returned resources (US Core: servers SHALL support _revinclude=Provenance:target on "
                        + "every profile; PDex requires Provenance for payer-sourced clinical data); skipped when neither search returns data",
                "US Core CapabilityStatement us-core-server searchRevInclude Provenance:target; PDex 2.1.0 Provenance guidance", true, (b, ctx) -> {
            SearchPage page = revinclude(ctx);
            if (page == null) {
                return b.skip("no search executed");
            }
            CheckSupport.record(b, page.response());
            if (!page.response().ok() || !page.isBundle()) {
                return b.fail("search with _revinclude answered " + CheckSupport.describe(page.response()));
            }
            if (page.count() == 0) {
                return b.skip("neither Condition nor Encounter returned resources for this patient to attach Provenance to");
            }
            List<JsonNode> provenance = provenances(page);
            if (provenance.isEmpty()) {
                return b.fail("no Provenance entry returned for " + page.count() + " " + page.resources().get(0).path("resourceType").asText() + " resource(s)");
            }
            return b.pass(provenance.size() + " Provenance resource(s) for " + page.count() + " "
                    + page.resources().get(0).path("resourceType").asText() + " resource(s)");
        });
    }

    @Bean
    Check provenanceProfile() {
        return SimpleCheck.of("provenance.profile", "provenance", "Provenance declares its profile", Severity.SHOULD,
                "Every Provenance returned declares the PDex Provenance or US Core Provenance profile in meta.profile; lists offenders",
                "PDex 2.1.0 pdex-provenance; US Core Provenance", true, (b, ctx) -> {
            SearchPage page = revinclude(ctx);
            List<JsonNode> provenance = provenances(page);
            if (page != null) {
                b.evidence(page.response().requestId());
            }
            if (provenance.isEmpty()) {
                return b.skip("no Provenance returned (see provenance.revinclude)");
            }
            List<String> offenders = new ArrayList<>();
            for (JsonNode p : provenance) {
                if (!CheckSupport.declaresProfile(p, PDEX + "pdex-provenance") && !CheckSupport.declaresProfile(p, US_CORE + "us-core-provenance")) {
                    offenders.add(CheckSupport.label(p) + " meta.profile=" + Fhir.profiles(p));
                }
            }
            CheckSupport.list(b, "", offenders);
            if (!offenders.isEmpty()) {
                return b.fail(offenders.size() + " of " + provenance.size() + " Provenance resource(s) declare no PDex / US Core Provenance profile");
            }
            return b.pass("all " + provenance.size() + " Provenance resource(s) declare a Provenance profile");
        });
    }
}
