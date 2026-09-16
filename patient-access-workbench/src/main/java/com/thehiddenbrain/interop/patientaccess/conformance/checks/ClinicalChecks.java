package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckProvider;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.C4BB;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.PDEX;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.US_CORE;

/**
 * US Core clinical data: generated from the catalog for every clinical resource type of the Patient
 * Access API. Per type: the patient search, the required search-parameter combinations (values taken
 * from a returned resource so the search must find it again), profile declaration and meta.lastUpdated.
 */
@Component
public class ClinicalChecks implements CheckProvider {

    /** Types every US Core server of a Patient Access API supports, and the newer ones (SHOULD). */
    static final List<String> SHALL_TYPES = List.of("AllergyIntolerance", "CarePlan", "CareTeam", "Condition", "Device", "DiagnosticReport",
            "DocumentReference", "Encounter", "Goal", "Immunization", "MedicationDispense", "MedicationRequest", "Observation", "Procedure");
    static final List<String> SHOULD_TYPES = List.of("ServiceRequest", "Specimen", "QuestionnaireResponse", "RelatedPerson");
    private static final String USCORE_CS = "US Core CapabilityStatement us-core-server";
    private static final String RULE = "CMS-9115-F 42 CFR 422.119(b)(1)(ii): clinical data per USCDI through US Core";

    private final IgCatalog catalog;

    public ClinicalChecks(IgCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<Check> checks() {
        List<Check> out = new ArrayList<>();
        for (String type : SHALL_TYPES) {
            add(out, type, Severity.SHALL);
        }
        for (String type : SHOULD_TYPES) {
            add(out, type, Severity.SHOULD);
        }
        out.add(summary());
        return out;
    }

    private void add(List<Check> out, String type, Severity severity) {
        out.add(searchByPatient(type, severity));
        out.add(combos(type, severity));
        out.add(profile(type));
        out.add(lastUpdated(type));
    }

    Check searchByPatient(String type, Severity severity) {
        return SimpleCheck.of("clinical." + type + ".search.patient", "clinical", type + " search by patient", severity,
                "GET " + type + "?patient={id} returns HTTP 200 with a searchset of " + type + " resources; when the CapabilityStatement does "
                        + "not declare " + type + " a failure is reported as a warning ('not declared') instead",
                USCORE_CS + " " + type + " patient search; " + RULE, true, (b, ctx) -> {
            SearchPage page = CheckSupport.record(b, CheckSupport.patientSearch(ctx, type));
            boolean declared = CheckSupport.declaresType(ctx.discovery().capabilityStatement(), type);
            String problem = CheckSupport.bundleProblem(page, type);
            if (problem != null) {
                if (!declared) {
                    return b.warn(type + " is not declared in the CapabilityStatement; search answered " + problem);
                }
                return b.fail(problem);
            }
            if (!declared) {
                b.detail(type + " is not declared in the CapabilityStatement although the search works");
            }
            return b.pass(CheckSupport.plural(page.count(), type + " resource") + (page.nextUrl() != null ? " on the first page" : ""));
        });
    }

    Check combos(String type, Severity severity) {
        List<IgCatalog.ComboSpec> specs = catalog.resource(type).map(IgCatalog.ResourceSpec::combos).orElse(List.of());
        StringBuilder desc = new StringBuilder();
        for (IgCatalog.ComboSpec c : specs) {
            desc.append(desc.length() == 0 ? "" : ", ").append(String.join("+", c.params())).append(" (").append(c.expectation()).append(')');
        }
        return SimpleCheck.of("clinical." + type + ".combos", "clinical", type + " search-parameter combinations", severity,
                "For each combination the US Core server CapabilityStatement requires for " + type + (specs.isEmpty() ? " (none in the catalog)"
                        : ": " + desc) + ", a search with values taken from a resource returned by the patient search finds at least one "
                        + "resource; combinations whose values cannot be derived are skipped, failing SHOULD combinations are warnings",
                USCORE_CS + " " + type + " searchParam combinations (SHALL / SHOULD extensions); " + RULE, true, (b, ctx) -> {
            if (specs.isEmpty()) {
                return b.skip("no search-parameter combinations for " + type + " in the catalog");
            }
            SearchPage page = CheckSupport.patientSearch(ctx, type);
            b.evidence(page.response().requestId());
            List<JsonNode> resources = CheckSupport.patientResources(ctx, type);
            if (resources.isEmpty()) {
                return b.skip(page.response().ok() ? "no " + type + " for this patient to derive values from"
                        : type + " patient search failed (HTTP " + page.response().status() + ")");
            }
            String pid = ctx.patientId().orElseThrow();
            List<String> failedShall = new ArrayList<>();
            List<String> failedShould = new ArrayList<>();
            int run = 0;
            for (IgCatalog.ComboSpec combo : specs) {
                MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
                JsonNode seed = null;
                for (JsonNode r : resources) {
                    MultiValueMap<String, String> p = fill(combo.params(), r, pid);
                    if (p != null) {
                        params = p;
                        seed = r;
                        break;
                    }
                }
                String name = String.join("+", combo.params()) + " (" + combo.expectation() + ")";
                if (seed == null) {
                    b.detail(name + ": skipped, no returned resource yields values for all parameters");
                    continue;
                }
                run++;
                SearchPage result = ctx.searchPage(type, params);
                b.evidence(result.response().requestId());
                String problem = CheckSupport.bundleProblem(result, type);
                String outcome;
                if (problem != null) {
                    outcome = problem;
                } else if (result.count() == 0) {
                    outcome = "no result although the values came from " + CheckSupport.label(seed);
                } else {
                    outcome = null;
                }
                b.detail(name + " " + result.response().url().substring(ctx.environment().baseUrl().length() + 1) + " -> "
                        + (outcome == null ? CheckSupport.plural(result.count(), "result") : outcome));
                if (outcome != null) {
                    ("SHALL".equals(combo.expectation()) ? failedShall : failedShould).add(name);
                }
            }
            if (run == 0) {
                return b.skip("no combination could be filled from the returned " + type + " resources");
            }
            if (!failedShall.isEmpty()) {
                return b.fail("SHALL combination(s) failed: " + String.join(", ", failedShall)
                        + (failedShould.isEmpty() ? "" : "; SHOULD: " + String.join(", ", failedShould)));
            }
            if (!failedShould.isEmpty()) {
                return b.warn("SHOULD combination(s) failed: " + String.join(", ", failedShould));
            }
            return b.pass(run + " of " + specs.size() + " combination(s) verified");
        });
    }

    /** Parameters for a combo filled from one resource, or null when a value cannot be derived. */
    static MultiValueMap<String, String> fill(List<String> params, JsonNode resource, String pid) {
        MultiValueMap<String, String> out = new LinkedMultiValueMap<>();
        for (String p : params) {
            String v = "patient".equals(p) ? pid : CheckSupport.searchValue(resource, p);
            if (v == null) {
                return null;
            }
            out.add(p, v);
        }
        return out;
    }

    Check profile(String type) {
        return SimpleCheck.of("clinical." + type + ".profile", "clinical", type + " declares a US Core profile", Severity.SHOULD,
                "Every " + type + " returned for the patient declares a US Core profile (" + US_CORE + "...) in meta.profile, or the PDex / "
                        + "C4BB profile of the type where one exists (MedicationDispense, Device, RelatedPerson); lists offenders",
                "US Core general guidance: meta.profile SHOULD be populated; PDex 2.1.0 and C4BB 2.1.0 profiles", true, (b, ctx) -> {
            SearchPage page = CheckSupport.patientSearch(ctx, type);
            b.evidence(page.response().requestId());
            List<JsonNode> resources = CheckSupport.patientResources(ctx, type);
            if (resources.isEmpty()) {
                return b.skip("no " + type + " for this patient");
            }
            List<String> offenders = new ArrayList<>();
            for (JsonNode r : resources) {
                if (!CheckSupport.declaresProfileStartingWith(r, US_CORE) && !CheckSupport.declaresProfileStartingWith(r, PDEX)
                        && !CheckSupport.declaresProfileStartingWith(r, C4BB)) {
                    offenders.add(CheckSupport.label(r) + " meta.profile=" + Fhir.profiles(r));
                }
            }
            CheckSupport.list(b, "", offenders);
            if (!offenders.isEmpty()) {
                return b.fail(offenders.size() + " of " + resources.size() + " " + type + " resource(s) declare no US Core / PDex / C4BB profile");
            }
            return b.pass("all " + resources.size() + " " + type + " resource(s) declare a US Core / PDex / C4BB profile");
        });
    }

    Check lastUpdated(String type) {
        return SimpleCheck.of("clinical." + type + ".lastUpdated", "clinical", type + " has meta.lastUpdated", Severity.SHOULD,
                "Every " + type + " returned for the patient carries meta.lastUpdated (apps poll with _lastUpdated)",
                "US Core server CapabilityStatement: _lastUpdated SHOULD; PDex 2.1.0 meta.lastUpdated", true, (b, ctx) -> {
            SearchPage page = CheckSupport.patientSearch(ctx, type);
            b.evidence(page.response().requestId());
            List<JsonNode> resources = CheckSupport.patientResources(ctx, type);
            if (resources.isEmpty()) {
                return b.skip("no " + type + " for this patient");
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode r : resources) {
                if (Fhir.lastUpdated(r) == null) {
                    missing.add(CheckSupport.label(r));
                }
            }
            CheckSupport.list(b, "without meta.lastUpdated: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + resources.size() + " " + type + " resource(s) lack meta.lastUpdated");
            }
            return b.pass("all " + resources.size() + " " + type + " resource(s) carry meta.lastUpdated");
        });
    }

    Check summary() {
        return SimpleCheck.of("clinical.summary", "clinical", "Clinical data summary", Severity.MAY,
                "Informational: how many resources of each US Core type the patient search returns (first page) and which types answered "
                        + "with an error", RULE, true, (b, ctx) -> {
            Map<String, String> counts = new LinkedHashMap<>();
            int withData = 0;
            int errors = 0;
            List<String> all = new ArrayList<>(SHALL_TYPES);
            all.addAll(SHOULD_TYPES);
            for (String type : all) {
                SearchPage page = CheckSupport.patientSearch(ctx, type);
                b.evidence(page.response().requestId());
                if (page.response().ok() && page.isBundle()) {
                    int n = page.count();
                    counts.put(type, n + (page.nextUrl() != null ? "+" : ""));
                    withData += n > 0 ? 1 : 0;
                } else {
                    counts.put(type, "HTTP " + page.response().status());
                    errors++;
                }
            }
            counts.forEach((k, v) -> b.detail(k + ": " + v));
            return b.info(withData + " of " + all.size() + " types have data" + (errors == 0 ? "" : ", " + errors + " answered with an error"));
        });
    }
}
