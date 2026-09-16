package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.C4BB;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.HREX;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.US_CORE;

/** Coverage search by patient, the C4BB Coverage profile essentials and payor resolution. */
@Configuration
public class CoverageChecks {

    private static final String C4BB_CS = "C4BB 2.1.0 CapabilityStatement c4bb Coverage";
    private static final List<String> PROFILES = List.of(C4BB + "C4BB-Coverage", HREX + "hrex-coverage", US_CORE + "us-core-coverage");

    static List<JsonNode> coverages(CheckContext ctx) {
        return CheckSupport.patientResources(ctx, "Coverage");
    }

    /** Skip result when the patient search failed or found nothing, else empty. */
    static Optional<CheckResult> noCoverage(CheckResult.Builder b, CheckContext ctx) {
        SearchPage page = CheckSupport.patientSearch(ctx, "Coverage");
        b.evidence(page.response().requestId());
        if (!page.response().ok()) {
            return Optional.of(b.skip("Coverage search failed (HTTP " + page.response().status() + ", see coverage.search.patient)"));
        }
        if (page.resources().isEmpty()) {
            return Optional.of(b.skip("no Coverage for Patient/" + ctx.patientId().orElseThrow()));
        }
        return Optional.empty();
    }

    @Bean
    Check coverageSearchByPatient() {
        return SimpleCheck.of("coverage.search.patient", "coverage", "Coverage search by patient", Severity.SHALL,
                "GET Coverage?patient={id} returns a searchset of Coverage resources whose beneficiary is the patient",
                "PDex 2.1.0 CapabilityStatement pdex-server Coverage patient SHALL; US Core Coverage patient SHALL (C4BB 2.1.0 declares _id and _lastUpdated only)", true, (b, ctx) -> {
            String pid = ctx.patientId().orElseThrow();
            SearchPage page = CheckSupport.record(b, CheckSupport.patientSearch(ctx, "Coverage"));
            String problem = CheckSupport.bundleProblem(page, "Coverage");
            if (problem != null) {
                return b.fail(problem);
            }
            List<String> foreign = new ArrayList<>();
            for (JsonNode c : page.resources()) {
                String ben = CheckSupport.ref(c.get("beneficiary"));
                if (ben != null && !CheckSupport.refersTo(ben, "Patient", pid)) {
                    foreign.add(CheckSupport.label(c) + " beneficiary " + ben);
                }
            }
            CheckSupport.list(b, "not this patient: ", foreign);
            if (!foreign.isEmpty()) {
                return b.fail(foreign.size() + " Coverage(s) belong to another patient");
            }
            if (page.resources().isEmpty()) {
                return b.warn("search succeeded but returned no Coverage for Patient/" + pid);
            }
            return b.pass(CheckSupport.plural(page.count(), "Coverage") + " for Patient/" + pid);
        });
    }

    @Bean
    Check coverageSearchByBeneficiary() {
        return SimpleCheck.of("coverage.search.beneficiary", "coverage", "Coverage search by beneficiary", Severity.MAY,
                "GET Coverage?beneficiary=Patient/{id} returns the same coverages (the standard FHIR reference parameter behind 'patient')",
                "FHIR R4 Coverage search parameter beneficiary (no Patient Access IG requires it; informational)", true, (b, ctx) -> {
            String pid = ctx.patientId().orElseThrow();
            SearchPage page = CheckSupport.search(ctx, b, "Coverage", CheckContext.params("beneficiary", "Patient/" + pid));
            String problem = CheckSupport.bundleProblem(page, "Coverage");
            if (problem != null) {
                return b.fail(problem);
            }
            int expected = coverages(ctx).size();
            if (page.count() == 0 && expected > 0) {
                return b.fail("beneficiary search returned nothing while patient search returned " + expected);
            }
            return b.pass(CheckSupport.plural(page.count(), "Coverage") + " by beneficiary");
        });
    }

    @Bean
    Check coverageSearchById() {
        return SimpleCheck.of("coverage.search.id", "coverage", "Coverage search by _id", Severity.SHALL,
                "GET Coverage?_id={id} with the id of one of the patient's coverages returns exactly that Coverage", C4BB_CS + " _id SHALL", true,
                (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String id = Fhir.idOf(coverages(ctx).get(0));
            SearchPage page = CheckSupport.search(ctx, b, "Coverage", CheckContext.params("_id", id));
            String problem = CheckSupport.bundleProblem(page, "Coverage");
            if (problem != null) {
                return b.fail(problem);
            }
            if (!CheckSupport.containsId(page.resources(), id)) {
                return b.fail("Coverage/" + id + " not in the results");
            }
            return b.pass("Coverage/" + id + " found by _id");
        });
    }

    @Bean
    Check coverageProfileDeclared() {
        return SimpleCheck.of("coverage.profile.declared", "coverage", "Coverage declares its profile", Severity.SHALL,
                "Every Coverage declares the C4BB Coverage, HRex Coverage or US Core Coverage profile in meta.profile; lists offenders",
                "C4BB 2.1.0 CapabilityStatement c4bb rest documentation item 5; C4BB-Coverage 2.1.0; PDex 2.1.0 (HRex Coverage)", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> offenders = new ArrayList<>();
            for (JsonNode c : coverages(ctx)) {
                if (CheckSupport.firstDeclaredProfile(c, PROFILES).isEmpty()) {
                    offenders.add(CheckSupport.label(c) + " meta.profile=" + Fhir.profiles(c));
                }
            }
            CheckSupport.list(b, "", offenders);
            if (!offenders.isEmpty()) {
                return b.fail(offenders.size() + " of " + coverages(ctx).size() + " Coverage(s) declare no C4BB / HRex / US Core Coverage profile");
            }
            return b.pass("all " + coverages(ctx).size() + " Coverage(s) declare a Coverage profile");
        });
    }

    @Bean
    Check coverageProfileLite() {
        return SimpleCheck.of("coverage.profileLite", "coverage", "C4BB Coverage required elements", Severity.SHALL,
                "Every Coverage satisfies the required elements and fixed values of the C4BB Coverage profile (lite check: cardinality, "
                        + "fixed/pattern values, slices); errors are listed per resource", "C4BB-Coverage 2.1.0 StructureDefinition", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            int errors = 0;
            int warnings = 0;
            int[] listed = {0};
            for (JsonNode c : coverages(ctx)) {
                ProfileLiteChecker.Report r = ctx.checker().check(c);
                errors += CheckSupport.addIssues(b, c, r, listed);
                warnings += r.warnings();
            }
            if (errors > 0) {
                return b.fail(CheckSupport.plural(errors, "error") + " across " + coverages(ctx).size() + " Coverage(s)");
            }
            return b.pass("no errors across " + coverages(ctx).size() + " Coverage(s) (" + CheckSupport.plural(warnings, "warning") + ")");
        });
    }

    @Bean
    Check coveragePayorResolvable() {
        return SimpleCheck.of("coverage.payor.resolvable", "coverage", "Coverage.payor resolves", Severity.SHALL,
                "Every distinct Coverage.payor reference can be read (HTTP 200) and is an Organization (C4BB: payor 1..1 referencing the "
                        + "C4BB Organization; a Patient/RelatedPerson payor is accepted for self-pay)",
                "C4BB-Coverage 2.1.0 payor (C4BB Organization); C4BB EOB documentation: referenced resources SHALL be readable", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            Set<String> refs = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            for (JsonNode c : coverages(ctx)) {
                if (c.path("payor").size() == 0) {
                    missing.add(CheckSupport.label(c) + " has no payor");
                }
                for (JsonNode p : c.path("payor")) {
                    String r = CheckSupport.ref(p);
                    if (r == null) {
                        missing.add(CheckSupport.label(c) + " payor without a literal reference");
                    } else {
                        refs.add(r);
                    }
                }
            }
            List<String> failed = new ArrayList<>(missing);
            for (String ref : refs) {
                Optional<HttpResult> r = CheckSupport.resolve(ctx, ref);
                if (r.isEmpty()) {
                    failed.add(ref + ": not resolvable inside the environment");
                    continue;
                }
                CheckSupport.record(b, r.get());
                String type = r.get().resourceType();
                if (!r.get().ok() || type == null || !ref.contains(type + "/")) {
                    failed.add(ref + " -> HTTP " + r.get().status() + (type == null ? "" : " " + type));
                }
            }
            CheckSupport.list(b, "problem: ", failed);
            if (!failed.isEmpty()) {
                return b.fail(failed.size() + " payor reference(s) could not be resolved");
            }
            return b.pass(refs.size() + " distinct payor reference(s) resolved");
        });
    }

    @Bean
    Check coverageIncludePayor() {
        return SimpleCheck.of("coverage.include.payor", "coverage", "_include=Coverage:payor", Severity.SHOULD,
                "GET Coverage?patient={id}&_include=Coverage:payor returns the payor Organization(s) as entries with search.mode=include",
                C4BB_CS + " searchInclude Coverage:payor", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String pid = ctx.patientId().orElseThrow();
            SearchPage page = CheckSupport.search(ctx, b, "Coverage", CheckContext.params("patient", pid, "_include", "Coverage:payor"));
            String problem = CheckSupport.bundleProblem(page, "Coverage");
            if (problem != null) {
                return b.fail(problem);
            }
            long orgs = page.included().stream().filter(r -> "Organization".equals(r.path("resourceType").asString(""))).count();
            if (orgs == 0) {
                return b.fail("no Organization entry with search.mode=include (" + page.included().size() + " included entries)");
            }
            return b.pass(orgs + " payor Organization(s) included");
        });
    }

    @Bean
    Check coverageLastUpdated() {
        return SimpleCheck.of("coverage.lastUpdated", "coverage", "Coverage has meta.lastUpdated", Severity.SHALL,
                "Every Coverage carries meta.lastUpdated (C4BB: Coverage is returned as of the date of service; apps rely on lastUpdated)",
                "C4BB-Coverage 2.1.0 meta.lastUpdated 1..1; C4BB 2.1.0 CapabilityStatement description", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode c : coverages(ctx)) {
                if (Fhir.lastUpdated(c) == null) {
                    missing.add(CheckSupport.label(c));
                }
            }
            CheckSupport.list(b, "without meta.lastUpdated: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + coverages(ctx).size() + " Coverage(s) lack meta.lastUpdated");
            }
            return b.pass("all " + coverages(ctx).size() + " Coverage(s) carry meta.lastUpdated");
        });
    }

    @Bean
    Check coverageSubscriberId() {
        return SimpleCheck.of("coverage.subscriberId", "coverage", "Coverage.subscriberId present", Severity.SHALL,
                "Every Coverage has subscriberId (C4BB Coverage: subscriberId 1..1 must support)", "C4BB-Coverage 2.1.0 subscriberId 1..1", true,
                (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode c : coverages(ctx)) {
                if (Fhir.text(c.get("subscriberId")) == null) {
                    missing.add(CheckSupport.label(c));
                }
            }
            CheckSupport.list(b, "without subscriberId: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + coverages(ctx).size() + " Coverage(s) lack subscriberId");
            }
            return b.pass("all " + coverages(ctx).size() + " Coverage(s) carry subscriberId");
        });
    }

    @Bean
    Check coverageStatus() {
        return SimpleCheck.of("coverage.status", "coverage", "Coverage statuses", Severity.MAY,
                "Informational: which Coverage.status values the patient's coverages carry and whether at least one is active",
                "C4BB-Coverage 2.1.0 status (required binding fm-status)", true, (b, ctx) -> {
            Optional<CheckResult> none = noCoverage(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            Set<String> statuses = new LinkedHashSet<>();
            boolean active = false;
            for (JsonNode c : coverages(ctx)) {
                String s = c.path("status").asString("(absent)");
                statuses.add(s);
                active |= "active".equals(s);
                b.detail(CheckSupport.label(c) + ": " + s + ", period " + (Fhir.period(c.get("period")) == null ? "-" : Fhir.period(c.get("period"))));
            }
            return b.info("statuses: " + String.join(", ", statuses) + (active ? "; at least one active coverage" : "; no active coverage"));
        });
    }
}
