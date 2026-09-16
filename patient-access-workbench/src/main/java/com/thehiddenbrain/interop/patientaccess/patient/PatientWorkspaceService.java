package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Everything the Patient tab shows for one member: overview counts per data class, coverage,
 * claims (C4BB EOBs), prior authorizations (PDex PA EOBs) and US Core clinical resources, each
 * with the profile-lite check results and the queries that were sent.
 */
@Service
public class PatientWorkspaceService {

    /** Data classes of the Patient Access API, in display order: key, label, resource type, extra params. */
    public static final List<DataClass> DATA_CLASSES = List.of(
            new DataClass("coverage", "Coverage", "Coverage", Map.of()),
            new DataClass("claims", "Claims (EOB)", "ExplanationOfBenefit", Map.of()),
            new DataClass("priorAuth", "Prior authorizations", "ExplanationOfBenefit", Map.of("use", "preauthorization")),
            new DataClass("Condition", "Conditions", "Condition", Map.of()),
            new DataClass("Observation", "Observations", "Observation", Map.of()),
            new DataClass("MedicationRequest", "Medication requests", "MedicationRequest", Map.of()),
            new DataClass("MedicationDispense", "Medication dispenses", "MedicationDispense", Map.of()),
            new DataClass("AllergyIntolerance", "Allergies", "AllergyIntolerance", Map.of()),
            new DataClass("Immunization", "Immunizations", "Immunization", Map.of()),
            new DataClass("Procedure", "Procedures", "Procedure", Map.of()),
            new DataClass("Encounter", "Encounters", "Encounter", Map.of()),
            new DataClass("DiagnosticReport", "Diagnostic reports", "DiagnosticReport", Map.of()),
            new DataClass("DocumentReference", "Documents", "DocumentReference", Map.of()),
            new DataClass("CarePlan", "Care plans", "CarePlan", Map.of()),
            new DataClass("CareTeam", "Care teams", "CareTeam", Map.of()),
            new DataClass("Goal", "Goals", "Goal", Map.of()),
            new DataClass("Device", "Devices", "Device", Map.of()),
            new DataClass("ServiceRequest", "Service requests", "ServiceRequest", Map.of()),
            new DataClass("RelatedPerson", "Related persons", "RelatedPerson", Map.of()));

    public record DataClass(String key, String label, String resourceType, Map<String, String> params) {
    }

    public record DataClassCount(String key, String label, String resourceType, Integer count, Integer total, boolean more, Integer status,
                                 String error, String url, String requestId, long durationMs) {
    }

    public record Overview(PatientSummary patient, ProfileLiteChecker.Report patientChecks, JsonNode patientResource,
                           List<DataClassCount> dataClasses, String correlationId) {
    }

    public record ResourceList(String resourceType, List<ResourceRow> rows, List<ResourceRow> included, Integer total, int pages, boolean truncated,
                               List<String> urls, List<String> warnings, List<String> requestIds, String correlationId) {
    }

    public record PriorAuthList(List<PriorAuthSummary> items, List<String> urls, List<String> notes, boolean fallbackUsed, int pages,
                                boolean truncated, List<String> requestIds, String correlationId) {
    }

    public record ResourceDetail(ResourceRow row, PriorAuthSummary priorAuth, String url, String requestId) {
    }

    private final FhirGateway gateway;
    private final ProfileLiteChecker checker;
    private final PriorAuthSummarizer priorAuth;
    private final IgCatalog catalog;
    private final Executor executor;

    public PatientWorkspaceService(FhirGateway gateway, ProfileLiteChecker checker, PriorAuthSummarizer priorAuth, IgCatalog catalog,
                                   @Qualifier("workbenchExecutor") TaskExecutor executor) {
        this.gateway = gateway;
        this.checker = checker;
        this.priorAuth = priorAuth;
        this.catalog = catalog;
        this.executor = executor;
    }

    public Overview overview(Environment env, String patientId) {
        String correlation = "patient-" + Ids.next(8);
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_READ, correlation);
        JsonNode patient = gateway.read(env, "Patient", patientId, options);
        if (!"Patient".equals(patient.path("resourceType").asText())) {
            throw new WorkbenchException(ErrorCode.UPSTREAM_ERROR, "Patient/" + patientId + " did not return a Patient");
        }
        List<CompletableFuture<DataClassCount>> futures = new ArrayList<>();
        for (DataClass dc : DATA_CLASSES) {
            futures.add(CompletableFuture.supplyAsync(() -> count(env, dc, patientId, correlation), executor));
        }
        List<DataClassCount> counts = new ArrayList<>();
        for (CompletableFuture<DataClassCount> f : futures) {
            counts.add(f.join());
        }
        return new Overview(PatientSummary.of(patient, "Patient/" + patientId), checker.check(patient), patient, counts, correlation);
    }

    private DataClassCount count(Environment env, DataClass dc, String patientId, String correlation) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("patient", patientId);
        dc.params().forEach(params::add);
        params.add("_count", "50");
        String url = UrlBuilder.searchUrl(env, dc.resourceType(), params);
        try {
            HttpResult r = gateway.get(env, url, FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH, correlation));
            if (!r.ok()) {
                return new DataClassCount(dc.key(), dc.label(), dc.resourceType(), null, null, false, r.status(), r.errorSummary(), url,
                        r.requestId(), r.durationMs());
            }
            SearchPage page = SearchPage.of(r, List.of());
            if (!page.isBundle()) {
                return new DataClassCount(dc.key(), dc.label(), dc.resourceType(), null, null, false, r.status(), "response is not a Bundle", url,
                        r.requestId(), r.durationMs());
            }
            List<JsonNode> matches = page.resources();
            if (dc.key().equals("priorAuth")) {
                matches = matches.stream().filter(PriorAuthSummarizer::isPriorAuth).toList();
            } else if (dc.key().equals("claims")) {
                matches = matches.stream().filter(e -> !PriorAuthSummarizer.isPriorAuth(e)).toList();
            }
            Integer total = page.total();
            if (dc.key().equals("priorAuth") || dc.key().equals("claims")) {
                total = page.nextUrl() == null ? matches.size() : null;
            }
            return new DataClassCount(dc.key(), dc.label(), dc.resourceType(), matches.size(), total, page.nextUrl() != null, r.status(), null,
                    url, r.requestId(), r.durationMs());
        } catch (WorkbenchException e) {
            return new DataClassCount(dc.key(), dc.label(), dc.resourceType(), null, null, false,
                    e.getUpstream() == null ? null : e.getUpstream().httpStatus(), e.getMessage(), url,
                    e.getUpstream() == null ? null : e.getUpstream().requestId(), 0);
        }
    }

    public ResourceList coverage(Environment env, String patientId) {
        String correlation = "coverage-" + Ids.next(8);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("patient", patientId);
        params.add("_include", "Coverage:payor");
        try {
            return list(env, "Coverage", params, correlation, null);
        } catch (WorkbenchException e) {
            if (e.getUpstream() != null && e.getUpstream().httpStatus() != null && e.getUpstream().httpStatus() < 500) {
                params.remove("_include");
                ResourceList plain = list(env, "Coverage", params, correlation, null);
                List<String> warnings = new ArrayList<>(plain.warnings());
                warnings.add("Coverage?_include=Coverage:payor was rejected (" + e.getUpstream().httpStatus() + "); C4BB declares this include");
                return new ResourceList(plain.resourceType(), plain.rows(), plain.included(), plain.total(), plain.pages(), plain.truncated(),
                        plain.urls(), warnings, plain.requestIds(), correlation);
            }
            throw e;
        }
    }

    /** Claims: C4BB EOBs with use=claim. Optional type filter (institutional, professional, pharmacy, oral, vision) and _lastUpdated. */
    public ResourceList claims(Environment env, String patientId, String type, String since, Map<String, String> extra) {
        String correlation = "claims-" + Ids.next(8);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("patient", patientId);
        if (type != null && !type.isBlank()) {
            params.add("type", type.trim());
        }
        if (since != null && !since.isBlank()) {
            params.add("_lastUpdated", since.startsWith("ge") || since.startsWith("gt") ? since.trim() : "ge" + since.trim());
        }
        if (extra != null) {
            extra.forEach(params::add);
        }
        ResourceList all = list(env, "ExplanationOfBenefit", params, correlation, null);
        List<ResourceRow> claims = all.rows().stream().filter(r -> !PriorAuthSummarizer.isPriorAuth(r.resource())).toList();
        return new ResourceList(all.resourceType(), claims, all.included(), all.total(), all.pages(), all.truncated(), all.urls(), all.warnings(),
                all.requestIds(), correlation);
    }

    /** Prior authorizations: EOB?patient=&use=preauthorization, falling back to filtering all EOBs when the server ignores or rejects 'use'. */
    public PriorAuthList priorAuthorizations(Environment env, String patientId, String since) {
        String correlation = "priorauth-" + Ids.next(8);
        List<String> notes = new ArrayList<>();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("patient", patientId);
        params.add("use", "preauthorization");
        if (since != null && !since.isBlank()) {
            params.add("_lastUpdated", since.startsWith("ge") || since.startsWith("gt") ? since.trim() : "ge" + since.trim());
        }
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH, correlation);
        FhirGateway.Collected collected;
        boolean fallback = false;
        try {
            collected = gateway.searchAll(env, "ExplanationOfBenefit", params, options, null);
            long nonPa = collected.resources().stream().filter(e -> !PriorAuthSummarizer.isPriorAuth(e)).count();
            if (nonPa > 0) {
                notes.add("the server returned " + nonPa + " EOB(s) with use != preauthorization for use=preauthorization: the 'use' search parameter (PDex) seems to be ignored; results were filtered client-side");
            }
        } catch (WorkbenchException e) {
            Integer status = e.getUpstream() == null ? null : e.getUpstream().httpStatus();
            if (status != null && status >= 400 && status < 500) {
                fallback = true;
                notes.add("ExplanationOfBenefit?use=preauthorization was rejected with HTTP " + status + " (PDex defines this search parameter); all EOBs of the patient were fetched and filtered by use / profile instead");
                params.remove("use");
                collected = gateway.searchAll(env, "ExplanationOfBenefit", params, options, null);
            } else {
                throw e;
            }
        }
        List<PriorAuthSummary> items = collected.resources().stream().filter(PriorAuthSummarizer::isPriorAuth).map(priorAuth::summarize).toList();
        if (items.isEmpty()) {
            notes.add("no prior authorization found for this member (CMS-0057-F: PA data must be available from 2027-01-01 within one business day of a status change)");
        }
        List<String> urls = new ArrayList<>();
        urls.add(UrlBuilder.searchUrl(env, "ExplanationOfBenefit", params));
        return new PriorAuthList(items, urls, notes, fallback, collected.pages(), collected.truncated(), collected.requestIds(), correlation);
    }

    /** Any resource type by patient plus extra parameters (validated against the IG catalog: warnings only). */
    public ResourceList clinical(Environment env, String patientId, String resourceType, Map<String, String> extra) {
        String correlation = resourceType.toLowerCase() + "-" + Ids.next(8);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("patient", patientId);
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    params.add(k.trim(), v.trim());
                }
            });
        }
        return list(env, resourceType, params, correlation, null);
    }

    public ResourceDetail resource(Environment env, String resourceType, String id) {
        String url = UrlBuilder.readUrl(env, resourceType, id);
        HttpResult r = gateway.getOrThrow(env, url, FhirGateway.Options.of(FhirGateway.PURPOSE_READ));
        JsonNode resource = r.json();
        if (resource == null || !resourceType.equals(resource.path("resourceType").asText())) {
            throw new WorkbenchException(ErrorCode.UPSTREAM_ERROR, url + " did not return a " + resourceType);
        }
        ResourceRow row = ResourceSummaries.row(resource, checker.check(resource));
        PriorAuthSummary pa = PriorAuthSummarizer.isPriorAuth(resource) ? priorAuth.summarize(resource) : null;
        return new ResourceDetail(row, pa, url, r.requestId());
    }

    private ResourceList list(Environment env, String resourceType, MultiValueMap<String, String> params, String correlation, Integer maxPages) {
        List<String> warnings = new ArrayList<>(catalog.validateParams(resourceType, params.keySet()));
        FhirGateway.Collected c = gateway.searchAll(env, resourceType, params, FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH, correlation), maxPages);
        List<ResourceRow> rows = new ArrayList<>();
        for (JsonNode res : c.resources()) {
            rows.add(ResourceSummaries.row(res, checker.check(res)));
        }
        List<ResourceRow> included = new ArrayList<>();
        for (JsonNode res : c.included()) {
            included.add(ResourceSummaries.row(res, checker.check(res)));
        }
        if (c.truncated()) {
            warnings.add("stopped after " + c.pages() + " page(s); raise fhir.maxPages on the environment to fetch more");
        }
        return new ResourceList(resourceType, rows, included, c.total(), c.pages(), c.truncated(),
                List.of(UrlBuilder.searchUrl(env, resourceType, params)), warnings, c.requestIds(), correlation);
    }
}
