package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummary;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.EOB;

/**
 * CMS-0057-F prior authorization content through PDex PriorAuthorization EOBs: the use=preauthorization
 * search, the profile, and the data elements the rule requires members to see (status, dates, items and
 * services, quantities, denial reasons).
 */
@Configuration
public class PriorAuthChecks {

    private static final String RULE = "CMS-0057-F 42 CFR 422.119(b)(1)(iv) (Patient Access API prior authorization data, applicable "
            + "2027-01-01): status, date of approval or denial, date or circumstance under which the authorization ends, items and services "
            + "approved, quantity used to date, and for denials the specific reason";
    private static final String PROFILE = "PDex 2.1.0 PDex Prior Authorization profile (pdex-priorauthorization)";
    private static final String NDC = "http://hl7.org/fhir/sid/ndc";

    /** The use=preauthorization search, fetched once per run. */
    static SearchPage useSearch(CheckContext ctx) {
        String pid = ctx.patientId().orElseThrow();
        return ctx.cached("search:" + EOB + ":use", () -> ctx.searchPage(EOB, CheckContext.params("patient", pid, "use", "preauthorization")));
    }

    /**
     * The patient's prior authorizations: those in the plain patient search, completed by the ones only the
     * use=preauthorization search returns (some servers keep PAs out of the default claim search).
     */
    static List<JsonNode> priorAuths(CheckContext ctx) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode e : ctx.patientPriorAuths()) {
            byId.put(Fhir.idOf(e), e);
        }
        SearchPage direct = useSearch(ctx);
        if (direct.response().ok() && direct.isBundle()) {
            for (JsonNode e : direct.resources()) {
                if (PriorAuthSummarizer.isPriorAuth(e)) {
                    byId.putIfAbsent(Fhir.idOf(e), e);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    static List<PriorAuthSummary> summaries(CheckContext ctx) {
        List<PriorAuthSummary> out = new ArrayList<>();
        for (JsonNode e : priorAuths(ctx)) {
            out.add(ctx.priorAuth().summarize(e));
        }
        return out;
    }

    /** Skip result when the patient has no prior authorizations, else empty (evidence of both fetches is recorded). */
    static Optional<CheckResult> noPriorAuth(CheckResult.Builder b, CheckContext ctx) {
        b.evidence(ctx.patientEobs().requestIds()).evidence(useSearch(ctx).response().requestId());
        if (priorAuths(ctx).isEmpty()) {
            return Optional.of(b.skip("no prior authorization (use=preauthorization) for Patient/" + ctx.patientId().orElseThrow()));
        }
        return Optional.empty();
    }

    static boolean denied(PriorAuthSummary s) {
        if ("DENIED".equals(s.decision())) {
            return true;
        }
        for (PriorAuthSummary.Item i : s.items()) {
            if (i.decision() != null && (i.decision().startsWith("Not certified") || i.decision().startsWith("Denied"))) {
                return true;
            }
        }
        return false;
    }

    static boolean approved(PriorAuthSummary s) {
        return s.decision() != null && (s.decision().startsWith("APPROVED") || s.decision().startsWith("PARTIAL"));
    }

    /** One check over every prior authorization: the body returns a problem for a PA or null. */
    static CheckResult perPriorAuth(CheckResult.Builder b, CheckContext ctx, java.util.function.Function<PriorAuthSummary, String> problem,
                                    String passMessage) {
        Optional<CheckResult> none = noPriorAuth(b, ctx);
        if (none.isPresent()) {
            return none.get();
        }
        List<String> problems = new ArrayList<>();
        List<PriorAuthSummary> all = summaries(ctx);
        for (PriorAuthSummary s : all) {
            String p = problem.apply(s);
            if (p != null) {
                problems.add(EOB + "/" + s.id() + ": " + p);
            }
        }
        CheckSupport.list(b, "", problems);
        if (!problems.isEmpty()) {
            return b.fail(problems.size() + " of " + all.size() + " prior authorization(s): " + problems.get(0));
        }
        return b.pass(all.size() + " prior authorization(s): " + passMessage);
    }

    @Bean
    Check priorAuthSearchByUse() {
        return SimpleCheck.of("priorauth.search.use", "priorauth", "EOB search by use=preauthorization", Severity.SHOULD,
                "GET ExplanationOfBenefit?patient={id}&use=preauthorization returns HTTP 200 with a searchset whose entries all have "
                        + "use=preauthorization (PDex defines the 'use' search parameter so apps can separate prior authorizations from claims); "
                        + "400/404 fails, a server that ignores the parameter and returns claims is a warning",
                "PDex 2.1.0 SearchParameter explanationofbenefit-use; PDex 2.1.0 CapabilityStatement pdex-server ExplanationOfBenefit; " + RULE, true,
                (b, ctx) -> {
            SearchPage page = CheckSupport.record(b, useSearch(ctx));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            List<String> other = new ArrayList<>();
            for (JsonNode e : page.resources()) {
                if (!"preauthorization".equals(Fhir.text(e.get("use")))) {
                    other.add(CheckSupport.label(e) + " use=" + Fhir.text(e.get("use")));
                }
            }
            CheckSupport.list(b, "not a prior authorization: ", other);
            if (!other.isEmpty()) {
                return b.warn("the use parameter is ignored: " + other.size() + " of " + page.count() + " result(s) are not prior authorizations");
            }
            if (page.count() == 0) {
                return b.pass("search accepted; no prior authorization for this member");
            }
            return b.pass(CheckSupport.plural(page.count(), "prior authorization") + " returned");
        });
    }

    @Bean
    Check priorAuthPresent() {
        return SimpleCheck.of("priorauth.present", "priorauth", "Prior authorizations present", Severity.MAY,
                "Informational: how many prior authorizations the patient has (from the patient search and the use=preauthorization search). "
                        + "CMS-0057-F: from 2027-01-01 the Patient Access API must include prior authorization requests and decisions "
                        + "(excluding drugs), updated within one business day of a status change and kept at least one year after the last "
                        + "status change", RULE, true, (b, ctx) -> {
            b.evidence(ctx.patientEobs().requestIds()).evidence(useSearch(ctx).response().requestId());
            List<PriorAuthSummary> all = summaries(ctx);
            for (PriorAuthSummary s : all) {
                b.detail(EOB + "/" + s.id() + ": " + s.decision() + " (" + s.decisionBasis() + "), status " + s.status() + ", outcome " + s.outcome()
                        + ", decided " + s.decisionDate() + ", valid " + s.validFrom() + " - " + s.validTo());
            }
            b.detail("in patient search: " + ctx.patientPriorAuths().size() + "; in use=preauthorization search: "
                    + (useSearch(ctx).response().ok() ? useSearch(ctx).count() : "n/a"));
            if (all.isEmpty()) {
                return b.info("no prior authorization for this member (required in the Patient Access API from 2027-01-01 when the member has any)");
            }
            return b.info(all.size() + " prior authorization(s) for this member");
        });
    }

    @Bean
    Check priorAuthProfileDeclared() {
        return SimpleCheck.of("priorauth.profile.declared", "priorauth", "PA declares the PDex PriorAuthorization profile", Severity.SHALL,
                "Every prior authorization EOB declares " + PriorAuthSummarizer.PDEX_PA_PROFILE + " in meta.profile", PROFILE, true,
                (b, ctx) -> perPriorAuth(b, ctx, s -> s.pdexProfileDeclared() ? null : "meta.profile=" + s.profiles(), "profile declared"));
    }

    @Bean
    Check priorAuthProfileLite() {
        return SimpleCheck.of("priorauth.profileLite", "priorauth", "PDex PriorAuthorization required elements", Severity.SHALL,
                "Every prior authorization satisfies the required elements, fixed values and slices of the PDex PriorAuthorization profile "
                        + "(lite check from the catalog rules); errors are listed", PROFILE + " StructureDefinition", true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            int errors = 0;
            int warnings = 0;
            int[] listed = {0};
            for (JsonNode e : priorAuths(ctx)) {
                ProfileLiteChecker.Report r = ctx.checker().check(e, PriorAuthSummarizer.PDEX_PA_PROFILE);
                errors += CheckSupport.addIssues(b, e, r, listed);
                warnings += r.warnings();
            }
            int total = priorAuths(ctx).size();
            if (errors > 0) {
                return b.fail(CheckSupport.plural(errors, "error") + " across " + total + " prior authorization(s)");
            }
            return b.pass("no errors across " + total + " prior authorization(s) (" + CheckSupport.plural(warnings, "warning") + ")");
        });
    }

    @Bean
    Check priorAuthUseFixed() {
        return SimpleCheck.of("priorauth.use.fixed", "priorauth", "PA has use=preauthorization", Severity.SHALL,
                "Every prior authorization EOB has use=preauthorization (fixed by the profile)", PROFILE + " use fixed 'preauthorization'", true,
                (b, ctx) -> perPriorAuth(b, ctx, s -> "preauthorization".equals(s.use()) ? null : "use=" + s.use(), "use=preauthorization"));
    }

    @Bean
    Check priorAuthDecision() {
        return SimpleCheck.of("priorauth.decision", "priorauth", "PA decision is expressed", Severity.SHALL,
                "Each prior authorization yields a decision (approved, partially approved, denied, pending, cancelled) from the reviewAction "
                        + "codes (X12 306: A1, A2, A3, A4, A6, C, CT), denialreason adjudications or outcome; UNKNOWN means the status "
                        + "CMS-0057-F requires cannot be derived", RULE + " ('status'); PDex 2.1.0 extension-reviewAction / reviewActionCode", true,
                (b, ctx) -> perPriorAuth(b, ctx, s -> "UNKNOWN".equals(s.decision()) ? "no decision derivable (" + s.decisionBasis() + ")" : null,
                        "decision derivable"));
    }

    @Bean
    Check priorAuthDates() {
        return SimpleCheck.of("priorauth.dates", "priorauth", "PA decision date and validity period", Severity.SHALL,
                "Each decided prior authorization carries a decision date (item preAuthIssueDate or adjudication when-adjudicated "
                        + "extension); approved or partially approved ones also carry a validity period (preAuthRefPeriod or item "
                        + "preAuthPeriod extension). Pended requests have no decision date yet; denied ones have no period to end",
                RULE + " ('date of approval or denial', 'date or circumstance under which the authorization ends'); PDex 2.1.0 preAuthRefPeriod, "
                        + "PAS extension-itemPreAuthPeriod / extension-itemPreAuthIssueDate, PDex base-ext-when-adjudicated", true, (b, ctx) ->
                perPriorAuth(b, ctx, s -> {
                    List<String> missing = new ArrayList<>();
                    String decision = s.decision() == null ? "" : s.decision();
                    boolean pending = decision.startsWith("PENDING");
                    boolean authorized = decision.startsWith("APPROVED") || decision.startsWith("PARTIAL");
                    if (s.decisionDate() == null && !pending) {
                        missing.add("no decision date");
                    }
                    if (authorized && s.validFrom() == null && s.validTo() == null) {
                        missing.add("no validity period although the authorization was granted");
                    }
                    return missing.isEmpty() ? null : String.join(", ", missing);
                }, "decision dates present; validity periods present on granted authorizations"));
    }

    @Bean
    Check priorAuthItemsServices() {
        return SimpleCheck.of("priorauth.items.services", "priorauth", "PA lists items and services", Severity.SHALL,
                "Each prior authorization has at least one item with productOrService (the items and services requested / approved)",
                RULE + " ('items and services approved'); " + PROFILE + " item.productOrService", true, (b, ctx) -> perPriorAuth(b, ctx, s -> {
            if (s.items().isEmpty()) {
                return "no item";
            }
            List<String> without = new ArrayList<>();
            for (PriorAuthSummary.Item i : s.items()) {
                if (i.productOrService() == null) {
                    without.add("item " + i.sequence());
                }
            }
            return without.isEmpty() ? null : "no productOrService on " + String.join(", ", without);
        }, "items with productOrService present"));
    }

    @Bean
    Check priorAuthQuantities() {
        return SimpleCheck.of("priorauth.quantities", "priorauth", "Approved PA carries quantities", Severity.SHOULD,
                "Approved prior authorizations carry an allowedunits adjudication on their items (consumedunits or the "
                        + "PriorAuthorizationUtilization extension on a total once units were used), so the member sees the quantity "
                        + "approved and used to date; skipped when no prior authorization is approved",
                RULE + " ('quantity used to date'); PDex 2.1.0 PDexAdjudicationDiscriminator allowedunits/consumedunits, PriorAuthorizationUtilization",
                true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<PriorAuthSummary> approved = summaries(ctx).stream().filter(PriorAuthChecks::approved).toList();
            if (approved.isEmpty()) {
                return b.skip("no approved prior authorization to check quantities on");
            }
            List<String> problems = new ArrayList<>();
            for (PriorAuthSummary s : approved) {
                boolean units = s.items().stream().anyMatch(i -> i.allowedUnits() != null);
                boolean utilization = s.totals().stream().anyMatch(t -> t.utilization() != null);
                String have = (s.items().stream().anyMatch(i -> i.allowedUnits() != null) ? "allowedunits " : "")
                        + (s.items().stream().anyMatch(i -> i.consumedUnits() != null) ? "consumedunits " : "")
                        + (utilization ? "utilization" : "");
                b.detail(EOB + "/" + s.id() + ": " + (have.isBlank() ? "no quantities" : have.trim()));
                if (!units && !utilization) {
                    problems.add(EOB + "/" + s.id() + ": neither an allowedunits adjudication nor PriorAuthorizationUtilization");
                } else if (s.items().stream().noneMatch(i -> i.consumedUnits() != null) && !utilization) {
                    b.detail(EOB + "/" + s.id() + ": no consumedunits / utilization yet (quantity used to date not expressed)");
                }
            }
            if (!problems.isEmpty()) {
                return b.fail(problems.size() + " of " + approved.size() + " approved prior authorization(s) lack quantity data");
            }
            return b.pass("all " + approved.size() + " approved prior authorization(s) carry quantity data");
        });
    }

    @Bean
    Check priorAuthDenialReason() {
        return SimpleCheck.of("priorauth.denialReason", "priorauth", "Denied PA carries a denial reason", Severity.SHALL,
                "Every denied prior authorization (review action A3 'Not certified' or decision DENIED) carries a denialreason adjudication "
                        + "with a reason CodeableConcept (X12 CARC / RARC); skipped when nothing is denied",
                RULE + " ('specific reason for a denial'); PDex 2.1.0 PDexAdjudicationDiscriminator denialreason, ValueSet X12 CARC/RARC", true,
                (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<PriorAuthSummary> denied = summaries(ctx).stream().filter(PriorAuthChecks::denied).toList();
            if (denied.isEmpty()) {
                return b.skip("no denied prior authorization (" + summaries(ctx).size() + " checked)");
            }
            List<String> problems = new ArrayList<>();
            for (PriorAuthSummary s : denied) {
                if (s.denialReasons().isEmpty()) {
                    problems.add(EOB + "/" + s.id() + ": denied (" + s.decision() + ") without a denialreason adjudication reason");
                } else {
                    b.detail(EOB + "/" + s.id() + ": " + String.join("; ", s.denialReasons()));
                }
            }
            CheckSupport.list(b, "", problems);
            if (!problems.isEmpty()) {
                return b.fail(problems.size() + " of " + denied.size() + " denied prior authorization(s) lack a denial reason");
            }
            return b.pass("all " + denied.size() + " denied prior authorization(s) carry a denial reason");
        });
    }

    @Bean
    Check priorAuthReviewAction() {
        return SimpleCheck.of("priorauth.reviewAction", "priorauth", "PA adjudication carries reviewAction", Severity.SHOULD,
                "Each prior authorization has an item (or claim-level) adjudication with the PDex reviewAction extension carrying a "
                        + "reviewActionCode: the explicit approved / denied / pended decision",
                PROFILE + " item.adjudication extension-reviewAction (reviewActionCode X12 306)", true, (b, ctx) -> perPriorAuth(b, ctx, s -> {
            boolean any = s.claimAdjudications().stream().anyMatch(a -> a.reviewAction() != null && a.reviewAction().code() != null);
            for (PriorAuthSummary.Item i : s.items()) {
                any |= i.adjudications().stream().anyMatch(a -> a.reviewAction() != null && a.reviewAction().code() != null);
            }
            return any ? null : "no adjudication carries a reviewAction code";
        }, "reviewAction codes present"));
    }

    @Bean
    Check priorAuthInsurerProvider() {
        return SimpleCheck.of("priorauth.insurerProvider", "priorauth", "PA has insurer and provider", Severity.SHALL,
                "Each prior authorization has insurer and provider references (1..1 in the profile)", PROFILE + " insurer 1..1, provider 1..1", true,
                (b, ctx) -> perPriorAuth(b, ctx, s -> {
                    List<String> missing = new ArrayList<>();
                    if (s.insurer() == null) {
                        missing.add("insurer");
                    }
                    if (s.provider() == null) {
                        missing.add("provider");
                    }
                    return missing.isEmpty() ? null : "missing " + String.join(", ", missing);
                }, "insurer and provider present"));
    }

    @Bean
    Check priorAuthSearchById() {
        return SimpleCheck.of("priorauth.search.id", "priorauth", "PA search by _id", Severity.SHALL,
                "GET ExplanationOfBenefit?_id={id} with a prior authorization id returns it",
                "PDex 2.1.0 CapabilityStatement pdex-server ExplanationOfBenefit _id SHALL", true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String id = Fhir.idOf(priorAuths(ctx).get(0));
            // use=preauthorization routes the search to the PDex base on vendors that serve each IG separately
            SearchPage page = CheckSupport.search(ctx, b, EOB, CheckContext.params("_id", id, "use", "preauthorization"));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            if (!CheckSupport.containsId(page.resources(), id)) {
                return b.fail(EOB + "/" + id + " not in the results");
            }
            return b.pass(EOB + "/" + id + " found by _id");
        });
    }

    @Bean
    Check priorAuthRead() {
        return SimpleCheck.of("priorauth.read", "priorauth", "PA read", Severity.SHALL,
                "GET ExplanationOfBenefit/{id} with a prior authorization id returns HTTP 200 and the EOB",
                "PDex 2.1.0 CapabilityStatement pdex-server ExplanationOfBenefit read SHALL", true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String id = Fhir.idOf(priorAuths(ctx).get(0));
            HttpResult r = ctx.get(com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder.readUrl(ctx.environment(), EOB, id,
                    com.thehiddenbrain.interop.patientaccess.fhir.IgRouting.PDEX));
            CheckSupport.record(b, r);
            if (r.ok() && r.isResource(EOB) && id.equals(Fhir.idOf(r.json()))) {
                return b.pass(EOB + "/" + id + " read");
            }
            return b.fail("read of " + EOB + "/" + id + " answered " + CheckSupport.describe(r));
        });
    }

    @Bean
    Check priorAuthSearchByLastUpdated() {
        return SimpleCheck.of("priorauth.search.lastUpdated", "priorauth", "PA search by _lastUpdated", Severity.SHOULD,
                "GET ExplanationOfBenefit?patient={id}&use=preauthorization&_lastUpdated=ge{oldest lastUpdated} returns at least one prior "
                        + "authorization (apps poll for status changes, which CMS-0057-F requires within one business day)",
                "PDex 2.1.0 CapabilityStatement pdex-server ExplanationOfBenefit _lastUpdated SHALL; " + RULE, true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            String oldest = null;
            for (JsonNode e : priorAuths(ctx)) {
                String lu = CheckSupport.date(Fhir.lastUpdated(e));
                if (lu != null && (oldest == null || lu.compareTo(oldest) < 0)) {
                    oldest = lu;
                }
            }
            if (oldest == null) {
                return b.skip("no prior authorization carries meta.lastUpdated");
            }
            SearchPage page = CheckSupport.search(ctx, b, EOB, CheckContext.params("patient", ctx.patientId().orElseThrow(),
                    "use", "preauthorization", "_lastUpdated", "ge" + oldest));
            String problem = CheckSupport.bundleProblem(page, EOB);
            if (problem != null) {
                return b.fail(problem);
            }
            if (page.count() == 0) {
                return b.fail("no result for _lastUpdated=ge" + oldest + " although a prior authorization was updated on that date");
            }
            return b.pass(CheckSupport.plural(page.count(), "result") + " for _lastUpdated=ge" + oldest);
        });
    }

    @Bean
    Check priorAuthDrugs() {
        return SimpleCheck.of("priorauth.drugs", "priorauth", "Drug prior authorizations", Severity.MAY,
                "Informational: items whose productOrService is coded with NDC (" + NDC + ") are drug prior authorizations, which CMS-0057-F "
                        + "excludes from the Patient Access API requirement; lists them", "CMS-0057-F 42 CFR 422.119(b)(1)(iv): excluding drugs", true,
                (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<String> drugs = new ArrayList<>();
            for (JsonNode e : priorAuths(ctx)) {
                for (JsonNode it : e.path("item")) {
                    for (JsonNode c : it.path("productOrService").path("coding")) {
                        if (NDC.equals(c.path("system").asString(""))) {
                            drugs.add(CheckSupport.label(e) + " item " + it.path("sequence").asString("?") + ": NDC " + c.path("code").asString(""));
                        }
                    }
                }
            }
            CheckSupport.list(b, "", drugs);
            if (drugs.isEmpty()) {
                return b.info("no NDC-coded items: no drug prior authorizations among " + priorAuths(ctx).size());
            }
            return b.info(drugs.size() + " drug (NDC) item(s) found; drug prior authorizations are outside the CMS-0057-F Patient Access requirement");
        });
    }

    @Bean
    Check priorAuthTimeliness() {
        return SimpleCheck.of("priorauth.timeliness", "priorauth", "PA timeliness (indicative)", Severity.MAY,
                "Informational: days between the decision date and meta.lastUpdated of each prior authorization. CMS-0057-F requires "
                        + "prior authorization data to be updated within one business day of a status change", RULE, true, (b, ctx) -> {
            Optional<CheckResult> none = noPriorAuth(b, ctx);
            if (none.isPresent()) {
                return none.get();
            }
            List<Long> lags = new ArrayList<>();
            for (PriorAuthSummary s : summaries(ctx)) {
                Long lag = EobChecks.daysBetween(s.decisionDate(), s.lastUpdated());
                b.detail(EOB + "/" + s.id() + ": decided " + s.decisionDate() + ", lastUpdated " + s.lastUpdated()
                        + (lag == null ? "" : " (" + lag + " day(s))"));
                if (lag != null) {
                    lags.add(lag);
                }
            }
            if (lags.isEmpty()) {
                return b.info("no prior authorization carries both a decision date and meta.lastUpdated");
            }
            long max = lags.stream().mapToLong(Long::longValue).max().orElse(0);
            long min = lags.stream().mapToLong(Long::longValue).min().orElse(0);
            return b.info("decision date -> lastUpdated lag: min " + min + ", max " + max + " day(s) over " + lags.size()
                    + " prior authorization(s); negative means lastUpdated precedes the decision date");
        });
    }
}
