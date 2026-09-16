package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.C4BB;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.EOB;

/**
 * CARIN Blue Button claims: the SHALL search parameters, _include, profiles, must-support essentials and
 * reference integrity. Works on the non-prior-authorization EOBs of the patient (shared per run).
 */
@Configuration
public class EobChecks {

    private static final String C4BB_CS = "C4BB 2.1.0 CapabilityStatement c4bb ExplanationOfBenefit";
    private static final String CLAIM_TYPE = "http://terminology.hl7.org/CodeSystem/claim-type";
    static final List<String> CLAIM_PROFILES = List.of(
            C4BB + "C4BB-ExplanationOfBenefit-Inpatient-Institutional", C4BB + "C4BB-ExplanationOfBenefit-Outpatient-Institutional",
            C4BB + "C4BB-ExplanationOfBenefit-Professional-NonClinician", C4BB + "C4BB-ExplanationOfBenefit-Pharmacy",
            C4BB + "C4BB-ExplanationOfBenefit-Oral");

    /** Skip result when the patient has no claims (or the EOB search failed), else empty. */
    static Optional<CheckResult> noClaims(CheckResult.Builder b, CheckContext ctx) {
        FhirGateway.Collected all = ctx.patientEobs();
        b.evidence(all.requestIds());
        if (ctx.patientClaims().isEmpty()) {
            return Optional.of(b.skip("no claims (use=claim) for Patient/" + ctx.patientId().orElseThrow()
                    + (ctx.patientPriorAuths().isEmpty() ? "" : "; only prior authorizations were returned")));
        }
        return Optional.empty();
    }

    /** The first claim carrying a value, applied to every claim in order. */
    static Optional<JsonNode> firstClaimWith(CheckContext ctx, java.util.function.Predicate<JsonNode> has) {
        return ctx.patientClaims().stream().filter(has).findFirst();
    }

    /** Runs a claims search with patient + one parameter and expects at least one EOB (and the given one when non-null). */
    static CheckResult expectClaims(CheckResult.Builder b, CheckContext ctx, MultiValueMap<String, String> params, String expectedId) {
        SearchPage page = CheckSupport.search(ctx, b, EOB, params);
        String problem = CheckSupport.bundleProblem(page, EOB);
        if (problem != null) {
            return b.fail(problem);
        }
        if (page.count() == 0) {
            return b.fail("no ExplanationOfBenefit returned although the value was taken from one of the patient's claims");
        }
        if (expectedId != null && !CheckSupport.containsId(page.resources(), expectedId)) {
            return b.fail(EOB + "/" + expectedId + " is not among the " + page.count() + " result(s)");
        }
        return b.pass(CheckSupport.plural(page.count(), "result") + (expectedId == null ? "" : ", including " + EOB + "/" + expectedId));
    }

    /** A date-parameter search seeded from billablePeriod.start of the first claim that has one. */
    static CheckResult dateSearch(CheckResult.Builder b, CheckContext ctx, String param) {
        Optional<CheckResult> none = noClaims(b, ctx);
        if (none.isPresent()) {
            return none.get();
        }
        Optional<JsonNode> seed = firstClaimWith(ctx, e -> Fhir.text(e.path("billablePeriod").get("start")) != null);
        if (seed.isEmpty()) {
            return b.skip("no claim carries billablePeriod.start to derive a value for " + param);
        }
        String date = CheckSupport.date(Fhir.text(seed.get().path("billablePeriod").get("start")));
        b.detail(param + "=ge" + date + " from " + CheckSupport.label(seed.get()) + " billablePeriod.start");
        return expectClaims(b, ctx, CheckContext.params("patient", ctx.patientId().orElseThrow(), param, "ge" + date), Fhir.idOf(seed.get()));
    }

    @Bean
    Check eobSearchByPatient() {
        return SimpleCheck.of("eob.search.patient", "eob", "EOB search by patient", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id} returns a searchset of ExplanationOfBenefit resources, none of which references another "
                        + "patient", C4BB_CS + " patient SHALL; CMS-9115-F 42 CFR 422.119(b)(1)(i) adjudicated claims", true, (b, ctx) -> {
            String pid = ctx.patientId().orElseThrow();
            SearchPage page = CheckSupport.record(b, CheckSupport.patientSearch(ctx, EOB));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            List<String> foreign = new ArrayList<>();
            for (JsonNode e : page.resources()) {
                String ref = CheckSupport.ref(e.get("patient"));
                if (!CheckSupport.refersTo(ref, "Patient", pid)) {
                    foreign.add(CheckSupport.label(e) + " patient " + ref);
                }
            }
            CheckSupport.list(b, "not this patient: ", foreign);
            if (!foreign.isEmpty()) {
                return b.fail(foreign.size() + " EOB(s) reference another patient: the search leaks data");
            }
            if (page.count() == 0) {
                return b.warn("search succeeded but returned no ExplanationOfBenefit for Patient/" + pid);
            }
            return b.pass(CheckSupport.plural(page.count(), "EOB") + " on the first page" + (page.nextUrl() != null ? " (more pages)" : ""));
        });
    }

    @Bean
    Check eobSearchById() {
        return SimpleCheck.of("eob.search.id", "eob", "EOB search by _id", Severity.SHALL,
                "GET ExplanationOfBenefit?_id={id} with one of the patient's claim ids returns that EOB", C4BB_CS + " _id SHALL", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String id = Fhir.idOf(ctx.patientClaims().get(0));
            return expectClaims(b, ctx, CheckContext.params("_id", id), id);
        });
    }

    @Bean
    Check eobSearchByIdentifier() {
        return SimpleCheck.of("eob.search.identifier", "eob", "EOB search by identifier", Severity.SHALL,
                "GET ExplanationOfBenefit?identifier={system}|{value} with the unique claim identifier (type uc) of one claim returns it",
                C4BB_CS + " identifier SHALL; C4BB-ExplanationOfBenefit identifier:uniqueclaimid", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            for (JsonNode e : ctx.patientClaims()) {
                JsonNode id = CheckSupport.identifierOfType(e, "uc").orElse(e.path("identifier").size() > 0 ? e.path("identifier").get(0) : null);
                String token = id == null ? null : CheckSupport.identifierToken(id);
                if (token != null) {
                    b.detail("identifier=" + token + " from " + CheckSupport.label(e));
                    return expectClaims(b, ctx, CheckContext.params("identifier", token), Fhir.idOf(e));
                }
            }
            return b.fail("no claim carries an identifier value to search with");
        });
    }

    @Bean
    Check eobSearchByLastUpdated() {
        return SimpleCheck.of("eob.search.lastUpdated", "eob", "EOB search by _lastUpdated", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&_lastUpdated=ge{oldest meta.lastUpdated date} returns at least one EOB (apps poll "
                        + "for new claims with _lastUpdated)", C4BB_CS + " _lastUpdated SHALL", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String oldest = null;
            for (JsonNode e : ctx.patientClaims()) {
                String lu = CheckSupport.date(Fhir.lastUpdated(e));
                if (lu != null && (oldest == null || lu.compareTo(oldest) < 0)) {
                    oldest = lu;
                }
            }
            if (oldest == null) {
                return b.skip("no claim carries meta.lastUpdated to derive a value");
            }
            b.detail("_lastUpdated=ge" + oldest);
            return expectClaims(b, ctx, CheckContext.params("patient", ctx.patientId().orElseThrow(), "_lastUpdated", "ge" + oldest), null);
        });
    }

    @Bean
    Check eobSearchByServiceDate() {
        return SimpleCheck.of("eob.search.serviceDate", "eob", "EOB search by service-date", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&service-date=ge{date} (date from billablePeriod.start of one claim) returns that claim",
                C4BB_CS + " service-date SHALL (C4BB SearchParameter explanationofbenefit-service-date)", true,
                (b, ctx) -> dateSearch(b, ctx, "service-date"));
    }

    @Bean
    Check eobSearchByServiceStartDate() {
        return SimpleCheck.of("eob.search.serviceStartDate", "eob", "EOB search by service-start-date", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&service-start-date=ge{date} returns the seeding claim",
                C4BB_CS + " service-start-date SHALL (C4BB 2.1 SearchParameter explanationofbenefit-service-start-date)", true,
                (b, ctx) -> dateSearch(b, ctx, "service-start-date"));
    }

    @Bean
    Check eobSearchByBillablePeriodStart() {
        return SimpleCheck.of("eob.search.billablePeriodStart", "eob", "EOB search by billable-period-start", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&billable-period-start=ge{date} returns the seeding claim",
                C4BB_CS + " billable-period-start SHALL (C4BB 2.1 SearchParameter explanationofbenefit-billable-period-start)", true,
                (b, ctx) -> dateSearch(b, ctx, "billable-period-start"));
    }

    @Bean
    Check eobSearchByType() {
        return SimpleCheck.of("eob.search.type", "eob", "EOB search by type", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&type={system}|{code} with the claim type of one claim returns only EOBs of that type",
                C4BB_CS + " type SHALL", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            Optional<JsonNode> seed = firstClaimWith(ctx, e -> Fhir.code(e.path("type"), null) != null);
            if (seed.isEmpty()) {
                return b.skip("no claim carries type.coding");
            }
            String code = Fhir.code(seed.get().path("type"), null);
            String token = CheckSupport.tokenOf(seed.get().path("type"));
            b.detail("type=" + token + " from " + CheckSupport.label(seed.get()));
            SearchPage page = CheckSupport.search(ctx, b, EOB, CheckContext.params("patient", ctx.patientId().orElseThrow(), "type", token));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            if (page.count() == 0) {
                return b.fail("no EOB returned for type " + token);
            }
            List<String> wrong = new ArrayList<>();
            for (JsonNode e : page.resources()) {
                if (!code.equals(Fhir.code(e.path("type"), null))) {
                    wrong.add(CheckSupport.label(e) + " type " + Fhir.code(e.path("type"), null));
                }
            }
            CheckSupport.list(b, "other type: ", wrong);
            if (!wrong.isEmpty()) {
                return b.fail(wrong.size() + " result(s) have another type: the parameter is ignored");
            }
            return b.pass(CheckSupport.plural(page.count(), "EOB") + " of type " + code);
        });
    }

    @Bean
    Check eobIncludeAll() {
        return SimpleCheck.of("eob.include.all", "eob", "_include=ExplanationOfBenefit:*", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&_include=ExplanationOfBenefit:* returns the referenced Patient, Coverage, Organization "
                        + "and Practitioner resources as entries with search.mode=include (C4BB: clients MAY request referenced resources "
                        + "with _include and payers SHALL support it)", C4BB_CS + " searchInclude ExplanationOfBenefit:*; C4BB EOB documentation", true,
                (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            SearchPage page = CheckSupport.search(ctx, b, EOB, CheckContext.params("patient", ctx.patientId().orElseThrow(),
                    "_include", "ExplanationOfBenefit:*", "_count", "10"));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            Map<String, Integer> counts = includedCounts(page);
            b.detail("included: " + counts);
            if (page.count() == 0) {
                return b.skip("no EOB returned on this page to include from");
            }
            if (counts.isEmpty()) {
                return b.fail("no entries with search.mode=include");
            }
            return b.pass(page.included().size() + " included resource(s): " + counts.keySet());
        });
    }

    @Bean
    Check eobIncludeSpecific() {
        return SimpleCheck.of("eob.include.specific", "eob", "_include patient, provider, coverage, insurer", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&_include=ExplanationOfBenefit:patient&_include=ExplanationOfBenefit:provider"
                        + "&_include=ExplanationOfBenefit:coverage&_include=ExplanationOfBenefit:insurer returns a Patient, a provider "
                        + "(Organization/Practitioner/PractitionerRole), a Coverage and an insurer Organization as included entries",
                C4BB_CS + " searchInclude ExplanationOfBenefit:patient/:provider/:coverage/:insurer", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            SearchPage page = CheckSupport.search(ctx, b, EOB, CheckContext.params("patient", ctx.patientId().orElseThrow(),
                    "_include", "ExplanationOfBenefit:patient", "_include", "ExplanationOfBenefit:provider",
                    "_include", "ExplanationOfBenefit:coverage", "_include", "ExplanationOfBenefit:insurer", "_count", "10"));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            if (page.count() == 0) {
                return b.skip("no EOB returned on this page to include from");
            }
            Map<String, Integer> counts = includedCounts(page);
            b.detail("included: " + counts);
            List<String> missing = new ArrayList<>();
            if (!counts.containsKey("Patient")) {
                missing.add(":patient (Patient)");
            }
            if (!counts.containsKey("Coverage")) {
                missing.add(":coverage (Coverage)");
            }
            if (!counts.containsKey("Organization")) {
                missing.add(":insurer (Organization)");
            }
            if (!counts.containsKey("Organization") && !counts.containsKey("Practitioner") && !counts.containsKey("PractitionerRole")) {
                missing.add(":provider (Organization/Practitioner/PractitionerRole)");
            }
            if (!missing.isEmpty()) {
                return b.fail("nothing included for " + String.join(", ", missing));
            }
            return b.pass("patient, provider, coverage and insurer included");
        });
    }

    static Map<String, Integer> includedCounts(SearchPage page) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode r : page.included()) {
            counts.merge(r.path("resourceType").asText("?"), 1, Integer::sum);
        }
        return counts;
    }

    @Bean
    Check eobProfileDeclared() {
        return SimpleCheck.of("eob.profile.declared", "eob", "Claims declare a C4BB EOB profile", Severity.SHALL,
                "Every claim EOB declares one of the five C4BB ExplanationOfBenefit profiles (Inpatient-Institutional, Outpatient-Institutional, "
                        + "Professional-NonClinician, Pharmacy, Oral) in meta.profile; lists offenders",
                "C4BB 2.1.0 CapabilityStatement c4bb rest documentation item 5; C4BB 2.1.0 EOB profiles", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> offenders = new ArrayList<>();
            for (JsonNode e : ctx.patientClaims()) {
                if (CheckSupport.firstDeclaredProfile(e, CLAIM_PROFILES).isEmpty()) {
                    offenders.add(CheckSupport.label(e) + " meta.profile=" + Fhir.profiles(e));
                }
            }
            CheckSupport.list(b, "", offenders);
            int total = ctx.patientClaims().size();
            if (!offenders.isEmpty()) {
                return b.fail(offenders.size() + " of " + total + " claim(s) declare no C4BB EOB profile");
            }
            return b.pass("all " + total + " claim(s) declare a C4BB EOB profile");
        });
    }

    @Bean
    Check eobProfileLite() {
        return SimpleCheck.of("eob.profileLite", "eob", "C4BB EOB required elements", Severity.SHALL,
                "Every claim satisfies the required elements, fixed values and slices of its C4BB EOB profile (lite check from the catalog "
                        + "rules; the profile is taken from meta.profile, else derived from type/subType); the first 20 issues are listed",
                "C4BB 2.1.0 ExplanationOfBenefit profiles (StructureDefinitions)", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            int errors = 0;
            int warnings = 0;
            int failing = 0;
            int[] listed = {0};
            for (JsonNode e : ctx.patientClaims()) {
                ProfileLiteChecker.Report r = ctx.checker().check(e);
                int n = CheckSupport.addIssues(b, e, r, listed);
                errors += n;
                warnings += r.warnings();
                if (n > 0) {
                    failing++;
                }
            }
            int total = ctx.patientClaims().size();
            if (errors > 0) {
                return b.fail(CheckSupport.plural(errors, "error") + " in " + failing + " of " + total + " claim(s)");
            }
            return b.pass("no errors across " + total + " claim(s) (" + CheckSupport.plural(warnings, "warning") + ")");
        });
    }

    @Bean
    Check eobUseClaim() {
        return SimpleCheck.of("eob.use.claim", "eob", "Claims have use=claim", Severity.SHALL,
                "Every EOB that is not a prior authorization has use=claim (fixed in all C4BB EOB profiles)",
                "C4BB-ExplanationOfBenefit 2.1.0 use fixed 'claim'", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> wrong = new ArrayList<>();
            for (JsonNode e : ctx.patientClaims()) {
                if (!"claim".equals(Fhir.text(e.get("use")))) {
                    wrong.add(CheckSupport.label(e) + " use=" + Fhir.text(e.get("use")));
                }
            }
            CheckSupport.list(b, "", wrong);
            if (!wrong.isEmpty()) {
                return b.fail(wrong.size() + " of " + ctx.patientClaims().size() + " claim(s) do not have use=claim");
            }
            return b.pass("all " + ctx.patientClaims().size() + " claim(s) have use=claim");
        });
    }

    @Bean
    Check eobReferencesResolvable() {
        return SimpleCheck.of("eob.references.resolvable", "eob", "EOB references resolve", Severity.SHALL,
                "For up to five claims, provider, insurer, patient and insurance.coverage can be read (HTTP 200, right resource type); "
                        + "C4BB: payers SHALL support reading referenced resources directly and SHALL return the same content as via _include",
                "C4BB 2.1.0 EOB documentation: referenced resources via read or vread; C4BB-ExplanationOfBenefit provider/insurer/patient/insurance 1..1",
                true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            Set<String> refs = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            List<JsonNode> sample = ctx.patientClaims().subList(0, Math.min(5, ctx.patientClaims().size()));
            for (JsonNode e : sample) {
                for (String field : List.of("provider", "insurer", "patient")) {
                    String r = CheckSupport.ref(e.get(field));
                    if (r == null) {
                        missing.add(CheckSupport.label(e) + " has no literal " + field + " reference");
                    } else {
                        refs.add(r);
                    }
                }
                for (JsonNode ins : e.path("insurance")) {
                    String r = CheckSupport.ref(ins.get("coverage"));
                    if (r == null) {
                        missing.add(CheckSupport.label(e) + " insurance without a literal coverage reference");
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
                b.evidence(r.get().requestId());
                String type = r.get().resourceType();
                if (!r.get().ok() || type == null || !ref.contains(type + "/")) {
                    failed.add(ref + " -> " + CheckSupport.describe(r.get()));
                }
            }
            b.detail(refs.size() + " distinct reference(s) from " + sample.size() + " claim(s)");
            CheckSupport.list(b, "problem: ", failed);
            if (!failed.isEmpty()) {
                return b.fail(failed.size() + " reference(s) could not be resolved");
            }
            return b.pass(refs.size() + " reference(s) resolved (provider, insurer, patient, coverage)");
        });
    }

    @Bean
    Check eobLastUpdated() {
        return SimpleCheck.of("eob.lastUpdated", "eob", "Claims have meta.lastUpdated", Severity.SHALL,
                "Every claim carries meta.lastUpdated (searchable with _lastUpdated; C4BB EOB meta.lastUpdated 1..1)",
                "C4BB-ExplanationOfBenefit 2.1.0 meta.lastUpdated 1..1", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode e : ctx.patientClaims()) {
                if (Fhir.lastUpdated(e) == null) {
                    missing.add(CheckSupport.label(e));
                }
            }
            CheckSupport.list(b, "without meta.lastUpdated: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + ctx.patientClaims().size() + " claim(s) lack meta.lastUpdated");
            }
            return b.pass("all " + ctx.patientClaims().size() + " claim(s) carry meta.lastUpdated");
        });
    }

    @Bean
    Check eobUniqueClaimId() {
        return SimpleCheck.of("eob.identifier.uniqueClaimId", "eob", "Unique claim identifier present", Severity.SHALL,
                "Every claim has an identifier with type coding uc (C4BBIdentifierType unique claim id): the payer's claim number",
                "C4BB-ExplanationOfBenefit 2.1.0 identifier:uniqueclaimid 1..1", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode e : ctx.patientClaims()) {
                if (CheckSupport.identifierOfType(e, "uc").isEmpty()) {
                    missing.add(CheckSupport.label(e) + " identifiers: " + e.path("identifier").size());
                }
            }
            CheckSupport.list(b, "without uc identifier: ", missing);
            if (!missing.isEmpty()) {
                return b.fail(missing.size() + " of " + ctx.patientClaims().size() + " claim(s) lack the uniqueclaimid identifier");
            }
            return b.pass("all " + ctx.patientClaims().size() + " claim(s) carry a uniqueclaimid identifier");
        });
    }

    @Bean
    Check eobFinancial() {
        return SimpleCheck.of("eob.financial", "eob", "Financial data present", Severity.MAY,
                "Informational: whether claims carry adjudication and total amounts (the full C4BB financial profiles) or only the Basis "
                        + "profiles without amounts (C4BB 2.1 allows *-Basis profiles for payers not sharing financial data)",
                "C4BB 2.1.0 EOB profiles vs *-Basis profiles; CMS-9115-F 42 CFR 422.119(b)(1)(i): claims including cost data", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            int withTotals = 0;
            int withItemAmounts = 0;
            int withPayment = 0;
            for (JsonNode e : ctx.patientClaims()) {
                boolean totals = false;
                for (JsonNode t : e.path("total")) {
                    totals |= t.path("amount").has("value");
                }
                boolean items = false;
                for (JsonNode it : e.path("item")) {
                    for (JsonNode a : it.path("adjudication")) {
                        items |= a.path("amount").has("value");
                    }
                }
                withTotals += totals ? 1 : 0;
                withItemAmounts += items ? 1 : 0;
                withPayment += e.path("payment").path("amount").has("value") ? 1 : 0;
            }
            int total = ctx.patientClaims().size();
            b.detail("claims with total amounts: " + withTotals + "/" + total);
            b.detail("claims with item adjudication amounts: " + withItemAmounts + "/" + total);
            b.detail("claims with payment.amount: " + withPayment + "/" + total);
            if (withTotals == 0 && withItemAmounts == 0) {
                return b.info("no financial amounts: claims look like C4BB Basis profiles (no cost data)");
            }
            return b.info("financial amounts present on " + Math.max(withTotals, withItemAmounts) + " of " + total + " claim(s)");
        });
    }

    @Bean
    Check eobTypesPresent() {
        return SimpleCheck.of("eob.types.present", "eob", "Claim types present", Severity.MAY,
                "Informational: which claim types (institutional inpatient/outpatient, professional, pharmacy, oral) and which C4BB profiles the "
                        + "patient's claims use, with counts", "C4BB 2.1.0 EOB profiles; " + CLAIM_TYPE, true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            Map<String, Integer> types = new TreeMap<>();
            Map<String, Integer> profiles = new TreeMap<>();
            for (JsonNode e : ctx.patientClaims()) {
                String type = Fhir.code(e.path("type"), null);
                String sub = Fhir.code(e.path("subType"), null);
                types.merge((type == null ? "(no type)" : type) + (sub == null ? "" : "/" + sub), 1, Integer::sum);
                String profile = CheckSupport.firstDeclaredProfile(e, CLAIM_PROFILES).map(p -> p.substring(p.lastIndexOf('/') + 1))
                        .orElse("(no C4BB profile)");
                profiles.merge(profile, 1, Integer::sum);
            }
            types.forEach((k, v) -> b.detail("type " + k + ": " + v));
            profiles.forEach((k, v) -> b.detail("profile " + k + ": " + v));
            return b.info(ctx.patientClaims().size() + " claim(s): " + types);
        });
    }

    @Bean
    Check eobTimeliness() {
        return SimpleCheck.of("eob.timeliness", "eob", "Claim timeliness (indicative)", Severity.MAY,
                "Informational: for each claim the days between billablePeriod.end (else created) and meta.lastUpdated, reported as "
                        + "min/median/max. CMS-9115-F requires claims to be available within one business day of adjudication; the "
                        + "adjudication date is not on the resource, so this only indicates how quickly data appears after the service",
                "CMS-9115-F 42 CFR 422.119(b)(1)(i): within one business day after a claim is adjudicated", true, (b, ctx) -> {
            Optional<CheckResult> none = noClaims(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<Long> lags = new ArrayList<>();
            Map<String, Long> perClaim = new LinkedHashMap<>();
            for (JsonNode e : ctx.patientClaims()) {
                String from = Fhir.text(e.path("billablePeriod").get("end"));
                if (from == null) {
                    from = Fhir.text(e.get("created"));
                }
                String to = Fhir.lastUpdated(e);
                Long lag = daysBetween(from, to);
                if (lag != null) {
                    lags.add(lag);
                    perClaim.put(CheckSupport.label(e), lag);
                }
            }
            if (lags.isEmpty()) {
                return b.info("no claim carries both a service/created date and meta.lastUpdated");
            }
            Collections.sort(lags);
            perClaim.forEach((k, v) -> b.detail(k + ": " + v + " day(s)"));
            long median = lags.get(lags.size() / 2);
            return b.info("service end -> lastUpdated: min " + lags.get(0) + ", median " + median + ", max " + lags.get(lags.size() - 1)
                    + " day(s) over " + lags.size() + " claim(s)");
        });
    }

    /** Whole days between two date/dateTime strings, null when either is missing or unparsable. */
    static Long daysBetween(String from, String to) {
        try {
            if (from == null || to == null) {
                return null;
            }
            return ChronoUnit.DAYS.between(LocalDate.parse(CheckSupport.date(from)), LocalDate.parse(CheckSupport.date(to)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
