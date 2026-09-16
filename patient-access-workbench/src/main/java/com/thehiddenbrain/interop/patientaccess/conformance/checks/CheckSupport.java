package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Helpers shared by the check catalog: CapabilityStatement lookups, deriving search values from a
 * resource the server returned, resolving references through the context (cached per run so several
 * checks share one read), OperationOutcome detection and evidence recording. Everything here returns
 * results, never throws for a 4xx/5xx: only transport failures propagate (the runner reports ERROR).
 */
final class CheckSupport {

    static final String C4BB = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/";
    static final String PDEX = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/";
    static final String HREX = "http://hl7.org/fhir/us/davinci-hrex/StructureDefinition/";
    static final String US_CORE = "http://hl7.org/fhir/us/core/StructureDefinition/";
    static final String EOB = "ExplanationOfBenefit";
    static final String V2_0203 = "http://terminology.hl7.org/CodeSystem/v2-0203";
    static final String C4BB_IDENTIFIER_TYPE = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBIdentifierType";

    private static final Pattern LITERAL_REFERENCE = Pattern.compile("^([A-Z][A-Za-z]+)/([A-Za-z0-9\\-.]{1,64})(/_history/[A-Za-z0-9\\-.]{1,64})?$");
    private static final int MAX_LISTED = 20;

    private CheckSupport() {
    }

    // ---------------------------------------------------------------- CapabilityStatement

    static List<JsonNode> restResources(JsonNode capabilityStatement) {
        List<JsonNode> out = new ArrayList<>();
        if (capabilityStatement == null) {
            return out;
        }
        for (JsonNode rest : capabilityStatement.path("rest")) {
            for (JsonNode r : rest.path("resource")) {
                out.add(r);
            }
        }
        return out;
    }

    static Optional<JsonNode> restResource(JsonNode capabilityStatement, String type) {
        return restResources(capabilityStatement).stream().filter(r -> type.equals(r.path("type").asText())).findFirst();
    }

    static Set<String> declaredTypes(JsonNode capabilityStatement) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode r : restResources(capabilityStatement)) {
            out.add(r.path("type").asText());
        }
        return out;
    }

    /** True when the type is declared, or when no CapabilityStatement is available (nothing contradicts it). */
    static boolean declaresType(JsonNode capabilityStatement, String type) {
        return capabilityStatement == null || declaredTypes(capabilityStatement).contains(type);
    }

    static Set<String> declaredSearchParams(JsonNode capabilityStatement, String type) {
        Set<String> out = new LinkedHashSet<>();
        restResource(capabilityStatement, type).ifPresent(r -> {
            for (JsonNode p : r.path("searchParam")) {
                out.add(p.path("name").asText());
            }
        });
        return out;
    }

    /** Profiles declared for one type (profile + supportedProfile), without the |version suffix. */
    static Set<String> supportedProfiles(JsonNode capabilityStatement, String type) {
        Set<String> out = new LinkedHashSet<>();
        restResource(capabilityStatement, type).ifPresent(r -> {
            if (r.hasNonNull("profile")) {
                out.add(bare(r.get("profile").asText()));
            }
            for (JsonNode p : r.path("supportedProfile")) {
                out.add(bare(p.asText()));
            }
        });
        return out;
    }

    static Set<String> allSupportedProfiles(JsonNode capabilityStatement) {
        Set<String> out = new LinkedHashSet<>();
        for (String type : declaredTypes(capabilityStatement)) {
            out.addAll(supportedProfiles(capabilityStatement, type));
        }
        return out;
    }

    static JsonNode security(JsonNode capabilityStatement) {
        if (capabilityStatement == null) {
            return null;
        }
        for (JsonNode rest : capabilityStatement.path("rest")) {
            if (rest.has("security")) {
                return rest.get("security");
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- resources

    /** Canonical without the |version part. */
    static String bare(String canonical) {
        if (canonical == null) {
            return null;
        }
        int i = canonical.indexOf('|');
        return i < 0 ? canonical : canonical.substring(0, i);
    }

    static boolean declaresProfile(JsonNode resource, String url) {
        for (String p : Fhir.profiles(resource)) {
            if (bare(p).equals(url)) {
                return true;
            }
        }
        return false;
    }

    static boolean declaresProfileStartingWith(JsonNode resource, String prefix) {
        for (String p : Fhir.profiles(resource)) {
            if (bare(p).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static Optional<String> firstDeclaredProfile(JsonNode resource, Collection<String> urls) {
        for (String p : Fhir.profiles(resource)) {
            if (urls.contains(bare(p))) {
                return Optional.of(bare(p));
            }
        }
        return Optional.empty();
    }

    static String label(JsonNode resource) {
        return resource.path("resourceType").asText("?") + "/" + resource.path("id").asText("?");
    }

    static List<String> ids(Collection<JsonNode> resources) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : resources) {
            out.add(r.path("id").asText(null));
        }
        return out;
    }

    static boolean containsId(Collection<JsonNode> resources, String id) {
        return ids(resources).contains(id);
    }

    /** Literal reference string of a Reference element, or null. */
    static String ref(JsonNode reference) {
        return reference == null ? null : Fhir.text(reference.get("reference"));
    }

    /** True when a reference (relative, absolute or versioned) points at Type/id. */
    static boolean refersTo(String reference, String type, String id) {
        if (reference == null) {
            return false;
        }
        String r = reference;
        int h = r.indexOf("/_history/");
        if (h > 0) {
            r = r.substring(0, h);
        }
        return r.equals(type + "/" + id) || r.endsWith("/" + type + "/" + id);
    }

    /** Date part (yyyy-mm-dd) of a date/dateTime, or the value itself when shorter. */
    static String date(String dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.length() > 10 ? dateTime.substring(0, 10) : dateTime;
    }

    /** {@code system|value} when a system is present, else the bare value. */
    static String identifierToken(JsonNode identifier) {
        String system = Fhir.text(identifier.get("system"));
        String value = Fhir.text(identifier.get("value"));
        if (value == null) {
            return null;
        }
        return system == null ? value : system + "|" + value;
    }

    /** First identifier whose type carries one of the codes (any system), else empty. */
    static Optional<JsonNode> identifierOfType(JsonNode resource, String... codes) {
        for (JsonNode i : resource.path("identifier")) {
            for (String c : codes) {
                if (Fhir.hasCoding(i.path("type"), null, c)) {
                    return Optional.of(i);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code system|code} of the first coding, else the bare code or text; null when absent. */
    static String tokenOf(JsonNode codeableConcept) {
        if (codeableConcept == null || codeableConcept.isMissingNode() || codeableConcept.isNull()) {
            return null;
        }
        JsonNode cc = codeableConcept.isArray() ? (codeableConcept.size() > 0 ? codeableConcept.get(0) : null) : codeableConcept;
        if (cc == null) {
            return null;
        }
        JsonNode coding = cc.path("coding");
        if (coding.isArray() && coding.size() > 0) {
            String system = Fhir.text(coding.get(0).get("system"));
            String code = Fhir.text(coding.get(0).get("code"));
            if (code == null) {
                return null;
            }
            return system == null ? code : system + "|" + code;
        }
        if (cc.has("code") && cc.has("system")) { // a Coding (Encounter.class)
            return cc.get("system").asText() + "|" + cc.get("code").asText();
        }
        return Fhir.text(cc.get("text"));
    }

    /** Bare code of the first coding (what US Core uses for category searches). */
    static String codeOf(JsonNode codeableConcept) {
        if (codeableConcept == null || codeableConcept.isMissingNode() || codeableConcept.isNull()) {
            return null;
        }
        JsonNode cc = codeableConcept.isArray() ? (codeableConcept.size() > 0 ? codeableConcept.get(0) : null) : codeableConcept;
        if (cc == null) {
            return null;
        }
        if (cc.has("code") && !cc.has("coding")) {
            return cc.get("code").asText();
        }
        return Fhir.code(cc, null);
    }

    /**
     * A value for a search parameter taken from a resource the server returned, so a search with it
     * must find that resource again: tokens from the first coding, dates as {@code ge<date>}. Null when
     * the resource has no value for the parameter.
     */
    static String searchValue(JsonNode resource, String param) {
        return switch (param) {
            case "category" -> codeOf(resource.path("category"));
            case "code" -> tokenOf(resource.path("code"));
            case "type" -> tokenOf(resource.path("type"));
            case "class" -> tokenOf(resource.path("class"));
            case "status" -> Fhir.text(resource.get("status"));
            case "intent" -> Fhir.text(resource.get("intent"));
            case "lifecycle-status" -> Fhir.text(resource.get("lifecycleStatus"));
            case "clinical-status" -> codeOf(resource.path("clinicalStatus"));
            case "discharge-disposition" -> tokenOf(resource.path("hospitalization").path("dischargeDisposition"));
            case "role" -> tokenOf(resource.path("participant").path(0).path("role"));
            case "description" -> tokenOf(resource.path("description"));
            case "identifier" -> resource.path("identifier").size() > 0 ? identifierToken(resource.path("identifier").get(0)) : null;
            case "name", "family" -> Fhir.text(resource.path("name").path(0).get("family"));
            case "encounter" -> ref(resource.get("encounter"));
            case "location" -> ref(resource.path("location").path(0).get("location"));
            case "questionnaire" -> Fhir.text(resource.get("questionnaire"));
            case "date" -> ge(firstDate(resource, "effective", "performed", "occurrence", "date", "period", "onset", "issued"));
            case "effective" -> ge(firstDate(resource, "effective"));
            case "authoredon", "authored" -> ge(date(Fhir.text(resource.has("authoredOn") ? resource.get("authoredOn") : resource.get("authored"))));
            case "onset-date" -> ge(firstDate(resource, "onset"));
            case "abatement-date" -> ge(firstDate(resource, "abatement"));
            case "recorded-date" -> ge(date(Fhir.text(resource.get("recordedDate"))));
            case "asserted-date" -> ge(date(Fhir.extensionValue(resource, "http://hl7.org/fhir/StructureDefinition/condition-assertedDate")));
            case "target-date" -> ge(date(Fhir.text(resource.path("target").path(0).get("dueDate"))));
            case "period" -> ge(dateOf(resource.path("context").has("period") ? resource.path("context").get("period") : resource.get("period")));
            case "_lastUpdated" -> ge(date(Fhir.lastUpdated(resource)));
            default -> null;
        };
    }

    private static String ge(String date) {
        return date == null ? null : "ge" + date;
    }

    /** First date found under the given element names (choice elements such as effective[x] included). */
    static String firstDate(JsonNode resource, String... prefixes) {
        for (String prefix : prefixes) {
            JsonNode direct = resource.get(prefix);
            String d = dateOf(direct != null ? direct : Fhir.choice(resource, prefix));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** yyyy-mm-dd of a date, dateTime, instant or Period (start, else end); null for other types. */
    static String dateOf(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String t = value.asText();
            return t.length() >= 4 && Character.isDigit(t.charAt(0)) ? date(t) : null;
        }
        if (value.isObject()) {
            String start = Fhir.text(value.get("start"));
            return date(start != null ? start : Fhir.text(value.get("end")));
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP results

    static boolean isOperationOutcome(HttpResult r) {
        return r.isResource("OperationOutcome");
    }

    /** "HTTP 200 Bundle searchset (3 entries)", "HTTP 404 OperationOutcome: ...", "HTTP 500 (text/html)". */
    static String describe(HttpResult r) {
        StringBuilder sb = new StringBuilder("HTTP ").append(r.status());
        String type = r.resourceType();
        if ("Bundle".equals(type)) {
            JsonNode b = r.json();
            sb.append(" Bundle ").append(b.path("type").asText("?")).append(" (").append(b.path("entry").size()).append(" entries");
            if (b.hasNonNull("total")) {
                sb.append(", total ").append(b.get("total").asInt());
            }
            sb.append(')');
        } else if ("OperationOutcome".equals(type)) {
            sb.append(" OperationOutcome: ").append(r.errorSummary());
        } else if (type != null) {
            sb.append(' ').append(type).append('/').append(r.json().path("id").asText(""));
        } else if (r.body().isBlank()) {
            sb.append(" (empty body)");
        } else {
            sb.append(" (").append(r.contentType() == null ? "unknown content type" : r.contentType()).append(": ")
                    .append(r.errorSummary().length() > 120 ? r.errorSummary().substring(0, 120) + "..." : r.errorSummary()).append(')');
        }
        return sb.toString();
    }

    /** Adds the call as evidence and a one-line detail of what came back. */
    static CheckResult.Builder record(CheckResult.Builder b, HttpResult r) {
        return b.evidence(r.requestId()).detail("GET " + r.url() + " -> " + describe(r));
    }

    /** Runs a search through the context and records it on the builder. */
    static SearchPage search(CheckContext ctx, CheckResult.Builder b, String type, MultiValueMap<String, String> params) {
        SearchPage page = searchQuietly(ctx, b, type, params);
        if (page != null && page.isBundle() && page.response().ok()) {
            List<String> ignored = paramsMissingFromSelfLink(page, params);
            if (!ignored.isEmpty()) {
                b.detail("self link of the " + type + " search omits " + String.join(", ", ignored) + ": the server may have ignored the parameter (FHIR R4 3.1.1.6)");
            }
            if (!page.outcomes().isEmpty()) {
                b.detail("server added " + page.outcomes().size() + " OperationOutcome entr" + (page.outcomes().size() == 1 ? "y" : "ies") + " (search.mode=outcome) to the Bundle");
            }
        }
        return page;
    }

    private static SearchPage searchQuietly(CheckContext ctx, CheckResult.Builder b, String type, MultiValueMap<String, String> params) {
        SearchPage page = ctx.searchPage(type, params);
        record(b, page.response());
        return page;
    }

    /** Records an already-fetched page (e.g. one shared through the cache). */
    /** Parameters that the server's self link does not echo: the server may have ignored them (FHIR R4 3.1.1.6). */
    static List<String> paramsMissingFromSelfLink(SearchPage page, MultiValueMap<String, String> params) {
        List<String> missing = new ArrayList<>();
        if (page == null || page.selfUrl() == null || params == null) {
            return missing;
        }
        String self = page.selfUrl();
        int q = self.indexOf('?');
        String query = q < 0 ? "" : self.substring(q + 1);
        for (String name : params.keySet()) {
            if (name.startsWith("_count") || name.startsWith("_format")) {
                continue;
            }
            String bare = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
            if (!query.contains(bare + "=") && !query.contains(java.net.URLEncoder.encode(bare, java.nio.charset.StandardCharsets.UTF_8) + "=")) {
                missing.add(name);
            }
        }
        return missing;
    }

    static SearchPage record(CheckResult.Builder b, SearchPage page) {
        record(b, page.response());
        return page;
    }

    /** Null when the page is a 2xx searchset Bundle whose matches are all of the given type, else what is wrong. */
    static String bundleProblem(SearchPage page, String type) {
        HttpResult r = page.response();
        if (!r.ok()) {
            return "HTTP " + r.status() + (r.body().isBlank() ? "" : ": " + r.errorSummary());
        }
        if (!page.isBundle()) {
            return "response is not a Bundle (" + (r.resourceType() == null ? "non-FHIR content" : r.resourceType()) + ")";
        }
        for (JsonNode m : page.resources()) {
            if (!type.equals(m.path("resourceType").asText())) {
                return "match entry of type " + m.path("resourceType").asText("?") + " in a " + type + " search";
            }
        }
        return null;
    }

    /** The first page of {@code <type>?patient=<pid>}, fetched once per run and shared by all checks of the type. */
    static SearchPage patientSearch(CheckContext ctx, String type) {
        String pid = ctx.patientId().orElseThrow();
        return ctx.cached("search:" + type, () -> ctx.searchPage(type, CheckContext.params("patient", pid)));
    }

    /** Matches of {@link #patientSearch}, empty when the search failed. */
    static List<JsonNode> patientResources(CheckContext ctx, String type) {
        SearchPage page = patientSearch(ctx, type);
        return page.response().ok() && page.isBundle() ? page.resources() : List.of();
    }

    /** The read of the patient under test, fetched once per run. */
    static HttpResult patientRead(CheckContext ctx) {
        String pid = ctx.patientId().orElseThrow();
        return ctx.cached("read:Patient/" + pid, () -> ctx.get(ctx.readUrl("Patient", pid)));
    }

    /**
     * Reads a referenced resource (relative "Type/id", versioned or absolute inside the environment),
     * cached per run. Empty when the reference is not a literal one the environment can serve
     * (contained, identifier-only, another host).
     */
    static Optional<HttpResult> resolve(CheckContext ctx, String reference) {
        if (reference == null || reference.startsWith("#")) {
            return Optional.empty();
        }
        String url;
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            url = reference;
        } else if (LITERAL_REFERENCE.matcher(reference).matches()) {
            url = ctx.environment().baseUrl() + "/" + reference;
        } else {
            return Optional.empty();
        }
        try {
            return Optional.of(ctx.cached("ref:" + url, () -> ctx.get(url)));
        } catch (WorkbenchException e) {
            if (e.getCode() == ErrorCode.TARGET_NOT_ALLOWED) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- profile-lite reports

    /** Adds the issues of a report as details ("Type/id path: message"); returns the number of errors. */
    static int addIssues(CheckResult.Builder b, JsonNode resource, ProfileLiteChecker.Report report, int[] listed) {
        for (ProfileLiteChecker.Issue i : report.issues()) {
            if (listed[0] >= MAX_LISTED) {
                break;
            }
            if ("error".equals(i.severity()) || "warning".equals(i.severity())) {
                b.detail(label(resource) + " " + i.path() + ": " + i.message() + " [" + i.severity() + "]");
                listed[0]++;
            }
        }
        return report.errors();
    }

    /** Adds up to {@value #MAX_LISTED} items as one detail line each with a "... and n more" tail. */
    static void list(CheckResult.Builder b, String prefix, List<String> items) {
        int n = 0;
        for (String i : items) {
            if (n++ >= MAX_LISTED) {
                b.detail(prefix + "... and " + (items.size() - MAX_LISTED) + " more");
                return;
            }
            b.detail(prefix + i);
        }
    }

    static String plural(int n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }
}
